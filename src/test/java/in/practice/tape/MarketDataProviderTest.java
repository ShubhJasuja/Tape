
package in.practice.tape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
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
            assertEquals(1, calls.get());
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
    void streamReconnectsWithFreshAuthorizationAndDoesNotRetainDisconnectedTick() throws Exception {
        var current = new AtomicReference<>(NOW);
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
            @Override public Instant instant() { return current.get(); }
        };
        var authorizations = new AtomicInteger();
        var established = new LinkedBlockingQueue<WebSocket.Listener>();
        var socket = mock(WebSocket.class);
        when(socket.sendBinary(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(socket));
        try (var provider = provider(clock, false, path -> { throw new AssertionError(); });
             var stream = new UpstoxQuoteStream(JSON, clock, () -> {
                 authorizations.incrementAndGet();
                 return URI.create("wss://fixture.upstox.com/fixture");
             }, (key, at, received, status, last, change) -> provider.quote(key, at, received, status, last, change,
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
            first.onClose(socket, 1001, "test");
            assertNull(stream.read(KEY).quote());
            assertEquals("DISCONNECTED", stream.read(KEY).status());
            current.set(NOW.plusSeconds(3));
            assertEquals("CONNECTING", stream.read(KEY).status());
            var second = established.poll(3, TimeUnit.SECONDS);
            assertNotNull(second);
            second.onBinary(socket, ByteBuffer.wrap(frame(current.get())), true);
            current.set(NOW.plusSeconds(35));
            assertEquals("STALE", stream.read(KEY).quote().freshness());
            assertEquals(NOW.plusSeconds(3), stream.read(KEY).quote().receivedAt());
            assertEquals(2, authorizations.get());
        }
    }

    private static byte[] frame(Instant time) throws IOException {
        byte[] ltpc = bytes(out -> { out.writeDouble(1, 101); out.writeInt64(2, time.toEpochMilli()); out.writeDouble(4, 100); });
        byte[] feed = bytes(out -> out.writeByteArray(1, ltpc));
        byte[] entry = bytes(out -> { out.writeString(1, KEY); out.writeByteArray(2, feed); });
        return bytes(out -> out.writeByteArray(2, entry));
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
    private static UpstoxMarketData provider(Clock clock, boolean configured, UpstoxMarketData.Transport transport) {
        return new UpstoxMarketData(JSON, clock, configured, transport);
    }
    private static UpstoxMarketData contract(String fixture, java.util.function.Consumer<String> check) throws IOException {
        try (var input = MarketDataProviderTest.class.getResourceAsStream("/market-data/upstox-contracts.json")) {
            JsonNode json = JSON.readTree(input).get(fixture);
            return provider(fixedClock(), true, path -> {
                check.accept(path);
                return new UpstoxMarketData.Response(200, "", JSON.writeValueAsBytes(json));
            });
        }
    }
}
