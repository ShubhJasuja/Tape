package in.practice.tape;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tape.postgres.tests", matches = "true")
class PaperTradingTest {
    private static final String SYMBOL = "NSE:RELIANCE";
    private static final long START = 100_000_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong PRICE = new AtomicLong();
    private static org.springframework.context.ConfigurableApplicationContext app;
    private static JdbcTemplate admin;
    private static PaperOrders first;
    private static PaperOrders second;

    record Identity(UUID owner, UUID account) {}
    record Client(AccountSecurityTest.Browser browser, String demo, String live) {
        String base() { return "/api/v1/accounts/" + demo; }
    }

    @BeforeAll
    static void start() throws Exception {
        AccountSecurityTest.start();
        admin = AccountSecurityTest.owner;
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.FileSystemResource("docs/trading-runtime-grants.sql"))
            .execute(admin.getDataSource());
        app = AccountSecurityTest.startApplication(true, true);
        var runtime = AccountSecurityTest.runtimeDataSource();
        first = engine(runtime);
        second = engine(new DriverManagerDataSource(runtime.getUrl(), runtime.getUsername(), runtime.getPassword()));
    }

    @AfterAll
    static void stop() {
        try { if (app != null) app.close(); }
        finally { AccountSecurityTest.stop(); }
    }

    @BeforeEach
    void price() { PRICE.set(10_000); }

    private static PaperOrders engine(DriverManagerDataSource dataSource) {
        var quotes = new SyntheticQuotes() {
            @Override Market.Quote latest(String symbol, Instant now) {
                return new Market.Quote(PRICE.get(), 0, now.toEpochMilli());
            }
        };
        return new PaperOrders(new JdbcTemplate(dataSource),
            new TransactionTemplate(new JdbcTransactionManager(dataSource)), quotes, true);
    }

    @Test
    void usersTradeIndependentlyAndCannotReadOrCancelForeignOrders() throws Exception {
        var alice = client();
        var bob = client();
        UUID key = UUID.randomUUID();
        var aliceFill = alice.browser.write("POST", alice.base() + "/orders", marketJson(key), "application/json", true);
        var bobFill = bob.browser.write("POST", bob.base() + "/orders", marketJson(key), "application/json", true);
        assertEquals(200, aliceFill.statusCode());
        assertEquals(200, bobFill.statusCode());
        var order = JSON.readTree(aliceFill.body());
        assertEquals("FILLED", order.path("status").asText());
        assertNotEquals(1, order.path("fillPaise").asLong(), "Client-supplied price must be ignored");
        assertEquals("TAPE_SYNTHETIC_V1", order.path("quoteSource").asText());
        assertTrue(order.hasNonNull("quoteAt"));
        assertNotEquals(order.path("id"), JSON.readTree(bobFill.body()).path("id"));
        String foreign = alice.base() + "/orders/" + order.path("id").asText();
        assertEquals(404, bob.browser.get(foreign).statusCode());
        assertEquals(404, bob.browser.get(bob.base() + "/orders/" + order.path("id").asText()).statusCode());
        assertEquals(404, bob.browser.write("DELETE", foreign, "", null, true).statusCode());
        assertEquals(404, bob.browser.get(alice.base() + "/wallet").statusCode());
        assertEquals(404, bob.browser.get(alice.base() + "/order-requests/" + key).statusCode());
        assertEquals(200, alice.browser.get(alice.base() + "/order-requests/" + key).statusCode());
        assertEquals(403, alice.browser.write("POST", alice.base() + "/orders", marketJson(UUID.randomUUID()),
            "application/json", false).statusCode());
        assertEquals(401, new AccountSecurityTest.Browser(app).get(alice.base() + "/orders").statusCode());
        assertEquals(403, alice.browser.write("POST", "/api/v1/accounts/" + alice.live + "/orders",
            marketJson(UUID.randomUUID()), "application/json", true).statusCode());
        assertEquals(204, alice.browser.write("PUT", alice.base() + "/watchlist",
            "{\"symbol\":\"NSE:RELIANCE\",\"pinned\":true}", "application/json", true).statusCode());
        assertEquals(1, JSON.readTree(alice.browser.get(alice.base() + "/market").body()).path("watchlist").size());
        assertEquals(0, JSON.readTree(bob.browser.get(bob.base() + "/market").body()).path("watchlist").size());
    }

    @Test
    void simultaneousBuysCannotOverspend() throws Exception {
        var a = account();
        var result = race(() -> submit(first, a, ticket("BUY", "MARKET", 6_000, null)),
            () -> submit(second, a, ticket("BUY", "MARKET", 6_000, null)));
        assertStates(result, "FILLED", "REJECTED");
        assertEquals(39_970_000, first.wallet(a.owner, a.account).balancePaise());
        assertEquals(6_000, first.portfolio(a.owner, a.account).positions().getFirst().quantity());
        assertEquals(1, executions(a));
        assertHealthy(a);
    }

    @Test
    void simultaneousSellsCannotOversell() throws Exception {
        var a = account();
        submit(first, a, ticket("BUY", "MARKET", 10, null));
        var result = race(() -> submit(first, a, ticket("SELL", "MARKET", 7, null)),
            () -> submit(second, a, ticket("SELL", "MARKET", 7, null)));
        assertStates(result, "FILLED", "REJECTED");
        assertEquals(3, first.portfolio(a.owner, a.account).positions().getFirst().quantity());
        assertEquals(2, executions(a));
        assertHealthy(a);
    }

    @Test
    void retriesSerializeAndConflictsCannotCreateAnotherExecution() throws Exception {
        var a = account();
        var ticket = ticket("BUY", "MARKET", 1, null);
        var results = race(() -> submit(first, a, ticket), () -> submit(second, a, ticket));
        assertEquals(results.get(0).id(), results.get(1).id());
        assertEquals(results.get(0).id(), first.retryStatus(a.owner, a.account, ticket.idempotencyKey()).id());
        var conflict = new PaperOrders.Ticket(ticket.idempotencyKey(), SYMBOL, "BUY", "MARKET", 2, null);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> submit(first, a, conflict)).getStatusCode().value());
        assertEquals(1, executions(a));
        assertHealthy(a);
    }

    @Test
    void feesAndAverageCostMatchLegacyForPartialSales() {
        var a = account();
        PRICE.set(10_001);
        var buy = submit(first, a, ticket("BUY", "MARKET", 3, null));
        assertEquals(16, buy.feePaise());
        assertEquals(START - 30_019, first.wallet(a.owner, a.account).balancePaise());
        PRICE.set(11_002);
        var sell = submit(first, a, ticket("SELL", "MARKET", 1, null));
        assertEquals(6, sell.feePaise());
        var portfolio = first.portfolio(a.owner, a.account);
        assertEquals(20_013, portfolio.positions().getFirst().costPaise());
        assertEquals(990, portfolio.realizedPaise());
        assertEquals(1_991, portfolio.unrealizedPaise());
        PRICE.set(12_003);
        submit(first, a, ticket("SELL", "MARKET", 2, null));
        assertEquals(4_970, first.portfolio(a.owner, a.account).realizedPaise());
        assertEquals(START + 4_970, first.wallet(a.owner, a.account).balancePaise());
        assertTrue(first.portfolio(a.owner, a.account).positions().isEmpty());
        assertHealthy(a);
    }

    @Test
    void limitAndStopRulesAndReservationsCoverBothSides() {
        var a = account();
        var buyLimit = submit(first, a, ticket("BUY", "LIMIT", 2, "95.00"));
        assertEquals(19_010, first.wallet(a.owner, a.account).reservedPaise());
        match(a, buyLimit);
        assertEquals("OPEN", first.detail(a.owner, a.account, buyLimit.id()).status());
        PRICE.set(9_000);
        match(a, buyLimit);
        assertEquals(9_000, first.detail(a.owner, a.account, buyLimit.id()).fillPaise());
        var sellLimit = submit(first, a, ticket("SELL", "LIMIT", 1, "100.00"));
        assertEquals(1, first.portfolio(a.owner, a.account).positions().getFirst().availableQuantity());
        var buyStop = submit(first, a, ticket("BUY", "STOP", 2, "105.00"));
        PRICE.set(10_100);
        match(a, sellLimit);
        match(a, buyStop);
        assertEquals("FILLED", first.detail(a.owner, a.account, sellLimit.id()).status());
        assertEquals("OPEN", first.detail(a.owner, a.account, buyStop.id()).status());
        PRICE.set(10_600);
        match(a, buyStop);
        assertEquals(10_600, first.detail(a.owner, a.account, buyStop.id()).fillPaise());
        var sellStop = submit(first, a, ticket("SELL", "STOP", 1, "100.00"));
        match(a, sellStop);
        assertEquals("OPEN", first.detail(a.owner, a.account, sellStop.id()).status());
        PRICE.set(9_900);
        match(a, sellStop);
        assertEquals(9_900, first.detail(a.owner, a.account, sellStop.id()).fillPaise());
        assertHealthy(a);
    }

    @Test
    void stopGapRejectionAndCancellationReleaseReservations() {
        var a = account();
        var stop = submit(first, a, ticket("BUY", "STOP", 9_000, "100.00"));
        PRICE.set(20_000);
        match(a, stop);
        assertEquals("INSUFFICIENT_CASH", first.detail(a.owner, a.account, stop.id()).rejectionCode());
        assertEquals(0, first.wallet(a.owner, a.account).reservedPaise());
        var limit = submit(first, a, ticket("BUY", "LIMIT", 1, "95.00"));
        assertEquals(9_505, first.wallet(a.owner, a.account).reservedPaise());
        assertEquals("CANCELLED", first.cancel(a.owner, a.account, limit.id()).status());
        submit(first, a, ticket("BUY", "MARKET", 1, null));
        var sale = submit(first, a, ticket("SELL", "LIMIT", 1, "300.00"));
        assertEquals(0, first.portfolio(a.owner, a.account).positions().getFirst().availableQuantity());
        first.cancel(a.owner, a.account, sale.id());
        assertEquals(1, first.portfolio(a.owner, a.account).positions().getFirst().availableQuantity());
        assertHealthy(a);
    }

    @Test
    void cancelVersusFillHasExactlyOneTerminalOutcome() throws Exception {
        for (int i = 0; i < 6; i++) {
            var a = account();
            var limit = submit(first, a, ticket("BUY", "LIMIT", 1, "101.00"));
            race(() -> {
                try { return first.cancel(a.owner, a.account, limit.id()); }
                catch (ResponseStatusException e) {
                    assertEquals(409, e.getStatusCode().value());
                    return first.detail(a.owner, a.account, limit.id());
                }
            }, () -> {
                second.match(new PaperOrders.Candidate(a.owner, a.account, limit.id()));
                return second.detail(a.owner, a.account, limit.id());
            });
            var finalOrder = first.detail(a.owner, a.account, limit.id());
            assertTrue(List.of("FILLED", "CANCELLED").contains(finalOrder.status()));
            assertEquals("FILLED".equals(finalOrder.status()) ? 1 : 0, executions(a));
            assertEquals(0, first.wallet(a.owner, a.account).reservedPaise());
            assertHealthy(a);
        }
    }

    @Test
    void rollbackOfLatePostingFailureLeavesNoOrderExecutionOrProjection() {
        var a = account();
        admin.execute("""
            CREATE FUNCTION test_reject_fee() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.kind='FEE' THEN RAISE EXCEPTION 'test rollback'; END IF; RETURN NEW; END;
            $$
            """);
        admin.execute("CREATE TRIGGER test_reject_fee BEFORE INSERT ON cash_ledger_entries FOR EACH ROW EXECUTE FUNCTION test_reject_fee()");
        var ticket = ticket("BUY", "MARKET", 1, null);
        try { assertThrows(DataAccessException.class, () -> submit(first, a, ticket)); }
        finally {
            admin.execute("DROP TRIGGER test_reject_fee ON cash_ledger_entries");
            admin.execute("DROP FUNCTION test_reject_fee()");
        }
        assertTrue(first.list(a.owner, a.account, 0, 10).orders().isEmpty());
        assertEquals(0, executions(a));
        assertEquals(1, count("SELECT count(*) FROM cash_ledger_entries WHERE account_id=?", a.account));
        assertEquals(START, first.wallet(a.owner, a.account).balancePaise());
        assertHealthy(a);
        assertEquals("FILLED", submit(second, a, ticket).status());
    }

    @Test
    void restartPreservesOrdersAndReconciliationDetectsProjectionDrift() {
        var a = account();
        submit(first, a, ticket("BUY", "MARKET", 3, null));
        var pending = submit(first, a, ticket("SELL", "LIMIT", 2, "500000.00"));
        try (var restarted = AccountSecurityTest.startApplication(true, true)) {
            var engine = restarted.getBean(PaperOrders.class);
            assertEquals("OPEN", engine.detail(a.owner, a.account, pending.id()).status());
            assertEquals(1, engine.portfolio(a.owner, a.account).positions().getFirst().availableQuantity());
            assertEquals(START - 30_015, engine.wallet(a.owner, a.account).balancePaise());
            assertTrue(engine.reconcile(a.owner, a.account).isEmpty());
        }
        admin.update("UPDATE wallets SET balance_paise=balance_paise+1 WHERE account_id=?", a.account);
        admin.update("UPDATE positions SET cost_paise=cost_paise+1 WHERE account_id=?", a.account);
        var issues = first.reconcile(a.owner, a.account);
        assertTrue(issues.contains("WALLET_BALANCE"));
        assertTrue(issues.stream().anyMatch(issue -> issue.startsWith("POSITION:")));
    }

    @Test
    void quoteBucketsSurviveProviderRestartAndRejectStaleOrFutureTicks() {
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        var quote = new SyntheticQuotes().latest(SYMBOL, time);
        assertEquals(quote, new SyntheticQuotes().latest(SYMBOL, time.plusMillis(1999)));
        assertTrue(SyntheticQuotes.eligible(quote, time));
        assertFalse(SyntheticQuotes.eligible(quote, time.minusMillis(1)));
        assertFalse(SyntheticQuotes.eligible(quote, time.plusMillis(6001)));
    }

    @Test
    void backgroundMatcherResumesPersistedOrdersAndUsesSyntheticQuotesOnly() throws Exception {
        var a = account();
        var pending = submit(first, a, ticket("BUY", "LIMIT", 1, "5000.00"));
        try (var workerApp = AccountSecurityTest.startApplication(true, true, true)) {
            assertTrue(workerApp.getBean(PaperOrderMatcher.class).isRunning());
            var engine = workerApp.getBean(PaperOrders.class);
            var deadline = Instant.now().plusSeconds(10);
            PaperOrders.Order order;
            do {
                order = engine.detail(a.owner, a.account, pending.id());
                if (order.open()) Thread.sleep(100);
            } while (order.open() && Instant.now().isBefore(deadline));
            assertEquals("FILLED", order.status());
            assertEquals(SyntheticQuotes.SOURCE, order.quoteSource());
            assertEquals(1, executions(a));
            assertEquals(0, engine.wallet(a.owner, a.account).reservedPaise());
            assertHealthy(a);
        }
    }

    private static Client client() throws Exception {
        var browser = new AccountSecurityTest.Browser(app);
        String email = UUID.randomUUID() + "@example.test";
        assertEquals(201, browser.register(email).statusCode());
        assertEquals(204, browser.login(email, AccountSecurityTest.PASSWORD).statusCode());
        HttpResponse<String> accounts = browser.get("/api/v1/accounts");
        var json = JSON.readTree(accounts.body());
        return new Client(browser, json.get(0).path("id").asText(), json.get(1).path("id").asText());
    }

    private static String marketJson(UUID key) {
        return """
            {"idempotencyKey":"%s","symbol":"NSE:RELIANCE","side":"BUY","kind":"MARKET","quantity":1,"executionPrice":1}
            """.formatted(key);
    }

    private static Identity account() {
        UUID owner = UUID.randomUUID(), account = UUID.randomUUID();
        admin.update("INSERT INTO users (id,identity_issuer,identity_subject) VALUES (?,'test',?)", owner, owner.toString());
        admin.update("INSERT INTO trading_accounts (id,user_id,mode,trading_enabled) VALUES (?,?,'DEMO',true)", account, owner);
        admin.update("INSERT INTO wallets (account_id,balance_paise) VALUES (?,?)", account, START);
        admin.update("INSERT INTO cash_ledger_entries (id,account_id,kind,amount_paise) VALUES (?,?,'OPENING_BALANCE',?)",
            UUID.randomUUID(), account, START);
        return new Identity(owner, account);
    }

    private static PaperOrders.Ticket ticket(String side, String kind, int quantity, String trigger) {
        return new PaperOrders.Ticket(UUID.randomUUID(), SYMBOL, side, kind, quantity,
            trigger == null ? null : new BigDecimal(trigger));
    }

    private static PaperOrders.Order submit(PaperOrders engine, Identity a, PaperOrders.Ticket ticket) {
        return engine.submit(a.owner, a.account, ticket);
    }

    private static void match(Identity a, PaperOrders.Order order) {
        first.match(new PaperOrders.Candidate(a.owner, a.account, order.id()));
    }

    private static long executions(Identity a) { return count("SELECT count(*) FROM executions WHERE account_id=?", a.account); }
    private static long count(String sql, UUID id) { return admin.queryForObject(sql, Long.class, id); }
    private static void assertHealthy(Identity a) { assertEquals(List.of(), first.reconcile(a.owner, a.account)); }

    private static void assertStates(List<PaperOrders.Order> orders, String... statuses) {
        assertEquals(List.of(statuses), orders.stream().map(PaperOrders.Order::status).sorted().toList());
    }

    private static <T> List<T> race(Callable<T> left, Callable<T> right) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var a = threads.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return left.call(); });
            var b = threads.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return right.call(); });
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
}
