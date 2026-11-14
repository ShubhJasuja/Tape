package in.practice.tape;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

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
            return "demo".equals(mode) ? quotes.get(symbol) : null;
        }
    }
    private final long started = System.currentTimeMillis();

    static Instrument instrument(String symbol) {
        return INSTRUMENTS.stream().filter(i -> i.symbol().equals(symbol)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown instrument."));
    }

    Snapshot snapshot(String mode) {
        if ("live".equals(mode)) return new Snapshot("live", "Disconnected", Map.of());
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
        return List.of();
    }

    static long demoClose(Instrument i, int n) {
        double phase = Math.floorMod(i.symbol().hashCode(), 7) * 0.45;
        return Math.round(i.base() * (0.92 + n * 0.001 + Math.sin(n * 0.24 + phase) * 0.018 +
            Math.sin(n * 1.31 + phase) * 0.008));
    }

}
