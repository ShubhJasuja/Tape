package in.practice.tape;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
class Research {
    record Observation(String id, String label, String value, String detail) {}
    record Report(String subject, String source, String focus, List<Observation> observations) {}

    private final ObjectMapper json;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    Research(ObjectMapper json, @Value("${OLLAMA_MODEL:}") String model) {
        this.json = json;
        this.model = model.strip();
    }

    Report stock(String mode, Market.Instrument instrument, List<Market.Candle> candles) {
        if (candles.size() < 21) return report(instrument.symbol(), mode, List.of());
        var window = candles.subList(candles.size() - 21, candles.size());
        double[] returns = new double[20];
        for (int n = 0; n < returns.length; n++) {
            returns[n] = (double) window.get(n + 1).close() / window.get(n).close() - 1;
        }
        double mean = Arrays.stream(returns).average().orElseThrow();
        double variance = Arrays.stream(returns).map(r -> Math.pow(r - mean, 2)).sum() / 19;
        long end = window.getLast().close();
        long peak = 0;
        double drawdown = 0;
        for (var candle : window) {
            peak = Math.max(peak, candle.close());
            drawdown = Math.min(drawdown, (double) candle.close() / peak - 1);
        }
        return report(instrument.symbol(), mode, List.of(
            new Observation("return", "20-session return", percent((double) end / window.getFirst().close() - 1),
                "Price change across the last 20 close-to-close intervals, ending " + window.getLast().date() + "."),
            new Observation("volatility", "Daily volatility", percent(Math.sqrt(variance)),
                "Sample standard deviation of the last 20 daily returns."),
            new Observation("drawdown", "Maximum drawdown", percent(drawdown),
                "Largest peak-to-trough closing-price decline within this window.")
        ));
    }

    Report portfolio(String mode, Trading.Account account) {
        if (account.equity() == null) return report("Portfolio", mode, List.of());
        long equity = account.equity();
        var largest = account.holdings().stream().max(Comparator.comparingLong(Trading.Holding::value));
        Map<String, Long> sectorValues = new TreeMap<>();
        for (var holding : account.holdings()) {
            sectorValues.merge(Market.instrument(holding.symbol()).sector(), holding.value(), Long::sum);
        }
        var sector = sectorValues.entrySet().stream().max(Map.Entry.comparingByValue());
        List<Observation> rows = new ArrayList<>();
        largest.ifPresent(h -> rows.add(new Observation("position", "Largest position", percent((double) h.value() / equity),
            h.symbol() + " as a share of portfolio equity, including cash.")));
        sector.ifPresent(s -> rows.add(new Observation("sector", "Largest sector", percent((double) s.getValue() / equity),
            s.getKey() + " as a share of portfolio equity, including cash.")));
        rows.add(new Observation("cash", "Cash allocation", percent((double) account.cash() / equity),
            "Cash as a share of portfolio equity, including cash reserved by open orders."));
        rows.add(new Observation("performance", "Return since start", percent((double) equity / Trading.STARTING_CASH - 1),
            "Change in marked equity relative to your initial virtual balance, including simulated fees."));
        return report("Portfolio", mode, rows);
    }

    private Report report(String subject, String mode, List<Observation> rows) {
        String source = ("demo".equals(mode) ? "Synthetic demo" : "Upstox data") + " · Calculated metrics";
        if (rows.isEmpty()) return new Report(subject, source, null, rows);
        if (!model.isBlank()) {
            String focus = choose(rows);
            if (focus != null) return new Report(subject, source + " · AI-selected focus", focus, rows);
            source += " · Local model unavailable";
        }
        return new Report(subject, source, rows.getFirst().id(), rows);
    }

    private String choose(List<Observation> rows) {
        var ids = rows.stream().map(Observation::id).toList();
        try {
            String prompt = "Select the most educational descriptive metric for a reader. " +
                "Choose exactly one of these IDs based on the observed values. No advice or forecasts. Metrics: " +
                json.writeValueAsString(rows);
            var schema = Map.of("type", "object", "properties", Map.of("focus", Map.of("type", "string", "enum", ids)),
                "required", List.of("focus"), "additionalProperties", false);
            String body = json.writeValueAsString(Map.of("model", model, "stream", false, "prompt", prompt,
                "format", schema, "options", Map.of("temperature", 0, "num_predict", 60)));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/generate"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var result = json.readTree(json.readTree(response.body()).path("response").asText());
                String focus = result.path("focus").asText();
                if (result.isObject() && result.size() == 1 && ids.contains(focus)) return focus;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Calculated observations remain available.
        }
        return null;
    }

    private static String percent(double value) { return String.format(Locale.ROOT, "%.2f%%", value * 100); }
}
