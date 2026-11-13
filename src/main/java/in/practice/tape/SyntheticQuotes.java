package in.practice.tape;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Profile("accounts")
class SyntheticQuotes {
    static final String SOURCE = "TAPE_SYNTHETIC_V1";
    static final long MAX_PRICE = 100_000_000;
    private static final long TICK_MS = 2_000;

    Market.Quote latest(String symbol, Instant now) {
        var instrument = Market.instrument(symbol);
        long tick = Math.floorDiv(now.toEpochMilli(), TICK_MS);
        long close = Market.demoClose(instrument, 89);
        double angle = Math.floorMod(tick, 121) * (2 * Math.PI / 121)
            + Math.floorMod(symbol.hashCode(), 11) * 0.65;
        long change = Math.round(instrument.base() * 0.007 * Math.sin(angle));
        return new Market.Quote(close + change, change, tick * TICK_MS);
    }

    Map<String, Market.Quote> snapshot(Instant now) {
        Map<String, Market.Quote> quotes = new LinkedHashMap<>();
        for (var instrument : Market.INSTRUMENTS) {
            quotes.put(instrument.symbol(), latest(instrument.symbol(), now));
        }
        return quotes;
    }

    static boolean eligible(Market.Quote quote, Instant now) {
        return quote != null && quote.price() > 0 && quote.price() <= MAX_PRICE
            && quote.time() <= now.toEpochMilli() && quote.time() >= now.toEpochMilli() - 6_000;
    }
}
