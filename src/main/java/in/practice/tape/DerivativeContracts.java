
package in.practice.tape;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

final class DerivativeContracts {
    enum Kind { FUTURE, CALL, PUT }
    enum Underlying { INDEX, STOCK }
    enum Settlement { CASH, PHYSICAL }
    enum Status { SCHEDULED, ACTIVE, EXPIRED }

    record Contract(UUID id, String source, String sourceKey, String exchange, String underlyingKey,
                    Underlying underlying, Kind kind, Instant expiry, long strikePaise, Settlement settlement) {
        Status status(Instant at, Spec spec) {
            if (!at.isBefore(expiry)) return Status.EXPIRED;
            return spec == null || at.isBefore(spec.effectiveAt()) ? Status.SCHEDULED : Status.ACTIVE;
        }
    }
    record Spec(UUID contractId, int version, Instant effectiveAt, int lotSize, int multiplier, long tickPaise,
                long initialMarginPaise, long maintenanceMarginPaise, int maxLots, long maxOrderNotionalPaise) {
        long units(long lots) { return Math.multiplyExact(Math.multiplyExact(lots, lotSize), multiplier); }
    }

    static final RowMapper<Spec> SPEC = (rs, row) -> new Spec(rs.getObject("contract_id", UUID.class),
        rs.getInt("version"), rs.getTimestamp("effective_at").toInstant(), rs.getInt("lot_size"), rs.getInt("multiplier"),
        rs.getLong("tick_paise"), rs.getLong("initial_margin_paise"), rs.getLong("maintenance_margin_paise"),
        rs.getInt("max_lots"), rs.getLong("max_order_notional_paise"));
    static final RowMapper<Contract> CONTRACT = (rs, row) -> new Contract(rs.getObject("id", UUID.class),
        rs.getString("source"), rs.getString("source_key"), rs.getString("exchange"), rs.getString("underlying_key"),
        Underlying.valueOf(rs.getString("underlying_kind")), Kind.valueOf(rs.getString("kind")),
        rs.getTimestamp("expiry_at").toInstant(), rs.getLong("strike_paise"), Settlement.valueOf(rs.getString("settlement")));
    private final JdbcTemplate db;

    DerivativeContracts(JdbcTemplate db) { this.db = db; }

    void register(Contract contract) {
        requirePrecision(contract.expiry());
        db.update("""
            INSERT INTO derivative_contracts
              (id,source,source_key,exchange,underlying_key,underlying_kind,kind,expiry_at,strike_paise,settlement)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """, contract.id(), contract.source(), contract.sourceKey(), contract.exchange(), contract.underlyingKey(),
            contract.underlying().name(), contract.kind().name(), Timestamp.from(contract.expiry()), contract.strikePaise(), contract.settlement().name());
    }

    void addSpec(Spec spec) {
        requirePrecision(spec.effectiveAt());
        if (!spec.effectiveAt().isBefore(contract(spec.contractId()).expiry())) {
            throw new IllegalArgumentException("Specification must take effect before expiry");
        }
        db.update("""
            INSERT INTO derivative_specs(contract_id,version,effective_at,lot_size,multiplier,tick_paise,
                initial_margin_paise,maintenance_margin_paise,max_lots,max_order_notional_paise)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """, spec.contractId(), spec.version(), Timestamp.from(spec.effectiveAt()), spec.lotSize(), spec.multiplier(),
            spec.tickPaise(), spec.initialMarginPaise(), spec.maintenanceMarginPaise(), spec.maxLots(), spec.maxOrderNotionalPaise());
    }

    Contract contract(UUID id) {
        return db.query("""
            SELECT * FROM derivative_contracts WHERE id=?
            """, CONTRACT, id).stream().findFirst()
            .orElseThrow(() -> DerivativeBook.reject("CONTRACT_NOT_FOUND"));
    }

    Spec effective(UUID id, Instant at) {
        return db.query("""
            SELECT * FROM derivative_specs WHERE contract_id=? AND effective_at<=? ORDER BY effective_at DESC LIMIT 1
            """, SPEC, id, Timestamp.from(at)).stream().findFirst().orElse(null);
    }

    Spec version(UUID id, int version) {
        return db.queryForObject("SELECT * FROM derivative_specs WHERE contract_id=? AND version=?", SPEC, id, version);
    }

    private static void requirePrecision(Instant at) {
        if (at == null || !at.equals(at.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("Contract times must use microsecond precision");
        }
    }
}
