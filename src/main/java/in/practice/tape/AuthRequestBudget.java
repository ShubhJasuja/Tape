package in.practice.tape;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile("accounts")
final class AuthRequestBudget {
    private final JdbcTemplate db;
    private final AtomicLong prunedMinute = new AtomicLong(-1);

    AuthRequestBudget(JdbcTemplate db) { this.db = db; }

    boolean allow(String bucket, int limit) {
        return !db.query("""
            INSERT INTO auth_request_budget(bucket, window_id, used)
            VALUES (?, floor(extract(epoch FROM statement_timestamp()) / 60)::bigint, 1)
            ON CONFLICT (bucket, window_id) DO UPDATE SET used = auth_request_budget.used + 1
                WHERE auth_request_budget.used < ?
            RETURNING used
            """, (rs, row) -> rs.getInt(1), digest(bucket), limit).isEmpty();
    }

    void prune(long nowMillis) {
        long minute = nowMillis / 60_000;
        long previous = prunedMinute.get();
        if (minute <= previous || !prunedMinute.compareAndSet(previous, minute)) return;
        db.update("""
            DELETE FROM auth_request_budget
            WHERE window_id < floor(extract(epoch FROM statement_timestamp()) / 60)::bigint - 1
            """);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
