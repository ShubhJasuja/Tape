
package in.practice.tape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Flow;
import java.util.concurrent.CompletableFuture;
import java.nio.ByteBuffer;

import static in.practice.tape.MarketDataAccess.failure;

@Component
@Profile("accounts")
final class UpstoxMarketData implements MarketDataProvider, AutoCloseable {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private final Transport transport;
    private final ObjectMapper json;
    private final Clock clock;
    private final Semaphore inFlight = new Semaphore(2);
    private final Deque<Instant> calls = new ArrayDeque<>();
    private Instant blockedUntil = Instant.MIN;
    private final boolean configured;
    private final UpstoxQuoteStream stream;
    private final Map<String, ExchangeStatus> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    private record ExchangeStatus(String status, Instant received) {}

    record Response(int status, String retryAfter, byte[] body) {}
    interface Transport { Response get(String path) throws IOException, InterruptedException; }

    @org.springframework.beans.factory.annotation.Autowired
    UpstoxMarketData(ObjectMapper json, @Value("${UPSTOX_ANALYTICS_TOKEN:}") String token) {
        this(json, Clock.systemUTC(), !token.isBlank(), http(token.strip()));
    }

    UpstoxMarketData(ObjectMapper json, Clock clock, boolean configured, Transport transport) {
        this.transport = transport;
        this.json = json;
        this.clock = clock;
        this.configured = configured;
        this.stream = new UpstoxQuoteStream(json, clock, this::authorizeStream, (key, at, received, status, last, change) ->
            quote(key, at, received, status, last, change, null, null, null, null, null));
    }

    @jakarta.annotation.PreDestroy
    @Override public void close() { stream.close(); }

    @Override public Stream stream(String key) { return stream.read(key); }

    private static Transport http(String token) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return path -> {
            var request = HttpRequest.newBuilder(URI.create("https://api.upstox.com" + path))
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                .header("Authorization", "Bearer " + token).GET().build();
            var pending = client.sendAsync(request, ignored -> new BoundedBody());
            try {
                var raw = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
                return new Response(raw.statusCode(), raw.headers().firstValue("Retry-After").orElse(""), raw.body());
            } catch (java.util.concurrent.TimeoutException e) {
                throw new java.net.http.HttpTimeoutException("Provider request timed out");
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof IOException failure) throw failure;
                throw new IOException("Provider connection failed");
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
        };
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BODY - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Provider response exceeds limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }

    @Override public String source() { return "UPSTOX"; }

    @Override public Page search(String query, String exchange, Segment segment, LocalDate expiry, int page) {
        String filter = switch (segment) {
            case EQUITY -> "EQ";
            case INDEX -> "INDEX";
            case FUTURES -> "FUT";
            case OPTIONS -> "OPT";
        };
        String match = expiry == null ? "" : "&expiry=" + expiry;
        var root = get("/v2/instruments/search?query=" + encode(query) + "&segments=" + filter +
            "&exchanges=" + encode(exchange.isEmpty() && segment == Segment.EQUITY ? "NSE,BSE" : exchange.isEmpty() ? "NSE" : exchange) +
            "&page_number=" + page + "&records=20" + match);
        var items = instruments(root.path("data")).stream().filter(i -> i.type() == segment)
            .filter(i -> exchange.isEmpty() || exchange.equals(i.exchange()))
            .filter(i -> expiry == null || expiry.equals(i.expiry())).toList();
        return new Page(items, page < root.path("meta_data").path("page").path("total_pages").asInt() ? page + 1 : null);
    }

    @Override public Quote quote(String key) {
        var response = get("/v3/market-quote/quotes?instrument_key=" + encode(key)).path("data");
        Instant received = clock.instant();
        for (var row : response) {
            if (!key.equals(str(row, "instrument_token"))) continue;
            BigDecimal last = positive(row.get("last_price"));
            BigDecimal change = number(row.get("net_change"));
            var ohlc = row.path("ohlc");
            var session = sessions.get(exchange(key));
            String status = session == null || session.received.plusSeconds(30).isBefore(received) ? "UNKNOWN" : session.status;
            return quote(key, epoch(row.get("last_trade_time")), received, status, last, change,
                positive(ohlc.get("open")), positive(ohlc.get("high")), positive(ohlc.get("low")), positive(ohlc.get("close")),
                count(row.get("volume")));
        }
        throw failure(HttpStatus.NOT_FOUND, "MARKET_QUOTE_UNAVAILABLE");
    }

    Quote quote(String key, Instant exchangeAt, Instant received, String marketStatus, BigDecimal last, BigDecimal change,
                BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume) {
        Long age = exchangeAt == null ? null : Duration.between(exchangeAt, received).toMillis();
        String freshness = last == null ? "UNAVAILABLE" : age == null || age < -5_000 ? "UNKNOWN" :
            age > 30_000 ? "STALE" : "RECENT";
        BigDecimal previous = last != null && change != null ? last.subtract(change) : null;
        BigDecimal percent = previous != null && previous.signum() > 0 ?
            change.multiply(BigDecimal.valueOf(100)).divide(previous, 4, RoundingMode.HALF_UP) : null;
        return new Quote(source(), exchange(key), key, exchangeAt, received, freshness, age, marketStatus,
            last, change, percent, open, high, low, close, volume);
    }

    @Override public String marketStatus(String exchange) {
        var cached = sessions.get(exchange);
        if (cached != null && cached.received.plusSeconds(15).isAfter(clock.instant())) return cached.status;
        var row = get("/v2/market/status/" + exchange).path("data");
        String status = str(row, "status");
        if (status == null || !status.matches("[A-Z_]{1,32}")) return "UNKNOWN";
        sessions.put(exchange, new ExchangeStatus(status, clock.instant()));
        return status;
    }

    @Override public History candles(String key, Interval interval, LocalDate from, LocalDate to) {
        var data = get("/v3/historical-candle/" + encode(key) + "/" + interval.path + "/" + to + "/" + from)
            .path("data").path("candles");
        Instant received = clock.instant();
        Map<Instant, Candle> bars = new TreeMap<>();
        for (var row : array(data)) {
            if (!row.isArray() || row.size() < 5) continue;
            Instant at = dateTime(row.get(0).asText());
            if (at == null || at.isAfter(received)) continue;
            LocalDate day = at.atZone(IST).toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            var open = positive(row.get(1));
            var high = positive(row.get(2));
            var low = positive(row.get(3));
            var close = positive(row.get(4));
            if (open == null || high == null || low == null || close == null || high.compareTo(open.max(close)) < 0 ||
                low.compareTo(open.min(close)) > 0) continue;
            Candle bar = new Candle(at, open, high, low, close, count(row.get(5)));
            Candle duplicate = bars.putIfAbsent(at, bar);
            if (duplicate != null && !duplicate.equals(bar)) throw failure(HttpStatus.BAD_GATEWAY, "MARKET_INVALID_RESPONSE");
        }
        return new History(source(), exchange(key), key, received, interval, List.copyOf(bars.values()));
    }

    @Override public List<Instrument> options(String underlying, LocalDate expiry) {
        String extra = expiry == null ? "" : "&expiry_date=" + expiry;
        return instruments(get("/v2/option/contract?instrument_key=" + encode(underlying) + extra).path("data")).stream()
            .filter(item -> item.type() == Segment.OPTIONS && underlying.equals(item.underlyingKey()))
            .filter(item -> !"EXPIRED".equals(item.status()) && (expiry == null || expiry.equals(item.expiry()))).toList();
    }

    @Override public Chain chain(String underlying, LocalDate expiry) {
        var rows = array(get("/v2/option/chain?instrument_key=" + encode(underlying) + "&expiry_date=" + expiry).path("data"));
        var strikes = new TreeMap<BigDecimal, Strike>();
        for (var row : rows) {
            if (!underlying.equals(str(row, "underlying_key")) || !expiry.equals(date(row.get("expiry")))) continue;
            var strike = positive(row.get("strike_price"));
            if (strike == null) continue;
            strikes.putIfAbsent(strike, new Strike(strike, leg(row.path("call_options")), leg(row.path("put_options"))));
        }
        return new Chain(source(), exchange(underlying), underlying, expiry, clock.instant(), List.copyOf(strikes.values()));
    }

    private static Leg leg(JsonNode node) {
        String key = str(node, "instrument_key");
        if (!validKey(key) || !key.startsWith("NSE_FO|")) return null;
        var data = node.path("market_data");
        return new Leg(key, positive(data.get("ltp")), count(data.get("volume")), count(data.get("oi")),
            positive(data.get("bid_price")), positive(data.get("ask_price")));
    }

    List<Instrument> instruments(JsonNode rows) {
        return array(rows).valueStream().map(this::instrument).filter(Objects::nonNull).toList();
    }

    private Instrument instrument(JsonNode row) {
        String key = str(row, "instrument_key"), segment = str(row, "segment"), symbol = str(row, "trading_symbol");
        if (!validKey(key) || segment == null || !key.startsWith(segment + "|") || symbol == null || symbol.length() > 120 ||
            !exchange(key).equals(str(row, "exchange"))) return null;
        String kind = str(row, "instrument_type");
        Segment type = switch (segment) {
            case "NSE_EQ", "BSE_EQ" -> Segment.EQUITY;
            case "NSE_INDEX" -> Segment.INDEX;
            default -> "FUT".equals(kind) ? Segment.FUTURES : Set.of("CE", "PE").contains(Objects.toString(kind, "")) ? Segment.OPTIONS : null;
        };
        if (type == null) return null;
        boolean derivative = type == Segment.OPTIONS || type == Segment.FUTURES;
        LocalDate expiry = derivative ? date(row.get("expiry")) : null;
        String underlying = str(row, "underlying_key");
        String underlyingType = str(row, "underlying_type");
        Long lot = count(row.get("lot_size"));
        BigDecimal strike = type == Segment.OPTIONS ? positive(row.get("strike_price")) : null;
        if (derivative && (expiry == null || !validKey(underlying) || lot == null || lot == 0 ||
            (type == Segment.OPTIONS && strike == null))) return null;
        String status = str(row, "trading_status");
        if (expiry != null && expiry.isBefore(LocalDate.now(clock.withZone(IST)))) status = "EXPIRED";
        if (status == null || !Set.of("ACTIVE", "INACTIVE", "SUSPENDED", "DELISTED", "EXPIRED").contains(status)) status = "UNKNOWN";
        return new Instrument(source(), key, exchange(key), segment, symbol, str(row, "name"), str(row, "isin"), type,
            status, expiry, underlying, underlyingType, strike, type == Segment.OPTIONS ? kind : null, lot, positive(row.get("tick_size")));
    }

    URI authorizeStream() {
        try {
            URI uri = URI.create(get("/v3/feed/market-data-feed/authorize").path("data").path("authorized_redirect_uri").asText());
            if ("wss".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getFragment() == null &&
                uri.getHost() != null && uri.getHost().endsWith(".upstox.com") && (uri.getPort() == -1 || uri.getPort() == 443)) return uri;
        } catch (IllegalArgumentException ignored) {
            // The signed URI never leaves the server or appears in errors.
        }
        throw failure(HttpStatus.BAD_GATEWAY, "MARKET_INVALID_RESPONSE");
    }

    private JsonNode get(String path) {
        if (!configured) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_TOKEN_REQUIRED");
        if (!inFlight.tryAcquire()) throw failure(HttpStatus.TOO_MANY_REQUESTS, "MARKET_RATE_LIMITED");
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                takeSlot();
                try {
                    Response response = transport.get(path);
                    if (response.status == 401 || response.status == 403) {
                        cooldown(60);
                        throw failure(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_AUTH_FAILED");
                    }
                    if (response.status == 429) {
                        cooldown(retrySeconds(response.retryAfter));
                        throw failure(HttpStatus.TOO_MANY_REQUESTS, "MARKET_RATE_LIMITED");
                    }
                    if (response.status >= 500 && attempt == 0) continue;
                    if (response.status == 404) throw failure(HttpStatus.NOT_FOUND, "MARKET_DATA_UNAVAILABLE");
                    if (response.status != 200) throw failure(HttpStatus.BAD_GATEWAY, "MARKET_PROVIDER_ERROR");
                    if (response.body.length > MAX_BODY) throw failure(HttpStatus.BAD_GATEWAY, "MARKET_INVALID_RESPONSE");
                    var root = json.readTree(response.body);
                    if (root == null || !"success".equals(root.path("status").asText()) || !root.hasNonNull("data")) {
                        throw failure(HttpStatus.BAD_GATEWAY, "MARKET_INVALID_RESPONSE");
                    }
                    return root;
                } catch (java.net.http.HttpTimeoutException e) {
                    if (attempt == 1) throw failure(HttpStatus.GATEWAY_TIMEOUT, "MARKET_TIMEOUT");
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw failure(HttpStatus.BAD_GATEWAY, "MARKET_INVALID_RESPONSE");
                } catch (IOException e) {
                    if (attempt == 1) throw failure(HttpStatus.BAD_GATEWAY, "MARKET_DISCONNECTED");
                }
            }
            throw failure(HttpStatus.BAD_GATEWAY, "MARKET_DISCONNECTED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_DISCONNECTED");
        } finally {
            inFlight.release();
        }
    }

    private synchronized void takeSlot() {
        Instant now = clock.instant();
        while (!calls.isEmpty() && !calls.peekFirst().plusSeconds(1800).isAfter(now)) calls.removeFirst();
        if (blockedUntil.isAfter(now) || calls.size() >= 1550 ||
            calls.stream().filter(time -> time.plusSeconds(60).isAfter(now)).count() >= 400 ||
            calls.stream().filter(time -> time.plusSeconds(1).isAfter(now)).count() >= 15) {
            throw failure(HttpStatus.TOO_MANY_REQUESTS, "MARKET_RATE_LIMITED");
        }
        calls.addLast(now);
    }

    private synchronized void cooldown(long seconds) { blockedUntil = clock.instant().plusSeconds(seconds); }

    private static long retrySeconds(String header) {
        try { return Math.clamp(Long.parseLong(header), 2, 1800); }
        catch (NumberFormatException e) { return 60; }
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static JsonNode array(JsonNode node) {
        if (!node.isArray()) throw failure(HttpStatus.BAD_GATEWAY, "MARKET_INVALID_RESPONSE");
        return node;
    }
    static boolean validKey(String key) {
        try { MarketDataAccess.key(key); return true; }
        catch (MarketDataAccess.Rejected ignored) { return false; }
    }
    static String str(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
    static BigDecimal number(JsonNode node) {
        if (node == null || !node.isNumber()) return null;
        var result = node.decimalValue();
        return result.precision() <= 32 && Math.abs((long) result.scale()) <= 20 ? result : null;
    }
    static BigDecimal positive(JsonNode node) {
        var value = number(node);
        return value != null && value.signum() > 0 ? value : null;
    }
    static Long count(JsonNode node) {
        var value = number(node);
        if (value == null || value.signum() < 0) return null;
        try { return value.longValueExact(); }
        catch (ArithmeticException e) { return null; }
    }
    static Instant epoch(JsonNode node) {
        if (node == null || !(node.isIntegralNumber() || node.isTextual())) return null;
        try {
            long ms = Long.parseLong(node.asText());
            return ms >= 946684800000L && ms < 7258118400000L ? Instant.ofEpochMilli(ms) : null;
        } catch (NumberFormatException e) { return null; }
    }
    private static Instant dateTime(String value) {
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeException e) { return null; }
    }
    private static LocalDate date(JsonNode value) {
        if (value == null) return null;
        if (value.isNumber()) {
            Instant time = epoch(value);
            return time == null ? null : time.atZone(IST).toLocalDate();
        }
        try { return LocalDate.parse(value.asText()); }
        catch (DateTimeException e) { return null; }
    }
}
