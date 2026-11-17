package in.practice.tape;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "tape.postgres.tests", matches = "true")
class IdentityActionsTest {
    private static ConfigurableApplicationContext app;
    private JdbcTemplate db;
    private IdentityActions actions;
    private JavaMailSender mail;
    private AccountDirectory directory;
    private PasswordEncoder passwords;
    private MockEnvironment environment;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry meters;
    private String email;
    private final String original = UUID.randomUUID().toString();

    @BeforeAll static void start() {
        AccountSecurityTest.start();
        app = AccountSecurityTest.startApplication(true, false);
    }

    @AfterAll static void stop() {
        try { if (app != null) app.close(); }
        finally { AccountSecurityTest.stop(); }
    }

    @BeforeEach void fixture() {
        db = new JdbcTemplate(AccountSecurityTest.runtimeDataSource());
        var tx = new TransactionTemplate(new JdbcTransactionManager(db.getDataSource()));
        passwords = app.getBean(PasswordEncoder.class);
        environment = new MockEnvironment().withProperty("tape.identity.mail.enabled", "true")
            .withProperty("TAPE_REGISTRATION_MODE", "PUBLIC")
            .withProperty("TAPE_PUBLIC_ORIGIN", "https://tape.example.test").withProperty("TAPE_MAIL_FROM", "accounts@example.test")
            .withProperty("TAPE_SMTP_HOST", "fixture.example.test")
            .withProperty("TAPE_IDENTITY_MAIL_KEY", Base64.getEncoder().encodeToString(new byte[32]));
        environment.setActiveProfiles("accounts");
        directory = new AccountDirectory(db, tx, passwords, false, environment);
        mail = mock(JavaMailSender.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("fixtureMail", mail);
        meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        actions = new IdentityActions(db, tx, directory, passwords, new AuthRequestBudget(db),
            beans.getBeanProvider(JavaMailSender.class), environment, new TradingMetrics(meters));
        email = UUID.randomUUID() + "@example.test";
    }

    @AfterEach void closeMeters() { meters.close(); }

    private IdentityActions independentActions() {
        var independentDb = new JdbcTemplate(AccountSecurityTest.runtimeDataSource());
        var tx = new TransactionTemplate(new JdbcTransactionManager(independentDb.getDataSource()));
        var beans = new StaticListableBeanFactory();
        beans.addBean("mail", mail);
        return new IdentityActions(independentDb, tx, directory, passwords, new AuthRequestBudget(independentDb),
            beans.getBeanProvider(JavaMailSender.class), environment, new TradingMetrics(meters));
    }

    private String deliveredToken() {
        actions.deliverOne();
        var captured = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(captured.capture());
        String text = captured.getAllValues().stream()
            .filter(message -> email.equals(message.getTo()[0])).reduce((a, b) -> b).orElseThrow().getText();
        var match = java.util.regex.Pattern.compile("#(?:verify|reset)=([A-Za-z0-9_-]{43})").matcher(text);
        assertTrue(match.find());
        return match.group(1);
    }

    @Test void verificationRequiresEmailProofAndDoesNotTrustTheOriginalRegistrationPassword() {
        actions.register(new AccountDirectory.Registration(email, original));
        assertFalse(directory.load(email).isEnabled());
        String token = deliveredToken();
        assertEquals(0, db.queryForObject("SELECT count(*) FROM identity_mail", Integer.class));
        String chosen = UUID.randomUUID().toString();
        actions.confirm(new IdentityActions.Confirmation(token, chosen), "VERIFY");
        assertTrue(directory.load(email).isEnabled());
        assertTrue(passwords.matches(chosen, directory.load(email).getPassword()));
        assertFalse(passwords.matches(original, directory.load(email).getPassword()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> actions.confirm(new IdentityActions.Confirmation(token, chosen), "VERIFY"));
        var user = directory.load(email).userId();
        assertEquals(2, db.queryForObject("SELECT count(*) FROM trading_accounts WHERE user_id=?", Integer.class, user));
        assertEquals(200_000_000L, db.queryForObject("""
            SELECT sum(balance_paise) FROM wallets WHERE account_id IN (SELECT id FROM trading_accounts WHERE user_id=?)
            """, Long.class, user));
    }

    @Test void resetIsSingleUseAcrossIndependentServicesAndRevokesEverySession() throws Exception {
        actions.register(new AccountDirectory.Registration(email, original));
        String verify = deliveredToken();
        actions.confirm(new IdentityActions.Confirmation(verify, original), "VERIFY");
        var prior = directory.load(email);
        db.update("""
            INSERT INTO tape_http_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name)
            VALUES (?,?,0,0,1200,9999999999999,?)
            """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), email);
        actions.request(new IdentityActions.Email(email), "RESET");
        String token = deliveredToken();
        var independent = new AccountDirectory(new JdbcTemplate(AccountSecurityTest.runtimeDataSource()),
            new TransactionTemplate(new JdbcTransactionManager(AccountSecurityTest.runtimeDataSource())), passwords, false, new MockEnvironment());
        var barrier = new CyclicBarrier(2);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var other = independentActions();
            var work = (java.util.function.Function<IdentityActions, java.util.concurrent.Callable<Boolean>>) service -> () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.confirm(new IdentityActions.Confirmation(token, UUID.randomUUID().toString()), "RESET");
                    return true;
                } catch (org.springframework.web.server.ResponseStatusException used) { return false; }
            };
            var a = threads.submit(work.apply(actions));
            var b = threads.submit(work.apply(other));
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        assertFalse(independent.current(prior));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tape_http_session WHERE principal_name=?", Integer.class, email));
    }

    @Test void expiredAndWrongPurposeTokensAreRejectedAndUnknownEmailQueuesNothing() {
        actions.register(new AccountDirectory.Registration(email, original));
        String token = deliveredToken();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> actions.confirm(new IdentityActions.Confirmation(token, original), "RESET"));
        AccountSecurityTest.owner.update("""
            UPDATE identity_actions SET created_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 hour'
            WHERE user_id=(SELECT user_id FROM local_credentials WHERE email=?)
            """, email);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> actions.confirm(new IdentityActions.Confirmation(token, original), "VERIFY"));
        actions.request(new IdentityActions.Email(UUID.randomUUID() + "@example.test"), "RESET");
        assertEquals(0, db.queryForObject("SELECT count(*) FROM identity_mail", Integer.class));
    }

    @Test void mailOutageKeepsEncryptedWorkForRetryAndAnotherWorkerCanDeliverIt() {
        actions.register(new AccountDirectory.Registration(email, original));
        doThrow(new MailSendException("fixture outage")).when(mail).send(any(SimpleMailMessage.class));
        actions.deliverOne();
        assertEquals(1, db.queryForObject("SELECT max(attempts) FROM identity_mail", Integer.class));
        assertTrue(db.queryForObject("SELECT bool_and(octet_length(encrypted_token)>43) FROM identity_mail", Boolean.class));
        AccountSecurityTest.owner.update("UPDATE identity_mail SET next_attempt_at=clock_timestamp()");
        doNothing().when(mail).send(any(SimpleMailMessage.class));
        actions = independentActions();
        String token = deliveredToken();
        assertEquals(43, token.length());
    }

    @Test void unreadableQueueItemDoesNotBlockOtherAccountsOrExposeItsToken() {
        actions.register(new AccountDirectory.Registration(email, original));
        AccountSecurityTest.owner.update("UPDATE identity_mail SET encrypted_token=decode(repeat('00',64),'hex')");
        actions.deliverOne();
        verifyNoInteractions(mail);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM identity_mail WHERE attempts=8", Integer.class));
        String other = UUID.randomUUID() + "@example.test";
        actions.register(new AccountDirectory.Registration(other, original));
        actions.deliverOne();
        verify(mail).send(any(SimpleMailMessage.class));
        AccountSecurityTest.owner.update("DELETE FROM identity_actions WHERE user_id=(SELECT user_id FROM local_credentials WHERE email=?)", email);
    }

    @Test void closedRegistrationDoesNotProvisionOrQueueAndExistingUsersCanStillRecover() {
        actions.register(new AccountDirectory.Registration(email, original));
        actions.confirm(new IdentityActions.Confirmation(deliveredToken(), original), "VERIFY");
        environment.setProperty("TAPE_REGISTRATION_MODE", "CLOSED");
        actions = independentActions();
        String stranger = UUID.randomUUID() + "@example.test";
        actions.register(new AccountDirectory.Registration(stranger, original));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM local_credentials WHERE email=?", Integer.class, stranger));
        actions.request(new IdentityActions.Email(email), "RESET");
        assertEquals(43, deliveredToken().length());
    }

    @Test void invitationsAreNormalizedAndEachInvitedUserHasIndependentFunds() {
        String other = UUID.randomUUID() + "@example.test";
        environment.setProperty("TAPE_REGISTRATION_MODE", "ALLOWLIST");
        environment.setProperty("TAPE_REGISTRATION_ALLOWLIST", email.toUpperCase(java.util.Locale.ROOT) + ", " + other);
        actions = independentActions();
        actions.register(new AccountDirectory.Registration(email, original));
        actions.register(new AccountDirectory.Registration(other, original));
        var first = directory.load(email).userId();
        String uninvited = UUID.randomUUID() + "@example.test";
        actions.register(new AccountDirectory.Registration(uninvited, original));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM local_credentials WHERE email=?", Integer.class, uninvited));
        var second = directory.load(other).userId();
        assertNotEquals(first, second);
        assertEquals(200_000_000L, db.queryForObject("SELECT sum(balance_paise) FROM wallets w JOIN trading_accounts a ON a.id=w.account_id WHERE a.user_id=?", Long.class, first));
        assertEquals(200_000_000L, db.queryForObject("SELECT sum(balance_paise) FROM wallets w JOIN trading_accounts a ON a.id=w.account_id WHERE a.user_id=?", Long.class, second));
        actions.deliverOne();
        actions.deliverOne();
    }

    @Test void invitationPolicyDefaultsClosedAndInvalidConfigurationFailsClosed() {
        assertFalse(new RegistrationAccess(new MockEnvironment()).permits(email));
        assertThrows(IllegalArgumentException.class, () -> new RegistrationAccess(new MockEnvironment().withProperty("TAPE_REGISTRATION_MODE", "ALLOWLIST")));
        assertThrows(IllegalArgumentException.class, () -> new RegistrationAccess(new MockEnvironment().withProperty("TAPE_REGISTRATION_MODE", "OPEN")));
    }
}
