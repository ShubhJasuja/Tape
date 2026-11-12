package in.practice.tape;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Profile("accounts")
class AccountApi {
    private final AccountDirectory directory;

    record CurrentUser(String email) {}
    record Token(String headerName, String token) {}

    AccountApi(AccountDirectory directory) { this.directory = directory; }

    @GetMapping("/auth/csrf")
    Token csrf(CsrfToken token) { return new Token(token.getHeaderName(), token.getToken()); }

    @PostMapping(value = "/auth/register", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    void register(@RequestBody AccountDirectory.Registration registration) { directory.register(registration); }

    @GetMapping("/auth/me")
    CurrentUser me(@AuthenticationPrincipal AccountDirectory.Principal user) {
        return new CurrentUser(user.getUsername());
    }

    @GetMapping("/accounts")
    List<AccountDirectory.Account> accounts(@AuthenticationPrincipal AccountDirectory.Principal user) {
        return directory.accounts(user.userId());
    }

    @GetMapping("/accounts/{id}")
    AccountDirectory.Account account(@AuthenticationPrincipal AccountDirectory.Principal user, @PathVariable UUID id) {
        return directory.account(user.userId(), id);
    }
}
