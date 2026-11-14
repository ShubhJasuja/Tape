
package in.practice.tape;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.*;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.*;

import static in.practice.tape.DerivativeContracts.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tape.postgres.tests", matches = "true")
class DerivativeBookTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    private static final Instant START = Instant.parse("2026-01-05T04:00:00Z");
    private static JdbcTemplate db;
    private static TransactionTemplate tx;
    private static TimeZone originalZone;
    private Instant now = START;
    private final UUID user = UUID.randomUUID(), account = UUID.randomUUID();
    private DerivativeContracts catalog;
    private DerivativeBook book;

    @BeforeAll
    static void start() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        POSTGRES.start();
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration/postgresql").load().migrate();
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new JdbcTransactionManager(source));
    }

    @AfterAll static void stop() {
        try { POSTGRES.stop(); }
        finally { TimeZone.setDefault(originalZone); }
    }

    @BeforeEach
    void setup() {
        catalog = new DerivativeContracts(db);
        book = new DerivativeBook(db, tx, new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
            @Override public Instant instant() { return now; }
        });
        db.update("INSERT INTO users(id,identity_issuer,identity_subject) VALUES (?,'test',?)", user, user.toString());
        db.update("INSERT INTO trading_accounts(id,user_id,mode) VALUES (?,?,'DEMO')", account, user);
        db.update("INSERT INTO wallets(account_id,balance_paise) VALUES (?,1234)", account);
        book.open(user, account, UUID.randomUUID(), 100_000);
    }

    @Test
    void contractIdentityVersionAndTradingCutoffRemainStable() {
        var call = contract(Kind.CALL, Settlement.CASH);
        assertEquals(Status.SCHEDULED, call.status(START.minusSeconds(2), catalog.effective(call.id(), START.minusSeconds(2))));
        assertThrows(DataAccessException.class, () -> catalog.register(new Contract(UUID.randomUUID(), call.source(),
            call.sourceKey(), call.exchange(), call.underlyingKey(), call.underlying(), call.kind(), call.expiry(),
            call.strikePaise(), call.settlement())));
        code("INVALID_LOT_SIZE", () -> fill(call, 9, 100));
        code("INVALID_FILL", () -> fill(call, 10, 101));
        fill(call, 10, 100);
        var second = spec(call, 2, START.plusSeconds(5), 20);
        catalog.addSpec(second);
        now = START.plusSeconds(6);
        assertEquals(2, catalog.effective(call.id(), now).version());
        code("SPEC_CHANGED", () -> fill(call, 10, 100));
        assertEquals(1, fill(call, -10, 100).version());
        assertEquals(2, fill(call, 20, 100).version());
        now = call.expiry();
        code("CONTRACT_NOT_ACTIVE", () -> fill(call, 20, 100));
        assertEquals(Status.EXPIRED, call.status(now, second));
    }

    @Test
    void callPremiumAndPartialCloseAndPutShortStaySeparatelyReconciled() {
        var call = contract(Kind.CALL, Settlement.CASH);
        var put = contract(Kind.PUT, Settlement.CASH);
        assertEquals(-4_000, fill(call, 20, 100).premium());
        assertEquals(1_000, book.unrealized(user, account, call.id(), 125));
        var exit = fill(call, -10, 150);
        assertEquals(3_000, exit.premium());
        assertEquals(1_000, exit.realized());
        assertEquals(5_000, fill(put, -20, 125).premium());
        var state = book.reconcile(user, account);
        assertEquals(104_000, state.wallet().cash());
        assertEquals(40_000, state.wallet().collateral());
        assertEquals(-2, state.positions().get(put.id()).lots());
        assertEquals(1_000, book.unrealized(user, account, put.id(), 100));
        assertEquals(500, fill(put, 10, 100).realized());
        state = book.reconcile(user, account);
        assertEquals(20_000, state.wallet().collateral());
        assertEquals(1_500, state.realized());
        assertEquals(1_234L, db.queryForObject("SELECT balance_paise FROM wallets WHERE account_id=?", Long.class, account));
    }

    @Test
    void voluntaryShortCoverCannotCreateAnOptionsCashDeficit() {
        var option = contract(Kind.CALL, Settlement.CASH);
        fill(option, -10, 100);
        var before = book.reconcile(user, account);
        code("INSUFFICIENT_MARGIN", () -> fill(option, 10, 5500));
        assertEquals(before, book.reconcile(user, account));
    }

    @Test
    void futuresMarkPostsVariationAndRebasesBothSidesAndPartialClose() {
        var future = contract(Kind.FUTURE, Settlement.CASH);
        assertEquals(0, fill(future, -20, 1000).cash());
        assertEquals(40_000, book.reconcile(user, account).wallet().collateral());
        now = START.plusSeconds(3600);
        var mark = book.mark(user, account, UUID.randomUUID(), future.id(), LocalDate.of(2026, 1, 5), now, 900);
        assertEquals(4_000, mark.cash());
        UUID markRetry = UUID.randomUUID();
        assertEquals(mark, book.mark(user, account, markRetry, future.id(), LocalDate.of(2026, 1, 5), now, 900));
        code("REQUEST_CONFLICT", () -> book.fill(user, account, markRetry, future.id(), 10, 900));
        code("INVALID_EVENT_ORDER", () -> fill(future, 10, 850));
        now = now.plusSeconds(1);
        assertEquals(1_000, fill(future, 10, 850).realized());
        assertEquals(1_000, book.unrealized(user, account, future.id(), 850));
        assertEquals(5_000, book.reconcile(user, account).realized());
        now = future.expiry();
        var event = book.settle(user, account, UUID.randomUUID(), future.id(), 950);
        assertEquals(-1_000, event.cash());
        assertEquals(event, book.settle(user, account, UUID.randomUUID(), future.id(), 950));
        code("REQUEST_CONFLICT", () -> book.settle(user, account, UUID.randomUUID(), future.id(), 951));
        var finished = book.reconcile(user, account);
        assertEquals(0, finished.wallet().collateral());
        assertEquals(104_000, finished.wallet().cash());
        assertTrue(finished.positions().isEmpty());
    }

    @Test
    void maintenanceDeficitPostsLossWithoutFundingNewExposure() {
        var future = contract(Kind.FUTURE, Settlement.CASH);
        fill(future, 20, 5000);
        now = START.plusSeconds(3600);
        book.mark(user, account, UUID.randomUUID(), future.id(), LocalDate.of(2026, 1, 5), now, 1500);
        assertEquals(-40_000, book.reconcile(user, account).wallet().cash());
        assertEquals(64_000, book.maintenanceShortfall(user, account));
        now = now.plusSeconds(1);
        code("INSUFFICIENT_MARGIN", () -> fill(future, 10, 1500));
        assertEquals(0, fill(future, -10, 1500).cash());
        assertEquals(20_000, book.reconcile(user, account).wallet().collateral());
    }

    @Test
    void reservationsFutureInitialMarginAndRiskLimitsAreAtomic() {
        var futures = contract(Kind.FUTURE, Settlement.CASH);
        var reservation = UUID.randomUUID();
        var event = book.reserve(user, account, reservation, 90_000);
        assertEquals(event, book.reserve(user, account, reservation, 90_000));
        code("REQUEST_CONFLICT", () -> book.reserve(user, account, reservation, 80_000));
        code("INSUFFICIENT_MARGIN", () -> fill(futures, 10, 1000));
        book.reserve(user, account, UUID.randomUUID(), -90_000);
        code("POSITION_LIMIT", () -> fill(futures, 60, 1000));
        code("NOTIONAL_LIMIT", () -> fill(futures, 10, 100_000));
        var accepted = fill(futures, 10, 1000);
        assertEquals(0, accepted.cash());
        var state = book.reconcile(user, account);
        assertEquals(80_000, state.wallet().available());
        assertEquals(0, state.wallet().reserved());
    }

    @Test
    void cashOptionsAndPhysicalObligationsSettleExactlyOnce() {
        var index = contract(Kind.PUT, Settlement.CASH);
        var stock = contract(Kind.CALL, Settlement.PHYSICAL);
        var outOfMoney = contract(Kind.CALL, Settlement.CASH);
        fill(index, 10, 100);
        fill(stock, -10, 125);
        fill(outOfMoney, 10, 50);
        code("INVALID_EVENT_TIME", () -> book.settle(user, account, UUID.randomUUID(), stock.id(), 1100));
        now = stock.expiry();
        assertEquals(4_000, book.settle(user, account, UUID.randomUUID(), index.id(), 800).cash());
        var physical = book.settle(user, account, UUID.randomUUID(), stock.id(), 1100);
        assertEquals(0, physical.cash());
        assertEquals(-20, physical.deliveryUnits());
        assertEquals(20_000, physical.deliveryCash());
        assertEquals(20_000, physical.deliveryCollateral());
        assertEquals(physical, book.settle(user, account, UUID.randomUUID(), stock.id(), 1100));
        assertEquals(-1000, book.settle(user, account, UUID.randomUUID(), outOfMoney.id(), 800).realized());
        var state = book.reconcile(user, account);
        assertTrue(state.positions().isEmpty());
        assertEquals(20_000, state.wallet().collateral());
        assertEquals(1_500, state.realized());
    }

    @Test
    void duplicateConcurrentFillsAreSerializedAndOwnershipIsEnforced() throws Exception {
        var option = contract(Kind.PUT, Settlement.CASH);
        UUID request = UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<DerivativeBook.Event> action = () -> { start.await(); return book.fill(user, account, request, option.id(), 10, 100); };
            var a = pool.submit(action);
            var b = pool.submit(action);
            start.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            var first = pool.submit(() -> fill(option, 10, 100));
            var second = pool.submit(() -> fill(option, 10, 100));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(3, book.reconcile(user, account).positions().get(option.id()).lots());
        code("REQUEST_CONFLICT", () -> book.fill(user, account, request, option.id(), 20, 100));
        code("ACCOUNT_NOT_FOUND", () -> book.fill(UUID.randomUUID(), account, request, option.id(), 10, 100));
        code("ACCOUNT_NOT_FOUND", () -> book.reconcile(UUID.randomUUID(), account));
        UUID live = UUID.randomUUID();
        db.update("INSERT INTO trading_accounts(id,user_id,mode) VALUES (?,?,'PERMITTED_MARKET_DATA')", live, user);
        code("ACCOUNT_NOT_FOUND", () -> book.open(user, live, request, 100_000));
    }

    @Test
    void immutableJournalAndSpecificationsRejectMutationAndFlagProjectionDrift() {
        var option = contract(Kind.CALL, Settlement.CASH);
        var event = fill(option, 10, 100);
        assertThrows(DataAccessException.class, () -> db.update("UPDATE derivative_events SET price_paise=5 WHERE seq=?", event.seq()));
        assertThrows(DataAccessException.class, () -> db.update("DELETE FROM derivative_events WHERE seq=?", event.seq()));
        assertThrows(DataAccessException.class, () -> db.execute("TRUNCATE derivative_events"));
        assertThrows(DataAccessException.class, () -> db.update("UPDATE derivative_specs SET lot_size=1 WHERE contract_id=?", option.id()));
        db.update("UPDATE derivative_books SET cash_paise=cash_paise+1 WHERE account_id=?", account);
        code("JOURNAL_MISMATCH", () -> book.reconcile(user, account));
    }

    private Contract contract(Kind kind, Settlement settlement) {
        UUID id = UUID.randomUUID();
        var type = settlement == Settlement.PHYSICAL ? Underlying.STOCK : Underlying.INDEX;
        var value = new Contract(id, "TEST", id.toString(), "NSE", id.toString(), type, kind,
            START.plus(Duration.ofDays(2)), kind == Kind.FUTURE ? 0 : 1000, settlement);
        catalog.register(value);
        catalog.addSpec(spec(value, 1, START.minusSeconds(1), 10));
        return value;
    }

    private static Spec spec(Contract contract, int version, Instant effectiveAt, int lotSize) {
        return new Spec(contract.id(), version, effectiveAt, lotSize, 2, 5, 20_000, 12_000, 5, 1_000_000);
    }

    private DerivativeBook.Event fill(Contract contract, long quantity, long price) {
        return book.fill(user, account, UUID.randomUUID(), contract.id(), quantity, price);
    }

    private static void code(String expected, org.junit.jupiter.api.function.Executable run) {
        assertEquals(expected, assertThrows(DerivativeBook.Rejected.class, run).getMessage());
    }
}
