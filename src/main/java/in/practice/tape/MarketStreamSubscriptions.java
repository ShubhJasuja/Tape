package in.practice.tape;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("accounts")
final class MarketStreamSubscriptions {
    private static final int MAX_PER_USER = 8;
    private static final int MAX_LEASES = 128;
    private static final int MAX_KEYS = 64;
    private final Map<Client, Lease> leases = new HashMap<>();
    private final Clock clock;
    private final MarketDataProvider provider;
    private final TradingMetrics metrics;

    record Client(UUID owner, String session, UUID tab) {}
    private record Lease(Set<String> keys, Instant expiresAt) {}

    @Autowired
    MarketStreamSubscriptions(MarketDataProvider provider, TradingMetrics metrics) {
        this(provider, metrics, Clock.systemUTC());
    }

    MarketStreamSubscriptions(MarketDataProvider provider, TradingMetrics metrics, Clock clock) {
        this.provider = provider;
        this.metrics = metrics;
        this.clock = clock;
    }

    synchronized MarketDataProvider.Streams read(Client client, Set<String> keys) {
        Instant now = clock.instant();
        leases.values().removeIf(lease -> !lease.expiresAt().isAfter(now));
        if (!leases.containsKey(client) &&
            (leases.size() >= MAX_LEASES || leases.keySet().stream().filter(other -> other.owner().equals(client.owner())).count() >= MAX_PER_USER))
            throw limited();

        var union = new HashSet<>(keys);
        leases.forEach((other, lease) -> { if (!other.equals(client)) union.addAll(lease.keys()); });
        if (union.size() > MAX_KEYS) throw limited();

        leases.put(client, new Lease(Set.copyOf(keys), now.plusSeconds(15)));
        var feed = provider.streams(Set.copyOf(union));
        metrics.count("tape.market.stream.polls", "CONNECTED".equals(feed.status()) ? "connected" : "unavailable");
        var result = new HashMap<String, MarketDataProvider.Quote>();
        keys.forEach(key -> {
            var quote = feed.quotes().get(key);
            if (quote != null) result.put(key, quote);
        });
        return new MarketDataProvider.Streams(feed.status(), Map.copyOf(result));
    }

    private MarketDataAccess.Rejected limited() {
        metrics.count("tape.market.stream.polls", "limited");
        return MarketDataAccess.failure(HttpStatus.TOO_MANY_REQUESTS, "MARKET_RATE_LIMITED");
    }
}
