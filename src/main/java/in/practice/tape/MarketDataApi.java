
package in.practice.tape;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static in.practice.tape.MarketDataProvider.*;
import static in.practice.tape.MarketDataAccess.*;

@RestController
@Profile("accounts")
@RequestMapping("/api/v1/market-data")
final class MarketDataApi {
    private final MarketDataProvider provider;
    private final MarketDataAccess access;
    private final JdbcTemplate db;
    private final ProviderCatalog catalog;

    record Status(String source, String status, Set<Capability> capabilities, List<Interval> intervals) {}
    record Watch(String instrumentKey) {}

    MarketDataApi(MarketDataProvider provider, MarketDataAccess access, JdbcTemplate db, ProviderCatalog catalog) {
        this.provider = provider;
        this.access = access;
        this.db = db;
        this.catalog = catalog;
    }

    @GetMapping("/status")
    ResponseEntity<Status> status(@AuthenticationPrincipal AccountDirectory.Principal user) {
        Set<Capability> capabilities = access.capabilities(user.userId());
        return response(new Status(provider.source(), access.status(user.userId()), capabilities,
            capabilities.contains(Capability.HISTORY) ? Arrays.asList(Interval.values()) : List.of()));
    }

    @GetMapping("/instruments")
    ResponseEntity<Page> search(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String q,
                                @RequestParam(defaultValue = "") String exchange, @RequestParam(defaultValue = "EQUITY") Segment segment,
                                @RequestParam(required = false) LocalDate expiry, @RequestParam(defaultValue = "1") int page) {
        access.require(user.userId(), Capability.CATALOG);
        if (segment == Segment.OPTIONS || segment == Segment.FUTURES) access.require(user.userId(), Capability.DERIVATIVES);
        if (page < 1 || page > 50 || query(q).isEmpty()) throw invalid();
        if (expiry != null) activeExpiry(expiry, today());
        return response(provider.search(query(q), exchangeFilter(exchange), segment, expiry, page));
    }

    @GetMapping("/instrument")
    ResponseEntity<Instrument> instrument(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key) {
        access.requirePermission(user.userId(), Capability.CATALOG);
        access.requirePermission(user.userId(), Capability.CATALOG_STORAGE);
        checkDerivative(user, key);
        return response(catalog.instrument(user.userId(), provider.source(), key(key)));
    }

    @GetMapping("/indices")
    ResponseEntity<Page> indices(@AuthenticationPrincipal AccountDirectory.Principal user,
                                 @RequestParam(defaultValue = "Nifty") String q, @RequestParam(defaultValue = "1") int page) {
        return search(user, q, "NSE", Segment.INDEX, null, page);
    }

    @GetMapping("/futures")
    ResponseEntity<Page> futures(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String q,
                                 @RequestParam(required = false) LocalDate expiry, @RequestParam(defaultValue = "1") int page) {
        return search(user, q, "NSE", Segment.FUTURES, expiry, page);
    }

    @GetMapping("/options")
    ResponseEntity<List<Instrument>> options(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key,
                                               @RequestParam(required = false) LocalDate expiry) {
        access.require(user.userId(), Capability.CATALOG);
        access.require(user.userId(), Capability.DERIVATIVES);
        if (expiry != null) activeExpiry(expiry, today());
        return response(provider.options(underlying(key), expiry));
    }

    @GetMapping("/option-chain")
    ResponseEntity<Chain> chain(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key,
                                @RequestParam LocalDate expiry) {
        access.require(user.userId(), Capability.OPTION_CHAIN);
        access.require(user.userId(), Capability.DERIVATIVES);
        return response(provider.chain(underlying(key), activeExpiry(expiry, today())));
    }

    @GetMapping("/quote")
    ResponseEntity<Quote> quote(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key) {
        access.require(user.userId(), Capability.QUOTES);
        checkDerivative(user, key);
        return response(provider.quote(key(key)));
    }

    @GetMapping("/exchange-status")
    ResponseEntity<java.util.Map<String, String>> exchangeStatus(@AuthenticationPrincipal AccountDirectory.Principal user,
                                                                   @RequestParam String exchange) {
        access.require(user.userId(), Capability.QUOTES);
        if (exchangeFilter(exchange).isEmpty()) throw invalid();
        return response(java.util.Map.of("source", provider.source(), "exchange", exchange, "status", provider.marketStatus(exchange)));
    }

    @GetMapping("/candles")
    ResponseEntity<History> candles(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key,
                                    @RequestParam(defaultValue = "DAILY") Interval interval,
                                    @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        access.require(user.userId(), Capability.HISTORY);
        checkDerivative(user, key);
        LocalDate last = to == null ? today().minusDays(1) : to;
        LocalDate first = from == null ? last.minusDays(interval.days - 1L) : from;
        LocalDate earliest = interval == Interval.DAILY ? LocalDate.of(2000, 1, 1) : LocalDate.of(2022, 1, 1);
        if (first.isBefore(earliest) || first.isAfter(last) || !last.isBefore(today()) ||
            java.time.temporal.ChronoUnit.DAYS.between(first, last) >= interval.days) throw invalid();
        return response(provider.candles(key(key), interval, first, last));
    }

    @GetMapping("/stream")
    ResponseEntity<Stream> stream(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key) {
        access.require(user.userId(), Capability.STREAM);
        checkDerivative(user, key);
        return response(provider.stream(key(key)));
    }

    @GetMapping("/watchlist")
    ResponseEntity<List<Watch>> watchlist(@AuthenticationPrincipal AccountDirectory.Principal user) {
        access.requirePermission(user.userId(), Capability.WATCHLIST);
        return response(db.query("SELECT instrument_key FROM market_watchlist WHERE user_id=? AND source=? ORDER BY instrument_key",
            (rs, n) -> new Watch(rs.getString(1)), user.userId(), provider.source()));
    }

    @PutMapping("/watchlist")
    ResponseEntity<Void> pin(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestBody Watch watch) {
        access.requirePermission(user.userId(), Capability.WATCHLIST);
        checkDerivative(user, watch.instrumentKey());
        db.update("""
            INSERT INTO market_watchlist(user_id, source, instrument_key) VALUES (?, ?, ?) ON CONFLICT DO NOTHING
            """, user.userId(), provider.source(), key(watch.instrumentKey()));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/watchlist")
    ResponseEntity<Void> unpin(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key) {
        access.requirePermission(user.userId(), Capability.WATCHLIST);
        db.update("DELETE FROM market_watchlist WHERE user_id=? AND source=? AND instrument_key=?",
            user.userId(), provider.source(), key(key));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private void checkDerivative(AccountDirectory.Principal user, String key) {
        if (key(key).startsWith("NSE_FO|")) access.require(user.userId(), Capability.DERIVATIVES);
    }

    @ExceptionHandler(MarketDataAccess.Rejected.class)
    ResponseEntity<ProblemDetail> rejected(MarketDataAccess.Rejected error) {
        return problem(error.failure.status(), error.failure.code());
    }

    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalidBinding() { return rejected(invalid()); }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable() { return problem(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_STORAGE_UNAVAILABLE"); }

    private static LocalDate today() { return LocalDate.now(UpstoxMarketData.IST); }
    private static <T> ResponseEntity<T> response(T payload) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payload); }
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code) {
        var problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(problem);
    }
}
