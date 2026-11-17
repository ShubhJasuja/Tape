
package in.practice.tape;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
    private final MarketStreamSubscriptions subscriptions;

    static final int STREAM_LIMIT = 16;
    private static final String DEFAULT_LIST = "Default";
    private static final UUID SINGLE_LEGACY_TAB = new UUID(0, 1);
    private static final UUID BATCH_LEGACY_TAB = new UUID(0, 2);
    record Status(String source, String status, Set<Capability> capabilities, List<Interval> intervals, String displayDelay,
                  Set<String> exchanges) {}
    record Watch(String instrumentKey) {}
    record Watchlist(String name) {}

    MarketDataApi(MarketDataProvider provider, MarketDataAccess access, JdbcTemplate db, ProviderCatalog catalog,
                  MarketStreamSubscriptions subscriptions) {
        this.provider = provider;
        this.access = access;
        this.db = db;
        this.catalog = catalog;
        this.subscriptions = subscriptions;
    }

    @GetMapping("/status")
    ResponseEntity<Status> status(@AuthenticationPrincipal AccountDirectory.Principal user) {
        Set<Capability> capabilities = access.capabilities(user.userId());
        return response(new Status(provider.source(), access.status(user.userId()), capabilities,
            capabilities.contains(Capability.HISTORY) ? Arrays.asList(Interval.values()) : List.of(),
            access.displayDelay(user.userId()), access.exchanges(user.userId())));
    }

    @GetMapping("/instruments")
    ResponseEntity<Page> search(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String q,
                                @RequestParam(defaultValue = "") String exchange, @RequestParam(defaultValue = "EQUITY") Segment segment,
                                @RequestParam(required = false) LocalDate expiry, @RequestParam(defaultValue = "1") int page) {
        access.require(user.userId(), Capability.CATALOG);
        if (segment == Segment.OPTIONS || segment == Segment.FUTURES) access.requirePermission(user.userId(), Capability.DERIVATIVES);
        if (page < 1 || page > 50 || query(q).isEmpty()) throw invalid();
        if (((segment == Segment.FUTURES || segment == Segment.OPTIONS) && "BSE".equals(exchange)) ||
            (expiry != null && segment != Segment.OPTIONS && segment != Segment.FUTURES)) throw invalid();
        if (expiry != null) activeExpiry(expiry, today());
        return response(provider.search(query(q), access.searchExchange(user.userId(), exchangeFilter(exchange), segment), segment, expiry, page));
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
                                 @RequestParam(defaultValue = "Nifty") String q, @RequestParam(defaultValue = "NSE") String exchange,
                                 @RequestParam(defaultValue = "1") int page) {
        return search(user, q, exchange, Segment.INDEX, null, page);
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
        access.requireExchange(user.userId(), "NSE");
        if (expiry != null) activeExpiry(expiry, today());
        return response(provider.options(underlying(key), expiry));
    }

    @GetMapping("/option-chain")
    ResponseEntity<Chain> chain(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key,
                                @RequestParam LocalDate expiry) {
        access.require(user.userId(), Capability.OPTION_CHAIN);
        access.require(user.userId(), Capability.DERIVATIVES);
        access.requireExchange(user.userId(), "NSE");
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
        access.requireExchange(user.userId(), exchange);
        return response(java.util.Map.of("source", provider.source(), "exchange", exchange, "status", provider.marketStatus(exchange)));
    }

    @GetMapping("/candles")
    ResponseEntity<History> candles(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key,
                                    @RequestParam(defaultValue = "DAILY") Interval interval,
                                    @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        access.require(user.userId(), Capability.HISTORY);
        checkDerivative(user, key);
        LocalDate latest = access.historyThrough(Instant.now());
        LocalDate last = to == null ? latest : to;
        LocalDate first = from == null ? last.minusDays(interval.days - 1L) : from;
        LocalDate earliest = interval == Interval.DAILY ? LocalDate.of(2000, 1, 1) : LocalDate.of(2022, 1, 1);
        if (first.isBefore(earliest) || first.isAfter(last) || last.isAfter(latest) ||
            java.time.temporal.ChronoUnit.DAYS.between(first, last) >= interval.days) throw invalid();
        return response(provider.candles(key(key), interval, first, last));
    }

    @GetMapping("/intraday")
    ResponseEntity<History> intraday(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key,
                                    @RequestParam(defaultValue = "FIVE_MINUTES") Interval interval) {
        access.require(user.userId(), Capability.INTRADAY);
        checkDerivative(user, key);
        return response(provider.intraday(key(key), interval));
    }

    @GetMapping("/market-session")
    ResponseEntity<Session> session(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String exchange) {
        access.require(user.userId(), Capability.CALENDAR);
        if (!Set.of("NSE", "BSE", "NFO").contains(exchange)) throw invalid();
        if ("NFO".equals(exchange)) access.requirePermission(user.userId(), Capability.DERIVATIVES);
        access.requireExchange(user.userId(), "NFO".equals(exchange) ? "NSE" : exchange);
        return response(provider.session(exchange, today()));
    }

    @GetMapping("/stream")
    ResponseEntity<Stream> stream(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key,
                                  HttpServletRequest request) {
        access.require(user.userId(), Capability.STREAM);
        checkDerivative(user, key);
        var batch = subscriptions.read(client(user, request, SINGLE_LEGACY_TAB), Set.of(key), validSession(request));
        return response(new Stream(batch.status(), batch.quotes().get(key)));
    }

    @GetMapping("/streams")
    ResponseEntity<Streams> streams(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam List<String> keys,
                                    @RequestParam(required = false) UUID tab, HttpServletRequest request) {
        access.require(user.userId(), Capability.STREAM);
        if (keys.isEmpty() || keys.size() > STREAM_LIMIT) throw invalid();
        var unique = new LinkedHashSet<String>();
        for (var key : keys) {
            checkDerivative(user, key);
            unique.add(key(key));
        }
        return response(subscriptions.read(client(user, request, tab == null ? BATCH_LEGACY_TAB : tab), unique, validSession(request)));
    }

    private static java.util.function.BooleanSupplier validSession(HttpServletRequest request) {
        var session = request.getSession(false);
        return () -> {
            try {
                return session != null && session.getAttribute("SPRING_SECURITY_CONTEXT") instanceof
                    org.springframework.security.core.context.SecurityContext context && context.getAuthentication() != null &&
                    context.getAuthentication().isAuthenticated();
            } catch (IllegalStateException invalidated) { return false; }
        };
    }

    private static MarketStreamSubscriptions.Client client(AccountDirectory.Principal user, HttpServletRequest request, UUID tab) {
        var session = request.getSession(false);
        if (session == null) throw failure(HttpStatus.UNAUTHORIZED, "MARKET_SESSION_REQUIRED");
        return new MarketStreamSubscriptions.Client(user.userId(), session.getId(), tab);
    }

    @GetMapping("/watchlists")
    ResponseEntity<List<Watchlist>> watchlists(@AuthenticationPrincipal AccountDirectory.Principal user) {
        access.requirePermission(user.userId(), Capability.WATCHLIST);
        var lists = db.query("SELECT name FROM market_watchlists WHERE user_id=? AND source=? AND name<>? ORDER BY name",
            (rs, n) -> new Watchlist(rs.getString(1)), user.userId(), provider.source(), DEFAULT_LIST);
        var result = new java.util.ArrayList<Watchlist>();
        result.add(new Watchlist(DEFAULT_LIST));
        result.addAll(lists);
        return response(result);
    }

    @PostMapping("/watchlists")
    ResponseEntity<Void> createWatchlist(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestBody Watchlist list) {
        access.requirePermission(user.userId(), Capability.WATCHLIST);
        String name = listName(list.name());
        if (DEFAULT_LIST.equals(name)) throw invalid();
        createList(user, name);
        return noContent();
    }

    @DeleteMapping("/watchlists")
    ResponseEntity<Void> deleteWatchlist(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String name) {
        access.requirePermission(user.userId(), Capability.WATCHLIST);
        if (DEFAULT_LIST.equals(listName(name))) throw invalid();
        db.update("DELETE FROM market_watchlists WHERE user_id=? AND source=? AND name=?", user.userId(), provider.source(), name);
        return noContent();
    }

    @GetMapping("/watchlist")
    ResponseEntity<List<Watch>> watchlist(@AuthenticationPrincipal AccountDirectory.Principal user,
                                         @RequestParam(defaultValue = DEFAULT_LIST) String name) {
        access.requirePermission(user.userId(), Capability.WATCHLIST);
        return response(db.query("""
            SELECT instrument_key FROM market_watchlist WHERE user_id=? AND source=? AND watchlist_name=? ORDER BY instrument_key
            """, (rs, n) -> new Watch(rs.getString(1)), user.userId(), provider.source(), listName(name)).stream()
            .filter(row -> access.exchanges(user.userId()).contains(provider.exchange(row.instrumentKey())))
            .filter(row -> !row.instrumentKey().startsWith("NSE_FO|") || access.capabilities(user.userId()).contains(Capability.DERIVATIVES))
            .toList());
    }

    @PutMapping("/watchlist")
    ResponseEntity<Void> pin(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestBody Watch watch,
                             @RequestParam(defaultValue = DEFAULT_LIST) String name) {
        access.requirePermission(user.userId(), Capability.WATCHLIST);
        checkDerivative(user, watch.instrumentKey());
        listName(name);
        if (DEFAULT_LIST.equals(name)) createList(user, name);
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM market_watchlists WHERE user_id=? AND source=? AND name=?)",
            Boolean.class, user.userId(), provider.source(), name))) throw failure(HttpStatus.NOT_FOUND, "MARKET_WATCHLIST_UNAVAILABLE");
        db.update("""
            INSERT INTO market_watchlist(user_id, source, watchlist_name, instrument_key) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
            """, user.userId(), provider.source(), name, key(watch.instrumentKey()));
        return noContent();
    }

    @DeleteMapping("/watchlist")
    ResponseEntity<Void> unpin(@AuthenticationPrincipal AccountDirectory.Principal user, @RequestParam String key,
                               @RequestParam(defaultValue = DEFAULT_LIST) String name) {
        access.requirePermission(user.userId(), Capability.WATCHLIST);
        db.update("DELETE FROM market_watchlist WHERE user_id=? AND source=? AND watchlist_name=? AND instrument_key=?",
            user.userId(), provider.source(), listName(name), key(key));
        return noContent();
    }

    private void createList(AccountDirectory.Principal user, String name) {
        db.update("INSERT INTO market_watchlists (user_id, source, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            user.userId(), provider.source(), name);
    }

    private static String listName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,39}") || !name.equals(name.strip())) throw invalid();
        return name;
    }

    private void checkDerivative(AccountDirectory.Principal user, String key) {
        String valid = key(key);
        access.requireExchange(user.userId(), provider.exchange(valid));
        if (valid.startsWith("NSE_FO|")) access.requirePermission(user.userId(), Capability.DERIVATIVES);
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
    private static ResponseEntity<Void> noContent() { return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
    private static <T> ResponseEntity<T> response(T payload) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payload); }
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code) {
        var problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(problem);
    }
}
