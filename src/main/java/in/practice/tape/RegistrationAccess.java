package in.practice.tape;

import org.springframework.core.env.Environment;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Deployment-owned invitation list. Never accepts invitation decisions from a request. */
final class RegistrationAccess {
    private enum Mode { CLOSED, ALLOWLIST, PUBLIC }
    private final Mode mode;
    private final Set<String> invited;

    RegistrationAccess(Environment env) {
        mode = Mode.valueOf(env.getProperty("TAPE_REGISTRATION_MODE", "CLOSED").toUpperCase(Locale.ROOT));
        invited = Arrays.stream(env.getProperty("TAPE_REGISTRATION_ALLOWLIST", "").split(","))
            .map(String::strip).filter(value -> !value.isEmpty()).map(value -> {
                String email = AccountDirectory.normalizedEmail(value);
                if (email == null) throw new IllegalArgumentException("Invalid registration allowlist");
                return email;
            }).collect(Collectors.toUnmodifiableSet());
        if (mode == Mode.ALLOWLIST && invited.isEmpty())
            throw new IllegalArgumentException("Allowlist registration requires at least one invitation");
    }

    boolean permits(String normalizedEmail) {
        return mode == Mode.PUBLIC || (mode == Mode.ALLOWLIST && invited.contains(normalizedEmail));
    }
}
