package in.practice.tape;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
class TerminalApi {
    private final Market market;
    private final Trading trading;
    private final Research research;
    private final JdbcTemplate db;

    TerminalApi(Market market, Trading trading, Research research, JdbcTemplate db) {
        this.market = market;
        this.trading = trading;
        this.research = research;
        this.db = db;
    }

    record Terminal(List<Market.Instrument> instruments, Market.Snapshot feed, boolean liveConfigured,
                    Trading.Account account, List<String> watchlist, List<String> tradeable) {}

    @GetMapping("/terminal")
    Terminal terminal(@RequestParam(defaultValue = "demo") String mode) {
        var feed = market.snapshot(mode);
        long now = System.currentTimeMillis();
        return new Terminal(Market.INSTRUMENTS, feed, market.enabled(), trading.account(feed),
            db.queryForList("SELECT symbol FROM watchlist ORDER BY symbol", String.class),
            Market.INSTRUMENTS.stream().map(Market.Instrument::symbol).filter(s -> feed.tradeable(s, now) != null).toList());
    }

    @GetMapping("/candles")
    List<Market.Candle> candles(@RequestParam String mode, @RequestParam String symbol) {
        return market.candles(mode, Market.instrument(symbol));
    }

    @PostMapping(value = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    Trading.Order submit(@RequestParam String mode, @RequestBody Trading.Ticket ticket) {
        return trading.submit(mode, ticket);
    }

    @DeleteMapping("/orders/{id}")
    void cancel(@RequestParam String mode, @PathVariable UUID id) { trading.cancel(mode, id); }

    @PutMapping("/watchlist")
    void watch(@RequestParam String symbol, @RequestParam boolean pinned) {
        Market.instrument(symbol);
        if (pinned) db.update("MERGE INTO watchlist KEY(symbol) VALUES (?)", symbol);
        else db.update("DELETE FROM watchlist WHERE symbol=?", symbol);
    }

    @GetMapping("/research")
    Research.Report research(@RequestParam String mode, @RequestParam(required = false) String symbol) {
        var feed = market.snapshot(mode);
        if (symbol == null) {
            // Never present retained prices as current portfolio research.
            var account = trading.account(feed);
            long now = System.currentTimeMillis();
            boolean stale = "live".equals(mode) && account.holdings().stream()
                .anyMatch(h -> feed.tradeable(h.symbol(), now) == null);
            if (stale) return new Research.Report("Portfolio", "Fresh quotes required", null, List.of());
            return research.portfolio(mode, account);
        }
        var instrument = Market.instrument(symbol);
        return research.stock(mode, instrument, market.candles(mode, instrument));
    }
}
