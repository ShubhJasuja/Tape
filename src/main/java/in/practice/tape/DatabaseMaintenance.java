package in.practice.tape;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;

/** Offline operator CLI. Starts no web server, workers, email sender or provider adapter. */
public final class DatabaseMaintenance {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> PRESERVED = List.of("users", "local_credentials", "trading_accounts", "wallets",
        "orders", "executions", "cash_ledger_entries", "positions", "watchlists", "watchlist_items", "instruments");
    record Table(List<String> columns, List<String> ids, long count, String sha256) {}
    record Snapshot(int version, Map<String, Table> tables) {}

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        if (args.length != 2) throw new IllegalArgumentException("Usage: snapshot|migrate|verify|grants|init <file-or-repository>");
        var ds = new DriverManagerDataSource(required("TAPE_MAINTENANCE_URL"), required("TAPE_MAINTENANCE_USER"), required("TAPE_MAINTENANCE_PASSWORD"));
        var db = new JdbcTemplate(ds);
        Path file = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "snapshot" -> {
                Path repository = Path.of("").toAbsolutePath().normalize();
                if (file.startsWith(repository)) throw new IllegalArgumentException("Financial fingerprints must be outside the repository");
                offline(db);
                if (Files.exists(file)) throw new IllegalArgumentException("Refusing to overwrite a fingerprint");
                JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), capture(ds));
            }
            case "migrate" -> {
                offline(db);
                Snapshot original = JSON.readValue(file.toFile(), Snapshot.class);
                if (original.version() != 4 || version(db) != 4) throw new IllegalStateException("This upgrade requires an exact V4 baseline");
                compare(ds, original, false);
                Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").target("12")
                    .cleanDisabled(true).baselineOnMigrate(false).load().migrate();
                compare(ds, original, true);
            }
            case "init" -> {
                offline(db);
                if (db.queryForObject("SELECT to_regclass('public.flyway_schema_history')::text", String.class) != null && version(db) != 12)
                    throw new IllegalStateException("Use the backup-gated procedure for an existing database");
                Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").target("12").cleanDisabled(true).load().migrate();
                applyGrants(ds, file);
                reconcile(ds);
            }
            case "grants" -> applyGrants(ds, file);
            case "verify" -> {
                offline(db);
                compare(ds, JSON.readValue(file.toFile(), Snapshot.class), true);
                reconcile(ds);
                reconcile(runtime(ds));
                System.out.println("V12 financial fingerprints, equity/derivative reconciliation and runtime-role access passed");
            }
            default -> throw new IllegalArgumentException("Unknown maintenance command");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    static int version(JdbcTemplate db) {
        return db.queryForObject("SELECT max(version::integer) FROM flyway_schema_history WHERE success", Integer.class);
    }

    static void offline(JdbcTemplate db) {
        if (db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'", Long.class) != 0)
            throw new IllegalStateException("Other database connections exist. Stop every application instance gracefully first");
    }

    static Snapshot capture(DataSource ds) {
        var db = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new JdbcTransactionManager(ds));
        tx.setReadOnly(true);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(ignored -> {
            db.execute("SET LOCAL timezone='UTC'");
            Map<String, Table> tables = new LinkedHashMap<>();
            for (String table : PRESERVED) {
                List<String> columns = table.equals("instruments") ? List.of("id", "exchange", "symbol", "name") : db.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position", String.class, table);
                List<String> ids = table.equals("instruments") ? db.queryForList("SELECT id::text FROM instruments ORDER BY id", String.class) : List.of();
                tables.put(table, fingerprint(db, table, columns, ids));
            }
            return new Snapshot(version(db), tables);
        });
    }

    private static Table fingerprint(JdbcTemplate db, String table, List<String> columns, List<String> ids) {
        if (!PRESERVED.contains(table) || columns.isEmpty() || columns.stream().anyMatch(column -> !column.matches("[a-z_]+")))
            throw new IllegalArgumentException("Invalid fingerprint table or column");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String filter = table.equals("instruments") ? (ids.isEmpty() ? " WHERE false" : " WHERE id IN (" + ids.stream()
                .map(id -> "'" + UUID.fromString(id) + "'::uuid").collect(java.util.stream.Collectors.joining(",")) + ")") : "";
            String sql = "SELECT to_jsonb(r)::text FROM (SELECT " + String.join(",", columns) + " FROM public." + table + filter + ") r ORDER BY to_jsonb(r)::text COLLATE \"C\"";
            long[] count = {0};
            db.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                digest.update(rs.getString(1).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                count[0]++;
            });
            return new Table(columns, ids, count[0], HexFormat.of().formatHex(digest.digest()));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void compare(DataSource ds, Snapshot snapshot, boolean upgraded) {
        if (!snapshot.tables().keySet().equals(new HashSet<>(PRESERVED))) throw new IllegalArgumentException("Incomplete financial fingerprint");
        var db = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new JdbcTransactionManager(ds));
        tx.setReadOnly(true);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(ignored -> {
            db.execute("SET LOCAL timezone='UTC'");
            if (version(db) != (upgraded ? 12 : snapshot.version())) throw new IllegalStateException("Unexpected schema version");
            snapshot.tables().forEach((name, expected) -> {
                if (!expected.equals(fingerprint(db, name, expected.columns(), expected.ids())))
                    throw new IllegalStateException("Preserved records changed in " + name);
            });
        });
    }

    static void applyGrants(DataSource ds, Path repository) {
        for (String name : List.of("accounts", "trading", "options"))
            new ResourceDatabasePopulator(new FileSystemResource(repository.resolve("docs/" + name + "-runtime-grants.sql"))).execute(ds);
        var db = new JdbcTemplate(ds);
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT NOT(rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls) FROM pg_roles WHERE rolname='tape_runtime'", Boolean.class)) ||
            db.queryForObject("SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='tape_runtime')", Long.class) != 0)
            throw new IllegalStateException("Runtime role has elevated privileges or role memberships");
        if (Boolean.TRUE.equals(db.queryForObject("""
            SELECT bool_or(has_table_privilege('tape_runtime',name,'UPDATE,DELETE') OR has_any_column_privilege('tape_runtime',name,'UPDATE'))
            FROM (VALUES ('cash_ledger_entries'),('executions'),('derivative_events')) AS immutable(name)
            """, Boolean.class)))
            throw new IllegalStateException("Runtime role can mutate immutable accounting records");
        if (Boolean.TRUE.equals(db.queryForObject("SELECT has_schema_privilege('tape_runtime','public','CREATE')", Boolean.class)) ||
            db.queryForObject("SELECT count(*) FROM pg_class WHERE relowner=(SELECT oid FROM pg_roles WHERE rolname='tape_runtime')", Long.class) != 0)
            throw new IllegalStateException("Runtime role can own or create database objects");
    }

    private static DataSource runtime(DataSource owner) {
        return new DelegatingDataSource(owner) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (var command = connection.createStatement()) { command.execute("SET ROLE tape_runtime"); }
                catch (SQLException failure) { connection.close(); throw failure; }
                return connection;
            }
        };
    }

    static void reconcile(DataSource ds) {
        var db = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new JdbcTransactionManager(ds));
        var equities = new PaperOrders(db, tx, new SyntheticQuotes(), false);
        var derivatives = new DerivativeBook(db, tx, Clock.systemUTC());
        if (db.queryForObject("SELECT count(*) FROM wallets w WHERE w.balance_paise<>(SELECT coalesce(sum(l.amount_paise),0) FROM cash_ledger_entries l WHERE l.account_id=w.account_id)", Long.class) != 0)
            throw new IllegalStateException("Wallet/ledger mismatch");
        db.query("SELECT user_id,id,mode,EXISTS(SELECT 1 FROM derivative_books b WHERE b.account_id=a.id) FROM trading_accounts a", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            UUID owner = rs.getObject(1, UUID.class), account = rs.getObject(2, UUID.class);
            if ("DEMO".equals(rs.getString(3)) && !equities.reconcile(owner, account).isEmpty())
                throw new IllegalStateException("Equity reconciliation failed");
            if (rs.getBoolean(4)) derivatives.reconcile(owner, account);
        });
    }
}
