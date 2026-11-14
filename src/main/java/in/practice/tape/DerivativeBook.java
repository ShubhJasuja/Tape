
package in.practice.tape;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static in.practice.tape.DerivativeContracts.*;
import static java.lang.Math.*;

final class DerivativeBook {
    private static final long MAX_PAISE = 9_007_199_254_740_991L;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Position FLAT = new Position(0, 0, 0, 0);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DerivativeContracts contracts;

    record Wallet(long cash, long reserved, long collateral) {
        long available() { return cash - reserved - collateral; }
    }

    record Position(long lots, long basis, long collateral, int version) {}
    record AccountState(Wallet wallet, Map<UUID, Position> positions, long realized) {}
    record Event(long seq, UUID request, UUID contract, Integer version, String kind, Long price, LocalDate day,
                 long lots, long basis, long premium, long cash, long reserved, long collateral, long realized,
                 long deliveryUnits, long deliveryCash, long deliveryCollateral, Instant occurredAt, long fee) {
        Event(long seq, UUID request, UUID contract, Integer version, String kind, Long price, LocalDate day,
              long lots, long basis, long premium, long cash, long reserved, long collateral, long realized,
              long deliveryUnits, long deliveryCash, long deliveryCollateral, Instant occurredAt) {
            this(seq, request, contract, version, kind, price, day, lots, basis, premium, cash, reserved, collateral,
                realized, deliveryUnits, deliveryCash, deliveryCollateral, occurredAt, 0);
        }
    }

    private static final RowMapper<Event> EVENT = (rs, row) -> new Event(rs.getLong("seq"),
        rs.getObject("request_id", UUID.class), rs.getObject("contract_id", UUID.class), rs.getObject("spec_version", Integer.class),
        rs.getString("kind"), rs.getObject("price_paise", Long.class), rs.getObject("session_day", LocalDate.class),
        rs.getLong("signed_lots"), rs.getLong("basis_delta_paise"), rs.getLong("premium_paise"), rs.getLong("cash_delta_paise"),
        rs.getLong("reserve_delta_paise"), rs.getLong("collateral_delta_paise"), rs.getLong("realized_paise"),
        rs.getLong("delivery_units"), rs.getLong("delivery_cash_paise"), rs.getLong("delivery_collateral_paise"),
        rs.getTimestamp("occurred_at").toInstant(), rs.getLong("fee_paise"));

    DerivativeBook(JdbcTemplate db, TransactionTemplate tx, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        contracts = new DerivativeContracts(db);
    }

    Event open(UUID owner, UUID account, UUID request, long funding) {
        return locked(owner, account, request, () -> {
            if (funding <= 0) throw reject("INVALID_FUNDING");
            bounded(funding);
            var prior = existing(account, request);
            if (prior != null) {
                if (!"OPEN".equals(prior.kind()) || prior.cash() != funding) throw reject("REQUEST_CONFLICT");
                return prior;
            }
            int created = db.update("""
                INSERT INTO derivative_books(account_id,cash_paise,reserved_paise) VALUES (?,?,0)
                ON CONFLICT DO NOTHING
                """, account, funding);
            if (created != 1) throw reject("BOOK_EXISTS");
            return append(account, new Event(0, request, null, null, "OPEN", null, null, 0, 0, 0, funding,
                0, 0, 0, 0, 0, 0, now()));
        });
    }

    Event reserve(UUID owner, UUID account, UUID request, long delta) {
        return locked(owner, account, request, () -> {
            var prior = existing(account, request);
            if (prior != null) {
                if (!"RESERVE".equals(prior.kind()) || prior.reserved() != delta) throw reject("REQUEST_CONFLICT");
                return prior;
            }
            if (delta == 0) throw reject("INVALID_RESERVATION");
            bounded(delta);
            Wallet book = wallet(account);
            Wallet next = move(book, 0, delta, 0);
            if (delta > 0 && next.available() < 0) throw reject("INSUFFICIENT_MARGIN");
            saveWallet(account, next);
            return append(account, new Event(0, request, null, null, "RESERVE", null, null, 0, 0, 0, 0,
                delta, 0, 0, 0, 0, 0, now()));
        });
    }

    Event fill(UUID owner, UUID account, UUID request, UUID contractId, long signedQuantity, long price) {
        return fill(owner, account, request, contractId, signedQuantity, price, 0);
    }

    Event fill(UUID owner, UUID account, UUID request, UUID contractId, long signedQuantity, long price, long fee) {
        return locked(owner, account, request, () -> {
            if (fee < 0) throw reject("INVALID_FEE");
            bounded(fee);
            var prior = existing(account, request);
            if (prior != null) {
                if (!"FILL".equals(prior.kind()) || !prior.contract().equals(contractId) || prior.price() != price || prior.fee() != fee ||
                    multiplyExact(prior.lots(), contracts.version(contractId, prior.version()).lotSize()) != signedQuantity) {
                    throw reject("REQUEST_CONFLICT");
                }
                return prior;
            }
            Instant at = now();
            Contract contract = contracts.contract(contractId);
            Spec effective = contracts.effective(contractId, at);
            if (contract.status(at, effective) != Status.ACTIVE) throw reject("CONTRACT_NOT_ACTIVE");
            requireChronology(account, contractId, at, false);
            Position before = position(account, contractId);
            Spec spec = before.lots() == 0 ? effective : contracts.version(contractId, before.version());
            if (signedQuantity == 0 || signedQuantity % spec.lotSize() != 0) throw reject("INVALID_LOT_SIZE");
            long delta = signedQuantity / spec.lotSize();
            if (delta < -100_000 || delta > 100_000 || price <= 0 || price > MAX_PAISE || price % spec.tickPaise() != 0) {
                throw reject("INVALID_FILL");
            }
            long oldSize = abs(before.lots()), size = abs(delta);
            long net = before.lots() + delta;
            boolean opposite = before.lots() != 0 && Long.signum(before.lots()) != Long.signum(delta);
            long closed = opposite ? min(oldSize, size) : 0;
            long opened = size - closed;
            if (opened > 0 && spec.version() != effective.version()) throw reject("SPEC_CHANGED");
            if (abs(net) > spec.maxLots() && opened > 0) throw reject("POSITION_LIMIT");
            long notional = bounded(multiplyExact(spec.units(size), price));
            if (opened > 0 && notional > spec.maxOrderNotionalPaise()) throw reject("NOTIONAL_LIMIT");
            long allocated = allocation(before.basis(), closed, oldSize);
            long closeValue = multiplyExact(spec.units(closed), price);
            long realized = bounded(multiplyExact(Long.signum(before.lots()), closeValue - allocated));
            long basis = bounded(addExact(before.basis() - allocated, multiplyExact(spec.units(opened), price)));
            long collateral = collateral(contract, spec, net);
            long marginDelta = collateral - before.collateral();
            boolean future = contract.kind() == Kind.FUTURE;
            long premium = future ? 0 : -Long.signum(delta) * notional;
            long cash = bounded(subtractExact(future ? realized : premium, fee));
            Wallet book = wallet(account);
            Wallet next = move(book, cash, 0, marginDelta);
            if (!future && next.available() < 0) throw reject("INSUFFICIENT_MARGIN");
            // New short collateral cannot be funded by the premium for the newly opened lots.
            long openingShortPremium = !future && delta < 0 ? notional - closeValue : 0;
            if (opened > 0 && (next.available() < 0 || book.available() < 0 ||
                next.available() < openingShortPremium)) throw reject("INSUFFICIENT_MARGIN");
            return apply(account, contractId, next, new Position(net, basis, collateral, spec.version()),
                new Event(0, request, contractId, spec.version(), "FILL", price, null, delta,
                    basis - before.basis(), premium, cash, 0, marginDelta, bounded(subtractExact(realized, fee)),
                    0, 0, 0, at, fee));
        });
    }

    Event mark(UUID owner, UUID account, UUID request, UUID contractId, LocalDate session, Instant markAt, long price) {
        return locked(owner, account, request, () -> {
            Event prior = previousLifecycle(account, request, contractId, "MARK", session, markAt, price);
            if (prior != null) return prior;
            Contract contract = contracts.contract(contractId);
            validateTime(markAt);
            requireChronology(account, contractId, markAt, true);
            if (contract.kind() != Kind.FUTURE || !markAt.isBefore(contract.expiry()) ||
                !markAt.atZone(INDIA).toLocalDate().equals(session) || price <= 0) throw reject("INVALID_MARK");
            Position before = position(account, contractId);
            if (before.lots() == 0) throw reject("POSITION_NOT_FOUND");
            Spec spec = contracts.version(contractId, before.version());
            long basis = bounded(multiplyExact(spec.units(abs(before.lots())), price));
            long pnl = bounded(Long.signum(before.lots()) * (basis - before.basis()));
            return apply(account, contractId, move(wallet(account), pnl, 0, 0),
                new Position(before.lots(), basis, before.collateral(), spec.version()),
                new Event(0, request, contractId, spec.version(), "MARK", price, session, 0, basis - before.basis(),
                    0, pnl, 0, 0, pnl, 0, 0, 0, markAt));
        });
    }

    Event settle(UUID owner, UUID account, UUID request, UUID contractId, long finalPrice) {
        return locked(owner, account, request, () -> {
            Contract contract = contracts.contract(contractId);
            Instant expiry = contract.expiry();
            LocalDate day = expiry.atZone(INDIA).toLocalDate();
            var prior = previousLifecycle(account, request, contractId, "SETTLE", day, expiry, finalPrice);
            if (prior != null) return prior;
            validateTime(expiry);
            requireChronology(account, contractId, expiry, false);
            if (finalPrice <= 0) throw reject("INVALID_SETTLEMENT_PRICE");
            bounded(finalPrice);
            Wallet book = wallet(account);
            Position before = position(account, contractId);
            Spec spec = before.lots() == 0 ? contracts.effective(contractId, expiry.minusNanos(1_000)) :
                contracts.version(contractId, before.version());
            if (spec == null) throw reject("SPEC_NOT_FOUND");
            long units = spec.units(before.lots());
            long intrinsic = switch (contract.kind()) {
                case FUTURE -> finalPrice;
                case CALL -> max(0, finalPrice - contract.strikePaise());
                case PUT -> max(0, contract.strikePaise() - finalPrice);
            };
            long signedValue = bounded(multiplyExact(units, intrinsic));
            long pnl = bounded(subtractExact(signedValue, Long.signum(units) * before.basis()));
            long deliver = contract.settlement() == Settlement.PHYSICAL && intrinsic > 0 ?
                (contract.kind() == Kind.PUT ? -units : units) : 0;
            long deliveryCash = bounded(multiplyExact(-deliver, contract.kind() == Kind.FUTURE ? finalPrice : contract.strikePaise()));
            long retained = deliver == 0 ? 0 : max(before.collateral(), max(0, -deliveryCash));
            long cash = contract.kind() == Kind.FUTURE ? pnl : deliver == 0 ? signedValue : 0;
            long marginDelta = retained - before.collateral();
            return apply(account, contractId, move(book, cash, 0, marginDelta), FLAT,
                new Event(0, request, contractId, spec.version(), "SETTLE", finalPrice, day, -before.lots(),
                    -before.basis(), 0, cash, 0, marginDelta, pnl, deliver, deliveryCash, retained, expiry));
        });
    }

    long unrealized(UUID owner, UUID account, UUID contract, long markPrice) {
        return locked(owner, account, account, () -> {
            var position = position(account, contract);
            if (markPrice < 0 || position.lots() == 0) {
                if (markPrice < 0) throw reject("INVALID_MARK");
                return 0L;
            }
            var spec = contracts.version(contract, position.version());
            long value = bounded(multiplyExact(spec.units(abs(position.lots())), markPrice));
            return bounded(Long.signum(position.lots()) * (value - position.basis()));
        });
    }

    long maintenanceShortfall(UUID owner, UUID account) {
        return locked(owner, account, account, () -> {
            var required = db.queryForObject("""
                SELECT coalesce(sum(abs(p.lots)::numeric * s.maintenance_margin_paise),0)
                FROM derivative_positions p JOIN derivative_specs s
                  ON (p.contract_id,p.spec_version)=(s.contract_id,s.version)
                WHERE p.account_id=? AND p.collateral_paise > 0
                """, java.math.BigDecimal.class, account).longValueExact();
            Wallet book = wallet(account);
            long pendingDelivery = db.queryForObject("""
                SELECT coalesce(sum(delivery_collateral_paise),0) FROM derivative_events WHERE account_id=? AND kind='SETTLE'
                """, java.math.BigDecimal.class, account).longValueExact();
            return max(0L, addExact(addExact(required, pendingDelivery), subtractExact(book.reserved(), book.cash())));
        });
    }

    AccountState reconcile(UUID owner, UUID account) {
        return locked(owner, account, account, () -> {
            Wallet journal = new Wallet(0, 0, 0);
            Map<UUID, Position> expected = new HashMap<>();
            long realized = 0, delivery = 0;
            for (var event : db.query("SELECT * FROM derivative_events WHERE account_id=? ORDER BY seq", EVENT, account)) {
                journal = move(journal, event.cash(), event.reserved(), event.collateral());
                realized = addExact(realized, event.realized());
                delivery = addExact(delivery, event.deliveryCollateral());
                if (event.contract() == null) continue;
                Position old = expected.getOrDefault(event.contract(), FLAT);
                var next = new Position(old.lots() + event.lots(), old.basis() + event.basis(),
                    old.collateral() + event.collateral() - event.deliveryCollateral(), event.version());
                if (next.lots() == 0) {
                    if (next.basis() != 0 || next.collateral() != 0) throw reject("JOURNAL_MISMATCH");
                    expected.remove(event.contract());
                } else expected.put(event.contract(), next);
            }
            Wallet actual = wallet(account);
            Map<UUID, Position> actualPositions = new HashMap<>();
            db.query("SELECT * FROM derivative_positions WHERE account_id=?", rs -> {
                actualPositions.put(rs.getObject("contract_id", UUID.class), readPosition(rs));
            }, account);
            long projectedCollateral = actualPositions.values().stream().mapToLong(Position::collateral).reduce(delivery, Math::addExact);
            if (!journal.equals(actual) || !expected.equals(actualPositions) || projectedCollateral != actual.collateral()) {
                throw reject("JOURNAL_MISMATCH");
            }
            return new AccountState(actual, Map.copyOf(actualPositions), realized);
        });
    }

    private Event apply(UUID account, UUID contract, Wallet next, Position position, Event event) {
        saveWallet(account, next);
        if (position.lots() == 0) {
            db.update("DELETE FROM derivative_positions WHERE account_id=? AND contract_id=?", account, contract);
        } else {
            db.update("""
                INSERT INTO derivative_positions(account_id,contract_id,spec_version,lots,basis_paise,collateral_paise)
                VALUES (?,?,?,?,?,?) ON CONFLICT(account_id,contract_id) DO UPDATE SET
                spec_version=excluded.spec_version,lots=excluded.lots,basis_paise=excluded.basis_paise,
                collateral_paise=excluded.collateral_paise
                """, account, contract, position.version(), position.lots(), position.basis(), position.collateral());
        }
        return append(account, event);
    }

    private Event append(UUID account, Event e) {
        return db.queryForObject("""
            INSERT INTO derivative_events(account_id,request_id,contract_id,spec_version,kind,price_paise,session_day,
                signed_lots,basis_delta_paise,premium_paise,cash_delta_paise,reserve_delta_paise,collateral_delta_paise,
                realized_paise,delivery_units,delivery_cash_paise,delivery_collateral_paise,occurred_at,recorded_at,fee_paise)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
            """, EVENT, account, e.request(), e.contract(), e.version(), e.kind(), e.price(), e.day(), e.lots(), e.basis(),
            e.premium(), e.cash(), e.reserved(), e.collateral(), e.realized(), e.deliveryUnits(), e.deliveryCash(),
            e.deliveryCollateral(), Timestamp.from(e.occurredAt()), Timestamp.from(now()), e.fee());
    }

    private Event existing(UUID account, UUID request) {
        return db.query("""
            SELECT * FROM derivative_events WHERE account_id=? AND request_id=coalesce((
                SELECT original_request_id FROM derivative_lifecycle_retries WHERE account_id=? AND request_id=?),?)
            """, EVENT, account, account, request, request).stream().findFirst().orElse(null);
    }

    private Event previousLifecycle(UUID account, UUID request, UUID contract, String kind, LocalDate day, Instant at, long price) {
        Event prior = existing(account, request);
        boolean retryByLifecycle = prior == null;
        if (prior == null) {
            prior = db.query("""
                SELECT * FROM derivative_events WHERE account_id=? AND contract_id=? AND kind=? AND session_day=?
                """, EVENT, account, contract, kind, day).stream().findFirst().orElse(null);
        }
        if (prior != null && (!kind.equals(prior.kind()) || !contract.equals(prior.contract()) ||
            !java.util.Objects.equals(day, prior.day()) || price != prior.price() ||
            !java.util.Objects.equals(at, prior.occurredAt()))) throw reject("REQUEST_CONFLICT");
        if (prior != null && retryByLifecycle) {
            db.update("INSERT INTO derivative_lifecycle_retries VALUES (?,?,?)", account, request, prior.request());
        }
        return prior;
    }

    private void requireChronology(UUID account, UUID contract, Instant at, boolean mark) {
        var prior = db.query("""
            SELECT * FROM derivative_events WHERE account_id=? AND contract_id=? ORDER BY seq DESC LIMIT 1
            """, EVENT, account, contract).stream().findFirst().orElse(null);
        if (prior != null && ("SETTLE".equals(prior.kind()) || at.isBefore(prior.occurredAt()) ||
            ((mark || "MARK".equals(prior.kind())) && at.equals(prior.occurredAt())))) throw reject("INVALID_EVENT_ORDER");
        if (mark) {
            var day = at.atZone(INDIA).toLocalDate();
            var latest = db.queryForObject("""
                SELECT max(session_day) FROM derivative_events WHERE account_id=? AND contract_id=? AND kind='MARK'
                """, LocalDate.class, account, contract);
            if (latest != null && !day.isAfter(latest)) throw reject("INVALID_EVENT_ORDER");
        }
    }

    private void validateTime(Instant at) {
        if (at == null || at.isAfter(now()) || !at.equals(at.truncatedTo(ChronoUnit.MICROS))) throw reject("INVALID_EVENT_TIME");
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    Wallet wallet(UUID account) {
        return db.query("SELECT * FROM derivative_books WHERE account_id=?", (rs, row) ->
            new Wallet(rs.getLong("cash_paise"), rs.getLong("reserved_paise"), rs.getLong("collateral_paise")), account)
            .stream().findFirst().orElseThrow(() -> reject("BOOK_NOT_FOUND"));
    }

    private void saveWallet(UUID account, Wallet book) {
        db.update("UPDATE derivative_books SET cash_paise=?,reserved_paise=?,collateral_paise=? WHERE account_id=?",
            book.cash(), book.reserved(), book.collateral(), account);
    }

    Position position(UUID account, UUID contract) {
        return db.query("SELECT * FROM derivative_positions WHERE account_id=? AND contract_id=?", (rs, row) ->
            readPosition(rs), account, contract).stream().findFirst().orElse(FLAT);
    }

    private static Position readPosition(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Position(rs.getLong("lots"), rs.getLong("basis_paise"), rs.getLong("collateral_paise"), rs.getInt("spec_version"));
    }

    private static long collateral(Contract contract, Spec spec, long lots) {
        return contract.kind() == Kind.FUTURE || lots < 0 ? bounded(multiplyExact(abs(lots), spec.initialMarginPaise())) : 0;
    }

    private static long allocation(long basis, long closed, long size) {
        return closed == 0 ? 0 : BigInteger.valueOf(basis).multiply(BigInteger.valueOf(closed))
            .divide(BigInteger.valueOf(size)).longValueExact();
    }

    private static Wallet move(Wallet book, long cash, long reserved, long collateral) {
        long nextReserved = bounded(addExact(book.reserved(), reserved));
        long nextCollateral = bounded(addExact(book.collateral(), collateral));
        bounded(addExact(nextReserved, nextCollateral));
        if (nextReserved < 0 || nextCollateral < 0) throw reject("INVALID_RESERVATION");
        return new Wallet(bounded(addExact(book.cash(), cash)), nextReserved, nextCollateral);
    }

    static long bounded(long value) {
        if (value < -MAX_PAISE || value > MAX_PAISE) throw reject("NUMERIC_LIMIT");
        return value;
    }

    <T> T locked(UUID owner, UUID account, UUID request, Supplier<T> action) {
        if (owner == null || account == null || request == null) throw reject("INVALID_REQUEST");
        try {
            return tx.execute(status -> {
                String mode = db.queryForObject("SELECT lock_tape_trading_account(?,?)", String.class, owner, account);
                if (!"DEMO".equals(mode)) throw reject("ACCOUNT_NOT_FOUND");
                return action.get();
            });
        } catch (ArithmeticException overflow) {
            throw reject("NUMERIC_LIMIT");
        }
    }

    static Rejected reject(String code) { return new Rejected(code); }

    static final class Rejected extends RuntimeException {
        Rejected(String code) { super(code); }
    }
}
