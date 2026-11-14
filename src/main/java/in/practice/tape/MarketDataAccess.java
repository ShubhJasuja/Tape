
package in.practice.tape;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import static in.practice.tape.MarketDataProvider.Capability;

@Component
@Profile("accounts")
final class MarketDataAccess {
    private final String owner;
    private final Set<Capability> approved;
    private final boolean local;
    private final boolean configured;

    MarketDataAccess(Environment env) {
        local = env.acceptsProfiles(Profiles.of("accounts-local"));
        owner = env.getProperty("TAPE_MARKET_OWNER_ID", "").strip();
        String reference = env.getProperty("TAPE_MARKET_PERMISSION_REF", "").strip();
        String delay = env.getProperty("TAPE_MARKET_DISPLAY_DELAY", "").strip().toUpperCase(Locale.ROOT);
        approved = reference.isEmpty() || owner.isEmpty() || !local ? Set.of() :
            Arrays.stream(env.getProperty("TAPE_MARKET_PERMISSIONS", "").split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).map(Capability::valueOf)
                .filter(capability -> "NONE".equals(delay) || !Set.of(Capability.QUOTES, Capability.HISTORY,
                    Capability.STREAM, Capability.OPTION_CHAIN).contains(capability))
                .collect(Collectors.toUnmodifiableSet());
        configured = !env.getProperty("UPSTOX_ANALYTICS_TOKEN", "").isBlank();
    }

    Set<Capability> capabilities(UUID user) {
        return owner.equals(user.toString()) ? approved : Set.of();
    }

    UUID catalogOwner() {
        try {
            var user = UUID.fromString(owner);
            require(user, Capability.CATALOG);
            require(user, Capability.CATALOG_STORAGE);
            return user;
        } catch (IllegalArgumentException error) {
            throw failure(HttpStatus.FORBIDDEN, "MARKET_PERMISSION_REQUIRED");
        }
    }

    String status(UUID user) {
        if (!local || capabilities(user).isEmpty()) return "DISCONNECTED";
        return configured ? "CONFIGURED" : "TOKEN_REQUIRED";
    }

    void require(UUID user, Capability capability) {
        requirePermission(user, capability);
        if (!configured) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_TOKEN_REQUIRED");
    }

    void requirePermission(UUID user, Capability capability) {
        if (!capabilities(user).contains(capability)) throw failure(HttpStatus.FORBIDDEN, "MARKET_PERMISSION_REQUIRED");
    }

    static String key(String key) {
        if (key == null || !key.matches("(NSE_EQ|BSE_EQ|NSE_INDEX|NSE_FO)\\|[A-Za-z0-9 ._&-]{1,65}")) {
            throw invalid();
        }
        return key;
    }

    static String underlying(String key) {
        key(key);
        if (!key.startsWith("NSE_EQ|") && !key.startsWith("NSE_INDEX|")) throw invalid();
        return key;
    }

    static String exchangeFilter(String ex) {
        if (!Set.of("", "NSE", "BSE").contains(ex)) throw invalid();
        return ex;
    }

    static String query(String q) {
        if (q.length() > 80 || q.chars().anyMatch(Character::isISOControl)) throw invalid();
        return q.strip();
    }

    static LocalDate activeExpiry(LocalDate date, LocalDate today) {
        if (date == null || date.isBefore(today) || date.isAfter(today.plusYears(6))) throw invalid();
        return date;
    }

    record MarketFailure(HttpStatus status, String code) {}

    static final class Rejected extends RuntimeException {
        final MarketFailure failure;
        Rejected(HttpStatus status, String code) {
            super(code);
            failure = new MarketFailure(status, code);
        }
    }

    static Rejected invalid() { return failure(HttpStatus.BAD_REQUEST, "INVALID_MARKET_REQUEST"); }
    static Rejected failure(HttpStatus status, String code) { return new Rejected(status, code); }
}
