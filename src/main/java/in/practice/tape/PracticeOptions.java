package in.practice.tape;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static in.practice.tape.DerivativeBook.*;
import static in.practice.tape.DerivativeContracts.*;
import static java.lang.Math.*;

@Service
@Profile("accounts")
@Primary
class PracticeOptions {
    enum Side { BUY, SELL }
    record Ticket(UUID requestId, UUID contractId, Integer specVersion, Side side, Integer lots, Long limitPaise, Boolean reduceOnly) {
        Ticket(UUID requestId, UUID contractId, Integer specVersion, Side side, Integer lots, Long limitPaise) {
            this(requestId, contractId, specVersion, side, lots, limitPaise, false);
        }
    }
    record Listing(UUID id, Kind kind, Instant expiry, long strikePaise, int specVersion, int lotSize, int multiplier, long tickPaise,
                   long markPaise, long marginPerLotPaise, int maxLots, long maintenancePerLotPaise) {}
    record Holding(UUID contractId, Kind kind, Instant expiry, long strikePaise, long lots, long basisPaise,
                   long marginPaise, long markPaise, long unrealizedPaise) {}
    record Balances(long cashPaise, long reservedPaise, long marginPaise, long availablePaise, long realizedPaise,
                    long maintenanceShortfallPaise) {}
    record View(Instant asOf, String source, long underlyingPaise, long orderFeePaise, List<Listing> chain,
                Balances balances, List<Holding> positions, List<Order> orders, boolean hasMore, List<Expiry> settlements,
                List<Posting> journal, boolean marksPending, boolean submissionsOpen) {}
    record Posting(long sequence, UUID contractId, Instant expiry, String kind, Instant occurredAt, Long pricePaise,
                   long cashPaise, long feePaise, long realizedPaise) {}
    record Expiry(UUID contractId, Kind kind, Instant expiry, long strikePaise, long lots, long underlyingPaise,
                  long payoutPaise, long realizedPaise) {}
    record Order(UUID id, UUID requestId, UUID contractId, int specVersion, Side side, int lots, Long limitPaise,
                 long feePaise, long reservationPaise, String status, String reason, Long fillPaise,
                 Long premiumPaise, Long realizedPaise, Kind kind, Instant expiry, long strikePaise, Instant createdAt, boolean reduceOnly) {}
    record Work(UUID owner, UUID account, UUID item) {}
    private record ContractSpec(Contract contract, Spec spec) {}
    private record Mark(UUID contract, int version, LocalDate day, Instant at) {}

    private static final long FUNDING_PAISE = 100_000_000;
    private static final UUID FIRST = new UUID(0, 0);
    private static final String FUTURE_POSITIONS = """
        FROM derivative_positions p JOIN derivative_contracts c ON c.id=p.contract_id
        JOIN LATERAL (SELECT occurred_at FROM derivative_events e WHERE e.account_id=p.account_id AND e.contract_id=p.contract_id
            ORDER BY e.seq DESC LIMIT 1) last ON true
        """;
    private static final String ORDER_SELECT = """
        SELECT o.*, c.kind, c.expiry_at, c.strike_paise, e.price_paise, e.premium_paise, e.realized_paise
        FROM practice_derivative_orders o JOIN derivative_contracts c ON c.id=o.contract_id
        LEFT JOIN derivative_events e ON e.account_id=o.account_id AND e.request_id=o.fill_request_id
        """;
    private static final RowMapper<Order> ORDER = (rs, row) -> new Order(rs.getObject("id", UUID.class),
        rs.getObject("request_id", UUID.class), rs.getObject("contract_id", UUID.class), rs.getInt("spec_version"),
        Side.valueOf(rs.getString("side")), rs.getInt("lots"), rs.getObject("limit_paise", Long.class),
        rs.getLong("fee_paise"), rs.getLong("reservation_paise"), rs.getString("status"), rs.getString("reason"),
        rs.getObject("price_paise", Long.class), rs.getObject("premium_paise", Long.class),
        rs.getObject("realized_paise", Long.class), Kind.valueOf(rs.getString("kind")), rs.getTimestamp("expiry_at").toInstant(),
        rs.getLong("strike_paise"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("reduce_only"));

    private final JdbcTemplate db;
    private final DerivativeBook book;
    private final DerivativeContracts contracts;
    private final TransactionTemplate savepoint;
    private final Clock clock;
    private final boolean enabled;
    private final boolean submissions;
    private final long fee;
    private final String source;
    private final boolean futures;

    @Autowired
    PracticeOptions(JdbcTemplate db, TransactionTemplate tx, @Value("${tape.practice-options.enabled:false}") boolean enabled,
                    @Value("${tape.practice-options.fee-paise:200}") long fee,
                    @Value("${tape.practice-options.submissions:true}") boolean submissions) {
        this(db, tx, Clock.systemUTC(), enabled, fee, submissions);
    }

    PracticeOptions(JdbcTemplate db, TransactionTemplate tx, Clock clock, boolean enabled, long fee) {
        this(db, tx, clock, enabled, fee, true);
    }

    PracticeOptions(JdbcTemplate db, TransactionTemplate tx, Clock clock, boolean enabled, long fee, boolean submissions) {
        this(db, tx, clock, enabled, fee, submissions, PracticeOptionPrices.SOURCE);
    }

    PracticeOptions(JdbcTemplate db, TransactionTemplate tx, Clock clock, boolean enabled, long fee, boolean submissions, String source) {
        if (fee <= 0 || fee > 10_000) throw new IllegalArgumentException("Practice fee must be 1–10000 paise");
        this.db = db;
        this.clock = clock;
        this.enabled = enabled;
        this.submissions = submissions;
        this.fee = fee;
        this.source = source;
        this.futures = PracticeFutures.SOURCE.equals(source);
        savepoint = new TransactionTemplate(tx.getTransactionManager());
        savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        book = new DerivativeBook(db, savepoint, clock);
        contracts = new DerivativeContracts(db);
    }

    View view(UUID owner, UUID account, int offset) {
        if (offset < 0 || offset > 1_000_000) throw reject("INVALID_REQUEST");
        return book.locked(owner, account, account, () -> {
            requireEnabled();
            Instant at = now();
            var chain = db.query("""
                SELECT c.*,s.* FROM derivative_contracts c
                LEFT JOIN derivative_positions p ON p.account_id=? AND p.contract_id=c.id
                JOIN LATERAL (SELECT * FROM derivative_specs s WHERE s.contract_id=c.id AND
                    (s.version=p.spec_version OR (p.spec_version IS NULL AND s.effective_at<=?))
                    ORDER BY s.effective_at DESC LIMIT 1) s ON true
                WHERE c.source=? AND c.expiry_at>? ORDER BY c.expiry_at,c.strike_paise,c.kind
                """, (rs, row) -> new ContractSpec(CONTRACT.mapRow(rs, row), SPEC.mapRow(rs, row)), account, Timestamp.from(at),
                source, Timestamp.from(at)).stream()
                .filter(item -> eligible(item.contract(), item.spec())).map(item -> listing(item, at)).toList();
            var orders = db.query(ORDER_SELECT + " WHERE o.account_id=? AND c.source=? ORDER BY o.created_at DESC, o.id DESC LIMIT 26 OFFSET ?",
                ORDER, account, source, offset);
            boolean funded = exists(account);
            var wallet = funded ? book.wallet(account) : new Wallet(FUNDING_PAISE, 0, 0);
            long realized = db.queryForObject("""
                SELECT coalesce(sum(e.realized_paise),0) FROM derivative_events e
                JOIN derivative_contracts c ON c.id=e.contract_id WHERE e.account_id=? AND c.source=?
                """, java.math.BigDecimal.class, account, source).longValueExact();
            var positions = db.query("""
                SELECT c.*,s.*,p.lots,p.basis_paise,p.collateral_paise FROM derivative_positions p
                JOIN derivative_contracts c ON c.id=p.contract_id
                JOIN derivative_specs s ON s.contract_id=p.contract_id AND s.version=p.spec_version
                WHERE p.account_id=? AND c.source=? ORDER BY c.expiry_at,c.strike_paise,c.kind
                """, (rs, row) -> {
                    var contract = CONTRACT.mapRow(rs, row);
                    var spec = SPEC.mapRow(rs, row);
                    long lots = rs.getLong("lots"), basis = rs.getLong("basis_paise");
                    long mark = at.isBefore(contract.expiry()) ? price(contract, spec, at) : expiryPrice(contract);
                    long pnl = bounded(Long.signum(lots) * subtractExact(multiplyExact(spec.units(abs(lots)), mark), basis));
                    return new Holding(contract.id(), contract.kind(), contract.expiry(), contract.strikePaise(), lots,
                        basis, rs.getLong("collateral_paise"), mark, pnl);
                }, account, source);
            var settlements = db.query("""
                SELECT e.*,c.kind AS option_kind,c.strike_paise FROM derivative_events e
                JOIN derivative_contracts c ON c.id=e.contract_id
                WHERE e.account_id=? AND e.kind='SETTLE' AND c.source=? ORDER BY e.seq DESC LIMIT 25
                """, (rs, row) -> new Expiry(rs.getObject("contract_id", UUID.class), Kind.valueOf(rs.getString("option_kind")),
                rs.getTimestamp("occurred_at").toInstant(), rs.getLong("strike_paise"), -rs.getLong("signed_lots"),
                rs.getLong("price_paise"), rs.getLong("cash_delta_paise"), rs.getLong("realized_paise")), account, source);
            var journal = futures ? db.query("""
                SELECT e.*,c.expiry_at FROM derivative_events e JOIN derivative_contracts c ON c.id=e.contract_id
                WHERE e.account_id=? AND c.source=? ORDER BY e.seq DESC LIMIT 25
                """, (rs, row) -> new Posting(rs.getLong("seq"), rs.getObject("contract_id", UUID.class),
                rs.getTimestamp("expiry_at").toInstant(), rs.getString("kind"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("price_paise", Long.class), rs.getLong("cash_delta_paise"), rs.getLong("fee_paise"),
                rs.getLong("realized_paise")), account, source) : List.<Posting>of();
            return new View(at, source, PracticeOptionPrices.underlying(at), fee, chain,
                new Balances(wallet.cash(), wallet.reserved(), wallet.collateral(), wallet.available(), realized,
                    funded ? book.maintenanceShortfall(owner, account) : 0), positions, orders.subList(0, min(25, orders.size())),
                orders.size() > 25, settlements, journal, futures && !dueMarks(account, at, 1).isEmpty(), submissions && tradingEnabled(account));
        });
    }

    Order submit(UUID owner, UUID account, Ticket ticket) {
        if (ticket == null || ticket.requestId() == null || ticket.contractId() == null || ticket.specVersion() == null ||
            ticket.side() == null || ticket.lots() == null || ticket.lots() < 1 || ticket.lots() > 100_000) throw reject("INVALID_REQUEST");
        return book.locked(owner, account, ticket.requestId(), () -> {
            requireEnabled();
            boolean reduceOnly = Boolean.TRUE.equals(ticket.reduceOnly());
            var prior = find(account, "o.request_id", ticket.requestId());
            if (prior != null) {
                if ((prior.kind() == Kind.FUTURE) != futures || !prior.contractId().equals(ticket.contractId()) || prior.specVersion() != ticket.specVersion() ||
                    prior.side() != ticket.side() || prior.lots() != ticket.lots() || prior.reduceOnly() != reduceOnly ||
                    !java.util.Objects.equals(prior.limitPaise(), ticket.limitPaise())) throw reject("REQUEST_CONFLICT");
                return prior;
            }
            if (!submissions || !tradingEnabled(account)) throw reject(disabledCode());
            Instant at = now();
            Contract contract = contracts.contract(ticket.contractId());
            Spec effective = contracts.effective(contract.id(), at);
            if (!at.isBefore(contract.expiry())) throw reject("CONTRACT_NOT_ACTIVE");
            if (!supports(contract) || effective == null) throw reject("UNSUPPORTED_CONTRACT");
            advanceFutures(owner, account, at);
            if (Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM practice_derivative_orders WHERE account_id=? AND contract_id=? AND status='OPEN')
                """, Boolean.class, account, contract.id()))) throw reject("OPEN_ORDER_EXISTS");
            var position = book.position(account, contract.id());
            Spec spec = position.lots() == 0 ? effective : contracts.version(contract.id(), position.version());
            if (spec.version() != ticket.specVersion()) throw reject("SPEC_CHANGED");
            if (!eligible(contract, spec)) throw reject("UNSUPPORTED_CONTRACT");
            long delta = ticket.side() == Side.BUY ? ticket.lots() : -ticket.lots();
            long net = position.lots() + delta;
            long opened = ticket.lots() - (Long.signum(position.lots()) == -Long.signum(delta) ? min(abs(position.lots()), abs(delta)) : 0);
            if (reduceOnly && opened > 0) throw reject("REDUCTION_REQUIRED");
            if (opened > 0 && spec.version() != effective.version()) throw reject("SPEC_CHANGED");
            if (ticket.lots() > spec.maxLots() || opened > 0 && abs(net) > spec.maxLots()) throw reject("POSITION_LIMIT");
            Long limit = ticket.limitPaise();
            if (limit != null && (limit <= 0 || limit > 9_007_199_254_740_991L || limit % spec.tickPaise() != 0)) throw reject("INVALID_TICK");
            if (!exists(account)) book.open(owner, account, UUID.randomUUID(), FUNDING_PAISE);
            if (futures && opened > 0 && book.wallet(account).available() < 0) throw reject("INSUFFICIENT_MARGIN");
            long mark = price(contract, spec, at);
            boolean fillNow = limit == null || crosses(ticket.side(), limit, mark);
            long reserve = 0;
            if (!fillNow) {
                long collateralDelta = bounded(multiplyExact(futures ? abs(net) : max(0, -net), spec.initialMarginPaise())) - position.collateral();
                long value = !futures && delta > 0 ? bounded(multiplyExact(spec.units(ticket.lots()), limit)) : 0;
                if (opened > 0 && delta > 0 && value > spec.maxOrderNotionalPaise()) throw reject("NOTIONAL_LIMIT");
                if (!futures || opened > 0) reserve = max(0, bounded(addExact(addExact(fee, value), collateralDelta)));
                if (reserve != 0) book.reserve(owner, account, UUID.randomUUID(), reserve);
            }
            UUID id = UUID.randomUUID();
            if (fillNow && book.fill(owner, account, id, contract.id(), multiplyExact(delta, spec.lotSize()), mark, fee, at).version()
                != spec.version()) throw reject("SPEC_CHANGED");
            db.update("""
                INSERT INTO practice_derivative_orders(id,account_id,request_id,contract_id,spec_version,side,lots,limit_paise,
                    fee_paise,reservation_paise,status,fill_request_id,created_at,updated_at,reduce_only) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, account, ticket.requestId(), contract.id(), spec.version(), ticket.side().name(), ticket.lots(), limit,
                fee, reserve, fillNow ? "FILLED" : "OPEN", fillNow ? id : null, Timestamp.from(at), Timestamp.from(at), reduceOnly);
            return find(account, "o.id", id);
        });
    }

    Order cancel(UUID owner, UUID account, UUID id) {
        return book.locked(owner, account, id, () -> {
            requireEnabled();
            var order = find(account, "o.id", id);
            if (order == null) throw reject("ORDER_NOT_FOUND");
            if ("OPEN".equals(order.status())) finish(owner, account, order, now().isBefore(order.expiry()) ? "CANCELLED" : "EXPIRED", null);
            return find(account, "o.id", id);
        });
    }

    List<Work> openOrders(UUID cursor) {
        return db.query("""
            SELECT a.user_id,o.account_id,o.id FROM practice_derivative_orders o JOIN trading_accounts a ON a.id=o.account_id
            JOIN derivative_contracts c ON c.id=o.contract_id
            WHERE c.source=? AND o.status='OPEN' AND o.id>? ORDER BY o.id LIMIT 100
            """, PracticeOptions::work, source, cursor == null ? FIRST : cursor);
    }

    List<Work> expiredPositions(Work cursor) {
        return db.query("""
            SELECT a.user_id,p.account_id,p.contract_id FROM derivative_positions p
            JOIN derivative_contracts c ON c.id=p.contract_id JOIN trading_accounts a ON a.id=p.account_id
            WHERE c.source=? AND c.expiry_at<=? AND (p.contract_id,p.account_id)>(?,?)
            ORDER BY p.contract_id,p.account_id LIMIT 100
            """, PracticeOptions::work, source, Timestamp.from(now()),
            cursor == null ? FIRST : cursor.item(), cursor == null ? FIRST : cursor.account());
    }

    List<Work> dueFutures(UUID cursor) {
        var at = Timestamp.from(now());
        return db.query("""
            SELECT a.user_id,a.id,a.id FROM trading_accounts a
            WHERE a.id>? AND EXISTS (SELECT 1
            """ + FUTURE_POSITIONS + """
                WHERE p.account_id=a.id AND c.source=? AND (c.expiry_at<=? OR EXISTS (
                    SELECT 1 FROM practice_future_sessions s WHERE s.contract_id=p.contract_id
                    AND s.mark_at>last.occurred_at AND s.mark_at<=? AND s.mark_at<c.expiry_at)))
            ORDER BY a.id LIMIT 100
            """, PracticeOptions::work, cursor == null ? FIRST : cursor, PracticeFutures.SOURCE, at, at);
    }

    void markFutures(Work work) {
        book.locked(work.owner(), work.account(), work.account(), () -> {
            advanceFutures(work.owner(), work.account(), now());
            return null;
        });
    }

    private List<Mark> dueMarks(UUID account, Instant at, int limit) {
        return db.query("SELECT p.contract_id,p.spec_version,s.session_day,s.mark_at " + FUTURE_POSITIONS + """
            JOIN practice_future_sessions s ON s.contract_id=p.contract_id
            WHERE p.account_id=? AND c.source=? AND s.mark_at>last.occurred_at AND s.mark_at<=? AND s.mark_at<c.expiry_at
            ORDER BY s.mark_at,p.contract_id LIMIT ?
            """, (rs, row) -> new Mark(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, LocalDate.class),
                rs.getTimestamp(4).toInstant()), account, PracticeFutures.SOURCE, Timestamp.from(at), limit);
    }

    private void advanceFutures(UUID owner, UUID account, Instant at) {
        for (var mark : dueMarks(account, at, Integer.MAX_VALUE)) {
            var spec = contracts.version(mark.contract(), mark.version());
            book.mark(owner, account, UUID.randomUUID(), mark.contract(), mark.day(), mark.at(), PracticeFutures.quote(spec, mark.at()));
        }
        var matured = db.query("""
            SELECT c.* FROM derivative_positions p JOIN derivative_contracts c ON c.id=p.contract_id
            WHERE p.account_id=? AND c.source=? AND c.expiry_at<=? ORDER BY c.expiry_at,c.id
            """, CONTRACT, account, PracticeFutures.SOURCE, Timestamp.from(at));
        for (var contract : matured) {
            var open = findOpen(account, contract.id());
            if (open != null) finish(owner, account, open, "EXPIRED", null);
            book.settle(owner, account, UUID.randomUUID(), contract.id(), PracticeOptionPrices.underlying(contract.expiry()));
        }
    }

    void match(Work work) {
        book.locked(work.owner(), work.account(), work.item(), () -> {
            var order = find(work.account(), "o.id", work.item());
            if (order == null || !"OPEN".equals(order.status())) return null;
            Instant at = now();
            if (!at.isBefore(order.expiry())) {
                finish(work.owner(), work.account(), order, "EXPIRED", null);
                return null;
            }
            if (!submissions || !tradingEnabled(work.account())) return null;
            var contract = contracts.contract(order.contractId());
            var spec = contracts.version(contract.id(), order.specVersion());
            if (!eligible(contract, spec)) {
                finish(work.owner(), work.account(), order, "REJECTED", "UNSUPPORTED_CONTRACT");
                return null;
            }
            long mark = price(contract, spec, at);
            if (!crosses(order.side(), order.limitPaise(), mark)) return null;
            advanceFutures(work.owner(), work.account(), at);
            try {
                savepoint.executeWithoutResult(status -> {
                    if (order.reduceOnly()) {
                        long lots = book.position(work.account(), order.contractId()).lots();
                        if (abs(lots) < order.lots() || (lots > 0) == (order.side() == Side.BUY)) throw reject("REDUCTION_REQUIRED");
                    }
                    release(work.owner(), work.account(), order);
                    long quantity = multiplyExact((long) order.lots(), spec.lotSize()) * (order.side() == Side.BUY ? 1 : -1);
                    var fill = book.fill(work.owner(), work.account(), order.id(), contract.id(), quantity, mark, order.feePaise(), at);
                    if (fill.version() != order.specVersion()) throw reject("SPEC_CHANGED");
                    update(work.account(), order, "FILLED", null);
                });
            } catch (Rejected failure) {
                String state = "CONTRACT_NOT_ACTIVE".equals(failure.getMessage()) && !now().isBefore(order.expiry()) ? "EXPIRED" : "REJECTED";
                finish(work.owner(), work.account(), order, state, "EXPIRED".equals(state) ? null : failure.getMessage());
            }
            return null;
        });
    }

    void settle(Work work) {
        book.locked(work.owner(), work.account(), work.item(), () -> {
            var contract = contracts.contract(work.item());
            var position = book.position(work.account(), work.item());
            if (position.lots() == 0 || now().isBefore(contract.expiry()) || !supports(contract)) return null;
            if (futures) {
                advanceFutures(work.owner(), work.account(), now());
                return null;
            }
            var open = findOpen(work.account(), work.item());
            if (open != null) finish(work.owner(), work.account(), open, "EXPIRED", null);
            book.settle(work.owner(), work.account(), UUID.randomUUID(), work.item(), PracticeOptionPrices.underlying(contract.expiry()));
            return null;
        });
    }

    AccountState reconcile(UUID owner, UUID account) {
        return book.locked(owner, account, account, () -> {
            var state = book.reconcile(owner, account);
            var reserved = db.queryForObject("""
                SELECT coalesce(sum(reservation_paise),0) FROM practice_derivative_orders WHERE account_id=? AND status='OPEN'
                """, java.math.BigDecimal.class, account).longValueExact();
            boolean mismatchedFill = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM practice_derivative_orders o JOIN derivative_events e
                    ON e.account_id=o.account_id AND e.request_id=o.fill_request_id
                WHERE o.account_id=? AND (e.kind<>'FILL' OR e.fee_paise<>o.fee_paise
                    OR e.signed_lots<>CASE WHEN o.side='BUY' THEN o.lots ELSE -o.lots END))
                """, Boolean.class, account));
            if (reserved != state.wallet().reserved() || mismatchedFill) throw reject("JOURNAL_MISMATCH");
            return state;
        });
    }

    private boolean tradingEnabled(UUID account) {
        return enabled && Boolean.TRUE.equals(db.queryForObject("SELECT trading_enabled FROM trading_accounts WHERE id=?", Boolean.class, account));
    }

    private void requireEnabled() { if (!enabled) throw reject(disabledCode()); }
    private String disabledCode() { return futures ? "FUTURES_DISABLED" : "OPTIONS_DISABLED"; }
    boolean isFutures() { return futures; }

    private boolean supports(Contract contract) {
        return futures ? PracticeFutures.supports(contract) : PracticeOptionPrices.supports(contract);
    }

    private long price(Contract contract, Spec spec, Instant at) {
        return futures ? PracticeFutures.quote(spec, at) : PracticeOptionPrices.premium(contract, spec, at);
    }

    private long expiryPrice(Contract contract) {
        long underlying = PracticeOptionPrices.underlying(contract.expiry());
        if (futures) return underlying;
        return max(0, (contract.kind() == Kind.CALL ? 1 : -1) * (underlying - contract.strikePaise()));
    }

    private boolean exists(UUID account) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM derivative_books WHERE account_id=?)", Boolean.class, account));
    }

    private Order find(UUID account, String column, UUID value) {
        String filter = "o.id".equals(column) ? " AND c.source=?" : "";
        Object[] parameters = "o.id".equals(column) ? new Object[] { account, value, source } : new Object[] { account, value };
        return db.query(ORDER_SELECT + " WHERE o.account_id=? AND " + column + "=?" + filter, ORDER, parameters)
            .stream().findFirst().orElse(null);
    }

    private Order findOpen(UUID account, UUID contract) {
        return db.query(ORDER_SELECT + " WHERE o.account_id=? AND o.contract_id=? AND o.status='OPEN'", ORDER, account, contract)
            .stream().findFirst().orElse(null);
    }

    private void finish(UUID owner, UUID account, Order order, String status, String reason) {
        release(owner, account, order);
        update(account, order, status, reason);
    }

    private void release(UUID owner, UUID account, Order order) {
        if (order.reservationPaise() != 0) book.reserve(owner, account, UUID.randomUUID(), -order.reservationPaise());
    }

    private void update(UUID account, Order order, String status, String reason) {
        db.update("""
            UPDATE practice_derivative_orders SET status=?,reason=?,fill_request_id=?,updated_at=greatest(?,created_at)
            WHERE account_id=? AND id=?
            """, status, reason, "FILLED".equals(status) ? order.id() : null, Timestamp.from(now()), account, order.id());
    }

    private boolean eligible(Contract contract, Spec spec) {
        return supports(contract) && spec != null && (futures || spec.initialMarginPaise() >= PracticeOptionPrices.maximumPayout(contract, spec));
    }

    private Listing listing(ContractSpec item, Instant at) {
        var contract = item.contract();
        var spec = item.spec();
        return new Listing(contract.id(), contract.kind(), contract.expiry(), contract.strikePaise(), spec.version(), spec.lotSize(), spec.multiplier(),
            spec.tickPaise(), price(contract, spec, at), spec.initialMarginPaise(), spec.maxLots(), spec.maintenanceMarginPaise());
    }

    private static Work work(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Work(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class));
    }

    private static boolean crosses(Side side, long limit, long mark) { return side == Side.BUY ? mark <= limit : mark >= limit; }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
