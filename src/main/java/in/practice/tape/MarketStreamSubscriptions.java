package in.practice.tape;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@Component
@Profile("accounts")
final class MarketStreamSubscriptions implements AutoCloseable {
    private static final int MAX_PER_USER = 8;
    private static final int MAX_LEASES = 128;
    private static final int MAX_KEYS = 64;
    private final Map<Client, Lease> leases = new HashMap<>();
    private final Clock clock;
    private final MarketDataProvider provider;
    private final TradingMetrics metrics;
    private final DataSource source;
    private Connection feedLease;
    private final java.util.concurrent.ScheduledExecutorService cleanup =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> Thread.ofVirtual().unstarted(task));

    record Client(UUID owner, String session, UUID tab) {}
    private record Lease(Set<String> keys, Instant expiresAt, BooleanSupplier valid) {}

    @Autowired
    MarketStreamSubscriptions(MarketDataProvider provider, TradingMetrics metrics, DataSource source) {
        this(provider, metrics, Clock.systemUTC(), source);
        cleanup.scheduleWithFixedDelay(this::expireSafely, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    MarketStreamSubscriptions(MarketDataProvider provider, TradingMetrics metrics, Clock clock) {
        this(provider, metrics, clock, null);
    }

    MarketStreamSubscriptions(MarketDataProvider provider, TradingMetrics metrics, Clock clock, DataSource source) {
        this.provider = provider;
        this.metrics = metrics;
        this.clock = clock;
        this.source = source;
    }

    synchronized MarketDataProvider.Streams read(Client client, Set<String> keys) {
        return read(client, keys, () -> true);
    }

    synchronized MarketDataProvider.Streams read(Client client, Set<String> keys, BooleanSupplier valid) {
        Instant now = clock.instant();
        expire();
        if (!valid.getAsBoolean()) throw MarketDataAccess.failure(HttpStatus.UNAUTHORIZED, "MARKET_SESSION_REQUIRED");
        if (!leases.containsKey(client) &&
            (leases.size() >= MAX_LEASES || leases.keySet().stream().filter(other -> other.owner().equals(client.owner())).count() >= MAX_PER_USER))
            throw limited();

        var union = new HashSet<>(keys);
        leases.forEach((other, lease) -> { if (!other.equals(client)) union.addAll(lease.keys()); });
        if (union.size() > MAX_KEYS) throw limited();

        if (!acquireFeed()) return new MarketDataProvider.Streams("MARKET_FEED_BUSY", Map.of());
        leases.put(client, new Lease(Set.copyOf(keys), now.plusSeconds(15), valid));
        var feed = provider.streams(Set.copyOf(union));
        metrics.count("tape.market.stream.polls", "CONNECTED".equals(feed.status()) ? "connected" : "unavailable");
        var result = new HashMap<String, MarketDataProvider.Quote>();
        keys.forEach(key -> {
            var quote = feed.quotes().get(key);
            if (quote != null) result.put(key, quote);
        });
        return new MarketDataProvider.Streams(feed.status(), Map.copyOf(result));
    }

    private boolean acquireFeed() {
        if (source == null) return true;
        try {
            if (feedLease != null && feedLease.isValid(1)) return true;
            releaseFeed();
            var connection = source.getConnection();
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(1413566533, 901)")) {
                try (var result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) { feedLease = connection; return true; }
                }
            } catch (SQLException error) {
                connection.close();
                throw error;
            }
            connection.rollback();
            connection.close();
            return false;
        } catch (SQLException error) {
            releaseFeed();
            throw MarketDataAccess.failure(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_STORAGE_UNAVAILABLE");
        }
    }

    synchronized void expire() {
        if (leases.values().removeIf(lease -> !lease.expiresAt().isAfter(clock.instant()) || !lease.valid().getAsBoolean())) {
            var keys = new HashSet<String>();
            leases.values().forEach(lease -> keys.addAll(lease.keys()));
            provider.streams(Set.copyOf(keys));
        }
        if (leases.isEmpty()) releaseFeed();
    }

    synchronized void removeSession(String session) {
        leases.entrySet().removeIf(entry -> entry.getKey().session().equals(session));
        var keys = new HashSet<String>();
        leases.values().forEach(lease -> keys.addAll(lease.keys()));
        if (feedLease != null || source == null) provider.streams(Set.copyOf(keys));
        if (leases.isEmpty()) releaseFeed();
    }

    private void expireSafely() {
        try { expire(); }
        catch (RuntimeException failure) { metrics.count("tape.market.stream.cleanup", "error"); }
    }

    private void releaseFeed() {
        if (feedLease == null) return;
        provider.streams(Set.of());
        try { feedLease.rollback(); feedLease.close(); }
        catch (SQLException error) { metrics.count("tape.market.stream.cleanup", "error"); }
        finally { feedLease = null; }
    }

    @Bean
    ServletListenerRegistrationBean<HttpSessionListener> streamSessionListener() {
        return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
            @Override public void sessionDestroyed(HttpSessionEvent event) { removeSession(event.getSession().getId()); }
        });
    }

    @jakarta.annotation.PreDestroy
    @Override public synchronized void close() {
        cleanup.shutdownNow();
        leases.clear();
        releaseFeed();
    }

    private MarketDataAccess.Rejected limited() {
        metrics.count("tape.market.stream.polls", "limited");
        return MarketDataAccess.failure(HttpStatus.TOO_MANY_REQUESTS, "MARKET_RATE_LIMITED");
    }
}
