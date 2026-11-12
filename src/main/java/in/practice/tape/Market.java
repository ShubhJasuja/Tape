package in.practice.tape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
class Market {
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final List<Instrument> INSTRUMENTS = List.of(
        new Instrument("NSE:RELIANCE", "Reliance Industries", "Energy", "NSE_EQ|INE002A01018", 128450),
        new Instrument("NSE:TCS", "Tata Consultancy Services", "Technology", "NSE_EQ|INE467B01029", 341280),
        new Instrument("NSE:INFY", "Infosys", "Technology", "NSE_EQ|INE009A01021", 156750),
        new Instrument("NSE:HDFCBANK", "HDFC Bank", "Financials", "NSE_EQ|INE040A01034", 99230),
        new Instrument("NSE:SBIN", "State Bank of India", "Financials", "NSE_EQ|INE062A01020", 85465),
        new Instrument("NSE:ITC", "ITC", "Consumer staples", "NSE_EQ|INE154A01025", 41530),
        new Instrument("NSE:BHARTIARTL", "Bharti Airtel", "Telecom", "NSE_EQ|INE397D01024", 177845),
        new Instrument("BSE:RELIANCE", "Reliance Industries", "Energy", "BSE_EQ|INE002A01018", 128490),
        new Instrument("BSE:TCS", "Tata Consultancy Services", "Technology", "BSE_EQ|INE467B01029", 341350)
    );

    record Instrument(String symbol, String name, String sector, String key, long base) {}
    record Quote(long price, long change, long time) {}
    record Candle(String date, long open, long high, long low, long close, long volume) {}
    record Snapshot(String mode, String status, Map<String, Quote> quotes) {
        Quote tradeable(String symbol, long now) {
            Quote q = quotes.get(symbol);
            if ("demo".equals(mode)) return q;
            ZonedDateTime ist = Instant.ofEpochMilli(now).atZone(INDIA);
            boolean regularHours = ist.getDayOfWeek().getValue() <= 5 &&
                !ist.toLocalTime().isBefore(LocalTime.of(9, 15)) &&
                ist.toLocalTime().isBefore(LocalTime.of(15, 30));
            return "Connected".equals(status) && regularHours && q != null &&
                q.time() <= now + 5000 && q.time() >= now - 60_000 ? q : null;
        }
    }
    record CachedHistory(Instant fetched, List<Candle> candles) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper json;
    private final String token;
    private final Map<String, CachedHistory> history = new ConcurrentHashMap<>();
    private volatile Snapshot live;
    private final long started = System.currentTimeMillis();

    Market(ObjectMapper json, @Value("${UPSTOX_ANALYTICS_TOKEN:}") String token) {
        this.json = json;
        this.token = token.strip();
        live = new Snapshot("live", enabled() ? "Connecting" : "Token required", Map.of());
    }

    boolean enabled() { return !token.isBlank(); }

    static Instrument instrument(String symbol) {
        return INSTRUMENTS.stream().filter(i -> i.symbol().equals(symbol)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown instrument."));
    }

    Snapshot snapshot(String mode) {
        if ("live".equals(mode)) return live;
        if (!"demo".equals(mode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown data mode.");
        long now = System.currentTimeMillis();
        Map<String, Quote> quotes = new LinkedHashMap<>();
        for (var i : INSTRUMENTS) {
            long lastClose = demoClose(i, 89);
            double elapsed = (now - started) / 5000.0;
            long price = lastClose + Math.round(i.base() * 0.007 * Math.sin(elapsed / 8 + Math.floorMod(i.symbol().hashCode(), 11) * 0.65));
            quotes.put(i.symbol(), new Quote(price, price - lastClose, now));
        }
        return new Snapshot("demo", "Synthetic demo", Map.copyOf(quotes));
    }

    @Scheduled(fixedDelay = 5000)
    void refresh() {
        if (!enabled()) return;
        try {
            String keys = INSTRUMENTS.stream().map(Instrument::key).collect(Collectors.joining(","));
            Map<String, Quote> byKey = new HashMap<>();
            for (JsonNode row : get("/v3/market-quote/quotes?instrument_key=" + encode(keys)).path("data")) {
                long price = money(row.path("last_price"));
                long time = row.path("last_trade_time").asLong();
                if (price > 0 && price <= 100_000_000 && time > 0) {
                    byKey.put(row.path("instrument_token").asText(), new Quote(price, money(row.path("net_change")), time));
                }
            }
            Map<String, Quote> quotes = new HashMap<>(live.quotes());
            for (var i : INSTRUMENTS) {
                if (byKey.containsKey(i.key())) quotes.put(i.symbol(), byKey.get(i.key()));
            }
            live = new Snapshot("live", byKey.isEmpty() ? "Feed unavailable" : "Connected", Map.copyOf(quotes));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            live = new Snapshot("live", "Feed unavailable", live.quotes());
        }
    }

    List<Candle> candles(String mode, Instrument i) {
        if ("demo".equals(mode)) {
            LocalDate last = LocalDate.now(INDIA).minusDays(1);
            List<Candle> candles = new ArrayList<>();
            for (int n = 89; n >= 0; n--) {
                long close = demoClose(i, n);
                long open = demoClose(i, n - 1);
                long wick = Math.max(1, Math.round(i.base() * (0.002 + (n % 7) * 0.0007)));
                while (last.getDayOfWeek().getValue() > 5) last = last.minusDays(1);
                candles.add(new Candle(last.toString(), open, Math.max(open, close) + wick,
                    Math.min(open, close) - wick, close, 500_000L + 80_000L * (n % 11)));
                last = last.minusDays(1);
            }
            return candles.reversed();
        }
        snapshot(mode);
        if (!enabled()) return List.of();
        var cached = history.compute(i.symbol(), (symbol, prior) -> {
            Instant now = Instant.now();
            if (prior != null && prior.fetched().plusSeconds(300).isAfter(now)) return prior;
            try {
                LocalDate to = LocalDate.now(INDIA).minusDays(1);
                List<Candle> result = new ArrayList<>();
                for (JsonNode row : get("/v3/historical-candle/" + encode(i.key()) + "/days/1/" +
                    to + "/" + to.minusDays(135)).path("data").path("candles")) {
                    if (row.size() >= 6 && row.get(0).asText().length() >= 10 && money(row.get(4)) > 0) {
                        result.add(new Candle(row.get(0).asText().substring(0, 10), money(row.get(1)),
                            money(row.get(2)), money(row.get(3)), money(row.get(4)), row.get(5).asLong()));
                    }
                }
                result.sort(Comparator.comparing(Candle::date));
                return new CachedHistory(now, List.copyOf(result));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // The chart can still show a previously retrieved series.
            }
            return new CachedHistory(now.minusSeconds(285), prior == null ? List.of() : prior.candles());
        });
        return cached.candles();
    }

    private JsonNode get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("https://api.upstox.com" + path))
            .header("Authorization", "Bearer " + token).header("Accept", "application/json")
            .timeout(Duration.ofSeconds(6)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Market data request failed.");
        return json.readTree(response.body());
    }

    private static long demoClose(Instrument i, int n) {
        double phase = Math.floorMod(i.symbol().hashCode(), 7) * 0.45;
        return Math.round(i.base() * (0.92 + n * 0.001 + Math.sin(n * 0.24 + phase) * 0.018 +
            Math.sin(n * 1.31 + phase) * 0.008));
    }

    private static long money(JsonNode value) { return Math.round(value.asDouble() * 100); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
