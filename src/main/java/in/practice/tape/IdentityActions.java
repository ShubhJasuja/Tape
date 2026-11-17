package in.practice.tape;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
@Profile("accounts")
final class IdentityActions {
    record Email(String email) {}
    record Confirmation(String token, String password) {}
    private record Identity(UUID user, boolean verified) {}
    private record Mail(UUID action, String kind, String email, byte[] encrypted) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AccountDirectory directory;
    private final PasswordEncoder passwords;
    private final AuthRequestBudget budget;
    private final JavaMailSender sender;
    private final TradingMetrics metrics;
    private final boolean enabled;
    private final String origin;
    private final String from;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    IdentityActions(JdbcTemplate db, TransactionTemplate tx, AccountDirectory directory, PasswordEncoder passwords,
                    AuthRequestBudget budget, ObjectProvider<JavaMailSender> sender, Environment env, TradingMetrics metrics) {
        this.db = db;
        this.tx = tx;
        this.directory = directory;
        this.passwords = passwords;
        this.budget = budget;
        this.metrics = metrics;
        enabled = env.getProperty("tape.identity.mail.enabled", Boolean.class, false);
        this.sender = enabled ? sender.getIfAvailable() : null;
        origin = env.getProperty("TAPE_PUBLIC_ORIGIN", "").replaceAll("/+$", "");
        from = env.getProperty("TAPE_MAIL_FROM", "");
        key = enabled ? decodeKey(env.getProperty("TAPE_IDENTITY_MAIL_KEY", "")) : null;
        if (enabled) {
            URI uri = URI.create(origin);
            boolean local = env.acceptsProfiles(Profiles.of("accounts-local"));
            if (this.sender == null || env.getProperty("TAPE_SMTP_HOST", "").isBlank() || uri.getHost() == null || (!"https".equals(uri.getScheme()) &&
                !(local && "http".equals(uri.getScheme()) && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost()))) ||
                uri.getRawUserInfo() != null || !uri.getPath().isEmpty() || uri.getQuery() != null || uri.getFragment() != null ||
                AccountDirectory.normalizedEmail(from) == null)
                throw new IllegalArgumentException("Invalid identity email configuration");
        }
    }

    boolean ready() { return enabled; }

    void register(AccountDirectory.Registration registration) {
        requireReady();
        String email = AccountDirectory.normalizedEmail(registration.email());
        if (email == null || !AccountDirectory.validPassword(registration.password())) throw invalid();
        // Hashing occurs for both existing and new identities.
        String hash = passwords.encode(registration.password());
        if (!budget.allow("VERIFY-mail:" + email, 1)) return;
        tx.executeWithoutResult(ignored -> {
            directory.lockIdentity(email);
            var identity = find(email);
            if (identity == null) {
                directory.registerHashed(email, hash);
                identity = find(email);
            }
            if (!identity.verified()) issue(identity.user(), "VERIFY");
        });
    }

    void request(Email request, String kind) {
        requireReady();
        String email = AccountDirectory.normalizedEmail(request.email());
        if (email == null || !budget.allow(kind + "-mail:" + email, 1)) return;
        tx.executeWithoutResult(ignored -> {
            directory.lockIdentity(email);
            var identity = find(email);
            if (identity != null && (("RESET".equals(kind) && identity.verified()) ||
                ("VERIFY".equals(kind) && !identity.verified()))) issue(identity.user(), kind);
        });
    }

    private Identity find(String email) {
        return db.query("SELECT user_id,verified_at IS NOT NULL FROM local_credentials WHERE email=? FOR UPDATE",
            (rs, row) -> new Identity(rs.getObject(1, UUID.class), rs.getBoolean(2)), email).stream().findFirst().orElse(null);
    }

    private void issue(UUID owner, String kind) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID action = UUID.randomUUID();
        db.update("DELETE FROM identity_actions WHERE user_id=? AND kind=?", owner, kind);
        db.update("""
            INSERT INTO identity_actions(id,user_id,kind,token_digest,expires_at)
            VALUES (?,?,?,?,clock_timestamp() + (? * interval '1 minute'))
            """, action, owner, kind, digest(token), "RESET".equals(kind) ? 30 : 1440);
        db.update("INSERT INTO identity_mail(action_id,encrypted_token) VALUES (?,?)",
            action, encrypt(action, token));
        metrics.count("tape.identity.mail", "queued");
    }

    void confirm(Confirmation confirmation, String kind) {
        requireReady();
        if (confirmation.token() == null || !confirmation.token().matches("[A-Za-z0-9_-]{43}") ||
            !AccountDirectory.validPassword(confirmation.password())) throw invalid();
        String hash = passwords.encode(confirmation.password());
        tx.executeWithoutResult(ignored -> {
            byte[] digest = digest(confirmation.token());
            var owners = db.query("SELECT user_id FROM identity_actions WHERE token_digest=? AND kind=?",
                (rs, row) -> rs.getObject(1, UUID.class), digest, kind);
            if (owners.isEmpty()) throw invalid();
            UUID owner = owners.getFirst();
            db.queryForObject("SELECT user_id FROM local_credentials WHERE user_id=? FOR UPDATE", UUID.class, owner);
            int consumed = db.update("""
                DELETE FROM identity_actions WHERE user_id=? AND token_digest=? AND kind=? AND expires_at>clock_timestamp()
                """, owner, digest, kind);
            if (consumed != 1) throw invalid();
            db.update("""
                UPDATE local_credentials SET password_hash=?,verified_at=coalesce(verified_at,clock_timestamp()),
                    auth_version=auth_version+1 WHERE user_id=?
                """, hash, owner);
            db.update("DELETE FROM identity_actions WHERE user_id=?", owner);
            db.update("DELETE FROM tape_http_session WHERE principal_name=(SELECT email FROM local_credentials WHERE user_id=?)", owner);
            metrics.count("tape.identity.confirmations", "completed");
        });
    }

    void deliverOne() {
        if (!enabled) return;
        tx.executeWithoutResult(ignored -> {
            db.update("DELETE FROM identity_actions WHERE expires_at<=clock_timestamp()");
            var due = db.query("""
                SELECT m.action_id,a.kind,c.email,m.encrypted_token FROM identity_mail m
                JOIN identity_actions a ON a.id=m.action_id JOIN local_credentials c ON c.user_id=a.user_id
                WHERE m.next_attempt_at<=clock_timestamp() AND m.attempts<8
                ORDER BY m.next_attempt_at,m.action_id LIMIT 1 FOR UPDATE OF m SKIP LOCKED
                """, (rs, row) -> new Mail(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBytes(4)));
            if (due.isEmpty()) return;
            var mail = due.getFirst();
            try {
                var message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(mail.email());
                boolean reset = "RESET".equals(mail.kind());
                message.setSubject(reset ? "Reset your Tape password" : "Verify your Tape email");
                message.setText((reset ? "Choose a new Tape password" : "Verify your email and choose your Tape password") +
                    " using this single-use link:\n\n" + origin + "/#" + (reset ? "reset=" : "verify=") +
                    decrypt(mail.action(), mail.encrypted()) + "\n\nIf you did not request this, ignore this email.");
                sender.send(message);
                db.update("DELETE FROM identity_mail WHERE action_id=?", mail.action());
                metrics.count("tape.identity.mail", "sent");
            } catch (org.springframework.mail.MailException failure) {
                db.update("""
                    UPDATE identity_mail SET attempts=attempts+1,next_attempt_at=clock_timestamp()+ interval '5 minutes' WHERE action_id=?
                    """, mail.action());
                metrics.count("tape.identity.mail", "retry");
            } catch (IllegalStateException unreadable) {
                db.update("UPDATE identity_mail SET attempts=8 WHERE action_id=?", mail.action());
                metrics.count("tape.identity.mail", "unreadable");
            }
        });
    }

    private byte[] encrypt(UUID action, String token) {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, action, iv, token.getBytes(StandardCharsets.UTF_8));
        return java.nio.ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
    }

    private String decrypt(UUID action, byte[] encrypted) {
        return new String(crypt(Cipher.DECRYPT_MODE, action, java.util.Arrays.copyOf(encrypted, 12),
            java.util.Arrays.copyOfRange(encrypted, 12, encrypted.length)), StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, UUID action, byte[] iv, byte[] input) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(action.toString().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Identity mail encryption failed"); }
    }

    private static byte[] decodeKey(String value) {
        try {
            var key = Base64.getDecoder().decode(value);
            if (key.length == 32) return key;
        } catch (IllegalArgumentException invalid) { /* Fail closed below. */ }
        throw new IllegalArgumentException("Identity mail requires a 32-byte encryption key");
    }

    private static byte[] digest(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    private void requireReady() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        budget.prune(System.currentTimeMillis());
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
