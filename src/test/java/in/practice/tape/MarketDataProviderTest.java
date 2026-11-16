
package in.practice.tape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static in.practice.tape.MarketDataProvider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDataProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "NSE_EQ|ZZ0000000002";
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2026, 12, 1);

    @Test
    void credentialsAndAuthorizationErrorsDoNotUseOrLeakProviderBody() {
        var calls = new AtomicInteger();
        var clock = fixedClock();
        try (var proxy = provider(clock, false, path -> { calls.incrementAndGet(); throw new AssertionError(); })) {
            assertEquals("MARKET_TOKEN_REQUIRED", code(() -> proxy.quote(KEY)));
            assertEquals(0, calls.get());
        }
        try (var proxy = provider(clock, true, path -> {
            calls.incrementAndGet();
            return new UpstoxMarketData.Response(401, "", "private diagnostic".getBytes(StandardCharsets.UTF_8));
        })) {
            assertEquals("MARKET_AUTH_FAILED", code(() -> proxy.quote(KEY)));
            assertEquals("MARKET_AUTH_FAILED", code(() -> proxy.quote(KEY)));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void providerDenialDoesNotMarkTheCredentialExpired() {
        var calls = new AtomicInteger();
        try (var proxy = provider(fixedClock(), true, path -> {
            calls.incrementAndGet();
            return new UpstoxMarketData.Response(403, "", new byte[0]);
        })) {
            assertEquals("MARKET_PROVIDER_FORBIDDEN", code(() -> proxy.quote(KEY)));
            assertEquals("MARKET_PROVIDER_FORBIDDEN", code(() -> proxy.quote(KEY)));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void timeoutRetriesOnceAndRateLimitStopsFurtherRequests() {
        var calls = new AtomicInteger();
        try (var proxy = provider(fixedClock(), true, path -> { calls.incrementAndGet(); throw new HttpTimeoutException("test"); })) {
            assertEquals("MARKET_TIMEOUT", code(() -> proxy.quote(KEY)));
            assertEquals(2, calls.get());
        }
        calls.set(0);
        try (var proxy = provider(fixedClock(), true, path -> {
            calls.incrementAndGet();
            return new UpstoxMarketData.Response(429, "30", new byte[0]);
        })) {
            assertEquals("MARKET_RATE_LIMITED", code(() -> proxy.quote(KEY)));
            assertEquals("MARKET_RATE_LIMITED", code(() -> proxy.quote(KEY)));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void honorsHttpDateRetryAfter() {
        var calls = new AtomicInteger();
        var current = new AtomicReference<>(NOW);
        try (var proxy = provider(changingClock(current), true, path -> {
            calls.incrementAndGet();
            return new UpstoxMarketData.Response(429, "Sat, 14 Nov 2026 10:02:00 GMT", new byte[0]);
        })) {
            assertEquals("MARKET_RATE_LIMITED", code(() -> proxy.quote(KEY)));
            current.set(NOW.plusSeconds(90));
            assertEquals("MARKET_RATE_LIMITED", code(() -> proxy.quote(KEY)));
            assertEquals(1, calls.get());
            current.set(NOW.plusSeconds(122));
            assertEquals("MARKET_RATE_LIMITED", code(() -> proxy.quote(KEY)));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void quotesResolveExactKeyAndMarkOldTradeStaleWithoutConvertingMissingFieldsToZero() throws Exception {
        try (var proxy = contract("quote", path -> assertTrue(path.matches(".*/quotes\\?instrument_key=[NB]SE_EQ%7CZZ0000000002")))) {
            var quote = proxy.quote(KEY);
            assertEquals("UPSTOX", quote.source());
            assertEquals("NSE", quote.exchange());
            assertEquals(KEY, quote.key());
            assertEquals(NOW, quote.receivedAt());
            assertEquals("STALE", quote.freshness());
            assertEquals(100, quote.last().intValueExact());
            assertEquals(0, quote.volume());
            var bse = proxy.quote("BSE_EQ|ZZ0000000002");
            assertNull(bse.change());
            assertNull(bse.exchangeAt());
            assertNull(bse.volume());
            assertEquals("UNKNOWN", bse.freshness());
        }
    }

    @Test
    void indexResponseKeyCanIdentifyAQuoteWithoutInventingTradeTime() throws Exception {
        try (var proxy = contract("indexQuote", path -> assertTrue(path.contains("INDEX%7C")))) {
            var quote = proxy.quote("NSE_INDEX|Redacted Index");
            assertEquals(100, quote.last().intValueExact());
            assertEquals(5, quote.change().intValueExact());
            assertNull(quote.exchangeAt());
            assertEquals(Instant.parse("2026-11-14T09:59:58Z"), quote.providerAt());
            assertEquals(NOW, quote.receivedAt());
            assertEquals("UNKNOWN", quote.freshness());
            assertEquals(101, proxy.quote("BSE_INDEX|Fixture Index").last().intValueExact());
            assertEquals("MARKET_QUOTE_UNAVAILABLE", code(() -> proxy.quote("BSE_INDEX|Missing")));
        }
    }

    @Test
    void instrumentMappingPreservesIdentityAndExpiry() throws Exception {
        try (var proxy = contract("search", path -> assertTrue(path.contains("/v2/instruments/search") || path.contains("/v2/option/contract")))) {
            var page = proxy.search("Redacted", "NSE", Segment.OPTIONS, null, 1);
            assertEquals(2, page.nextPage());
            assertEquals(2, page.instruments().size());
            assertEquals("NSE_FO|901", page.instruments().getFirst().key());
            assertEquals("EXPIRED", page.instruments().getLast().status());
            var options = proxy.options(KEY, EXPIRY);
            assertEquals(1, options.size());
            assertEquals(KEY, options.getFirst().underlyingKey());
            assertEquals(25, options.getFirst().lotSize());
            assertEquals("INDEX", proxy.search("Redacted", "NSE", Segment.FUTURES, EXPIRY, 1)
                .instruments().getFirst().underlyingType());
            assertEquals("BSE_INDEX|Fixture Index", proxy.search("Fixture", "BSE", Segment.INDEX, null, 1).instruments().getFirst().key());
            var future = proxy.search("Redacted", "NSE", Segment.FUTURES, EXPIRY, 1).instruments().getFirst();
            assertEquals(25, future.minimumLot());
            assertEquals(225, future.freezeQuantity());
            assertEquals(1, future.quantityMultiplier().intValueExact());
            assertEquals(false, future.weekly());
            var replacement = proxy.search("Redacted", "NSE", Segment.FUTURES, EXPIRY.plusDays(1), 1).instruments().getFirst();
            assertEquals(future.underlyingKey(), replacement.underlyingKey());
            assertNotEquals(future.key(), replacement.key());
            assertEquals(25, future.lotSize());
            assertEquals(50, replacement.lotSize());
            assertTrue(proxy.search("Redacted", "NSE", Segment.FUTURES, EXPIRY.plusDays(2), 1).instruments().isEmpty());
        }
    }

    @Test
    void intradayUsesCurrentIstDateAndPreservesMissingVolumeAndTradingGaps() throws Exception {
        try (var proxy = contract("intraday", path -> assertEquals("/v3/historical-candle/intraday/NSE_EQ%7CZZ0000000002/minutes/5", path))) {
            var result = proxy.intraday(KEY, Interval.FIVE_MINUTES);
            assertEquals(3, result.candles().size());
            assertEquals(0, result.candles().getFirst().volume());
            assertEquals(Instant.parse("2026-11-14T04:00:00Z"), result.candles().getLast().exchangeAt());
            assertNull(result.candles().getLast().volume());
        }
    }

    @Test
    void holidayIsExchangeSpecificAndSessionTransitionsUseProviderTimes() throws Exception {
        var current = new AtomicReference<>(NOW.minusSeconds(120));
        var calls = new AtomicInteger();
        try (var proxy = provider(changingClock(current), true, path -> {
            calls.incrementAndGet();
            if (path.equals("/v2/market/holidays/2026-11-14")) return fixture("holidays");
            assertEquals("/v2/market/timings/2026-11-14", path);
            return new UpstoxMarketData.Response(200, "", JSON.writeValueAsBytes(java.util.Map.of("status", "success", "data",
                java.util.List.of(java.util.Map.of("exchange", "BSE", "start_time", NOW.minusSeconds(30).toEpochMilli(),
                    "end_time", NOW.plusSeconds(30).toEpochMilli())))));
        })) {
            LocalDate day = LocalDate.of(2026, 11, 14);
            assertEquals("HOLIDAY", proxy.session("NSE", day).status());
            assertEquals("UPCOMING", proxy.session("BSE", day).status());
            current.set(NOW);
            assertEquals("OPEN", proxy.session("BSE", day).status());
            current.set(NOW.plusSeconds(30));
            assertEquals("CLOSED", proxy.session("BSE", day).status());
            assertEquals(3, calls.get());
        }
    }

    @Test
    void malformedOrFutureCandlesAreDroppedAndGoodCandlesAreSortedAndDeduplicated() throws Exception {
        try (var proxy = contract("history", path -> assertTrue(path.endsWith("/days/1/2026-11-16/2026-11-11")))) {
            var result = proxy.candles(KEY, Interval.DAILY, LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 16));
            assertEquals(2, result.candles().size());
            assertEquals(Instant.parse("2026-11-12T03:45:00Z"), result.candles().getFirst().exchangeAt());
            assertEquals(0, result.candles().getFirst().volume());
            assertNull(result.candles().getLast().volume());
        }
    }

    @Test
    void chainPreservesMissingLegsAndSortsStrikes() throws Exception {
        try (var proxy = contract("chain", path -> assertTrue(path.endsWith("expiry_date=2026-12-01")))) {
            var chain = proxy.chain(KEY, EXPIRY);
            assertEquals(2, chain.strikes().size());
            var first = chain.strikes().getFirst();
            assertEquals(100, first.strike().intValueExact());
            assertNull(first.put());
            assertNull(first.call().ask());
            assertEquals(0, first.call().volume());
            assertEquals(20, first.call().bidQuantity());
            assertEquals(800, first.call().previousOpenInterest());
            assertEquals(-0.45, first.call().greeks().theta().doubleValue());
            assertEquals(0, first.call().greeks().gamma().signum());
            assertEquals(15.5, first.call().greeks().iv().doubleValue());
            assertEquals(102, first.underlyingSpot().intValueExact());
            assertNull(chain.strikes().getLast().put().greeks());
        }
    }

    @Test
    void gateNeedsLocalProfileOwnerPermissionReferenceAndToken() {
        UUID owner = UUID.randomUUID();
        var env = new MockEnvironment().withProperty("TAPE_MARKET_OWNER_ID", owner.toString())
            .withProperty("TAPE_MARKET_PERMISSIONS", "QUOTES,STREAM");
        env.setActiveProfiles("accounts", "accounts-local");
        assertEquals("DISCONNECTED", new MarketDataAccess(env).status(owner));
        env.setProperty("TAPE_MARKET_PERMISSION_REF", "contract-test");
        assertEquals("DISCONNECTED", new MarketDataAccess(env).status(owner));
        env.setProperty("TAPE_MARKET_DISPLAY_DELAY", "30d");
        assertEquals("MARKET_PERMISSION_REQUIRED", code(() -> new MarketDataAccess(env).require(owner, Capability.STREAM)));
        env.setProperty("TAPE_MARKET_DISPLAY_DELAY", "NONE");
        var noToken = new MarketDataAccess(env);
        assertEquals("TOKEN_REQUIRED", noToken.status(owner));
        assertEquals("MARKET_TOKEN_REQUIRED", code(() -> noToken.require(owner, Capability.QUOTES)));
        env.setProperty("UPSTOX_ANALYTICS_TOKEN", "test-only");
        var access = new MarketDataAccess(env);
        access.require(owner, Capability.QUOTES);
        assertEquals("MARKET_PERMISSION_REQUIRED", code(() -> access.require(UUID.randomUUID(), Capability.QUOTES)));
        assertEquals("MARKET_PERMISSION_REQUIRED", code(() -> access.require(owner, Capability.HISTORY)));
        env.setActiveProfiles("accounts");
        assertEquals("DISCONNECTED", new MarketDataAccess(env).status(owner));
    }

    @Test
    void embargoAllowsOnlyHistoryAndCutsOffAtACompleteIstDay() {
        UUID owner = UUID.randomUUID();
        var env = new MockEnvironment().withProperty("TAPE_MARKET_OWNER_ID", owner.toString())
            .withProperty("TAPE_MARKET_PERMISSION_REF", "test-confirmation")
            .withProperty("TAPE_MARKET_PERMISSIONS", "QUOTES,HISTORY,INTRADAY,STREAM,OPTION_CHAIN,CALENDAR")
            .withProperty("TAPE_MARKET_DISPLAY_DELAY", "P30D")
            .withProperty("UPSTOX_ANALYTICS_TOKEN", "test-only");
        env.setActiveProfiles("accounts", "accounts-local");
        var access = new MarketDataAccess(env);
        assertEquals(Set.of(Capability.HISTORY, Capability.CALENDAR), access.capabilities(owner));
        assertEquals(LocalDate.of(2026, 10, 14), access.historyThrough(NOW));
        assertEquals(LocalDate.of(2026, 10, 15), access.historyThrough(Instant.parse("2026-11-14T18:30:00Z")));
        assertNull(access.displayDelay(UUID.randomUUID()));
        env.setProperty("TAPE_MARKET_DISPLAY_DELAY", "PT0S");
        assertEquals(Set.of(Capability.CALENDAR), new MarketDataAccess(env).capabilities(owner));
    }

    @Test
    void streamReconnectsWithFreshAuthorizationAndDoesNotRetainDisconnectedTick() throws Exception {
        var current = new AtomicReference<>(NOW);
        var clock = changingClock(current);
        var authorizations = new AtomicInteger();
        var established = new LinkedBlockingQueue<WebSocket.Listener>();
        var socket = mock(WebSocket.class);
        var firstSend = new CompletableFuture<WebSocket>();
        when(socket.sendBinary(any(), anyBoolean())).thenReturn(firstSend, CompletableFuture.completedFuture(socket));
        try (var provider = provider(clock, false, path -> { throw new AssertionError(); });
             var stream = new UpstoxQuoteStream(JSON, clock, () -> {
                 authorizations.incrementAndGet();
                 return URI.create("wss://fixture.upstox.com/fixture");
             }, (key, at, received, status, last, change) -> provider.quote(key, at, null, received, status, last, change,
                  null, null, null, null, null), (uri, listener) -> {
                 listener.onOpen(socket);
                 established.add(listener);
                 return CompletableFuture.completedFuture(socket);
             })) {
            assertEquals("CONNECTING", stream.read(KEY).status());
            var first = established.poll(3, TimeUnit.SECONDS);
            assertNotNull(first);
            byte[] frame = frame(NOW);
            int split = frame.length / 2;
            first.onBinary(socket, ByteBuffer.wrap(frame, 0, split), false);
            first.onBinary(socket, ByteBuffer.wrap(frame, split, frame.length - split), true);
            assertEquals(101, stream.read(KEY).quote().last().intValueExact());
            assertEquals(NOW.plusMillis(125), stream.read(KEY).quote().providerAt());
            first.onClose(socket, 1001, "test");
            assertNull(stream.read(KEY).quote());
            assertEquals("DISCONNECTED", stream.read(KEY).status());
            current.set(NOW.plusSeconds(3));
            assertEquals("CONNECTING", stream.read(KEY).status());
            var second = established.poll(3, TimeUnit.SECONDS);
            assertNotNull(second);
            second.onBinary(socket, ByteBuffer.wrap(frame(current.get())), true);
            firstSend.completeExceptionally(new IOException("late failure"));
            first.onClose(socket, 1001, "late close");
            current.set(NOW.plusSeconds(35));
            assertEquals("STALE", stream.read(KEY).quote().freshness());
            assertEquals(NOW.plusSeconds(3), stream.read(KEY).quote().receivedAt());
            assertEquals(2, authorizations.get());
        }
    }

    @Test
    void oneSocketMaintainsAChangingBatchAndReconnectsWithAllDesiredKeys() throws Exception {
        String index = "BSE_INDEX|Fixture Index";
        String future = "NSE_FO|905";
        var current = new AtomicReference<>(NOW);
        var clock = changingClock(current);
        var established = new LinkedBlockingQueue<WebSocket.Listener>();
        var sent = new LinkedBlockingQueue<JsonNode>();
        var socket = mock(WebSocket.class);
        when(socket.sendBinary(any(), eq(true))).thenAnswer(call -> {
            ByteBuffer buffer = call.getArgument(0);
            byte[] message = new byte[buffer.remaining()];
            buffer.get(message);
            sent.add(JSON.readTree(message));
            return CompletableFuture.completedFuture(socket);
        });
        try (var provider = provider(clock, false, path -> { throw new AssertionError(); });
             var stream = new UpstoxQuoteStream(JSON, clock, () -> URI.create("wss://fixture.upstox.com/fixture"),
                 (key, at, received, status, last, change) -> provider.quote(key, at, null, received, status, last, change,
                     null, null, null, null, null), (uri, listener) -> {
                 listener.onOpen(socket);
                 established.add(listener);
                 return CompletableFuture.completedFuture(socket);
             })) {
            var initial = Set.of(KEY, index);
            assertEquals("CONNECTING", stream.read(initial).status());
            var listener = established.poll(3, TimeUnit.SECONDS);
            assertNotNull(listener);
            command(sent.poll(3, TimeUnit.SECONDS), "sub", initial);
            listener.onBinary(socket, ByteBuffer.wrap(frame(NOW, KEY, index, future)), true);
            assertEquals(initial, stream.read(initial).quotes().keySet());

            var next = Set.of(index, future);
            assertEquals(Set.of(index), stream.read(next).quotes().keySet());
            command(sent.poll(3, TimeUnit.SECONDS), "unsub", Set.of(KEY));
            command(sent.poll(3, TimeUnit.SECONDS), "sub", Set.of(future));
            assertNull(established.poll());
            listener.onBinary(socket, ByteBuffer.wrap(frame(NOW, KEY, future)), true);
            assertEquals(next, stream.read(next).quotes().keySet());
            listener.onClose(socket, 1001, "fixture reset");
            assertTrue(stream.read(next).quotes().isEmpty());
            current.set(NOW.plusSeconds(3));
            assertEquals("CONNECTING", stream.read(next).status());
            assertNotNull(established.poll(3, TimeUnit.SECONDS));
            command(sent.poll(3, TimeUnit.SECONDS), "sub", next);
        }
    }

    @Test
    void streamHandshakeRateLimitWaitsForRetryAfterBeforeReauthorizing() throws Exception {
        var now = new AtomicReference<>(NOW);
        var attempted = new LinkedBlockingQueue<CompletableFuture<WebSocket>>();
        try (var stream = new UpstoxQuoteStream(JSON, changingClock(now), () -> URI.create("wss://fixture.upstox.com/fixture"),
            (key, at, received, status, last, change) -> { throw new AssertionError(); }, (uri, listener) -> {
                var attempt = new CompletableFuture<WebSocket>();
                attempted.add(attempt);
                return attempt;
            })) {
            assertEquals("CONNECTING", stream.read(KEY).status());
            var original = attempted.poll(3, TimeUnit.SECONDS);
            assertNotNull(original);
            HttpResponse<?> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(429);
            when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of("120")), (key, value) -> true));
            original.completeExceptionally(new WebSocketHandshakeException(response));
            String status = stream.read(KEY).status();
            for (int attempt = 0; attempt < 100 && status.equals("CONNECTING"); attempt++) {
                Thread.sleep(10);
                status = stream.read(KEY).status();
            }
            assertEquals("MARKET_RATE_LIMITED", status);
            now.set(NOW.plusSeconds(80));
            assertEquals("MARKET_RATE_LIMITED", stream.read(KEY).status());
            now.set(NOW.plusSeconds(121));
            assertEquals("CONNECTING", stream.read(KEY).status());
            assertNotNull(attempted.poll(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void connectedSocketWithoutAnInitialDataFrameTimesOut() throws Exception {
        var current = new AtomicReference<>(NOW);
        var opened = new CountDownLatch(1);
        var socket = mock(WebSocket.class);
        when(socket.sendBinary(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(socket));
        try (var stream = new UpstoxQuoteStream(JSON, changingClock(current), () -> URI.create("wss://fixture.upstox.com/fixture"),
            (key, at, received, status, last, change) -> { throw new AssertionError(); }, (uri, listener) -> {
                listener.onOpen(socket);
                opened.countDown();
                return CompletableFuture.completedFuture(socket);
            })) {
            assertEquals("CONNECTING", stream.read(KEY).status());
            assertTrue(opened.await(3, TimeUnit.SECONDS));
            current.set(NOW.plusSeconds(13));
            assertEquals("DISCONNECTED", stream.read(KEY).status());
            verify(socket).abort();
        }
    }

    private static byte[] frame(Instant time) throws IOException {
        return frame(time, KEY);
    }

    private static byte[] frame(Instant time, String... keys) throws IOException {
        byte[] ltpc = bytes(out -> { out.writeDouble(1, 101); out.writeInt64(2, time.toEpochMilli()); out.writeDouble(4, 100); });
        byte[] feed = bytes(out -> out.writeByteArray(1, ltpc));
        return bytes(out -> {
            for (String key : keys) {
                byte[] entry = bytes(message -> { message.writeString(1, key); message.writeByteArray(2, feed); });
                out.writeByteArray(2, entry);
            }
            out.writeInt64(3, time.plusMillis(125).toEpochMilli());
        });
    }

    private static void command(JsonNode message, String method, Set<String> keys) {
        assertNotNull(message);
        assertEquals(method, message.path("method").asText());
        var actual = new java.util.HashSet<String>();
        message.path("data").path("instrumentKeys").forEach(value -> actual.add(value.asText()));
        assertEquals(keys, actual);
    }

    interface Output { void write(CodedOutputStream output) throws IOException; }
    private static byte[] bytes(Output action) throws IOException {
        var buffer = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(buffer);
        action.write(output);
        output.flush();
        return buffer.toByteArray();
    }

    private static String code(org.junit.jupiter.api.function.Executable request) {
        return assertThrows(MarketDataAccess.Rejected.class, request).failure.code();
    }
    private static Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    private static Clock changingClock(AtomicReference<Instant> current) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
            @Override public Instant instant() { return current.get(); }
        };
    }
    private static UpstoxMarketData provider(Clock clock, boolean configured, UpstoxMarketData.Transport transport) {
        return new UpstoxMarketData(JSON, clock, configured, transport);
    }
    private static UpstoxMarketData contract(String fixture, java.util.function.Consumer<String> check) throws IOException {
        var response = fixture(fixture);
        return provider(fixedClock(), true, path -> {
            check.accept(path);
            return response;
        });
    }
    private static UpstoxMarketData.Response fixture(String name) throws IOException {
        try (var input = MarketDataProviderTest.class.getResourceAsStream("/market-data/upstox-contracts.json")) {
            JsonNode json = JSON.readTree(input).get(name);
            return new UpstoxMarketData.Response(200, "", JSON.writeValueAsBytes(json));
        }
    }
}
