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
@RequestMapping("/api/v1/accounts/{account}/options")
final class PracticeOptionApi {
    private final PracticeOptions options;

    PracticeOptionApi(PracticeOptions options) { this.options = options; }

    @GetMapping
    PracticeOptions.View view(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                              @RequestParam(defaultValue = "0") int offset) {
        return options.view(user.userId(), account, offset);
    }

    @PostMapping(value = "/orders", consumes = "application/json")
    PracticeOptions.Order place(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                                @RequestBody PracticeOptions.Ticket ticket) {
        return options.submit(user.userId(), account, ticket);
    }

    @DeleteMapping("/orders/{id}")
    PracticeOptions.Order cancel(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID account,
                                 @PathVariable UUID id) {
        return options.cancel(user.userId(), account, id);
    }

    @ExceptionHandler(DerivativeBook.Rejected.class)
    ResponseEntity<ProblemDetail> rejected(DerivativeBook.Rejected failure) {
        String code = failure.getMessage();
        int status = switch (code) {
            case "ACCOUNT_NOT_FOUND", "CONTRACT_NOT_FOUND", "ORDER_NOT_FOUND" -> 404;
            case "OPTIONS_DISABLED" -> 403;
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
            .addKeyValue("error_type", failure.getClass().getSimpleName()).log("Practice options database operation failed");
        return problem(503, "DATABASE_UNAVAILABLE");
    }

    private static ResponseEntity<ProblemDetail> problem(int status, String code) {
        var body = ProblemDetail.forStatus(status);
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
