package in.practice.tape;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseEntity;
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
    private final IdentityActions identities;
    private final boolean local;

    record CurrentUser(String email) {}
    record Token(String headerName, String token) {}

    AccountApi(AccountDirectory directory, IdentityActions identities, Environment environment) {
        this.directory = directory;
        this.identities = identities;
        local = environment.acceptsProfiles(Profiles.of("accounts-local"));
    }

    @GetMapping("/auth/csrf")
    Token csrf(CsrfToken token) { return new Token(token.getHeaderName(), token.getToken()); }

    @PostMapping(value = "/auth/register", consumes = "application/json")
    ResponseEntity<Void> register(@RequestBody AccountDirectory.Registration registration) {
        if (local) {
            directory.register(registration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        }
        identities.register(registration);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/auth/verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void verification(@RequestBody IdentityActions.Email email) { identities.request(email, "VERIFY"); }

    @PostMapping("/auth/recovery")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void recovery(@RequestBody IdentityActions.Email email) { identities.request(email, "RESET"); }

    @PostMapping("/auth/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verify(@RequestBody IdentityActions.Confirmation confirmation) { identities.confirm(confirmation, "VERIFY"); }

    @PostMapping("/auth/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@RequestBody IdentityActions.Confirmation confirmation) { identities.confirm(confirmation, "RESET"); }

    @PostMapping("/auth/sessions/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal AccountDirectory.Principal user, HttpServletRequest request) {
        directory.revoke(user.userId());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

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
