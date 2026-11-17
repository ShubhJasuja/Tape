package in.practice.tape;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

final class AuthAttempts extends OncePerRequestFilter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_BUCKETS = 10_000;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final AuthRequestBudget shared;
    private final TradingMetrics metrics;
    private long nextCleanup;

    private record Bucket(long expiresAt, int used) {}

    AuthAttempts(AuthRequestBudget shared, TradingMetrics metrics) {
        this.shared = shared;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String path = request.getServletPath();
        long now = System.currentTimeMillis();
        try {
            if (admit(request, response, path, now)) chain.doFilter(request, response);
        } catch (BudgetUnavailable unavailable) {
            metrics.count("tape.auth.admission", "unavailable");
            response.setStatus(503);
        }
    }

    private boolean admit(HttpServletRequest request, HttpServletResponse response, String path, long now) {
        if (path.startsWith("/api/v1/market-data/")) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AccountDirectory.Principal user &&
                !local("market-user:" + user.userId(), 120, now)) {
                metrics.count("tape.market.admission", "limited");
                return throttled(response);
            }
        }
        if ("GET".equals(request.getMethod()) && "/api/v1/auth/csrf".equals(path)) {
            return rate("csrf", request.getRemoteAddr(), 90, 900, now) || throttled(response);
        }
        boolean login = "/api/v1/auth/login".equals(path);
        boolean register = "/api/v1/auth/register".equals(path);
        boolean identity = java.util.Set.of("/api/v1/auth/verification", "/api/v1/auth/recovery",
            "/api/v1/auth/verify", "/api/v1/auth/reset").contains(path);
        if (!"POST".equals(request.getMethod()) || (!login && !register && !identity)) return true;
        if (!rate(login ? "login" : identity ? "identity" : "register", request.getRemoteAddr(), login ? 20 : 10, login ? 600 : 60, now)) {
            return throttled(response);
        }
        if (request.getContentLengthLong() < 0 || request.getContentLengthLong() > 4096) {
            response.setStatus(413);
            return false;
        }
        if (!login) return true;
        String contentType = request.getContentType();
        if (request.getQueryString() != null || contentType == null ||
            !contentType.split(";", 2)[0].equalsIgnoreCase("application/x-www-form-urlencoded")) {
            response.setStatus(401);
            return false;
        }
        String email = AccountDirectory.normalizedEmail(request.getParameter("email"));
        if (email != null && (!local("login-email:" + email, 8, now) ||
            (shared != null && !allow("login-email:" + email, 8, now)))) {
            return throttled(response);
        }
        if (email == null || !AccountDirectory.validPassword(request.getParameter("password"))) {
            response.setStatus(401);
            return false;
        }
        return true;
    }

    private boolean rate(String action, String address, int perAddress, int global, long now) {
        // The local bucket sheds excess load before opening a database connection.
        if (!local(action + "-ip:" + address, perAddress, now)) return false;
        if (shared == null) return true;
        return allow(action + "-global", global, now) && allow(action + "-ip:" + address, perAddress, now);
    }

    private boolean allow(String key, int limit, long now) {
        if (shared == null) return local(key, limit, now);
        try {
            shared.prune(now);
            return shared.allow(key, limit);
        } catch (DataAccessException failure) {
            throw new BudgetUnavailable();
        }
    }

    private static final class BudgetUnavailable extends RuntimeException {}

    private synchronized boolean local(String key, int limit, long now) {
        if (now >= nextCleanup) {
            buckets.values().removeIf(bucket -> bucket.expiresAt() <= now);
            nextCleanup = now + WINDOW_MILLIS;
        }
        Bucket bucket = buckets.get(key);
        if (bucket == null || bucket.expiresAt() <= now) {
            if (bucket == null && buckets.size() >= MAX_BUCKETS) return false;
            buckets.put(key, new Bucket(now + WINDOW_MILLIS, 1));
            return true;
        }
        if (bucket.used() >= limit) return false;
        buckets.put(key, new Bucket(bucket.expiresAt(), bucket.used() + 1));
        return true;
    }

    private boolean throttled(HttpServletResponse response) {
        metrics.count("tape.request.admission", "limited");
        response.setHeader("Retry-After", "60");
        response.setStatus(429);
        return false;
    }
}
