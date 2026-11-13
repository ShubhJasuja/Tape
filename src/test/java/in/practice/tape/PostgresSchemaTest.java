package in.practice.tape;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.flywaydb.core.Flyway;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tape.postgres.tests", matches = "true")
class PostgresSchemaTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    private static JdbcTemplate pg;
    private static TimeZone originalTimeZone;
    private static final UUID EXISTING_INSTRUMENT = UUID.fromString("43446c65-7245-4400-a100-000000000001");
    private static UUID existingAccount;
    private static UUID existingOrder;
    private static UUID existingWatchlist;

    @BeforeAll
    static void initializeEmptyDatabase() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        POSTGRES.start();
        pg = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertNull(pg.queryForObject("SELECT to_regclass('public.trading_accounts')::text", String.class));
        Flyway.configure().dataSource(pg.getDataSource()).locations("classpath:db/migration/postgresql").target("4").load().migrate();
        var existingOwner = user();
        existingAccount = account(existingOwner, "DEMO");
        existingOrder = order(existingAccount, EXISTING_INSTRUMENT, UUID.randomUUID());
        pg.update("INSERT INTO positions (account_id,instrument_id,quantity,cost_paise) VALUES (?,?,4,40000)",
            existingAccount, EXISTING_INSTRUMENT);
        existingWatchlist = UUID.randomUUID();
        pg.update("INSERT INTO watchlists (id,user_id,name) VALUES (?,?,'Default')", existingWatchlist, existingOwner);
        pg.update("INSERT INTO watchlist_items (watchlist_id,instrument_id) VALUES (?,?)", existingWatchlist, EXISTING_INSTRUMENT);
        try (var application = startApplication()) {
            assertIsolatedDataSources(application);
        }
    }

    @AfterAll
    static void stopDatabase() {
        try { POSTGRES.stop(); }
        finally { if (originalTimeZone != null) TimeZone.setDefault(originalTimeZone); }
    }

    @Test
    void upgradesV4AndPreservesReferencesAcrossApplicationRestart() {
        var owner = user();
        try (var application = startApplication()) {
            assertIsolatedDataSources(application);
            assertEquals(List.of("1", "2", "3", "4", "5"), pg.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class));
            assertEquals(1L, count("SELECT count(*) FROM users WHERE id = ?", owner));
            assertEquals("NSE:RELIANCE", pg.queryForObject("SELECT requested_symbol FROM orders WHERE id=? AND instrument_id=?",
                String.class, existingOrder, EXISTING_INSTRUMENT));
            assertEquals(1L, count("SELECT count(*) FROM watchlist_items WHERE watchlist_id=? AND instrument_id=?",
                existingWatchlist, EXISTING_INSTRUMENT));
            assertEquals(4L, count("SELECT quantity FROM positions WHERE account_id=? AND instrument_id=?",
                existingAccount, EXISTING_INSTRUMENT));
        }
    }

    @Test
    void requiresExistingOwnersAndAllowsOneAccountPerUserAndMode() {
        var owner = user();
        var other = user();
        account(owner, "DEMO");
        account(owner, "PERMITTED_MARKET_DATA");
        account(other, "DEMO");
        fails("23505", () -> account(owner, "DEMO"));
        fails("23503", () -> account(UUID.randomUUID(), "DEMO"));
        fails("23514", () -> account(owner, "LIVE_UNRESTRICTED"));

        var watchlist = UUID.randomUUID();
        pg.update("INSERT INTO watchlists (id,user_id,name) VALUES (?,?,'Core')", watchlist, owner);
        fails("23503", () -> pg.update("INSERT INTO watchlists (id,user_id,name) VALUES (?,?,'Core')",
            UUID.randomUUID(), UUID.randomUUID()));
        fails("23505", () -> pg.update("INSERT INTO watchlists (id,user_id,name) VALUES (?,?,'Core')",
            UUID.randomUUID(), owner));
        var instrument = instrument();
        pg.update("INSERT INTO watchlist_items (watchlist_id,instrument_id) VALUES (?,?)", watchlist, instrument);
        fails("23505", () -> pg.update("INSERT INTO watchlist_items (watchlist_id,instrument_id) VALUES (?,?)",
            watchlist, instrument));
        fails("23503", () -> pg.update("INSERT INTO watchlist_items (watchlist_id,instrument_id) VALUES (?,?)",
            watchlist, UUID.randomUUID()));
    }

    @Test
    void scopesOrderIdempotencyToTheAccountAndExecutionToItsOrder() {
        var first = account(user(), "DEMO");
        var second = account(user(), "DEMO");
        var instrument = instrument();
        var key = UUID.randomUUID();
        var order = order(first, instrument, key);
        order(second, instrument, key);
        fails("23505", () -> order(first, instrument, key));
        fails("23503", () -> order(UUID.randomUUID(), instrument, UUID.randomUUID()));
        fails("23503", () -> execute(order, second, instrument, 20_000));
        fails("23503", () -> execute(order, first, instrument(), 20_000));
        var firstExecution = execute(order, first, instrument, 20_000);
        var secondExecution = execute(order, first, instrument, 20_000);
        assertTrue(sequence(secondExecution) > sequence(firstExecution));
    }

    @Test
    void enforcesPaiseBoundsAndNonnegativeAvailableBalancesAndShares() {
        var account = account(user(), "DEMO");
        var instrument = instrument();
        String walletSql = "INSERT INTO wallets (account_id,balance_paise,reserved_paise) VALUES (?,?,?)";
        fails("23503", () -> pg.update(walletSql, UUID.randomUUID(), 100, 0));
        fails("23514", () -> pg.update(walletSql, account, -1, 0));
        fails("23514", () -> pg.update(walletSql, account, 100, -1));
        fails("23514", () -> pg.update(walletSql, account, 100, 101));
        fails("23514", () -> pg.update(walletSql, account, 9_007_199_254_740_992L, 0));
        fails("22003", () -> pg.update(walletSql, account, new BigDecimal("9223372036854775808"), 0));
        pg.update(walletSql, account, 9_007_199_254_740_991L, 0);
        fails("23505", () -> pg.update(walletSql, account, 100, 0));

        String positionSql = "INSERT INTO positions (account_id,instrument_id,quantity,reserved_quantity,cost_paise) VALUES (?,?,?,?,?)";
        fails("23514", () -> pg.update(positionSql, account, instrument, -1, 0, 0));
        fails("23514", () -> pg.update(positionSql, account, instrument, 1, 2, 100));
        fails("23514", () -> pg.update(positionSql, account, instrument, 1, 0, -1));
        pg.update(positionSql, account, instrument, 2, 1, 20_010);
        fails("23505", () -> pg.update(positionSql, account, instrument, 1, 0, 100));
        var order = order(account, instrument, UUID.randomUUID());
        fails("23514", () -> execute(order, account, instrument, 19_999));
    }

    @Test
    void ledgerRejectsMismatchedOwnershipAndUpdatesDeletesAndTruncation() {
        var account = account(user(), "DEMO");
        var another = account(user(), "DEMO");
        pg.update("INSERT INTO wallets (account_id,balance_paise) VALUES (?,100000000),(?,100000000)", account, another);
        var instrument = instrument();
        var order = order(account, instrument, UUID.randomUUID());
        var execution = execute(order, account, instrument, 20_000);
        post(account, null, "OPENING_BALANCE", 100_000_000);
        post(account, execution, "TRADE_DEBIT", -20_000);
        var fee = post(account, execution, "FEE", -10);
        fails("23505", () -> post(account, null, "OPENING_BALANCE", 100_000_000));
        fails("23505", () -> post(account, execution, "FEE", -10));
        fails("23503", () -> post(another, execution, "TRADE_CREDIT", 20_000));
        fails("23514", () -> post(account, execution, "TRADE_CREDIT", -20_000));

        fails("55000", () -> pg.update("UPDATE executions SET fee_paise = 0 WHERE id = ?", execution));
        fails("55000", () -> pg.update("DELETE FROM executions WHERE id = ?", execution));
        fails("55000", () -> pg.update("UPDATE cash_ledger_entries SET amount_paise = -1 WHERE id = ?", fee));
        fails("55000", () -> pg.update("DELETE FROM cash_ledger_entries WHERE id = ?", fee));
        fails("55000", () -> pg.execute("TRUNCATE cash_ledger_entries"));
        fails("55000", () -> pg.execute("TRUNCATE executions, cash_ledger_entries"));
        assertEquals(3L, count("SELECT count(*) FROM cash_ledger_entries WHERE account_id = ?", account));
        assertEquals(1L, count("SELECT count(*) FROM executions WHERE id = ?", execution));
    }

    private static ConfigurableApplicationContext startApplication() {
        Map<String, Object> testOverrides = Map.of(
            "TAPE_POSTGRES_URL", POSTGRES.getJdbcUrl(),
            "TAPE_POSTGRES_USER", POSTGRES.getUsername(),
            "TAPE_POSTGRES_PASSWORD", POSTGRES.getPassword(),
            "UPSTOX_ANALYTICS_TOKEN", "",
            "OLLAMA_MODEL", ""
        );
        return new SpringApplicationBuilder(Tape.class)
            .web(WebApplicationType.NONE).profiles("postgres-dev")
            .initializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("postgres-test-container", testOverrides)))
            .run();
    }

    private static void assertIsolatedDataSources(ConfigurableApplicationContext application) {
        var h2 = application.getBean(JdbcTemplate.class);
        assertEquals(0L, h2.queryForObject("SELECT count(*) FROM paper_order", Long.class));
        assertEquals(0L, h2.queryForObject(
            "SELECT count(*) FROM INFORMATION_SCHEMA.TABLES WHERE upper(TABLE_NAME) = 'FLYWAY_SCHEMA_HISTORY'", Long.class));
        assertNull(pg.queryForObject("SELECT to_regclass('public.paper_order')::text", String.class));
        assertEquals(10L, count("""
            SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN
            ('users','trading_accounts','wallets','instruments','orders','executions','cash_ledger_entries',
             'positions','watchlists','watchlist_items')
            """));
    }

    private static UUID user() {
        var id = UUID.randomUUID();
        pg.update("INSERT INTO users (id,identity_issuer,identity_subject) VALUES (?,'test',?)", id, id.toString());
        return id;
    }

    private static UUID account(UUID owner, String mode) {
        var id = UUID.randomUUID();
        pg.update("INSERT INTO trading_accounts (id,user_id,mode) VALUES (?,?,?)", id, owner, mode);
        return id;
    }

    private static UUID instrument() {
        var id = UUID.randomUUID();
        pg.update("INSERT INTO instruments (id,exchange,symbol,name,tick_size_paise) VALUES (?,'NSE',?,'Test equity',5)",
            id, id.toString());
        return id;
    }

    private static UUID order(UUID account, UUID instrument, UUID key) {
        var id = UUID.randomUUID();
        pg.update("""
            INSERT INTO orders (id,account_id,instrument_id,idempotency_key,side,kind,quantity,status)
            VALUES (?,?,?,?,'BUY','MARKET',4,'FILLED')
            """, id, account, instrument, key);
        return id;
    }

    private static UUID execute(UUID order, UUID account, UUID instrument, long gross) {
        var id = UUID.randomUUID();
        pg.update("""
            INSERT INTO executions (id,order_id,account_id,instrument_id,quantity,price_paise,gross_paise,fee_paise,executed_at)
            VALUES (?,?,?,?,2,10000,?,10,?)
            """, id, order, account, instrument, gross, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        return id;
    }

    private static UUID post(UUID account, UUID execution, String kind, long amount) {
        var id = UUID.randomUUID();
        pg.update("INSERT INTO cash_ledger_entries (id,account_id,execution_id,kind,amount_paise) VALUES (?,?,?,?,?)",
            id, account, execution, kind, amount);
        return id;
    }

    private static long sequence(UUID id) { return count("SELECT execution_sequence FROM executions WHERE id = ?", id); }

    private static long count(String sql, Object... parameters) {
        return pg.queryForObject(sql, Long.class, parameters);
    }

    private static void fails(String sqlState, Runnable operation) {
        var error = assertThrows(DataAccessException.class, operation::run);
        var sqlError = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(sqlState, sqlError.getSQLState());
    }
}
