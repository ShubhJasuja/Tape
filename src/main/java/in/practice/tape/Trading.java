package in.practice.tape;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

@Service
class Trading {
    static final long STARTING_CASH = 100_000_000;
    record Ticket(UUID id, String symbol, String side, String kind, int quantity, BigDecimal trigger) {}
    record Order(UUID id, String symbol, String side, String kind, int quantity, Long trigger,
                 String status, Long fill, Long fee, long created, long updated) {}
    record Holding(String symbol, int quantity, long cost, Long price, Long value, Long pnl) {}
    record Account(long cash, long available, long realized, Long equity, Long unrealized,
                   List<Holding> holdings, List<Order> orders) {}

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Market market;

    Trading(JdbcTemplate db, TransactionTemplate tx, Market market) {
        this.db = db;
        this.tx = tx;
        this.market = market;
    }

    synchronized Account account(Market.Snapshot snapshot) {
        List<Order> orders = orders(snapshot.mode());
        var book = new Book(orders);
        List<Holding> holdings = new ArrayList<>();
        long value = 0;
        long cost = 0;
        boolean complete = true;
        for (var entry : book.positions.entrySet()) {
            String symbol = entry.getKey();
            Position pos = entry.getValue();
            var quote = snapshot.quotes().get(symbol);
            if (quote == null) complete = false;
            Long marked = quote == null ? null : quote.price() * pos.quantity();
            if (marked != null) value += marked;
            cost += pos.cost();
            holdings.add(new Holding(symbol, pos.quantity(), pos.cost(), quote == null ? null : quote.price(),
                marked, marked == null ? null : marked - pos.cost()));
        }
        return new Account(book.cash, book.available, book.realized, complete ? book.cash + value : null,
            complete ? value - cost : null, holdings, orders.reversed().stream().toList());
    }

    synchronized Order submit(String mode, Ticket ticket) {
        market.snapshot(mode);
        if (ticket == null || ticket.id() == null || ticket.quantity() < 1 || ticket.quantity() > 100_000 ||
            !("BUY".equals(ticket.side()) || "SELL".equals(ticket.side())) ||
            !("MARKET".equals(ticket.kind()) || "LIMIT".equals(ticket.kind()) || "STOP".equals(ticket.kind()))) {
            throw bad("Enter a valid order and a whole share quantity between 1 and 100,000.");
        }
        Market.instrument(ticket.symbol());
        boolean immediate = "MARKET".equals(ticket.kind());
        Long trigger = immediate ? null : trigger(ticket.trigger());
        return tx.execute(ignored -> {
            var orders = orders(mode);
            for (Order old : orders) {
                if (!old.id().equals(ticket.id())) continue;
                if (old.symbol().equals(ticket.symbol()) && old.side().equals(ticket.side()) &&
                    old.kind().equals(ticket.kind()) && old.quantity() == ticket.quantity() &&
                    Objects.equals(old.trigger(), trigger)) return old;
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order ID already used for a different ticket.");
            }
            long now = System.currentTimeMillis();
            var quote = market.snapshot(mode).tradeable(ticket.symbol(), now);
            if (immediate && quote == null) throw bad("Market orders need a fresh quote during market hours.");
            var book = new Book(orders);
            long reference = immediate ? quote.price() : trigger;
            long amount = reference * ticket.quantity();
            if (!book.canTrade(ticket.symbol(), ticket.side(), ticket.quantity(), amount)) {
                throw bad("Insufficient available cash or shares.");
            }
            var order = new Order(ticket.id(), ticket.symbol(), ticket.side(), ticket.kind(), ticket.quantity(), trigger,
                immediate ? "FILLED" : "OPEN", immediate ? reference : null, immediate ? fee(amount) : null, now, nextFillTime(orders, now));
            db.update("""
                INSERT INTO paper_order (mode,id,symbol,side,kind,quantity,trigger_price,status,fill_price,fee,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, mode, order.id(), order.symbol(), order.side(), order.kind(), order.quantity(),
                order.trigger(), order.status(), order.fill(), order.fee(), now, order.updated());
            return order;
        });
    }

    synchronized void cancel(String mode, UUID id) {
        market.snapshot(mode);
        if (db.update("UPDATE paper_order SET status='CANCELLED',updated_at=? WHERE mode=? AND id=? AND status='OPEN'",
            System.currentTimeMillis(), mode, id) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is no longer open.");
        }
    }

    @Scheduled(fixedDelay = 2000)
    synchronized void match() {
        for (String mode : List.of("demo", "live")) {
            var snapshot = market.snapshot(mode);
            tx.executeWithoutResult(ignored -> {
                List<Order> orders = orders(mode);
                Book book = new Book(orders);
                long now = System.currentTimeMillis();
                long fillTime = nextFillTime(orders, now);
                for (Order order : orders) {
                    if (!"OPEN".equals(order.status())) continue;
                    var quote = snapshot.tradeable(order.symbol(), now);
                    if (quote == null || !crossed(order, quote.price())) continue;
                    book.release(order);
                    long amount = quote.price() * order.quantity();
                    boolean accepted = book.canTrade(order.symbol(), order.side(), order.quantity(), amount);
                    var filled = new Order(order.id(), order.symbol(), order.side(), order.kind(), order.quantity(),
                        order.trigger(), accepted ? "FILLED" : "REJECTED", accepted ? quote.price() : null,
                        accepted ? fee(amount) : null, order.created(), fillTime++);
                    if (accepted) book.fill(filled);
                    db.update("UPDATE paper_order SET status=?,fill_price=?,fee=?,updated_at=? WHERE mode=? AND id=?",
                        filled.status(), filled.fill(), filled.fee(), filled.updated(), mode, order.id());
                }
            });
        }
    }

    private List<Order> orders(String mode) {
        return db.query("SELECT * FROM paper_order WHERE mode=? ORDER BY seq", (rs, row) ->
            new Order(rs.getObject("id", UUID.class), rs.getString("symbol"), rs.getString("side"),
                rs.getString("kind"), rs.getInt("quantity"), rs.getObject("trigger_price", Long.class),
                rs.getString("status"), rs.getObject("fill_price", Long.class), rs.getObject("fee", Long.class),
                rs.getLong("created_at"), rs.getLong("updated_at")), mode);
    }

    private static long nextFillTime(List<Order> orders, long now) {
        return Math.max(now, 1 + orders.stream().filter(o -> "FILLED".equals(o.status()))
            .mapToLong(Order::updated).max().orElse(0));
    }

    private static boolean crossed(Order order, long price) {
        boolean buy = "BUY".equals(order.side());
        boolean limit = "LIMIT".equals(order.kind());
        return buy == limit ? price <= order.trigger() : price >= order.trigger();
    }

    private static Long trigger(BigDecimal rupees) {
        try {
            if (rupees != null && rupees.signum() > 0 && rupees.compareTo(BigDecimal.valueOf(1_000_000)) <= 0) {
                return rupees.movePointRight(2).longValueExact();
            }
        } catch (ArithmeticException ignored) {
            // Invalid paise precision.
        }
        throw bad("Enter a trigger price with at most two decimal places.");
    }

    // 5 basis points of executed value, rounded up to the next paisa.
    static long fee(long amount) { return (amount + 1999) / 2000; }
    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record Position(int quantity, long cost) {}
    private static final class Book {
        long cash = STARTING_CASH;
        long available = STARTING_CASH;
        long realized;
        final Map<String, Position> positions = new TreeMap<>();
        final Map<String, Integer> reserved = new HashMap<>();

        Book(List<Order> orders) {
            // Fill chronology can differ from submission order for resting orders.
            orders.stream().filter(o -> "FILLED".equals(o.status()))
                .sorted(Comparator.comparingLong(Order::updated)).forEach(this::fill);
            for (Order order : orders) {
                if ("OPEN".equals(order.status())) reserve(order, 1);
            }
        }

        boolean canTrade(String symbol, String side, int quantity, long amount) {
            if ("BUY".equals(side)) return available >= amount + fee(amount);
            return position(symbol).quantity() - reserved.getOrDefault(symbol, 0) >= quantity;
        }

        void fill(Order order) {
            Position pos = position(order.symbol());
            long amount = order.fill() * order.quantity();
            if ("BUY".equals(order.side())) {
                long debit = amount + order.fee();
                cash -= debit;
                available -= debit;
                positions.put(order.symbol(), new Position(pos.quantity() + order.quantity(), pos.cost() + debit));
            } else {
                long proceeds = amount - order.fee();
                long cost = BigDecimal.valueOf(pos.cost()).multiply(BigDecimal.valueOf(order.quantity()))
                    .divideToIntegralValue(BigDecimal.valueOf(pos.quantity())).longValueExact();
                cash += proceeds;
                available += proceeds;
                realized += proceeds - cost;
                int remaining = pos.quantity() - order.quantity();
                if (remaining == 0) positions.remove(order.symbol());
                else positions.put(order.symbol(), new Position(remaining, pos.cost() - cost));
            }
        }

        Position position(String symbol) { return positions.getOrDefault(symbol, new Position(0, 0)); }

        void release(Order order) { reserve(order, -1); }

        void reserve(Order order, int direction) {
            if ("BUY".equals(order.side())) {
                long amount = order.trigger() * order.quantity();
                available -= direction * (amount + fee(amount));
            } else reserved.merge(order.symbol(), direction * order.quantity(), Integer::sum);
        }
    }
}
