package in.practice.tape;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Profile("accounts")
class AccountDirectory {
    private static final long OPENING_CASH = 100_000_000L;
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    private static final String ACCOUNTS = """
        SELECT a.id, a.mode, a.currency, a.trading_enabled, w.balance_paise, w.reserved_paise
        FROM trading_accounts a JOIN wallets w ON w.account_id = a.id
        WHERE a.user_id = ?
        """;

    record Registration(String email, String password) {}
    record Account(UUID id, String mode, String currency, long balancePaise, long reservedPaise,
                   boolean tradingEnabled) {}

    static final class Principal extends User {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final UUID userId;
        private final long authVersion;

        Principal(UUID userId, String email, String passwordHash, long authVersion, boolean enabled) {
            super(email, passwordHash, enabled, true, true, true, List.of());
            this.userId = userId;
            this.authVersion = authVersion;
        }

        UUID userId() { return userId; }
        long authVersion() { return authVersion; }
    }

    private final JdbcTemplate db;
    private final boolean demoTrading;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final boolean local;

    AccountDirectory(JdbcTemplate db, TransactionTemplate transactions, PasswordEncoder passwords,
                     @Value("${tape.synthetic-trading.enabled:false}") boolean demoTrading, Environment environment) {
        this.db = db;
        this.demoTrading = demoTrading;
        this.transactions = transactions;
        this.passwords = passwords;
        this.local = environment.acceptsProfiles(Profiles.of("accounts-local"));
    }

    void register(Registration registration) {
        String email = normalizedEmail(registration.email());
        if (email == null || !validPassword(registration.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String hash = passwords.encode(registration.password());
        registerHashed(email, hash);
    }

    void registerHashed(String email, String hash) {
        transactions.executeWithoutResult(ignored -> {
            lockIdentity(email);
            UUID userId = UUID.randomUUID();
            db.update("INSERT INTO users (id,identity_issuer,identity_subject) VALUES (?,'tape-local',?)",
                userId, userId.toString());
            int inserted = db.update("""
                INSERT INTO local_credentials (user_id,email,password_hash) VALUES (?,?,?)
                ON CONFLICT (email) DO NOTHING
                """, userId, email, hash);
            if (inserted == 0) throw new ResponseStatusException(HttpStatus.CONFLICT);
            for (String mode : List.of("DEMO", "PERMITTED_MARKET_DATA")) {
                UUID accountId = UUID.randomUUID();
                db.update("INSERT INTO trading_accounts (id,user_id,mode,trading_enabled) VALUES (?,?,?,?)", accountId, userId, mode, "DEMO".equals(mode));
                db.update("INSERT INTO wallets (account_id,balance_paise) VALUES (?,?)", accountId, OPENING_CASH);
                db.update("""
                    INSERT INTO cash_ledger_entries (id,account_id,kind,amount_paise) VALUES (?,?,'OPENING_BALANCE',?)
                    """, UUID.randomUUID(), accountId, OPENING_CASH);
            }
        });
    }

    void lockIdentity(String email) {
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 1230193998))", Object.class, email);
    }

    Principal load(String rawEmail) {
        String email = normalizedEmail(rawEmail);
        if (email != null) {
            var records = db.query("SELECT user_id,email,password_hash,auth_version,verified_at FROM local_credentials WHERE email=?",
                (rs, row) -> new Principal(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4),
                    local || rs.getTimestamp(5) != null), email);
            if (!records.isEmpty()) return records.getFirst();
        }
        throw new UsernameNotFoundException("Invalid credentials");
    }

    boolean current(Principal principal) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM local_credentials WHERE user_id=? AND auth_version=? AND (? OR verified_at IS NOT NULL))
            """, Boolean.class, principal.userId(), principal.authVersion(), local));
    }

    void revoke(UUID owner) {
        transactions.executeWithoutResult(ignored -> {
            db.update("UPDATE local_credentials SET auth_version=auth_version+1 WHERE user_id=?", owner);
            db.update("DELETE FROM tape_http_session WHERE principal_name=(SELECT email FROM local_credentials WHERE user_id=?)", owner);
        });
    }

    List<Account> accounts(UUID owner) { return read(ACCOUNTS + " ORDER BY a.mode", owner); }

    Account account(UUID owner, UUID id) {
        var accounts = read(ACCOUNTS + " AND a.id = ?", owner, id);
        if (accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return accounts.getFirst();
    }

    private List<Account> read(String sql, Object... parameters) {
        return db.query(sql, (rs, row) -> new Account(rs.getObject(1, UUID.class),
            "DEMO".equals(rs.getString(2)) ? "DEMO" : "LIVE_DATA", rs.getString(3), rs.getLong(5),
            rs.getLong(6), demoTrading && rs.getBoolean(4)), parameters);
    }

    static String normalizedEmail(String input) {
        if (input == null || input.length() > 254) return null;
        String email = input.strip().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(email).matches() && email.indexOf('@') <= 64 ? email : null;
    }

    static boolean validPassword(String password) {
        return password != null && password.length() <= 72 &&
            password.codePointCount(0, password.length()) >= 12 &&
            password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
