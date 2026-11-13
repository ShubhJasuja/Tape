package in.practice.tape;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("accounts")
@RequestMapping("/api/v1/accounts/{account}")
class PaperOrderApi {
    record Pin(String symbol, boolean pinned) {}
    record Listing(String symbol, String name) {}
    record MarketView(List<Listing> instruments, java.util.Map<String, Market.Quote> quotes, List<String> watchlist) {}

    private final PaperOrders orders;
    private final JdbcTemplate db;
    private final SyntheticQuotes quotes;
    private final Market market;
    private final TradingMetrics metrics;

    PaperOrderApi(PaperOrders orders, JdbcTemplate db, SyntheticQuotes quotes, Market market, TradingMetrics metrics) {
        this.orders = orders;
        this.db = db;
        this.quotes = quotes;
        this.market = market;
        this.metrics = metrics;
    }

    @PostMapping(value = "/orders", consumes = "application/json")
    PaperOrders.Order submit(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                             @RequestBody PaperOrders.Ticket ticket) {
        var sample = metrics.start();
        String outcome = "error";
        try {
            var order = orders.submit(user.userId(), account, ticket);
            outcome = order.status().toLowerCase(java.util.Locale.ROOT);
            return order;
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode().is4xxClientError()) outcome = "invalid";
            throw failure;
        } finally {
            metrics.count("tape.orders.submissions", outcome);
            metrics.stop(sample, "tape.orders.submission.duration", outcome);
        }
    }

    @GetMapping("/orders")
    PaperOrders.Page list(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                          @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "25") int limit) {
        return orders.list(user.userId(), account, offset, limit);
    }

    @GetMapping("/orders/{id}")
    PaperOrders.Order detail(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                             @PathVariable UUID id) {
        return orders.detail(user.userId(), account, id);
    }

    @GetMapping("/order-requests/{key}")
    PaperOrders.Order retryStatus(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                                  @PathVariable UUID key) {
        return orders.retryStatus(user.userId(), account, key);
    }

    @DeleteMapping("/orders/{id}")
    PaperOrders.Order cancel(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                             @PathVariable UUID id) {
        return orders.cancel(user.userId(), account, id);
    }

    @GetMapping("/wallet")
    PaperOrders.Wallet wallet(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account) {
        return orders.wallet(user.userId(), account);
    }

    @GetMapping("/portfolio")
    PaperOrders.Portfolio portfolio(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account) {
        return orders.portfolio(user.userId(), account);
    }

    @GetMapping("/market")
    MarketView market(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account) {
        return orders.owned(user.userId(), account, () -> new MarketView(
            Market.INSTRUMENTS.stream().map(i -> new Listing(i.symbol(), i.name())).toList(),
            quotes.snapshot(Instant.now()), db.queryForList("""
                SELECT i.exchange || ':' || i.symbol FROM watchlists w
                JOIN watchlist_items wi ON wi.watchlist_id=w.id JOIN instruments i ON i.id=wi.instrument_id
                WHERE w.user_id=? AND w.name='Default' ORDER BY 1
                """, String.class, user.userId())));
    }

    @GetMapping("/candles")
    List<Market.Candle> candles(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                                @RequestParam String symbol) {
        return orders.owned(user.userId(), account, () -> market.candles("demo", Market.instrument(symbol)));
    }

    @PutMapping(value = "/watchlist", consumes = "application/json")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void watch(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
               @RequestBody Pin pin) {
        orders.pin(user.userId(), account, pin.symbol(), pin.pinned());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> rejected(ResponseStatusException failure) {
        String reason = failure.getReason();
        String code = reason != null && reason.matches("[A-Z_]+") ? reason : "INVALID_REQUEST";
        return problem(failure.getStatusCode().value(), code);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalidRequest() { return problem(400, "INVALID_REQUEST"); }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> databaseUnavailable(DataAccessException failure) {
        LoggerFactory.getLogger(PaperOrderApi.class).atWarn()
            .addKeyValue("error_type", failure.getClass().getSimpleName()).log("Synthetic trading database operation failed");
        return problem(503, "DATABASE_UNAVAILABLE");
    }

    @ExceptionHandler(ArithmeticException.class)
    ResponseEntity<ProblemDetail> numericLimit() { return problem(409, "ACCOUNT_LIMIT"); }

    private static ResponseEntity<ProblemDetail> problem(int status, String code) {
        var body = ProblemDetail.forStatus(status);
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
