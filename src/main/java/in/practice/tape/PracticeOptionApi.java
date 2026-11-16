package in.practice.tape;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

@RestController
@Profile("accounts")
@RequestMapping("/api/v1/accounts/{account}/{desk:options|futures}")
final class PracticeOptionApi {
    private final PracticeOptions options;
    private final PracticeFutures futures;

    PracticeOptionApi(PracticeOptions options, PracticeFutures futures) {
        this.options = options;
        this.futures = futures;
    }

    @GetMapping
    PracticeOptions.View view(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                              @PathVariable String desk, @RequestParam(defaultValue = "0") int offset) {
        return desk(desk).view(user.userId(), account, offset);
    }

    @PostMapping(value = "/orders", consumes = "application/json")
    PracticeOptions.Order place(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                                @PathVariable String desk, @RequestBody PracticeOptions.Ticket ticket) {
        return desk(desk).submit(user.userId(), account, ticket);
    }

    @DeleteMapping("/orders/{id}")
    PracticeOptions.Order cancel(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                                 @PathVariable String desk, @PathVariable UUID id) {
        return desk(desk).cancel(user.userId(), account, id);
    }

    private PracticeOptions desk(String desk) { return "futures".equals(desk) ? futures : options; }

    @ExceptionHandler(DerivativeBook.Rejected.class)
    ResponseEntity<ProblemDetail> rejected(DerivativeBook.Rejected failure) {
        String code = failure.getMessage();
        int status = switch (code) {
            case "ACCOUNT_NOT_FOUND", "CONTRACT_NOT_FOUND", "ORDER_NOT_FOUND" -> 404;
            case "OPTIONS_DISABLED", "FUTURES_DISABLED" -> 403;
            case "INVALID_REQUEST", "INVALID_TICK", "INVALID_LOT_SIZE" -> 400;
            default -> 409;
        };
        return problem(status, code);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalid() { return problem(400, "INVALID_REQUEST"); }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> database(DataAccessException failure) {
        org.slf4j.LoggerFactory.getLogger(PracticeOptionApi.class).atWarn()
            .addKeyValue("error_type", failure.getClass().getSimpleName()).log("Practice derivatives database operation failed");
        return problem(503, "DATABASE_UNAVAILABLE");
    }

    private static ResponseEntity<ProblemDetail> problem(int status, String code) {
        var body = ProblemDetail.forStatus(status);
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
