package in.practice.tape;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="tape.postgres.tests", matches="true")
class DatabaseMaintenanceTest {
    private static java.util.TimeZone originalZone;
    @org.junit.jupiter.api.BeforeAll static void utc() {
        originalZone = java.util.TimeZone.getDefault();
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }
    @org.junit.jupiter.api.AfterAll static void restoreZone() { java.util.TimeZone.setDefault(originalZone); }
    @Test void v4UpgradePreservesFundedAccountsAndDetectsFinancialDrift() {
        try (var pg = new PostgreSQLContainer<>("postgres:17").withDatabaseName("tape")) {
            pg.start();
            var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            var db = new JdbcTemplate(ds);
            Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").target("4").load().migrate();
            var directory = new AccountDirectory(db, new TransactionTemplate(new JdbcTransactionManager(ds)), new BCryptPasswordEncoder(), true, new MockEnvironment());
            directory.register(new AccountDirectory.Registration(UUID.randomUUID() + "@example.test", UUID.randomUUID().toString()));
            DatabaseMaintenance.offline(db);
            var before = DatabaseMaintenance.capture(ds);
            assertEquals(4, before.version());
            assertEquals(2, before.tables().get("wallets").count());
            Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").target("12").load().migrate();
            DatabaseMaintenance.compare(ds, before, true);
            db.execute("CREATE ROLE tape_runtime NOLOGIN");
            DatabaseMaintenance.applyGrants(ds, Path.of(""));
            db.execute("GRANT UPDATE (price_paise) ON executions TO tape_runtime");
            assertThrows(IllegalStateException.class, () -> DatabaseMaintenance.applyGrants(ds, Path.of("")));
            db.execute("REVOKE UPDATE (price_paise) ON executions FROM tape_runtime");
            DatabaseMaintenance.applyGrants(ds, Path.of(""));
            DatabaseMaintenance.reconcile(ds);
            var account = db.queryForObject("SELECT id FROM trading_accounts WHERE mode='DEMO'", UUID.class);
            db.update("UPDATE wallets SET balance_paise=balance_paise-1 WHERE account_id=?", account);
            assertThrows(IllegalStateException.class, () -> DatabaseMaintenance.compare(ds, before, true));
            assertThrows(IllegalStateException.class, () -> DatabaseMaintenance.reconcile(ds));
            db.update("UPDATE wallets SET balance_paise=balance_paise+1 WHERE account_id=?", account);
            DatabaseMaintenance.compare(ds, before, true);
        }
    }
}
