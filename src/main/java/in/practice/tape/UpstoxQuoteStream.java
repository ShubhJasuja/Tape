
package in.practice.tape;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

final class UpstoxQuoteStream implements AutoCloseable {
    private static final int FRAME_LIMIT = 64 * 1024;
    private static final Duration IDLE = Duration.ofSeconds(45);
    private final ObjectMapper json;
    private final Clock clock;
    private final Supplier<URI> authorize;
    private final Connector connector;
    private final QuoteFactory quotes;
    private final java.util.concurrent.ScheduledExecutorService worker =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> Thread.ofVirtual().unstarted(task));
    private Session active;
    private boolean closed;

    interface Connector { CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener); }
    interface QuoteFactory {
        MarketDataProvider.Quote create(String key, Instant at, Instant received, String status, BigDecimal last, BigDecimal change);
    }

    UpstoxQuoteStream(ObjectMapper json, Clock clock, Supplier<URI> authorize, QuoteFactory quotes) {
        this(json, clock, authorize, quotes, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .build().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))::buildAsync);
    }

    UpstoxQuoteStream(ObjectMapper json, Clock clock, Supplier<URI> authorize, QuoteFactory quotes, Connector connector) {
        this.json = json;
        this.clock = clock;
        this.authorize = authorize;
        this.quotes = quotes;
        this.connector = connector;
        worker.scheduleWithFixedDelay(this::expire, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    synchronized MarketDataProvider.Stream read(String key) {
        var now = clock.instant();
        if (closed) return new MarketDataProvider.Stream("DISCONNECTED", null);
        if (active == null || !active.key.equals(key)) {
            disconnect();
            active = new Session(key);
        }
        Session session = active;
        session.demand = now;
        if ("CONNECTED".equals(session.status) && session.lastMessage.plus(IDLE).isBefore(now)) {
            failed(session, "DISCONNECTED");
        }
        if (!session.connecting && session.socket == null && !session.retryAt.isAfter(now)) {
            session.connecting = true;
            session.status = "CONNECTING";
            worker.execute(() -> connect(session));
        }
        var quote = session.tick == null || !"CONNECTED".equals(session.status) ? null :
            quotes.create(key, session.tick.at, now, session.marketStatus, session.tick.last, session.tick.change);
        if (quote != null) {
            var received = session.tick.received;
            quote = new MarketDataProvider.Quote(quote.source(), quote.exchange(), quote.key(), quote.exchangeAt(), received,
                quote.freshness(), quote.ageMillis(), quote.marketStatus(), quote.last(), quote.change(), quote.percentChange(),
                null, null, null, null, null);
        }
        return new MarketDataProvider.Stream(session.status, quote);
    }

    private void connect(Session session) {
        synchronized (this) { if (closed || active != session) return; }
        try {
            URI uri = authorize.get();
            synchronized (this) { if (closed || active != session) return; }
            connector.connect(uri, session).orTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((socket, error) -> { if (error != null) failed(session, "DISCONNECTED"); });
        } catch (MarketDataAccess.Rejected e) {
            failed(session, e.failure.code());
        } catch (RuntimeException e) {
            failed(session, "DISCONNECTED");
        }
    }

    private synchronized void expire() {
        if (active != null && active.demand.plus(IDLE).isBefore(clock.instant())) disconnect();
    }

    private void disconnect() {
        var old = active;
        active = null;
        if (old != null && old.socket != null) old.socket.abort();
    }

    private synchronized void failed(Session session, String status) {
        if (active != session || (!session.connecting && session.socket == null)) return;
        if (session.socket != null) session.socket.abort();
        session.socket = null;
        session.connecting = false;
        session.tick = null;
        session.marketStatus = "UNKNOWN";
        session.status = status;
        long wait = status.equals("MARKET_AUTH_FAILED") ? 60 : Math.min(30, 1L << Math.min(5, ++session.failures));
        session.retryAt = clock.instant().plusSeconds(wait);
        session.frame.reset();
    }

    @Override public synchronized void close() {
        closed = true;
        disconnect();
        worker.shutdownNow();
    }

    private final class Session implements WebSocket.Listener {
        final String key;
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        WebSocket socket;
        Instant retryAt = Instant.MIN, demand = clock.instant(), lastMessage = demand;
        boolean connecting;
        int failures;
        String status = "DISCONNECTED", marketStatus = "UNKNOWN";
        Tick tick;

        Session(String key) { this.key = key; }

        @Override public void onOpen(WebSocket ws) {
            synchronized (UpstoxQuoteStream.this) {
                if (closed || active != this || !connecting) { ws.abort(); return; }
                socket = ws;
                lastMessage = clock.instant();
                try {
                    byte[] request = json.writeValueAsBytes(Map.of("guid", UUID.randomUUID().toString(), "method", "sub",
                        "data", Map.of("mode", "ltpc", "instrumentKeys", java.util.List.of(key))));
                    ws.sendBinary(ByteBuffer.wrap(request), true)
                        .whenComplete((ignored, error) -> { if (error != null) failed(this, "DISCONNECTED"); });
                } catch (IOException e) {
                    failed(this, "DISCONNECTED");
                }
                ws.request(1);
            }
        }

        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer fragment, boolean last) {
            synchronized (UpstoxQuoteStream.this) {
                if (active != this || socket != ws) return null;
                if (fragment.remaining() > FRAME_LIMIT - frame.size()) { failed(this, "DISCONNECTED"); return null; }
                byte[] bytes = new byte[fragment.remaining()];
                fragment.get(bytes);
                frame.writeBytes(bytes);
                if (last) {
                    try {
                        var update = decode(frame.toByteArray(), key, clock.instant());
                        lastMessage = clock.instant();
                        if (update.marketStatus != null) marketStatus = update.marketStatus;
                        if (update.tick != null && (tick == null || !update.tick.at.isBefore(tick.at))) tick = update.tick;
                        connecting = false;
                        failures = 0;
                        status = "CONNECTED";
                    } catch (IOException | RuntimeException e) {
                        failed(this, "DISCONNECTED");
                        return null;
                    } finally {
                        frame.reset();
                    }
                }
                ws.request(1);
                return null;
            }
        }

        @Override public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            synchronized (UpstoxQuoteStream.this) {
                if (active == this && socket == ws) { lastMessage = clock.instant(); ws.request(1); }
            }
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            synchronized (UpstoxQuoteStream.this) { if (socket == ws) failed(this, "DISCONNECTED"); }
            return null;
        }
        @Override public void onError(WebSocket ws, Throwable error) {
            synchronized (UpstoxQuoteStream.this) { if (socket == ws) failed(this, "DISCONNECTED"); }
        }
    }

    record Tick(Instant at, Instant received, BigDecimal last, BigDecimal change) {}
    record Update(Tick tick, String marketStatus) {}

    // Only the documented FeedResponse/Feed/LTPC and segment-status fields are read.
    static Update decode(byte[] bytes, String key, Instant received) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        Tick quote = null;
        String status = null;
        for (int tag; (tag = input.readTag()) != 0;) {
            if (tag == 18) {
                var entry = CodedInputStream.newInstance(input.readByteArray());
                String found = null;
                byte[] value = null;
                for (int field; (field = entry.readTag()) != 0;) {
                    if (field == 10) found = entry.readStringRequireUtf8();
                    else if (field == 18) value = entry.readByteArray();
                    else entry.skipField(field);
                }
                if (key.equals(found) && value != null) quote = tick(value, received);
            } else if (tag == 34) {
                status = marketStatus(input.readByteArray(), key.substring(0, key.indexOf('|')));
            } else input.skipField(tag);
        }
        return new Update(quote, status);
    }

    private static Tick tick(byte[] bytes, Instant received) throws IOException {
        var feed = CodedInputStream.newInstance(bytes);
        for (int field; (field = feed.readTag()) != 0;) {
            if (field != 10) { feed.skipField(field); continue; }
            var ltpc = CodedInputStream.newInstance(feed.readByteArray());
            double price = 0, close = 0;
            long time = 0;
            for (int tag; (tag = ltpc.readTag()) != 0;) {
                if (tag == 9) price = ltpc.readDouble();
                else if (tag == 16) time = ltpc.readInt64();
                else if (tag == 33) close = ltpc.readDouble();
                else ltpc.skipField(tag);
            }
            if (!Double.isFinite(price) || price <= 0 || time < 946684800000L || time >= 7258118400000L) return null;
            var last = BigDecimal.valueOf(price);
            return new Tick(Instant.ofEpochMilli(time), received, last,
                Double.isFinite(close) && close > 0 ? last.subtract(BigDecimal.valueOf(close)) : null);
        }
        return null;
    }

    private static String marketStatus(byte[] bytes, String segment) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        for (int tag; (tag = input.readTag()) != 0;) {
            if (tag != 10) { input.skipField(tag); continue; }
            var entry = CodedInputStream.newInstance(input.readByteArray());
            String key = null;
            int status = 0;
            for (int field; (field = entry.readTag()) != 0;) {
                if (field == 10) key = entry.readStringRequireUtf8();
                else if (field == 16) status = entry.readEnum();
                else entry.skipField(field);
            }
            if (segment.equals(key)) return switch (status) {
                case 0 -> "PRE_OPEN_START";
                case 1 -> "PRE_OPEN_END";
                case 2 -> "NORMAL_OPEN";
                case 3 -> "NORMAL_CLOSE";
                case 4 -> "CLOSING_START";
                case 5 -> "CLOSING_END";
                default -> "UNKNOWN";
            };
        }
        return null;
    }
}
