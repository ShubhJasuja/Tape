
package in.practice.tape;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.*;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.*;

import static in.practice.tape.DerivativeContracts.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tape.postgres.tests", matches = "true")
class DerivativeBookTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    private static final Instant START = Instant.parse("2026-01-05T04:00:00Z");
    private static JdbcTemplate db;
    private static TransactionTemplate tx;
    private static TimeZone originalZone;
    private Instant now = START;
    private final UUID user = UUID.randomUUID(), account = UUID.randomUUID();
    private DerivativeContracts catalog;
    private DerivativeBook book;

    @BeforeAll
    static void start() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        POSTGRES.start();
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration/postgresql").load().migrate();
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new JdbcTransactionManager(source));
    }

    @AfterAll static void stop() {
        try { POSTGRES.stop(); }
        finally { TimeZone.setDefault(originalZone); }
    }

    @BeforeEach
    void setup() {
        catalog = new DerivativeContracts(db);
        book = new DerivativeBook(db, tx, bookClock());
        db.update("INSERT INTO users(id,identity_issuer,identity_subject) VALUES (?,'test',?)", user, user.toString());
        db.update("INSERT INTO trading_accounts(id,user_id,mode) VALUES (?,?,'DEMO')", account, user);
        db.update("INSERT INTO wallets(account_id,balance_paise) VALUES (?,1234)", account);
        book.open(user, account, UUID.randomUUID(), 100_000);
    }

    @Test
    void contractIdentityVersionAndTradingCutoffRemainStable() {
        var call = contract(Kind.CALL, Settlement.CASH);
        assertEquals(Status.SCHEDULED, call.status(START.minusSeconds(2), catalog.effective(call.id(), START.minusSeconds(2))));
        assertThrows(DataAccessException.class, () -> catalog.register(new Contract(UUID.randomUUID(), call.source(),
            call.sourceKey(), call.exchange(), call.underlyingKey(), call.underlying(), call.kind(), call.expiry(),
            call.strikePaise(), call.settlement())));
        code("INVALID_LOT_SIZE", () -> fill(call, 9, 100));
        code("INVALID_FILL", () -> fill(call, 10, 101));
        fill(call, 10, 100);
        var second = spec(call, 2, START.plusSeconds(5), 20);
        catalog.addSpec(second);
        now = START.plusSeconds(6);
        assertEquals(2, catalog.effective(call.id(), now).version());
        code("SPEC_CHANGED", () -> fill(call, 10, 100));
        assertEquals(1, fill(call, -10, 100).version());
        assertEquals(2, fill(call, 20, 100).version());
        now = call.expiry();
        code("CONTRACT_NOT_ACTIVE", () -> fill(call, 20, 100));
        assertEquals(Status.EXPIRED, call.status(now, second));
    }

    @Test
    void callPremiumAndPartialCloseAndPutShortStaySeparatelyReconciled() {
        var call = contract(Kind.CALL, Settlement.CASH);
        var put = contract(Kind.PUT, Settlement.CASH);
        assertEquals(-4_000, fill(call, 20, 100).premium());
        assertEquals(1_000, book.unrealized(user, account, call.id(), 125));
        var exit = fill(call, -10, 150);
        assertEquals(3_000, exit.premium());
        assertEquals(1_000, exit.realized());
        assertEquals(5_000, fill(put, -20, 125).premium());
        var state = book.reconcile(user, account);
        assertEquals(104_000, state.wallet().cash());
        assertEquals(40_000, state.wallet().collateral());
        assertEquals(-2, state.positions().get(put.id()).lots());
        assertEquals(1_000, book.unrealized(user, account, put.id(), 100));
        assertEquals(500, fill(put, 10, 100).realized());
        state = book.reconcile(user, account);
        assertEquals(20_000, state.wallet().collateral());
        assertEquals(1_500, state.realized());
        assertEquals(1_234L, db.queryForObject("SELECT balance_paise FROM wallets WHERE account_id=?", Long.class, account));
    }

    @Test
    void voluntaryShortCoverCannotCreateAnOptionsCashDeficit() {
        var option = contract(Kind.CALL, Settlement.CASH);
        fill(option, -10, 100);
        var before = book.reconcile(user, account);
        code("INSUFFICIENT_MARGIN", () -> fill(option, 10, 5500));
        assertEquals(before, book.reconcile(user, account));
    }

    @Test
    void futuresMarkPostsVariationAndRebasesBothSidesAndPartialClose() {
        var future = contract(Kind.FUTURE, Settlement.CASH);
        assertEquals(0, fill(future, -20, 1000).cash());
        assertEquals(40_000, book.reconcile(user, account).wallet().collateral());
        now = START.plusSeconds(3600);
        var mark = book.mark(user, account, UUID.randomUUID(), future.id(), LocalDate.of(2026, 1, 5), now, 900);
        assertEquals(4_000, mark.cash());
        UUID markRetry = UUID.randomUUID();
        assertEquals(mark, book.mark(user, account, markRetry, future.id(), LocalDate.of(2026, 1, 5), now, 900));
        code("REQUEST_CONFLICT", () -> book.fill(user, account, markRetry, future.id(), 10, 900));
        code("INVALID_EVENT_ORDER", () -> fill(future, 10, 850));
        now = now.plusSeconds(1);
        assertEquals(1_000, fill(future, 10, 850).realized());
        assertEquals(1_000, book.unrealized(user, account, future.id(), 850));
        assertEquals(5_000, book.reconcile(user, account).realized());
        now = future.expiry();
        var event = book.settle(user, account, UUID.randomUUID(), future.id(), 950);
        assertEquals(-1_000, event.cash());
        assertEquals(event, book.settle(user, account, UUID.randomUUID(), future.id(), 950));
        code("REQUEST_CONFLICT", () -> book.settle(user, account, UUID.randomUUID(), future.id(), 951));
        var finished = book.reconcile(user, account);
        assertEquals(0, finished.wallet().collateral());
        assertEquals(104_000, finished.wallet().cash());
        assertTrue(finished.positions().isEmpty());
    }

    @Test
    void maintenanceDeficitPostsLossWithoutFundingNewExposure() {
        var future = contract(Kind.FUTURE, Settlement.CASH);
        fill(future, 20, 5000);
        now = START.plusSeconds(3600);
        book.mark(user, account, UUID.randomUUID(), future.id(), LocalDate.of(2026, 1, 5), now, 1500);
        assertEquals(-40_000, book.reconcile(user, account).wallet().cash());
        assertEquals(64_000, book.maintenanceShortfall(user, account));
        now = now.plusSeconds(1);
        code("INSUFFICIENT_MARGIN", () -> fill(future, 10, 1500));
        assertEquals(0, fill(future, -10, 1500).cash());
        assertEquals(20_000, book.reconcile(user, account).wallet().collateral());
    }

    @Test
    void reservationsFutureInitialMarginAndRiskLimitsAreAtomic() {
        var futures = contract(Kind.FUTURE, Settlement.CASH);
        var reservation = UUID.randomUUID();
        var event = book.reserve(user, account, reservation, 90_000);
        assertEquals(event, book.reserve(user, account, reservation, 90_000));
        code("REQUEST_CONFLICT", () -> book.reserve(user, account, reservation, 80_000));
        code("INSUFFICIENT_MARGIN", () -> fill(futures, 10, 1000));
        book.reserve(user, account, UUID.randomUUID(), -90_000);
        code("POSITION_LIMIT", () -> fill(futures, 60, 1000));
        code("NOTIONAL_LIMIT", () -> fill(futures, 10, 100_000));
        var accepted = fill(futures, 10, 1000);
        assertEquals(0, accepted.cash());
        var state = book.reconcile(user, account);
        assertEquals(80_000, state.wallet().available());
        assertEquals(0, state.wallet().reserved());
    }

    @Test
    void cashOptionsAndPhysicalObligationsSettleExactlyOnce() {
        var index = contract(Kind.PUT, Settlement.CASH);
        var stock = contract(Kind.CALL, Settlement.PHYSICAL);
        var outOfMoney = contract(Kind.CALL, Settlement.CASH);
        fill(index, 10, 100);
        fill(stock, -10, 125);
        fill(outOfMoney, 10, 50);
        code("INVALID_EVENT_TIME", () -> book.settle(user, account, UUID.randomUUID(), stock.id(), 1100));
        now = stock.expiry();
        assertEquals(4_000, book.settle(user, account, UUID.randomUUID(), index.id(), 800).cash());
        var physical = book.settle(user, account, UUID.randomUUID(), stock.id(), 1100);
        assertEquals(0, physical.cash());
        assertEquals(-20, physical.deliveryUnits());
        assertEquals(20_000, physical.deliveryCash());
        assertEquals(20_000, physical.deliveryCollateral());
        assertEquals(physical, book.settle(user, account, UUID.randomUUID(), stock.id(), 1100));
        assertEquals(-1000, book.settle(user, account, UUID.randomUUID(), outOfMoney.id(), 800).realized());
        var state = book.reconcile(user, account);
        assertTrue(state.positions().isEmpty());
        assertEquals(20_000, state.wallet().collateral());
        assertEquals(1_500, state.realized());
    }

    @Test
    void duplicateConcurrentFillsAreSerializedAndOwnershipIsEnforced() throws Exception {
        var option = contract(Kind.PUT, Settlement.CASH);
        UUID request = UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<DerivativeBook.Event> action = () -> { start.await(); return book.fill(user, account, request, option.id(), 10, 100); };
            var a = pool.submit(action);
            var b = pool.submit(action);
            start.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            var first = pool.submit(() -> fill(option, 10, 100));
            var second = pool.submit(() -> fill(option, 10, 100));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(3, book.reconcile(user, account).positions().get(option.id()).lots());
        code("REQUEST_CONFLICT", () -> book.fill(user, account, request, option.id(), 20, 100));
        code("ACCOUNT_NOT_FOUND", () -> book.fill(UUID.randomUUID(), account, request, option.id(), 10, 100));
        code("ACCOUNT_NOT_FOUND", () -> book.reconcile(UUID.randomUUID(), account));
        UUID live = UUID.randomUUID();
        db.update("INSERT INTO trading_accounts(id,user_id,mode) VALUES (?,?,'PERMITTED_MARKET_DATA')", live, user);
        code("ACCOUNT_NOT_FOUND", () -> book.open(user, live, request, 100_000));
    }

    @Test
    void immutableJournalAndSpecificationsRejectMutationAndFlagProjectionDrift() {
        var option = contract(Kind.CALL, Settlement.CASH);
        var event = fill(option, 10, 100);
        assertThrows(DataAccessException.class, () -> db.update("UPDATE derivative_events SET price_paise=5 WHERE seq=?", event.seq()));
        assertThrows(DataAccessException.class, () -> db.update("DELETE FROM derivative_events WHERE seq=?", event.seq()));
        assertThrows(DataAccessException.class, () -> db.execute("TRUNCATE derivative_events"));
        assertThrows(DataAccessException.class, () -> db.update("UPDATE derivative_specs SET lot_size=1 WHERE contract_id=?", option.id()));
        db.update("UPDATE derivative_books SET cash_paise=cash_paise+1 WHERE account_id=?", account);
        code("JOURNAL_MISMATCH", () -> book.reconcile(user, account));
    }

    @Test
    void pausedPracticeOrdersCanBeReadRetriedCancelledAndSettled() {
        var session = practice();
        var call = listed(session, Kind.CALL);
        var ticket = new PracticeOptions.Ticket(UUID.randomUUID(), call.id(), call.specVersion(), PracticeOptions.Side.BUY, 1, null);
        var fill = session.options.submit(session.owner, session.account, ticket);
        var pending = session.place(call, PracticeOptions.Side.SELL, 1, 20_000L);
        var paused = new PracticeOptions(db, tx, bookClock(), true, 200, false);
        assertEquals(fill, paused.submit(session.owner, session.account, ticket));
        code("OPTIONS_DISABLED", () -> paused.submit(session.owner, session.account,
            new PracticeOptions.Ticket(UUID.randomUUID(), call.id(), call.specVersion(), PracticeOptions.Side.BUY, 1, null)));
        assertEquals("CANCELLED", paused.cancel(session.owner, session.account, pending.id()).status());
        now = call.expiry();
        paused.settle(new PracticeOptions.Work(session.owner, session.account, call.id()));
        assertEquals(1, paused.view(session.owner, session.account, 0).settlements().size());
    }

    @Test
    void practiceCallsAndPutsSupportBothDirectionsWithPremiumFeesAndPartialCloses() {
        var session = practice();
        var call = listed(session, Kind.CALL);
        var put = listed(session, Kind.PUT);
        var buy = session.place(call, PracticeOptions.Side.BUY, 3, null);
        var sell = session.place(put, PracticeOptions.Side.SELL, 2, null);
        assertEquals(-buy.fillPaise() * 30, buy.premiumPaise());
        assertEquals(sell.fillPaise() * 20, sell.premiumPaise());
        assertEquals(-200L, buy.realizedPaise());
        assertEquals(-200L, sell.realizedPaise());
        var initial = session.state();
        assertEquals(100_000_000 + buy.premiumPaise() + sell.premiumPaise() - 400, initial.wallet().cash());
        assertEquals(400_000, initial.wallet().collateral());

        now = now.plusSeconds(50);
        var partial = session.place(call, PracticeOptions.Side.SELL, 1, null);
        assertEquals(partial.premiumPaise() + buy.premiumPaise() / 3 - 200, partial.realizedPaise());
        var cover = session.place(put, PracticeOptions.Side.BUY, 1, null);
        assertEquals(cover.premiumPaise() + sell.premiumPaise() / 2 - 200, cover.realizedPaise());
        var reduced = session.state();
        assertEquals(2, reduced.positions().get(call.id()).lots());
        assertEquals(-1, reduced.positions().get(put.id()).lots());
        assertEquals(200_000, reduced.wallet().collateral());

        session.place(call, PracticeOptions.Side.SELL, 3, null);
        session.place(put, PracticeOptions.Side.BUY, 2, null);
        assertEquals(-1, session.state().positions().get(call.id()).lots());
        assertEquals(1, session.state().positions().get(put.id()).lots());
        session.place(call, PracticeOptions.Side.BUY, 1, null);
        session.place(put, PracticeOptions.Side.SELL, 1, null);
        assertTrue(session.state().positions().isEmpty());
        assertEquals(0, session.state().wallet().collateral());
    }

    @Test
    void practiceLimitsCancelRecoverAndSettleDeterministically() {
        var session = practice();
        var call = listed(session, Kind.CALL);
        var put = listed(session, Kind.PUT);
        var pending = session.place(call, PracticeOptions.Side.SELL, 2, 20_000L);
        assertEquals("OPEN", pending.status());
        assertEquals(400_200, session.state().wallet().reserved());
        code("OPEN_ORDER_EXISTS", () -> session.place(call, PracticeOptions.Side.SELL, 1, null));
        assertEquals("CANCELLED", session.options.cancel(session.owner, session.account, pending.id()).status());
        assertEquals("CANCELLED", session.options.cancel(session.owner, session.account, pending.id()).status());
        assertEquals(0, session.state().wallet().reserved());

        long low = Math.min(call.markPaise(), put.markPaise());
        var crossing = low == call.markPaise() ? put : call;
        var open = session.place(crossing, PracticeOptions.Side.BUY, 1, crossing.markPaise() - 5);
        assertEquals("OPEN", open.status());
        assertEquals(open.limitPaise() * 10 + 200, session.state().wallet().reserved());
        var restarted = new PracticeOptions(db, tx, bookClock(), true, 400);
        for (int i = 0; i < 80 && PracticeOptionPrices.premium(catalog.contract(crossing.id()),
            catalog.version(crossing.id(), crossing.specVersion()), now) > open.limitPaise(); i++) now = now.plusSeconds(5);
        restarted.match(new PracticeOptions.Work(session.owner, session.account, open.id()));
        var filled = restarted.view(session.owner, session.account, 0).orders().stream().filter(entry -> entry.id().equals(open.id())).findFirst().orElseThrow();
        assertEquals("FILLED", filled.status());
        assertEquals(200, filled.feePaise());
        assertEquals(0, restarted.reconcile(session.owner, session.account).wallet().reserved());

        var expire = session.place(crossing, PracticeOptions.Side.SELL, 1, 20_000L);
        assertEquals("OPEN", expire.status());
        now = crossing.expiry();
        var work = new PracticeOptions.Work(session.owner, session.account, crossing.id());
        restarted.settle(work);
        restarted.settle(work);
        var finalView = restarted.view(session.owner, session.account, 0);
        assertTrue(finalView.positions().isEmpty());
        assertEquals(0, finalView.balances().reservedPaise());
        assertEquals(1, finalView.settlements().size());
        long finalIndex = PracticeOptionPrices.underlying(now);
        assertEquals(Math.max(0, crossing.kind() == Kind.CALL ? finalIndex - crossing.strikePaise() : crossing.strikePaise() - finalIndex) * 10,
            finalView.settlements().getFirst().payoutPaise());
        assertEquals("EXPIRED", finalView.orders().stream().filter(entry -> entry.id().equals(expire.id())).findFirst().orElseThrow().status());
        code("CONTRACT_NOT_ACTIVE", () -> session.place(crossing, PracticeOptions.Side.BUY, 1, null));
        restarted.reconcile(session.owner, session.account);
    }

    @Test
    void practiceRejectsUnsupportedTermsAndUnfundedOrdersWithoutSideEffects() {
        var session = practice();
        var option = listed(session, Kind.PUT);
        code("INVALID_REQUEST", () -> session.place(option, PracticeOptions.Side.BUY, 0, null));
        code("INVALID_TICK", () -> session.place(option, PracticeOptions.Side.BUY, 1, 101L));
        code("POSITION_LIMIT", () -> session.place(option, PracticeOptions.Side.BUY, option.maxLots() + 1, null));
        code("UNSUPPORTED_CONTRACT", () -> session.options.submit(session.owner, session.account,
            new PracticeOptions.Ticket(UUID.randomUUID(), contract(Kind.CALL, Settlement.PHYSICAL).id(), 1, PracticeOptions.Side.BUY, 1, null)));
        book.open(session.owner, session.account, UUID.randomUUID(), 100);
        code("INSUFFICIENT_MARGIN", () -> session.place(option, PracticeOptions.Side.BUY, 1, null));
        code("INSUFFICIENT_MARGIN", () -> session.place(option, PracticeOptions.Side.SELL, 1, null));
        code("INSUFFICIENT_MARGIN", () -> session.place(option, PracticeOptions.Side.SELL, 1, 20_000L));
        assertTrue(session.options.view(session.owner, session.account, 0).orders().isEmpty());
        assertEquals(100, session.state().wallet().available());
        code("ACCOUNT_NOT_FOUND", () -> session.options.view(user, session.account, 0));
    }

    @Test
    void changedSpecificationRollsBackMatchedFillAndReleasesOnlyItsOwnReserve() {
        var session = practice();
        var put = listed(session, Kind.PUT);
        var call = session.options.view(session.owner, session.account, 0).chain().stream()
            .filter(item -> item.kind() == Kind.CALL && item.strikePaise() == 100_000).reduce((first, last) -> last).orElseThrow();
        var pending = session.place(call, PracticeOptions.Side.SELL, 1, call.markPaise() + 5);
        var unaffected = session.place(put, PracticeOptions.Side.SELL, 1, 20_000L);
        var spec = catalog.version(call.id(), call.specVersion());
        catalog.addSpec(new Spec(call.id(), 2, now.plusSeconds(1), spec.lotSize(), spec.multiplier(), spec.tickPaise(),
            spec.initialMarginPaise(), spec.maintenanceMarginPaise(), spec.maxLots(), spec.maxOrderNotionalPaise()));
        for (int i = 0; i < 80 && PracticeOptionPrices.premium(catalog.contract(call.id()), spec, now) < pending.limitPaise(); i++) {
            now = now.plusSeconds(5);
        }
        session.options.match(new PracticeOptions.Work(session.owner, session.account, pending.id()));
        var rejected = session.options.view(session.owner, session.account, 0).orders().stream()
            .filter(order -> order.id().equals(pending.id())).findFirst().orElseThrow();
        assertEquals("REJECTED", rejected.status());
        assertEquals("SPEC_CHANGED", rejected.reason());
        var state = session.state();
        assertEquals(100_000_000, state.wallet().cash());
        assertEquals(unaffected.reservationPaise(), state.wallet().reserved());
        assertTrue(state.positions().isEmpty());
    }

    @Test
    void practiceDuplicateAndCancelVersusExecutionUseOneAccountLock() throws Exception {
        var session = practice();
        var put = listed(session, Kind.PUT);
        var call = listed(session, Kind.CALL);
        var ticket = new PracticeOptions.Ticket(UUID.randomUUID(), put.id(), put.specVersion(), PracticeOptions.Side.BUY, 1, null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<PracticeOptions.Order> action = () -> {
                start.await();
                return session.options.submit(session.owner, session.account, ticket);
            };
            var first = pool.submit(action);
            var second = pool.submit(action);
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            code("REQUEST_CONFLICT", () -> session.options.submit(session.owner, session.account,
                new PracticeOptions.Ticket(ticket.requestId(), put.id(), put.specVersion(), PracticeOptions.Side.SELL, 1, null)));
            var pending = session.place(call, PracticeOptions.Side.SELL, 1, call.markPaise() + 5);
            assertEquals("OPEN", pending.status());
            for (int i = 0; i < 80 && PracticeOptionPrices.premium(catalog.contract(call.id()),
                catalog.version(call.id(), call.specVersion()), now) < pending.limitPaise(); i++) now = now.plusSeconds(5);
            var race = new CountDownLatch(1);
            var match = pool.submit(() -> { race.await(); session.options.match(new PracticeOptions.Work(session.owner, session.account, pending.id())); return null; });
            var cancel = pool.submit(() -> { race.await(); return session.options.cancel(session.owner, session.account, pending.id()); });
            race.countDown();
            match.get(10, TimeUnit.SECONDS);
            var result = cancel.get(10, TimeUnit.SECONDS);
            assertTrue(result.status().equals("FILLED") || result.status().equals("CANCELLED"));
            assertEquals(result.status().equals("FILLED") ? 1L : 0L, db.queryForObject(
                "SELECT count(*) FROM derivative_events WHERE account_id=? AND request_id=?", Long.class, session.account, pending.id()));
            assertEquals(0, session.state().wallet().reserved());
            assertEquals(1, session.state().positions().get(put.id()).lots());
        } finally { pool.shutdownNow(); }
    }

    private record Practice(UUID owner, UUID account, PracticeOptions options) {
        PracticeOptions.Order place(PracticeOptions.Listing listing, PracticeOptions.Side side, int lots, Long limit) {
            return options.submit(owner, account, new PracticeOptions.Ticket(UUID.randomUUID(), listing.id(), listing.specVersion(), side, lots, limit));
        }
        DerivativeBook.AccountState state() { return options.reconcile(owner, account); }
    }

    @Test
    void futuresPostOnlyVariationAndFeesAndAllocateBasisOnPartialCloses() {
        var session = practice(true);
        var chain = session.options.view(session.owner, session.account, 0).chain().stream()
            .filter(item -> item.expiry().isAfter(Instant.parse("2027-01-01T00:00:00Z"))).toList();
        var longContract = chain.get(0);
        var shortContract = chain.get(1);
        code("INVALID_TICK", () -> session.place(longContract, PracticeOptions.Side.BUY, 1, 100_001L));
        code("POSITION_LIMIT", () -> session.place(longContract, PracticeOptions.Side.BUY, 11, null));
        var buy = session.place(longContract, PracticeOptions.Side.BUY, 3, null);
        var sell = session.place(shortContract, PracticeOptions.Side.SELL, 2, null);
        long units = longContract.lotSize() * longContract.multiplier();
        assertEquals(0, buy.premiumPaise());
        assertEquals(0, sell.premiumPaise());
        assertEquals(99_999_600, session.state().wallet().cash());
        assertEquals(1_000_000, session.state().wallet().collateral());
        var resting = session.place(longContract, PracticeOptions.Side.BUY, 1, buy.fillPaise() - longContract.tickPaise());
        assertEquals("OPEN", resting.status());
        assertEquals(200_200, session.state().wallet().reserved());
        assertEquals("CANCELLED", session.options.cancel(session.owner, session.account, resting.id()).status());
        assertEquals(0, session.state().wallet().reserved());
        now = START.plusSeconds(40);
        var view = session.options.view(session.owner, session.account, 0);
        assertEquals((view.positions().getFirst().markPaise() - buy.fillPaise()) * units * 3, view.positions().getFirst().unrealizedPaise());
        var reduce = session.place(longContract, PracticeOptions.Side.SELL, 1, null);
        var cover = session.place(shortContract, PracticeOptions.Side.BUY, 1, null);
        assertEquals((reduce.fillPaise() - buy.fillPaise()) * units - 200, reduce.realizedPaise());
        assertEquals((sell.fillPaise() - cover.fillPaise()) * units - 200, cover.realizedPaise());
        assertEquals(2, session.state().positions().get(longContract.id()).lots());
        assertEquals(-1, session.state().positions().get(shortContract.id()).lots());
        assertEquals(600_000, session.state().wallet().collateral());
        code("REDUCTION_REQUIRED", () -> session.options.submit(session.owner, session.account,
            new PracticeOptions.Ticket(UUID.randomUUID(), longContract.id(), 1, PracticeOptions.Side.SELL, 3, null, true)));
        session.options.submit(session.owner, session.account,
            new PracticeOptions.Ticket(UUID.randomUUID(), longContract.id(), 1, PracticeOptions.Side.SELL, 2, null, true));
        assertFalse(session.state().positions().containsKey(longContract.id()));
    }

    @Test
    void futuresRecoverMissedDailyMarksAndSettleOnceWithOrderExpiry() {
        var session = practice(true);
        LocalDate day = LocalDate.of(2026, 1, 5);
        var future = shortDatedFuture(day.plusDays(3), 200_000, 150_000);
        var listing = futureListing(session, future);
        var entry = session.place(listing, PracticeOptions.Side.SELL, 2, null);
        var resting = session.place(listing, PracticeOptions.Side.BUY, 1, listing.tickPaise());
        assertEquals("OPEN", resting.status());
        now = close(day.plusDays(1)).plusSeconds(1);
        var restart = new PracticeFutures(db, tx, bookClock(), true, 200, true);
        assertTrue(restart.view(session.owner, session.account, 0).marksPending());
        var ready = restart.dueFutures(null).stream().filter(work -> work.account().equals(session.account)).findFirst().orElseThrow();
        restart.markFutures(ready);
        restart.markFutures(ready);
        var marked = restart.view(session.owner, session.account, 0);
        assertFalse(marked.marksPending());
        assertEquals(2, marked.journal().stream().filter(post -> post.kind().equals("MARK")).count());
        var last = marked.journal().getFirst();
        assertEquals(close(day.plusDays(1)), last.occurredAt());
        assertEquals(100_000_000 - 200 + (entry.fillPaise() - last.pricePaise()) * 40, marked.balances().cashPaise());

        now = future.expiry();
        restart.markFutures(ready);
        restart.markFutures(ready);
        var settled = restart.view(session.owner, session.account, 0);
        assertEquals(1, settled.settlements().size());
        assertEquals(3, settled.journal().stream().filter(post -> post.kind().equals("MARK")).count());
        assertEquals("EXPIRED", settled.orders().stream().filter(order -> order.id().equals(resting.id())).findFirst().orElseThrow().status());
        assertTrue(settled.positions().isEmpty());
        assertEquals(0, settled.balances().marginPaise());
        assertEquals(0, settled.balances().reservedPaise());
        assertEquals(100_000_000 - 200 + (entry.fillPaise() - PracticeOptionPrices.underlying(now)) * 40,
            settled.balances().cashPaise());
        code("CONTRACT_NOT_ACTIVE", () -> session.place(listing, PracticeOptions.Side.BUY, 1, null));
        restart.reconcile(session.owner, session.account);
    }

    @Test
    void realizedVariationCanCauseShortfallAndBlocksNewRiskButAllowsReducing() {
        var session = practice(true);
        LocalDate day = LocalDate.of(2026, 1, 5);
        var future = shortDatedFuture(day.plusDays(4), 1_000, 800);
        var spec = catalog.version(future.id(), 1);
        long closePrice = PracticeFutures.quote(spec, close(day));
        var direction = PracticeOptionPrices.underlying(close(day)) < 100_000 ? PracticeOptions.Side.BUY : PracticeOptions.Side.SELL;
        for (int i = 0; i < 80; i++) {
            long move = PracticeFutures.quote(spec, now) - closePrice;
            if (direction == PracticeOptions.Side.BUY ? move >= 1000 : move <= -1000) break;
            now = now.plusSeconds(5);
        }
        var listing = futureListing(session, future);
        book.open(session.owner, session.account, UUID.randomUUID(), 2400);
        code("INSUFFICIENT_MARGIN", () -> session.place(listing, direction, 3, null));
        session.place(listing, direction, 2, null);
        now = close(day).plusSeconds(1);
        var work = new PracticeOptions.Work(session.owner, session.account, session.account);
        session.options.markFutures(work);
        assertTrue(session.options.view(session.owner, session.account, 0).balances().maintenanceShortfallPaise() > 0);
        code("INSUFFICIENT_MARGIN", () -> session.place(listing, direction, 1, null));
        var reverse = direction == PracticeOptions.Side.BUY ? PracticeOptions.Side.SELL : PracticeOptions.Side.BUY;
        session.options.submit(session.owner, session.account,
            new PracticeOptions.Ticket(UUID.randomUUID(), listing.id(), 1, reverse, 1, null, true));
        assertEquals(1, Math.abs(session.state().positions().get(listing.id()).lots()));
        assertEquals(1000, session.state().wallet().collateral());
    }

    @Test
    void optionsRiskCheckPostsDueFuturesMarksBeforeItSpendsSharedCash() {
        var session = practice(true);
        LocalDate day = LocalDate.of(2026, 1, 5);
        var future = shortDatedFuture(day.plusDays(5), 200_000, 150_000);
        session.place(futureListing(session, future), PracticeOptions.Side.BUY, 1, null);
        var options = new PracticeOptions(db, tx, bookClock(), true, 200);
        var call = listed(new Practice(session.owner, session.account, options), Kind.CALL);
        now = close(day).plusSeconds(1);
        options.submit(session.owner, session.account, new PracticeOptions.Ticket(UUID.randomUUID(), call.id(), 1,
            PracticeOptions.Side.BUY, 1, null));
        assertEquals("MARK", session.options.view(session.owner, session.account, 0).journal().getFirst().kind());
        assertEquals(2, session.state().positions().size());
    }

    @Test
    void futuresConcurrentOrdersAndCancellationShareOneAccountLock() throws Exception {
        var session = practice(true);
        var listing = session.options.view(session.owner, session.account, 0).chain().getFirst();
        var side = PracticeOptions.Side.BUY;
        var ticket = new PracticeOptions.Ticket(UUID.randomUUID(), listing.id(), 1, side, 1, null);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return session.options.submit(session.owner, session.account, ticket); });
            var retry = pool.submit(() -> { start.await(); return session.options.submit(session.owner, session.account, ticket); });
            var another = pool.submit(() -> { start.await(); return session.place(listing, side, 1, null); });
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), retry.get(10, TimeUnit.SECONDS));
            another.get(10, TimeUnit.SECONDS);
            assertEquals(2, session.state().positions().get(listing.id()).lots());
            var spec = catalog.version(listing.id(), 1);
            while (PracticeFutures.quote(spec, now) == PracticeFutures.quote(spec, now.plusSeconds(5))) now = now.plusSeconds(5);
            long current = PracticeFutures.quote(spec, now), next = PracticeFutures.quote(spec, now.plusSeconds(5));
            var pending = session.place(listing, current > next ? PracticeOptions.Side.BUY : PracticeOptions.Side.SELL, 1, next);
            assertEquals("OPEN", pending.status());
            assertEquals(pending.reservationPaise(), session.state().wallet().reserved());
            code("ACCOUNT_NOT_FOUND", () -> session.options.cancel(user, session.account, pending.id()));
            now = now.plusSeconds(5);
            var race = new CountDownLatch(1);
            var match = pool.submit(() -> { race.await(); session.options.match(new PracticeOptions.Work(session.owner, session.account, pending.id())); return null; });
            var cancel = pool.submit(() -> { race.await(); return session.options.cancel(session.owner, session.account, pending.id()); });
            race.countDown();
            match.get(10, TimeUnit.SECONDS);
            var result = cancel.get(10, TimeUnit.SECONDS);
            assertTrue(result.status().equals("FILLED") || result.status().equals("CANCELLED"));
            assertEquals(0, session.state().wallet().reserved());
            assertEquals(result.status().equals("FILLED") ? 1L : 0L, db.queryForObject(
                "SELECT count(*) FROM derivative_events WHERE account_id=? AND request_id=?", Long.class, session.account, pending.id()));
        } finally { pool.shutdownNow(); }
    }

    private Practice practice() {
        return practice(false);
    }

    private Practice practice(boolean futures) {
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        db.update("INSERT INTO users(id,identity_issuer,identity_subject) VALUES (?,'practice-test',?)", owner, owner.toString());
        db.update("INSERT INTO trading_accounts(id,user_id,mode,trading_enabled) VALUES (?,?,'DEMO',true)", id, owner);
        return new Practice(owner, id, futures ? new PracticeFutures(db, tx, bookClock(), true, 200, true)
            : new PracticeOptions(db, tx, bookClock(), true, 200));
    }

    private Contract shortDatedFuture(LocalDate expiry, long initial, long maintenance) {
        UUID id = UUID.randomUUID();
        var contract = new Contract(id, PracticeFutures.SOURCE, id.toString(), "NSE", PracticeOptionPrices.UNDERLYING,
            Underlying.INDEX, Kind.FUTURE, close(expiry), 0, Settlement.CASH);
        catalog.register(contract);
        catalog.addSpec(new Spec(id, 1, START.minusSeconds(1), 10, 2, 5, initial, maintenance, 10, 25_000_000));
        for (var day = LocalDate.of(2026, 1, 5); day.isBefore(expiry); day = day.plusDays(1)) {
            db.update("INSERT INTO practice_future_sessions VALUES (?,?,?)", id, day, java.sql.Timestamp.from(close(day)));
        }
        return contract;
    }

    private PracticeOptions.Listing futureListing(Practice session, Contract contract) {
        return session.options.view(session.owner, session.account, 0).chain().stream()
            .filter(item -> item.id().equals(contract.id())).findFirst().orElseThrow();
    }

    private static Instant close(LocalDate day) { return day.atTime(15, 30).atZone(ZoneId.of("Asia/Kolkata")).toInstant(); }

    private PracticeOptions.Listing listed(Practice session, Kind kind) {
        return session.options.view(session.owner, session.account, 0).chain().stream()
            .filter(listing -> listing.kind() == kind && listing.strikePaise() == 100_000).findFirst().orElseThrow();
    }

    private Clock bookClock() {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
            @Override public Instant instant() { return now; }
        };
    }

    private Contract contract(Kind kind, Settlement settlement) {
        UUID id = UUID.randomUUID();
        var type = settlement == Settlement.PHYSICAL ? Underlying.STOCK : Underlying.INDEX;
        var value = new Contract(id, "TEST", id.toString(), "NSE", id.toString(), type, kind,
            START.plus(Duration.ofDays(2)), kind == Kind.FUTURE ? 0 : 1000, settlement);
        catalog.register(value);
        catalog.addSpec(spec(value, 1, START.minusSeconds(1), 10));
        return value;
    }

    private static Spec spec(Contract contract, int version, Instant effectiveAt, int lotSize) {
        return new Spec(contract.id(), version, effectiveAt, lotSize, 2, 5, 20_000, 12_000, 5, 1_000_000);
    }

    private DerivativeBook.Event fill(Contract contract, long quantity, long price) {
        return book.fill(user, account, UUID.randomUUID(), contract.id(), quantity, price);
    }

    private static void code(String expected, org.junit.jupiter.api.function.Executable run) {
        assertEquals(expected, assertThrows(DerivativeBook.Rejected.class, run).getMessage());
    }
}
