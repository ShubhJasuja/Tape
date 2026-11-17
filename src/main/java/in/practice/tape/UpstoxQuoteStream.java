
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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

    MarketDataProvider.Stream read(String key) {
        var result = read(Set.of(key));
        return new MarketDataProvider.Stream(result.status(), result.quotes().get(key));
    }

    synchronized MarketDataProvider.Streams read(Set<String> keys) {
        var now = clock.instant();
        if (keys.isEmpty()) {
            disconnect();
            return new MarketDataProvider.Streams("DISCONNECTED", Map.of());
        }
        if (closed) return new MarketDataProvider.Streams("DISCONNECTED", Map.of());
        if (active == null) active = new Session();
        Session session = active;
        session.keys = Set.copyOf(keys);
        session.ticks.keySet().retainAll(keys);
        session.demand = now;
        if (session.socket != null && (session.lastMessage.plus(IDLE).isBefore(now) ||
            (session.connecting && session.openedAt.plusSeconds(12).isBefore(now)))) {
            failed(session, session.attempt, "DISCONNECTED");
        }
        if (!session.connecting && session.socket == null && !session.retryAt.isAfter(now)) {
            session.connecting = true;
            session.status = "CONNECTING";
            long attempt = ++session.attempt;
            worker.execute(() -> connect(session, attempt));
        }
        syncSubscriptions(session);
        var result = new HashMap<String, MarketDataProvider.Quote>();
        if ("CONNECTED".equals(session.status)) session.ticks.forEach((key, tick) -> {
            var quote = quotes.create(key, tick.at, now, session.marketStatus.getOrDefault(segment(key), "UNKNOWN"), tick.last, tick.change);
            result.put(key, new MarketDataProvider.Quote(quote.source(), quote.exchange(), quote.key(), quote.exchangeAt(),
                tick.providerAt, tick.received,
                quote.freshness(), quote.ageMillis(), quote.marketStatus(), quote.last(), quote.change(), quote.percentChange(),
                null, null, null, null, null));
        });
        return new MarketDataProvider.Streams(session.status, Map.copyOf(result));
    }

    // The JDK allows only one outstanding binary send on a socket.
    private void syncSubscriptions(Session session) {
        var socket = session.socket;
        if (socket == null || session.sending) return;
        var keys = new HashSet<>(session.subscribed);
        keys.removeAll(session.keys);
        boolean remove = !keys.isEmpty();
        if (!remove) {
            keys.addAll(session.keys);
            keys.removeAll(session.subscribed);
        }
        if (keys.isEmpty()) return;
        long attempt = session.attempt;
        session.sending = true;
        try {
            var data = remove ? Map.of("instrumentKeys", keys) : Map.of("mode", "ltpc", "instrumentKeys", keys);
            byte[] request = json.writeValueAsBytes(Map.of("guid", UUID.randomUUID().toString(),
                "method", remove ? "unsub" : "sub", "data", data));
            socket.sendBinary(ByteBuffer.wrap(request), true).orTimeout(8, java.util.concurrent.TimeUnit.SECONDS).whenComplete((ignored, error) -> {
                synchronized (UpstoxQuoteStream.this) {
                    if (active != session || session.socket != socket || session.attempt != attempt) return;
                    if (error != null) { failed(session, attempt, "DISCONNECTED"); return; }
                    if (remove) session.subscribed.removeAll(keys);
                    else session.subscribed.addAll(keys);
                    session.sending = false;
                    syncSubscriptions(session);
                }
            });
        } catch (IOException | RuntimeException e) {
            failed(session, attempt, "DISCONNECTED");
        }
    }

    private void connect(Session session, long attempt) {
        synchronized (this) { if (closed || active != session) return; }
        try {
            URI uri = authorize.get();
            synchronized (this) { if (closed || active != session) return; }
            var handshake = connector.connect(uri, new Attempt(session, attempt));
            handshake.orTimeout(8, java.util.concurrent.TimeUnit.SECONDS).whenComplete((socket, error) -> {
                if (error != null) {
                    var cause = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
                    if (cause instanceof java.net.http.WebSocketHandshakeException rejected) {
                        var response = rejected.getResponse();
                        String status = switch (response.statusCode()) {
                            case 401 -> "MARKET_STREAM_AUTH_FAILED";
                            case 403 -> "MARKET_PROVIDER_FORBIDDEN";
                            case 429 -> "MARKET_RATE_LIMITED";
                            default -> "DISCONNECTED";
                        };
                        long retryAfter = response.statusCode() == 429 ?
                            UpstoxMarketData.retrySeconds(response.headers().firstValue("Retry-After").orElse(""), clock.instant()) : 0;
                        failed(session, attempt, status, retryAfter);
                    } else failed(session, attempt, "DISCONNECTED");
                }
            });
        } catch (MarketDataAccess.Rejected e) {
            failed(session, attempt, e.failure.code());
        } catch (RuntimeException e) {
            failed(session, attempt, "DISCONNECTED");
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

    private void failed(Session session, long attempt, String status) { failed(session, attempt, status, 0); }

    private synchronized void failed(Session session, long attempt, String status, long retryAfter) {
        if (active != session || session.attempt != attempt || (!session.connecting && session.socket == null)) return;
        var socket = session.socket;
        session.socket = null;
        session.connecting = false;
        session.ticks.clear();
        session.marketStatus.clear();
        session.subscribed.clear();
        session.sending = false;
        session.status = status;
        long wait = switch (status) {
            case "MARKET_AUTH_FAILED", "MARKET_STREAM_AUTH_FAILED", "MARKET_PROVIDER_FORBIDDEN", "MARKET_RATE_LIMITED" -> 60;
            default -> Math.min(30, 1L << Math.min(5, ++session.failures));
        };
        session.retryAt = clock.instant().plusSeconds(Math.max(wait, retryAfter));
        session.frame.reset();
        if (socket != null) socket.abort();
    }

    @Override public synchronized void close() {
        closed = true;
        disconnect();
        worker.shutdownNow();
    }

    private final class Session {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        Set<String> keys = Set.of();
        final Set<String> subscribed = new HashSet<>();
        final Map<String, Tick> ticks = new HashMap<>();
        final Map<String, String> marketStatus = new HashMap<>();
        WebSocket socket;
        Instant retryAt = Instant.MIN, demand = clock.instant(), lastMessage = demand, openedAt = demand;
        boolean connecting, sending;
        int failures;
        long attempt;
        String status = "DISCONNECTED";
    }

    private final class Attempt implements WebSocket.Listener {
        private final Session session;
        private final long id;

        Attempt(Session session, long id) { this.session = session; this.id = id; }

        private boolean current(WebSocket ws) { return active == session && session.attempt == id && session.socket == ws; }

        @Override public void onOpen(WebSocket ws) {
            synchronized (UpstoxQuoteStream.this) {
                if (closed || active != session || session.attempt != id || !session.connecting) { ws.abort(); return; }
                session.socket = ws;
                session.lastMessage = session.openedAt = clock.instant();
                syncSubscriptions(session);
                if (current(ws)) ws.request(1);
            }
        }

        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer fragment, boolean last) {
            synchronized (UpstoxQuoteStream.this) {
                if (!current(ws)) return null;
                var frame = session.frame;
                if (fragment.remaining() > FRAME_LIMIT - frame.size()) { failed(session, id, "DISCONNECTED"); return null; }
                byte[] bytes = new byte[fragment.remaining()];
                fragment.get(bytes);
                frame.writeBytes(bytes);
                if (last) {
                    try {
                        var received = clock.instant();
                        var update = decode(frame.toByteArray(), session.keys, received);
                        session.marketStatus.putAll(update.marketStatus);
                        update.ticks.forEach((key, tick) -> session.ticks.merge(key, tick, (prior, next) ->
                            next.at.isBefore(prior.at) ? prior : next));
                        if (!update.ticks.isEmpty()) session.failures = 0;
                        if (!update.marketStatus.isEmpty() || !update.ticks.isEmpty()) {
                            session.lastMessage = received;
                            session.connecting = false;
                            session.status = "CONNECTED";
                        }
                    } catch (IOException | RuntimeException e) {
                        failed(session, id, "DISCONNECTED");
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
                if (current(ws)) { session.lastMessage = clock.instant(); ws.request(1); }
            }
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            synchronized (UpstoxQuoteStream.this) { if (current(ws)) failed(session, id, "DISCONNECTED"); }
            return null;
        }
        @Override public void onError(WebSocket ws, Throwable error) {
            synchronized (UpstoxQuoteStream.this) { if (current(ws)) failed(session, id, "DISCONNECTED"); }
        }
    }

    record Tick(Instant at, Instant providerAt, Instant received, BigDecimal last, BigDecimal change) {}
    record Update(Map<String, Tick> ticks, Map<String, String> marketStatus) {}

    // Only the documented FeedResponse/Feed/LTPC and segment-status fields are read.
    static Update decode(byte[] bytes, Set<String> keys, Instant received) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var ticks = new HashMap<String, Tick>();
        Instant providerAt = null;
        Map<String, String> status = Map.of();
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
                if (found != null && keys.contains(found) && value != null) {
                    var quote = tick(value, received);
                    if (quote != null) ticks.put(found, quote);
                }
            } else if (tag == 24) {
                providerAt = wireTime(input.readInt64());
            } else if (tag == 34) {
                status = marketStatus(input.readByteArray());
                var segments = new HashSet<String>();
                keys.forEach(key -> segments.add(segment(key)));
                status.keySet().retainAll(segments);
            } else input.skipField(tag);
        }
        Instant providerTime = providerAt;
        ticks.replaceAll((key, quote) -> new Tick(quote.at, providerTime, received, quote.last, quote.change));
        return new Update(ticks, status);
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
            Instant at = wireTime(time);
            if (!Double.isFinite(price) || price <= 0 || at == null) return null;
            var last = BigDecimal.valueOf(price);
            return new Tick(at, null, received, last,
                Double.isFinite(close) && close > 0 ? last.subtract(BigDecimal.valueOf(close)) : null);
        }
        return null;
    }

    private static Instant wireTime(long millis) {
        return millis >= 946684800000L && millis < 7258118400000L ? Instant.ofEpochMilli(millis) : null;
    }

    private static String segment(String key) { return key.substring(0, key.indexOf('|')); }

    private static Map<String, String> marketStatus(byte[] bytes) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        var statuses = new HashMap<String, String>();
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
            if (key != null) statuses.put(key, switch (status) {
                case 0 -> "PRE_OPEN_START";
                case 1 -> "PRE_OPEN_END";
                case 2 -> "NORMAL_OPEN";
                case 3 -> "NORMAL_CLOSE";
                case 4 -> "CLOSING_START";
                case 5 -> "CLOSING_END";
                default -> "UNKNOWN";
            });
        }
        return statuses;
    }
}
