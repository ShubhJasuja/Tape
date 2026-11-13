package in.practice.tape;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class CatalogSync {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    CatalogSync(JdbcTemplate db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    record Result(boolean applied, int rows, int inactivated) {}

    Result synchronize(CatalogFeed feed) throws IOException {
        var snapshot = feed.read();
        validate(snapshot);
        return Objects.requireNonNull(tx.execute(ignored -> {
            db.update("INSERT INTO catalog_sync_state(source) VALUES (?) ON CONFLICT DO NOTHING", snapshot.source());
            Instant prior = db.queryForObject("SELECT last_snapshot_at FROM catalog_sync_state WHERE source=? FOR UPDATE",
                (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), snapshot.source());
            if (prior != null && !snapshot.asOf().isAfter(prior)) return new Result(false, 0, 0);
            Timestamp asOf = Timestamp.from(snapshot.asOf());
            var keys = new String[snapshot.instruments().size()];
            int index = 0;
            for (var item : snapshot.instruments()) {
                keys[index++] = item.sourceKey();
                int applied = db.update("""
                    INSERT INTO instruments (id,source,source_key,exchange,symbol,name,isin,instrument_type,
                        listing_status,tick_size_paise,synchronized_at,fictional)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,true)
                    ON CONFLICT (source,source_key) DO UPDATE SET symbol=excluded.symbol,name=excluded.name,
                        isin=excluded.isin,instrument_type=excluded.instrument_type,listing_status=excluded.listing_status,
                        tick_size_paise=excluded.tick_size_paise,synchronized_at=excluded.synchronized_at
                    WHERE instruments.exchange=excluded.exchange AND instruments.fictional
                      AND (instruments.isin IS NULL OR instruments.isin=excluded.isin)
                    """, UUID.randomUUID(), snapshot.source(), item.sourceKey(), item.exchange(), item.symbol(),
                    item.name(), item.isin(), item.instrumentType(), item.status(), item.tickSizePaise(), asOf);
                if (applied != 1) throw new IllegalArgumentException("Catalog identity conflicts with an existing listing");
            }
            int inactivated = snapshot.complete() ? db.update(connection -> {
                var statement = connection.prepareStatement("""
                    UPDATE instruments SET listing_status='INACTIVE',synchronized_at=?
                    WHERE source=? AND listing_status='ACTIVE' AND NOT (source_key=ANY (?))
                    """);
                statement.setTimestamp(1, asOf);
                statement.setString(2, snapshot.source());
                statement.setArray(3, connection.createArrayOf("text", keys));
                return statement;
            }) : 0;
            db.update("UPDATE catalog_sync_state SET last_snapshot_at=? WHERE source=?", asOf, snapshot.source());
            return new Result(true, index, inactivated);
        }));
    }

    private static void validate(CatalogFeed.Snapshot snapshot) {
        if (snapshot == null || !"TAPE_FIXTURE".equals(snapshot.source()) || snapshot.asOf() == null ||
            !snapshot.asOf().equals(snapshot.asOf().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)) ||
            snapshot.asOf().isAfter(Instant.now()) || snapshot.instruments() == null ||
            snapshot.instruments().isEmpty() || snapshot.instruments().size() > 10_000) {
            throw new IllegalArgumentException("Invalid fictional catalog snapshot");
        }
        var keys = new HashSet<String>();
        var symbols = new HashSet<String>();
        for (var item : snapshot.instruments()) {
            if (item == null || !matches(item.sourceKey(), "[A-Za-z0-9_-]{1,100}") ||
                !matches(item.exchange(), "NSE|BSE") || !matches(item.symbol(), "FICT[A-Z0-9-]{1,20}") ||
                item.name() == null || item.name().isBlank() || item.name().length() > 200 ||
                !item.name().endsWith(" (fictional)") ||
                (item.isin() != null && !matches(item.isin(), "ZZ[A-Z0-9]{9}[0-9]")) ||
                !matches(item.instrumentType(), "EQUITY|SME_EQUITY") ||
                !matches(item.status(), "ACTIVE|INACTIVE|DELISTED") ||
                (item.tickSizePaise() != null && (item.tickSizePaise() < 1 || item.tickSizePaise() > 100_000_000)) ||
                !keys.add(item.sourceKey()) || !symbols.add(item.exchange() + ":" + item.symbol())) {
                throw new IllegalArgumentException("Invalid or duplicate fictional listing");
            }
        }
    }

    private static boolean matches(String text, String pattern) { return text != null && text.matches(pattern); }
}
