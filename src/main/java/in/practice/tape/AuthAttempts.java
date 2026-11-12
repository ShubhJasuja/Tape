package in.practice.tape;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

final class AuthAttempts extends OncePerRequestFilter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_BUCKETS = 10_000;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private long nextCleanup;

    private record Bucket(long expiresAt, int used) {}

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String path = request.getServletPath();
        long now = System.currentTimeMillis();
        if ("GET".equals(request.getMethod()) && "/api/v1/auth/csrf".equals(path)
            && !allow("csrf-ip:" + request.getRemoteAddr(), 90, now)) {
            throttled(response);
            return;
        }
        boolean login = "/api/v1/auth/login".equals(path);
        boolean register = "/api/v1/auth/register".equals(path);
        if (!"POST".equals(request.getMethod()) || (!login && !register)) {
            chain.doFilter(request, response);
            return;
        }
        if (!allow((login ? "login-ip:" : "register-ip:") + request.getRemoteAddr(), login ? 20 : 10, now)) {
            throttled(response);
            return;
        }
        if (register && (request.getContentLengthLong() < 0 || request.getContentLengthLong() > 4096)) {
            response.setStatus(413);
            return;
        }
        if (login) {
            String contentType = request.getContentType();
            if (request.getQueryString() != null || contentType == null ||
                !contentType.split(";", 2)[0].equalsIgnoreCase("application/x-www-form-urlencoded")) {
                response.setStatus(401);
                return;
            }
            String email = AccountDirectory.normalizedEmail(request.getParameter("email"));
            if (email != null && !allow("login-email:" + email, 8, now)) {
                throttled(response);
                return;
            }
            if (email == null || !AccountDirectory.validPassword(request.getParameter("password"))) {
                response.setStatus(401);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean allow(String key, int limit, long now) {
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

    private static void throttled(HttpServletResponse response) {
        response.setHeader("Retry-After", "60");
        response.setStatus(429);
    }
}
