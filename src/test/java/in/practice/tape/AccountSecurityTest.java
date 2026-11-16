package in.practice.tape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.catalina.Context;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tape.postgres.tests", matches = "true")
class AccountSecurityTest {
    static final String PASSWORD = UUID.randomUUID().toString();
    private static final String API = "/api/v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
        .withDatabaseName("tape").withPassword(UUID.randomUUID().toString());
    private static ConfigurableApplicationContext local;
    static JdbcTemplate owner;
    private static String runtimePassword;
    private static TimeZone originalZone;

    @BeforeAll
    static void start() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        POSTGRES.start();
        var ownerDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ownerDataSource).locations("classpath:db/migration/postgresql").load().migrate();
        owner = new JdbcTemplate(ownerDataSource);
        runtimePassword = UUID.randomUUID().toString();
        owner.execute("CREATE ROLE tape_runtime LOGIN PASSWORD '" + runtimePassword + "'");
        new ResourceDatabasePopulator(new FileSystemResource("docs/accounts-runtime-grants.sql")).execute(ownerDataSource);
        local = startApplication(true);
    }

    @AfterAll
    static void stop() {
        try {
            if (local != null) local.close();
        } finally {
            try { POSTGRES.stop(); }
            finally { if (originalZone != null) TimeZone.setDefault(originalZone); }
        }
    }

    @Test
    void registrationHashesPasswordAndAtomicallyFundsTwoAccountsOnlyOnce() throws Exception {
        var client = new Browser(local);
        String email = email();
        assertEquals(201, client.register(email).statusCode());
        assertEquals(409, client.register(email.toUpperCase(java.util.Locale.ROOT)).statusCode());

        String hash = owner.queryForObject("SELECT password_hash FROM local_credentials WHERE email=?", String.class, email);
        assertNotEquals(PASSWORD, hash);
        assertTrue(new BCryptPasswordEncoder().matches(PASSWORD, hash));
        assertEquals(0L, owner.queryForObject("""
            SELECT count(*) FROM users u LEFT JOIN local_credentials c ON c.user_id=u.id
            WHERE u.identity_issuer='tape-local' AND c.user_id IS NULL
            """, Long.class));
        assertEquals(2L, owner.queryForObject("""
            SELECT count(*) FROM cash_ledger_entries l
            JOIN trading_accounts a ON a.id=l.account_id JOIN local_credentials c ON c.user_id=a.user_id
            WHERE c.email=? AND l.kind='OPENING_BALANCE' AND l.amount_paise=100000000
            """, Long.class, email));
        assertEquals(204, client.login(email, PASSWORD).statusCode());
        var accounts = JSON.readTree(client.get(API + "/accounts").body());
        assertEquals(2, accounts.size());
        assertEquals("DEMO", accounts.get(0).path("mode").asText());
        assertEquals("LIVE_DATA", accounts.get(1).path("mode").asText());
        for (JsonNode account : accounts) {
            assertEquals("INR", account.path("currency").asText());
            assertEquals(100_000_000, account.path("balancePaise").asLong());
            assertEquals(0, account.path("reservedPaise").asLong());
            assertFalse(account.path("tradingEnabled").asBoolean());
        }
    }

    @Test
    void loginRotatesSessionAndLogoutInvalidatesIt() throws Exception {
        var client = new Browser(local);
        String email = email();
        assertEquals(201, client.register(email).statusCode());
        assertEquals(401, client.get(API + "/auth/me").statusCode());
        assertEquals(401, client.get(API + "/accounts").statusCode());
        assertEquals(401, client.get(API + "/accounts/" + UUID.randomUUID()).statusCode());
        client.token();
        String initialSession = client.sessionId();
        var login = client.login(email, PASSWORD);
        assertEquals(204, login.statusCode());
        assertNotEquals(initialSession, client.sessionId());
        String cookie = String.join(";", login.headers().allValues("Set-Cookie"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        var me = JSON.readTree(client.get(API + "/auth/me").body());
        assertEquals(email, me.path("email").asText());
        assertEquals(1, me.size());
        assertEquals(204, client.write("POST", API + "/auth/logout", "", null, true).statusCode());
        assertEquals(401, client.get(API + "/auth/me").statusCode());
    }

    @Test
    void invalidCredentialsAreGenericAndRepeatedAttemptsAreThrottled() throws Exception {
        var client = new Browser(local);
        String email = email();
        assertEquals(201, client.register(email).statusCode());
        var wrong = client.login(email, "incorrect-password");
        var unknown = client.login(email(), "incorrect-password");
        assertEquals(401, wrong.statusCode());
        assertEquals(401, unknown.statusCode());
        assertEquals(wrong.body(), unknown.body());
        for (int i = 0; i < 7; i++) assertEquals(401, client.login(email, "incorrect-password").statusCode());
        var throttled = client.login(email, PASSWORD);
        assertEquals(429, throttled.statusCode());
        assertTrue(throttled.headers().firstValue("Retry-After").isPresent());
    }

    @Test
    void foreignIdsAndOwnerQueryParametersCannotCrossAccountBoundaries() throws Exception {
        String aliceEmail = email();
        String bobEmail = email();
        var alice = registered(aliceEmail);
        var bob = registered(bobEmail);
        var bobId = JSON.readTree(bob.get(API + "/accounts").body()).get(0).path("id").asText();
        var aliceId = JSON.readTree(alice.get(API + "/accounts").body()).get(0).path("id").asText();
        var bobUserId = owner.queryForObject("SELECT user_id FROM local_credentials WHERE email=?", UUID.class, bobEmail);
        assertNotEquals(bobId, aliceId);
        assertEquals(200, alice.get(API + "/accounts/" + aliceId).statusCode());
        assertEquals(404, alice.get(API + "/accounts/" + bobId).statusCode());
        assertEquals(404, alice.get(API + "/accounts/" + bobUserId).statusCode());
        var filtered = alice.get(API + "/accounts?userId=" + bobUserId + "&accountId=" + bobId);
        assertEquals(200, filtered.statusCode());
        assertTrue(filtered.body().contains(aliceId));
        assertFalse(filtered.body().contains(bobId));
        assertFalse(filtered.body().contains(bobUserId.toString()));
    }

    @Test
    void csrfRejectsWritesAndLegacyRoutesAreUnavailableToAuthenticatedUsers() throws Exception {
        var client = new Browser(local);
        assertEquals(403, client.write("POST", API + "/auth/register", "{}", "application/json", false).statusCode());
        assertEquals(403, client.write("POST", API + "/auth/login", "", "application/x-www-form-urlencoded", false).statusCode());
        client = registered(email());
        assertEquals(403, client.write("POST", API + "/auth/logout", "", null, false).statusCode());
        assertEquals(200, client.get(API + "/auth/me").statusCode());

        for (String route : new String[]{"/api/terminal", "/api/research", "/api/candles", "/actuator", "/h2-console"}) {
            assertEquals(403, client.get(route).statusCode(), route);
        }
        assertEquals(403, client.write("POST", "/api/orders?mode=demo", "{}", "application/json", true).statusCode());
        assertEquals(403, client.write("DELETE", "/api/orders/" + UUID.randomUUID() + "?mode=demo", "", null, true).statusCode());
        assertEquals(403, client.write("PUT", "/api/watchlist?symbol=NSE%3ATCS&pinned=true", "", null, true).statusCode());
        assertTrue(local.getBeansOfType(TerminalApi.class).isEmpty());
        var preflight = client.send(HttpRequest.newBuilder(client.base.resolve(API + "/accounts"))
            .header("Origin", "https://untrusted.example")
            .header("Access-Control-Request-Method", "GET")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build());
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
    }

    @Test
    void practiceOptionsUseSessionCsrfOwnershipAndLeastPrivilege() throws Exception {
        grantDerivativeAccess();
        try (var app = startApplication(true, false, false, Map.of("tape.practice-options.enabled", true))) {
            var client = new Browser(app);
            var other = new Browser(app);
            String email = email();
            assertEquals(201, client.register(email).statusCode());
            assertEquals(401, client.get(API + "/accounts/" + UUID.randomUUID() + "/options").statusCode());
            assertEquals(204, client.login(email, PASSWORD).statusCode());
            var accounts = JSON.readTree(client.get(API + "/accounts").body());
            String path = API + "/accounts/" + accounts.get(0).path("id").asText() + "/options";
            assertEquals(404, client.get(API + "/accounts/" + accounts.get(1).path("id").asText() + "/options").statusCode());
            var view = JSON.readTree(client.get(path).body());
            assertEquals("TAPE_OPTIONS_PRACTICE_V1", view.path("source").asText());
            var listing = view.path("chain").get(0);
            String ticket = JSON.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "contractId", listing.path("id").asText(),
                "specVersion", listing.path("specVersion").asInt(), "side", "SELL", "lots", 1, "limitPaise", 25_000));
            assertEquals(403, client.write("POST", path + "/orders", ticket, "application/json", false).statusCode());
            var result = client.write("POST", path + "/orders", ticket, "application/json", true);
            assertEquals(200, result.statusCode(), result.body());
            String order = path + "/orders/" + JSON.readTree(result.body()).path("id").asText();
            assertEquals(403, client.write("DELETE", order, "", null, false).statusCode());
            String otherEmail = email();
            assertEquals(201, other.register(otherEmail).statusCode());
            assertEquals(204, other.login(otherEmail, PASSWORD).statusCode());
            assertEquals(404, other.get(path).statusCode());
            assertEquals(404, other.write("DELETE", order, "", null, true).statusCode());
            assertEquals("CANCELLED", JSON.readTree(client.write("DELETE", order, "", null, true).body()).path("status").asText());
            String market = JSON.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "contractId", listing.path("id").asText(),
                "specVersion", listing.path("specVersion").asInt(), "side", "BUY", "lots", 1));
            var filled = client.write("POST", path + "/orders", market, "application/json", true);
            assertEquals(200, filled.statusCode(), filled.body());
            assertEquals("FILLED", JSON.readTree(filled.body()).path("status").asText());
            assertThrows(org.springframework.dao.DataAccessException.class, () -> new JdbcTemplate(runtimeDataSource())
                .update("UPDATE derivative_events SET cash_delta_paise=0"));
        }
    }

    @Test
    void futuresHttpUsesExistingOwnershipAndCsrfAndRejectsOptionContracts() throws Exception {
        grantDerivativeAccess();
        try (var app = startApplication(true, false, false,
            Map.of("tape.practice-options.enabled", true, "tape.practice-futures.enabled", true))) {
            String email = email();
            var client = new Browser(app);
            assertEquals(201, client.register(email).statusCode());
            assertEquals(204, client.login(email, PASSWORD).statusCode());
            String account = API + "/accounts/" + JSON.readTree(client.get(API + "/accounts").body()).get(0).path("id").asText();
            var listing = JSON.readTree(client.get(account + "/futures").body()).path("chain").get(0);
            String ticket = JSON.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "contractId", listing.path("id").asText(),
                "specVersion", listing.path("specVersion").asInt(), "side", "SELL", "lots", 1));
            assertEquals(403, client.write("POST", account + "/futures/orders", ticket, "application/json", false).statusCode());
            assertEquals(409, client.write("POST", account + "/options/orders", ticket, "application/json", true).statusCode());
            var result = client.write("POST", account + "/futures/orders", ticket, "application/json", true);
            assertEquals(200, result.statusCode(), result.body());
            assertEquals("FILLED", JSON.readTree(result.body()).path("status").asText());
            assertEquals(409, client.write("POST", account + "/options/orders", ticket, "application/json", true).statusCode());
            assertEquals(404, client.write("DELETE", account + "/options/orders/" + JSON.readTree(result.body()).path("id").asText(), "", null, true).statusCode());
            String other = email();
            var intruder = new Browser(app);
            assertEquals(201, intruder.register(other).statusCode());
            assertEquals(204, intruder.login(other, PASSWORD).statusCode());
            assertEquals(404, intruder.get(account + "/futures").statusCode());
            assertEquals("FILL", JSON.readTree(client.get(account + "/futures").body()).path("journal").get(0).path("kind").asText());
            assertEquals(0, JSON.readTree(client.get(account + "/options").body()).path("positions").size());
        }
    }

    private static void grantDerivativeAccess() {
        owner.execute("GRANT EXECUTE ON FUNCTION lock_tape_trading_account(uuid, uuid) TO tape_runtime");
        new ResourceDatabasePopulator(new FileSystemResource("docs/options-runtime-grants.sql")).execute(owner.getDataSource());
    }

    @Test
    void expiredTomcatSessionNoLongerAuthorizesRequests() throws Exception {
        var client = registered(email());
        var server = (TomcatWebServer) ((ServletWebServerApplicationContext) local).getWebServer();
        var webContext = (Context) server.getTomcat().getHost().findChild("");
        var session = webContext.getManager().findSession(client.sessionId());
        assertNotNull(session);
        session.setMaxInactiveInterval(1);
        Thread.sleep(1_200);
        assertEquals(401, client.get(API + "/accounts").statusCode());
    }

    @Test
    void runtimeRoleCannotChangeLedgerOrCreateSchemaObjects() {
        var runtime = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "tape_runtime", runtimePassword));
        for (String sql : new String[]{
            "UPDATE cash_ledger_entries SET amount_paise=1",
            "DELETE FROM cash_ledger_entries",
            "TRUNCATE cash_ledger_entries",
            "CREATE TABLE runtime_should_not_create (id int)",
            "SELECT * FROM executions"
        }) {
            var error = assertThrows(org.springframework.dao.DataAccessException.class, () -> runtime.execute(sql));
            assertEquals("42501", ((java.sql.SQLException) error.getMostSpecificCause()).getSQLState());
        }
    }

    @Test
    void productionRejectsHttpAndSetsSecureCookiesThroughTrustedLoopbackProxy() throws Exception {
        try (var production = startApplication(false)) {
            var client = new Browser(production);
            var insecure = client.get(API + "/auth/csrf");
            assertEquals(403, insecure.statusCode());
            assertTrue(insecure.headers().firstValue("Set-Cookie").isEmpty());
            var proxyRequest = HttpRequest.newBuilder(client.base.resolve(API + "/auth/csrf"))
                .header("X-Forwarded-Proto", "https").GET().build();
            var response = client.send(proxyRequest);
            assertEquals(200, response.statusCode());
            String cookie = String.join(";", response.headers().allValues("Set-Cookie"));
            assertTrue(cookie.contains("Secure"));
            assertTrue(cookie.contains("HttpOnly"));
            assertTrue(cookie.contains("SameSite=Lax"));
        }
    }

    @Test
    void httpsSessionAndLogoutAreSharedByIndependentNodes() throws Exception {
        String email = email();
        try (var first = startApplication(false); var second = startApplication(false)) {
            var a = new Browser(first);
            var b = new Browser(second);
            var bootstrap = a.proxy("GET", API + "/auth/csrf", null, "", null);
            assertEquals(200, bootstrap.statusCode());
            String before = sessionCookie(bootstrap);
            var csrf = JSON.readTree(bootstrap.body());
            var registration = JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD));
            assertEquals(201, a.proxy("POST", API + "/auth/register", before, registration, csrf).statusCode());
            String form = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8) +
                "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
            var login = a.proxy("POST", API + "/auth/login", before, form, csrf);
            assertEquals(204, login.statusCode());
            String signedIn = sessionCookie(login);
            assertNotEquals(before, signedIn);
            assertEquals(401, b.proxy("GET", API + "/auth/me", before, "", null).statusCode());
            var restored = b.proxy("GET", API + "/auth/me", signedIn, "", null);
            assertEquals(200, restored.statusCode());
            assertEquals(email, JSON.readTree(restored.body()).path("email").asText());
            assertEquals("DISCONNECTED", JSON.readTree(b.proxy("GET", API + "/market-data/status", signedIn, "", null).body())
                .path("status").asText());
            csrf = JSON.readTree(b.proxy("GET", API + "/auth/csrf", signedIn, "", null).body());
            assertEquals(204, b.proxy("POST", API + "/auth/logout", signedIn, "", csrf).statusCode());
            assertEquals(401, a.proxy("GET", API + "/accounts", signedIn, "", null).statusCode());
        }
    }

    @Test
    void readinessDetectsMissingRuntimeSessionGrants() {
        var storage = local.getBean("accountStorage", org.springframework.boot.actuate.health.HealthIndicator.class);
        assertEquals(org.springframework.boot.actuate.health.Status.UP, storage.health().getStatus());
        owner.execute("REVOKE DELETE ON tape_http_session FROM tape_runtime");
        try {
            assertEquals(org.springframework.boot.actuate.health.Status.DOWN, storage.health().getStatus());
        } finally {
            owner.execute("GRANT DELETE ON tape_http_session TO tape_runtime");
        }
        assertEquals(org.springframework.boot.actuate.health.Status.UP, storage.health().getStatus());
    }

    @Test
    void authenticationBudgetIsAtomicAndSharedBetweenRuntimeConnections() throws Exception {
        var first = new AuthRequestBudget(new JdbcTemplate(runtimeDataSource()));
        var second = new AuthRequestBudget(new JdbcTemplate(runtimeDataSource()));
        var bucket = email();
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var work = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 16; i++) {
                var budget = i % 2 == 0 ? first : second;
                work.add(() -> budget.allow(bucket, 2));
            }
            int admitted = 0;
            for (var result : threads.invokeAll(work)) if (result.get()) admitted++;
            assertTrue(admitted >= 2 && admitted <= 4, "A clock-minute boundary can admit two requests in each of two windows");
        }
        assertEquals(0L, owner.queryForObject("SELECT count(*) FROM auth_request_budget WHERE octet_length(bucket) <> 32", Long.class));
    }

    private static String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("JSESSIONID="))
            .map(value -> value.split(";", 2)[0]).findFirst().orElseThrow();
    }

    @Test
    void providerMarketDataRequiresAuthenticationAndExplicitLocalEntitlement() throws Exception {
        var anonymous = new Browser(local);
        var alice = registered(email());
        String routes = API + "/market-data";
        assertEquals(401, anonymous.get(routes + "/status").statusCode());
        assertEquals("DISCONNECTED", JSON.readTree(alice.get(routes + "/status").body()).path("status").asText());
        assertEquals(403, alice.get(routes + "/quote?key=NSE_EQ%7CZZ0000000002").statusCode());
        assertEquals(403, alice.get(routes + "/stream?key=NSE_EQ%7CZZ0000000002").statusCode());
        assertEquals(403, alice.get(routes + "/instrument?key=NSE_EQ%7CZZ0000000002").statusCode());
        assertEquals(403, alice.write("POST", routes + "/orders", "{}", "application/json", true).statusCode());

        String ownerEmail = email();
        var entitled = registered(ownerEmail);
        UUID ownerId = owner.queryForObject("SELECT user_id FROM local_credentials WHERE email=?", UUID.class, ownerEmail);
        new ResourceDatabasePopulator(new FileSystemResource("docs/market-data-runtime-grants.sql")).execute(owner.getDataSource());
        try (var market = startApplication(true, false, false, Map.of("TAPE_MARKET_OWNER_ID", ownerId.toString(),
            "TAPE_MARKET_PERMISSION_REF", "test-gate", "TAPE_MARKET_PERMISSIONS", "WATCHLIST,QUOTES", "TAPE_MARKET_DISPLAY_DELAY", "NONE",
            "TAPE_MARKET_EXCHANGES", "NSE,BSE", "UPSTOX_ANALYTICS_TOKEN", ""))) {
            var client = new Browser(market);
            assertEquals(204, client.login(ownerEmail, PASSWORD).statusCode());
            assertEquals("TOKEN_REQUIRED", JSON.readTree(client.get(routes + "/status").body()).path("status").asText());
            assertEquals(503, client.get(routes + "/quote?key=NSE_EQ%7CZZ0000000002").statusCode());
            assertEquals(403, client.write("PUT", routes + "/watchlist", "{\"instrumentKey\":\"NSE_EQ|ZZ0000000002\"}",
                "application/json", false).statusCode());
            assertEquals(204, client.write("PUT", routes + "/watchlist", "{\"instrumentKey\":\"NSE_EQ|ZZ0000000002\"}",
                "application/json", true).statusCode());
            assertEquals(1, JSON.readTree(client.get(routes + "/watchlist").body()).size());
            String name = "{\"name\":\"Derivatives\"}";
            assertEquals(403, client.write("POST", routes + "/watchlists", name, "application/json", false).statusCode());
            assertEquals(204, client.write("POST", routes + "/watchlists", name, "application/json", true).statusCode());
            assertEquals(2, JSON.readTree(client.get(routes + "/watchlists").body()).size());
            assertEquals(204, client.write("PUT", routes + "/watchlist?name=Derivatives", "{\"instrumentKey\":\"BSE_INDEX|Fixture\"}",
                "application/json", true).statusCode());
            assertEquals("BSE_INDEX|Fixture", JSON.readTree(client.get(routes + "/watchlist?name=Derivatives").body()).get(0).path("instrumentKey").asText());
            assertEquals(1, JSON.readTree(client.get(routes + "/watchlist").body()).size());
            assertEquals(400, client.write("DELETE", routes + "/watchlists?name=Default", "", null, true).statusCode());
            assertEquals(404, client.write("PUT", routes + "/watchlist?name=Missing", "{\"instrumentKey\":\"BSE_INDEX|Fixture\"}",
                "application/json", true).statusCode());
            assertEquals(403, client.get(routes + "/streams?keys=NSE_EQ%7CZZ0000000002").statusCode());
            assertEquals(403, client.get(routes + "/intraday?key=NSE_EQ%7CZZ0000000002").statusCode());
            assertEquals(403, client.get(routes + "/market-session?exchange=NSE").statusCode());
            assertEquals(204, client.write("DELETE", routes + "/watchlist?key=NSE_EQ%7CZZ0000000002", "", null, true).statusCode());
            assertTrue(JSON.readTree(client.get(routes + "/watchlist").body()).isEmpty());
            var other = new Browser(market);
            String otherEmail = email();
            assertEquals(201, other.register(otherEmail).statusCode());
            assertEquals(204, other.login(otherEmail, PASSWORD).statusCode());
            assertEquals(403, other.get(routes + "/watchlist").statusCode());
            assertEquals(403, other.get(routes + "/watchlist?name=Derivatives").statusCode());
            assertEquals(403, other.write("DELETE", routes + "/watchlists?name=Derivatives", "", null, true).statusCode());
            assertEquals(204, client.write("DELETE", routes + "/watchlists?name=Derivatives", "", null, true).statusCode());
            assertTrue(JSON.readTree(client.get(routes + "/watchlist?name=Derivatives").body()).isEmpty());
        }

        var catalog = local.getBean(ProviderCatalog.class);
        String key = "NSE_EQ|ZZ0000000002";
        var initial = new MarketDataProvider.Instrument("UPSTOX", key, "NSE", "NSE_EQ", "FIRST", "[redacted]",
            "ZZ0000000002", MarketDataProvider.Segment.EQUITY, "UNKNOWN", null, null, null, null, null, 1L, null, null, null, null, null);
        var renamed = new MarketDataProvider.Instrument("UPSTOX", key, "NSE", "NSE_EQ", "RENAMED", "[redacted]",
            "ZZ0000000002", MarketDataProvider.Segment.EQUITY, "SUSPENDED", null, null, null, null, null, 1L, null, null, null, null, null);
        owner.execute("GRANT SELECT, INSERT, UPDATE (instrument, synchronized_at) ON market_instrument_cache TO tape_runtime");
        catalog.synchronize(ownerId, "UPSTOX", List.of(initial));
        UUID id = owner.queryForObject("SELECT id FROM market_instrument_cache WHERE user_id=?", UUID.class, ownerId);
        catalog.synchronize(ownerId, "UPSTOX", List.of(renamed));
        assertEquals(id, owner.queryForObject("SELECT id FROM market_instrument_cache WHERE user_id=?", UUID.class, ownerId));
        assertEquals("RENAMED", catalog.instrument(ownerId, "UPSTOX", key).symbol());
        assertThrows(MarketDataAccess.Rejected.class, () -> catalog.instrument(UUID.randomUUID(), "UPSTOX", key));
    }

    @Test
    void legacyTerminalStillServesDemoStateOnlyOnLoopback() throws Exception {
        var settings = Map.<String, Object>of("server.port", 0, "server.address", "0.0.0.0",
            "spring.datasource.url", "jdbc:h2:mem:legacy-security-regression",
            "UPSTOX_ANALYTICS_TOKEN", "fixture-credential", "OLLAMA_MODEL", "");
        try (var app = new SpringApplicationBuilder(Tape.class).profiles("h2-legacy")
            .initializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("legacy-security-test", settings))).run()) {
            var client = new Browser(app);
            var server = (TomcatWebServer) ((ServletWebServerApplicationContext) app).getWebServer();
            var address = server.getTomcat().getConnector().getProperty("address");
            assertTrue(assertInstanceOf(InetAddress.class, address).isLoopbackAddress());
            assertEquals(404, client.get(API + "/auth/me").statusCode());
            var response = client.get("/api/terminal?mode=demo");
            assertEquals(200, response.statusCode());
            assertEquals(100_000_000, JSON.readTree(response.body()).path("account").path("cash").asLong());
            assertEquals(200, client.get("/api/candles?mode=demo&symbol=NSE%3ATCS").statusCode());
            assertEquals(200, client.get("/api/research?mode=demo&symbol=NSE%3ATCS").statusCode());
            var legacyLive = JSON.readTree(client.get("/api/terminal?mode=live").body());
            assertFalse(legacyLive.path("liveConfigured").asBoolean());
            assertTrue(legacyLive.path("feed").path("quotes").isEmpty());
            assertEquals(403, client.write("POST", "/api/orders?mode=live", "{}", "application/json", false).statusCode());
        }
    }

    private static ConfigurableApplicationContext startApplication(boolean localMode) {
        return startApplication(localMode, false);
    }

    static DriverManagerDataSource runtimeDataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "tape_runtime", runtimePassword);
    }

    static ConfigurableApplicationContext startApplication(boolean localMode, boolean trading) {
        return startApplication(localMode, trading, false);
    }

    static ConfigurableApplicationContext startApplication(boolean localMode, boolean trading, boolean matcher) {
        return startApplication(localMode, trading, matcher, Map.of());
    }

    static ConfigurableApplicationContext startApplication(boolean localMode, boolean trading, boolean matcher,
                                                           Map<String, Object> overrides) {
        var profiles = localMode ? new String[]{"accounts", "accounts-local"} : new String[]{"accounts"};
        var settings = new java.util.HashMap<String, Object>(Map.of(
            "TAPE_ACCOUNTS_URL", POSTGRES.getJdbcUrl(), "TAPE_ACCOUNTS_PASSWORD", runtimePassword,
            "TAPE_ACCOUNTS_USER", "tape_runtime", "TAPE_ACCOUNTS_MIGRATE", "false", "server.port", 0,
            "UPSTOX_ANALYTICS_TOKEN", "", "OLLAMA_MODEL", ""));
        settings.put("TAPE_SYNTHETIC_TRADING_ENABLED", trading);
        settings.put("TAPE_SYNTHETIC_MATCHER_ENABLED", matcher);
        if (!localMode) {
            settings.put("server.forward-headers-strategy", "native");
            settings.put("server.tomcat.remoteip.internal-proxies", "127\\.0\\.0\\.1");
        }
        settings.put("TAPE_RECONCILIATION_SCHEDULED", false);
        settings.putAll(overrides);
        return new SpringApplicationBuilder(Tape.class).profiles(profiles)
            .initializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("accounts-test", settings)))
            .run();
    }

    private static String email() { return UUID.randomUUID() + "@example.test"; }

    private static Browser registered(String email) throws Exception {
        var browser = new Browser(local);
        assertEquals(201, browser.register(email).statusCode());
        assertEquals(204, browser.login(email, PASSWORD).statusCode());
        return browser;
    }

    static final class Browser {
        private final URI base;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5)).build();

        Browser(ConfigurableApplicationContext app) {
            int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
            base = URI.create("http://127.0.0.1:" + port);
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.getName())).findFirst().orElseThrow().getValue();
        }

        JsonNode token() throws Exception {
            var response = get(API + "/auth/csrf");
            assertEquals(200, response.statusCode());
            return JSON.readTree(response.body());
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(base.resolve(path)).GET().build());
        }

        HttpResponse<String> register(String email) throws Exception {
            return write("POST", API + "/auth/register", JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD)),
                "application/json", true);
        }

        HttpResponse<String> login(String email, String password) throws Exception {
            return write("POST", API + "/auth/login",
                "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded", true);
        }

        HttpResponse<String> write(String method, String path, String body, String type, boolean csrf) throws Exception {
            var request = HttpRequest.newBuilder(base.resolve(path)).method(method, HttpRequest.BodyPublishers.ofString(body));
            if (type != null) request.header("Content-Type", type);
            if (csrf) {
                var token = token();
                request.header(token.path("headerName").asText(), token.path("token").asText());
            }
            return send(request.build());
        }

        HttpResponse<String> send(HttpRequest request) throws Exception {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> proxy(String method, String path, String cookie, String body, JsonNode csrf) throws Exception {
            var request = HttpRequest.newBuilder(base.resolve(path)).header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-For", "198.51.100.33").method(method, HttpRequest.BodyPublishers.ofString(body));
            if (cookie != null) request.header("Cookie", cookie);
            if (path.endsWith("/login")) request.header("Content-Type", "application/x-www-form-urlencoded");
            if (path.endsWith("/register")) request.header("Content-Type", "application/json");
            if (csrf != null) request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
            return send(request.build());
        }
    }
}
