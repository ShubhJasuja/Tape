package in.practice.tape;

import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static java.lang.Math.*;

@Service
@Profile("accounts")
class PaperOrders {
    static final long MAX = 9_007_199_254_740_991L;
    private static final String SELECT_ORDER = """
        SELECT o.id, i.exchange || ':' || i.symbol AS symbol, o.instrument_id, o.side, o.kind, o.quantity,
               o.trigger_price_paise, o.status, o.rejection_code, o.created_at, o.updated_at,
               e.price_paise, e.fee_paise, e.quote_at, e.quote_source
        FROM orders o JOIN instruments i ON i.id=o.instrument_id
        LEFT JOIN executions e ON e.order_id=o.id AND e.quote_source='TAPE_SYNTHETIC_V1'
        WHERE o.account_id=?
        """;
    private static final RowMapper<Order> ORDER = (rs, row) -> new Order(
        rs.getObject("id", UUID.class), rs.getString("symbol"), rs.getObject("instrument_id", UUID.class),
        rs.getString("side"), rs.getString("kind"), rs.getInt("quantity"),
        rs.getObject("trigger_price_paise", Long.class), rs.getString("status"), rs.getString("rejection_code"),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
        rs.getObject("price_paise", Long.class), rs.getObject("fee_paise", Long.class),
        rs.getTimestamp("quote_at") == null ? null : rs.getTimestamp("quote_at").toInstant(), rs.getString("quote_source"));

    record Ticket(UUID idempotencyKey, String symbol, String side, String kind, int quantity, BigDecimal trigger) {}
    record Order(UUID id, String symbol, UUID instrumentId, String side, String kind, int quantity,
                 Long triggerPaise, String status, String rejectionCode, Instant createdAt, Instant updatedAt,
                 Long fillPaise, Long feePaise, Instant quoteAt, String quoteSource) {
        boolean buy() { return "BUY".equals(side); }
        boolean open() { return "OPEN".equals(status); }
        boolean crosses(long price) {
            return buy() == "LIMIT".equals(kind) ? price <= triggerPaise : price >= triggerPaise;
        }
    }
    record Page(List<Order> orders, Integer nextOffset) {}
    record Wallet(long balancePaise, long reservedPaise, long availablePaise) {
        Wallet(long balance, long reserved) { this(balance, reserved, subtractExact(balance, reserved)); }
    }
    record Position(long quantity, long reserved, long cost) {
        long available() { return subtractExact(quantity, reserved); }
    }
    record Holding(String symbol, long quantity, long reservedQuantity, long availableQuantity,
                   long costPaise, long markPaise, long valuePaise, long unrealizedPaise) {}
    record Portfolio(Wallet wallet, List<Holding> positions, long equityPaise, long realizedPaise, long unrealizedPaise) {}
    record Candidate(UUID owner, UUID account, UUID order) {}

    private final JdbcTemplate db;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final SyntheticQuotes quotes;
    private final boolean enabled;

    PaperOrders(JdbcTemplate db, TransactionTemplate transactions, SyntheticQuotes quotes,
                @Value("${tape.synthetic-trading.enabled:false}") boolean enabled) {
        this.db = db;
        this.writes = transactions;
        this.quotes = quotes;
        this.enabled = enabled;
        reads = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    Order submit(UUID owner, UUID account, Ticket ticket) {
        Long trigger = normalize(ticket);
        return writes.execute(ignored -> {
            Wallet wallet = lock(owner, account);
            var existing = find(account, " AND o.idempotency_key=?", ticket.idempotencyKey());
            if (existing != null) {
                if (existing.symbol().equals(ticket.symbol()) && existing.side().equals(ticket.side())
                    && existing.kind().equals(ticket.kind()) && existing.quantity() == ticket.quantity()
                    && Objects.equals(existing.triggerPaise(), trigger)) return existing;
                throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
            UUID instrument = instrumentId(ticket.symbol());
            Instant now = Instant.now();
            boolean immediate = "MARKET".equals(ticket.kind());
            var quote = immediate ? quotes.latest(ticket.symbol(), now) : null;
            if (immediate && !SyntheticQuotes.eligible(quote, now)) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "QUOTE_UNAVAILABLE");
            }
            UUID id = UUID.randomUUID();
            db.update("""
                INSERT INTO orders (id,account_id,instrument_id,idempotency_key,side,kind,quantity,
                    trigger_price_paise,status,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,'OPEN',?,?)
                """, id, account, instrument, ticket.idempotencyKey(), ticket.side(), ticket.kind(),
                ticket.quantity(), trigger, Timestamp.from(now), Timestamp.from(now));
            Order order = requireOrder(account, id);
            Position position = position(account, instrument);
            if (immediate) {
                fill(account, order, wallet, position, quote, now);
            } else if (order.buy()) {
                long required = debit(gross(trigger, order.quantity()));
                if (required > wallet.availablePaise()) transition(account, order, "REJECTED", "INSUFFICIENT_CASH");
                else updateWallet(account, wallet.balancePaise(), addExact(wallet.reservedPaise(), required));
            } else if (order.quantity() > position.available()) {
                transition(account, order, "REJECTED", "INSUFFICIENT_SHARES");
            } else {
                savePosition(account, instrument, position.quantity(), addExact(position.reserved(), order.quantity()), position.cost());
            }
            return requireOrder(account, id);
        });
    }

    Order cancel(UUID owner, UUID account, UUID id) {
        return writes.execute(ignored -> {
            Wallet wallet = lock(owner, account);
            Order order = requireOrder(account, id);
            if ("CANCELLED".equals(order.status())) return order;
            if (!order.open()) throw error(HttpStatus.CONFLICT, "ORDER_NOT_OPEN");
            release(account, order, wallet, position(account, order.instrumentId()));
            transition(account, order, "CANCELLED", null);
            return requireOrder(account, id);
        });
    }

    List<Candidate> candidates() {
        return db.query("""
            SELECT a.user_id,a.id,o.id FROM orders o JOIN trading_accounts a ON a.id=o.account_id
            WHERE a.mode='DEMO' AND a.trading_enabled AND o.status='OPEN' AND o.kind<>'MARKET'
            ORDER BY a.id,o.created_at,o.id
            """, (rs, row) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)));
    }

    void match(Candidate candidate) {
        writes.executeWithoutResult(ignored -> {
            UUID account = candidate.account();
            Wallet wallet = lock(candidate.owner(), account);
            Order order = requireOrder(account, candidate.order());
            if (!order.open() || "MARKET".equals(order.kind())) return;
            Instant now = Instant.now();
            var quote = quotes.latest(order.symbol(), now);
            if (!SyntheticQuotes.eligible(quote, now) || quote.time() < order.createdAt().toEpochMilli()
                || !order.crosses(quote.price())) return;
            Position position = position(account, order.instrumentId());
            release(account, order, wallet, position);
            fill(account, order, readWallet(account, false), position(account, order.instrumentId()), quote, now);
        });
    }

    private Wallet lock(UUID owner, UUID account) {
        String mode = db.queryForObject("SELECT lock_tape_trading_account(?,?)", String.class, owner, account);
        if (mode == null) throw error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND");
        checkEnabled(account, mode);
        return readWallet(account, true);
    }

    private void checkEnabled(UUID account, String mode) {
        if (!enabled || !"DEMO".equals(mode) || !Boolean.TRUE.equals(db.queryForObject(
            "SELECT trading_enabled FROM trading_accounts WHERE id=?", Boolean.class, account))) {
            throw error(HttpStatus.FORBIDDEN, "TRADING_DISABLED");
        }
    }

    private void fill(UUID account, Order order, Wallet wallet, Position position, Market.Quote quote, Instant now) {
        long gross = gross(quote.price(), order.quantity());
        long fee = Trading.fee(gross);
        long cash;
        long quantity;
        long cost;
        if (order.buy()) {
            long total = addExact(gross, fee);
            if (total > wallet.availablePaise()) {
                transition(account, order, "REJECTED", "INSUFFICIENT_CASH");
                return;
            }
            cash = subtractExact(wallet.balancePaise(), total);
            quantity = addExact(position.quantity(), order.quantity());
            cost = addExact(position.cost(), total);
        } else {
            if (order.quantity() > position.available()) {
                transition(account, order, "REJECTED", "INSUFFICIENT_SHARES");
                return;
            }
            cash = addExact(wallet.balancePaise(), subtractExact(gross, fee));
            quantity = subtractExact(position.quantity(), order.quantity());
            cost = subtractExact(position.cost(), allocated(position.cost(), order.quantity(), position.quantity()));
        }
        if (cash > MAX || quantity > MAX || cost > MAX) {
            transition(account, order, "REJECTED", "ACCOUNT_LIMIT");
            return;
        }
        transition(account, order, "FILLED", null);
        UUID execution = UUID.randomUUID();
        db.update("""
            INSERT INTO executions (id,order_id,account_id,instrument_id,quantity,price_paise,
                gross_paise,fee_paise,executed_at,quote_source,quote_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, execution, order.id(), account, order.instrumentId(), order.quantity(), quote.price(), gross, fee,
            Timestamp.from(now), SyntheticQuotes.SOURCE, Timestamp.from(Instant.ofEpochMilli(quote.time())));
        post(account, execution, order.buy() ? "TRADE_DEBIT" : "TRADE_CREDIT", order.buy() ? negateExact(gross) : gross);
        post(account, execution, "FEE", negateExact(fee));
        updateWallet(account, cash, wallet.reservedPaise());
        savePosition(account, order.instrumentId(), quantity, position.reserved(), cost);
    }

    private void post(UUID account, UUID execution, String kind, long amount) {
        db.update("INSERT INTO cash_ledger_entries (id,account_id,execution_id,kind,amount_paise) VALUES (?,?,?,?,?)",
            UUID.randomUUID(), account, execution, kind, amount);
    }

    private void release(UUID account, Order order, Wallet wallet, Position position) {
        if (order.buy()) updateWallet(account, wallet.balancePaise(),
            subtractExact(wallet.reservedPaise(), debit(gross(order.triggerPaise(), order.quantity()))));
        else savePosition(account, order.instrumentId(), position.quantity(),
            subtractExact(position.reserved(), order.quantity()), position.cost());
    }

    private void transition(UUID account, Order order, String status, String reason) {
        int updated = db.update("""
            UPDATE orders SET status=?,rejection_code=?,updated_at=greatest(updated_at,clock_timestamp())
            WHERE account_id=? AND id=? AND status='OPEN'
            """, status, reason, account, order.id());
        if (updated != 1) throw error(HttpStatus.CONFLICT, "ORDER_NOT_OPEN");
    }

    private Wallet readWallet(UUID account, boolean lock) {
        return db.queryForObject("SELECT balance_paise,reserved_paise FROM wallets WHERE account_id=?" +
            (lock ? " FOR UPDATE" : ""), (rs, row) -> new Wallet(rs.getLong(1), rs.getLong(2)), account);
    }

    private void updateWallet(UUID account, long balance, long reserved) {
        if (db.update("UPDATE wallets SET balance_paise=?,reserved_paise=? WHERE account_id=?", balance, reserved, account) != 1) {
            throw new IllegalStateException("Wallet missing");
        }
    }

    private Position position(UUID account, UUID instrument) {
        return db.query("SELECT quantity,reserved_quantity,cost_paise FROM positions WHERE account_id=? AND instrument_id=?",
            (rs, row) -> new Position(rs.getLong(1), rs.getLong(2), rs.getLong(3)), account, instrument)
            .stream().findFirst().orElse(new Position(0, 0, 0));
    }

    private void savePosition(UUID account, UUID instrument, long quantity, long reserved, long cost) {
        db.update("""
            INSERT INTO positions (account_id,instrument_id,quantity,reserved_quantity,cost_paise) VALUES (?,?,?,?,?)
            ON CONFLICT (account_id,instrument_id) DO UPDATE SET quantity=excluded.quantity,
                reserved_quantity=excluded.reserved_quantity,cost_paise=excluded.cost_paise
            """, account, instrument, quantity, reserved, cost);
    }

    UUID instrumentId(String symbol) {
        var instrument = Market.instrument(symbol);
        String[] parts = instrument.symbol().split(":", 2);
        return db.queryForObject("SELECT id FROM instruments WHERE exchange=? AND symbol=?", UUID.class, parts[0], parts[1]);
    }

    Order detail(UUID owner, UUID account, UUID id) {
        return owned(owner, account, () -> requireOrder(account, id));
    }

    Order retryStatus(UUID owner, UUID account, UUID key) {
        return owned(owner, account, () -> {
            var order = find(account, " AND o.idempotency_key=?", key);
            if (order == null) throw error(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
            return order;
        });
    }

    Page list(UUID owner, UUID account, int offset, int limit) {
        if (offset < 0 || offset > Integer.MAX_VALUE - 100 || limit < 1 || limit > 100) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        return owned(owner, account, () -> {
            var orders = db.query(SELECT_ORDER + " ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?",
                ORDER, account, limit + 1, offset);
            return orders.size() <= limit ? new Page(orders, null) : new Page(orders.subList(0, limit), offset + limit);
        });
    }

    Wallet wallet(UUID owner, UUID account) {
        return owned(owner, account, () -> readWallet(account, false));
    }

    Portfolio portfolio(UUID owner, UUID account) {
        return owned(owner, account, () -> {
            Wallet wallet = readWallet(account, false);
            Instant now = Instant.now();
            var positions = db.query("""
                SELECT i.exchange || ':' || i.symbol, p.quantity,p.reserved_quantity,p.cost_paise
                FROM positions p JOIN instruments i ON i.id=p.instrument_id
                WHERE p.account_id=? AND p.quantity>0 ORDER BY 1
                """, (rs, row) -> {
                    String symbol = rs.getString(1);
                    long quantity = rs.getLong(2), reserved = rs.getLong(3), cost = rs.getLong(4);
                    Market.Quote quote = quotes.latest(symbol, now);
                    if (!SyntheticQuotes.eligible(quote, now)) throw error(HttpStatus.SERVICE_UNAVAILABLE, "QUOTE_UNAVAILABLE");
                    long value = bounded(gross(quote.price(), quantity));
                    return new Holding(symbol, quantity, reserved, subtractExact(quantity, reserved), cost, quote.price(),
                        value, subtractExact(value, cost));
                }, account);
            long equity = wallet.balancePaise(), unrealized = 0;
            for (var position : positions) {
                equity = bounded(addExact(equity, position.valuePaise()));
                unrealized = bounded(addExact(unrealized, position.unrealizedPaise()));
            }
            return new Portfolio(wallet, positions, equity, bounded(replay(account).realized()), unrealized);
        });
    }

    <T> T owned(UUID owner, UUID account, Supplier<T> action) {
        return reads.execute(ignored -> {
            var modes = db.queryForList("SELECT mode FROM trading_accounts WHERE id=? AND user_id=?",
                String.class, account, owner);
            if (modes.isEmpty()) throw error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND");
            checkEnabled(account, modes.getFirst());
            return action.get();
        });
    }

    void pin(UUID owner, UUID account, String symbol, boolean pinned) {
        writes.executeWithoutResult(ignored -> {
            lock(owner, account);
            UUID instrument = instrumentId(symbol);
            if (pinned) {
                db.update("INSERT INTO watchlists (id,user_id,name) VALUES (?,?,'Default') ON CONFLICT DO NOTHING",
                    UUID.randomUUID(), owner);
                db.update("""
                    INSERT INTO watchlist_items (watchlist_id,instrument_id)
                    SELECT id,? FROM watchlists WHERE user_id=? AND name='Default' ON CONFLICT DO NOTHING
                    """, instrument, owner);
            } else {
                db.update("""
                    DELETE FROM watchlist_items WHERE instrument_id=? AND watchlist_id IN
                    (SELECT id FROM watchlists WHERE user_id=? AND name='Default')
                    """, instrument, owner);
            }
        });
    }

    private Order requireOrder(UUID account, UUID id) {
        var order = find(account, " AND o.id=?", id);
        if (order == null) throw error(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        return order;
    }

    private Order find(UUID account, String predicate, UUID value) {
        return db.query(SELECT_ORDER + predicate, ORDER, account, value).stream().findFirst().orElse(null);
    }

    record Replay(java.util.Map<UUID, Position> positions, long realized) {}

    private Replay replay(UUID account) {
        record Fill(UUID instrument, boolean buy, long quantity, long gross, long fee) {}
        var fills = db.query("""
            SELECT e.instrument_id,o.side,e.quantity,e.gross_paise,e.fee_paise
            FROM executions e JOIN orders o ON o.id=e.order_id AND o.account_id=e.account_id
            WHERE e.account_id=? ORDER BY e.execution_sequence
            """, (rs, row) -> new Fill(rs.getObject(1, UUID.class), "BUY".equals(rs.getString(2)),
            rs.getLong(3), rs.getLong(4), rs.getLong(5)), account);
        var positions = new java.util.HashMap<UUID, Position>();
        long realized = 0;
        for (var fill : fills) {
            Position position = positions.getOrDefault(fill.instrument(), new Position(0, 0, 0));
            if (fill.buy()) {
                positions.put(fill.instrument(), new Position(addExact(position.quantity(), fill.quantity()), 0,
                    addExact(position.cost(), addExact(fill.gross(), fill.fee()))));
            } else {
                long allocated = allocated(position.cost(), fill.quantity(), position.quantity());
                realized = addExact(realized, subtractExact(subtractExact(fill.gross(), fill.fee()), allocated));
                positions.put(fill.instrument(), new Position(subtractExact(position.quantity(), fill.quantity()), 0,
                    subtractExact(position.cost(), allocated)));
            }
        }
        return new Replay(positions, realized);
    }

    List<String> reconcile(UUID owner, UUID account) {
        return owned(owner, account, () -> {
            var issues = new java.util.ArrayList<String>();
            Wallet wallet = readWallet(account, false);
            var posted = db.queryForObject("SELECT coalesce(sum(amount_paise),0) FROM cash_ledger_entries WHERE account_id=?",
                BigDecimal.class, account);
            if (posted.compareTo(BigDecimal.valueOf(wallet.balancePaise())) != 0) issues.add("WALLET_BALANCE");
            var replayed = replay(account).positions();
            var pending = db.query(SELECT_ORDER + " AND o.status='OPEN'", ORDER, account);
            long cashReserved = 0;
            var sharesReserved = new java.util.HashMap<UUID, Long>();
            for (var order : pending) {
                if (order.buy()) cashReserved = addExact(cashReserved, debit(gross(order.triggerPaise(), order.quantity())));
                else sharesReserved.merge(order.instrumentId(), (long) order.quantity(), Math::addExact);
            }
            if (cashReserved != wallet.reservedPaise()) issues.add("CASH_RESERVATION");
            var ids = new java.util.HashSet<>(replayed.keySet());
            ids.addAll(sharesReserved.keySet());
            ids.addAll(db.queryForList("SELECT instrument_id FROM positions WHERE account_id=?", UUID.class, account));
            for (UUID id : ids) {
                var expected = replayed.getOrDefault(id, new Position(0, 0, 0));
                var current = position(account, id);
                if (current.quantity() != expected.quantity() || current.cost() != expected.cost()) issues.add("POSITION:" + id);
                if (current.reserved() != sharesReserved.getOrDefault(id, 0L)) issues.add("SHARE_RESERVATION:" + id);
            }
            return List.copyOf(issues);
        });
    }

    private static long allocated(long cost, long shares, long totalShares) {
        return BigInteger.valueOf(cost).multiply(BigInteger.valueOf(shares))
            .divide(BigInteger.valueOf(totalShares)).longValueExact();
    }

    private static long gross(long price, long quantity) { return multiplyExact(price, quantity); }
    private static long debit(long gross) { return addExact(gross, Trading.fee(gross)); }

    private static long bounded(long amount) {
        if (amount < -MAX || amount > MAX) throw new ArithmeticException("Display amount outside safe range");
        return amount;
    }

    private static Long normalize(Ticket ticket) {
        if (ticket == null || ticket.idempotencyKey() == null || ticket.quantity() < 1 || ticket.quantity() > 100_000 ||
            !("BUY".equals(ticket.side()) || "SELL".equals(ticket.side())) ||
            !("MARKET".equals(ticket.kind()) || "LIMIT".equals(ticket.kind()) || "STOP".equals(ticket.kind())) ||
            Market.INSTRUMENTS.stream().noneMatch(i -> i.symbol().equals(ticket.symbol()))) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_TICKET");
        }
        if ("MARKET".equals(ticket.kind())) {
            if (ticket.trigger() != null) throw error(HttpStatus.BAD_REQUEST, "INVALID_TRIGGER");
            return null;
        }
        try {
            BigDecimal trigger = ticket.trigger();
            if (trigger != null && trigger.signum() > 0 && trigger.compareTo(BigDecimal.valueOf(1_000_000)) <= 0) {
                var normalized = trigger.stripTrailingZeros();
                if (normalized.scale() <= 2) return normalized.movePointRight(2).longValueExact();
            }
        } catch (ArithmeticException ignored) {
            // Out-of-range decimal values cannot form a ticket.
        }
        throw error(HttpStatus.BAD_REQUEST, "INVALID_TRIGGER");
    }

    static ResponseStatusException error(HttpStatus status, String code) { return new ResponseStatusException(status, code); }
}
