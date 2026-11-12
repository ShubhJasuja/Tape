package in.practice.tape;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradingCharacterizationTest {
    private static final String SYMBOL = "NSE:RELIANCE";
    private static final long OPENING_CASH = 100_000_000L;

    private EmbeddedDatabase database;
    private Market market;
    private Trading trading;
    private Market.Snapshot demo;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        market = mock(Market.class);
        when(market.snapshot("live")).thenReturn(new Market.Snapshot("live", "Token required", Map.of()));
        quote(10_000);
        trading = new Trading(new JdbcTemplate(database),
            new TransactionTemplate(new JdbcTransactionManager(database)), market);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.shutdown();
    }

    @Test
    void startsWithOneMillionRupeesAndNoActivity() {
        var account = account();
        assertEquals(OPENING_CASH, account.cash());
        assertEquals(OPENING_CASH, account.available());
        assertEquals(OPENING_CASH, account.equity());
        assertEquals(0, account.realized());
        assertEquals(0L, account.unrealized());
        assertTrue(account.holdings().isEmpty());
        assertTrue(account.orders().isEmpty());
    }

    @Test
    void marketRoundTripReconcilesCashHoldingsFeesAndProfit() {
        var buy = submit("BUY", "MARKET", 2, null);
        assertEquals("FILLED", buy.status());
        assertEquals(10_000L, buy.fill());
        assertEquals(10L, buy.fee());
        assertEquals(OPENING_CASH - 20_010, account().cash());
        assertEquals(20_010L, account().holdings().getFirst().cost());

        quote(12_000);
        assertEquals(3_990L, account().unrealized());
        var sell = submit("SELL", "MARKET", 2, null);
        assertEquals("FILLED", sell.status());
        assertEquals(12_000L, sell.fill());
        assertEquals(12L, sell.fee());
        assertEquals(3_978L, account().realized());
        assertEquals(OPENING_CASH + 3_978, account().cash());
        assertTrue(account().holdings().isEmpty());
    }

    @Test
    void buyLimitWaitsThenFillsBelowItsLimitAndReleasesSurplus() {
        var order = submit("BUY", "LIMIT", 2, "95.00");
        trading.match();
        assertEquals("OPEN", order(order.id()).status());
        assertEquals(OPENING_CASH - 19_010, account().available());

        quote(9_000);
        trading.match();
        assertEquals("FILLED", order(order.id()).status());
        assertEquals(9_000L, order(order.id()).fill());
        assertEquals(OPENING_CASH - 18_009, account().cash());
        assertEquals(account().cash(), account().available());
    }

    @Test
    void sellStopWaitsAboveTriggerThenFillsAtGappedQuote() {
        submit("BUY", "MARKET", 2, null);
        var stop = submit("SELL", "STOP", 2, "95.00");
        trading.match();
        assertEquals("OPEN", order(stop.id()).status());

        quote(9_000);
        trading.match();
        assertEquals("FILLED", order(stop.id()).status());
        assertEquals(9_000L, order(stop.id()).fill());
        assertEquals(-2_019L, account().realized());
        assertTrue(account().holdings().isEmpty());
    }

    @Test
    void reservedCashBlocksOverspendUntilCancellation() {
        var order = submit("BUY", "LIMIT", 1, "900000.00");
        assertEquals(OPENING_CASH, account().cash());
        assertEquals(9_955_000L, account().available());
        assertStatus(400, () -> submit("BUY", "MARKET", 1_000, null));

        trading.cancel("demo", order.id());
        assertEquals("CANCELLED", order(order.id()).status());
        assertEquals(OPENING_CASH, account().available());
        trading.match();
        assertEquals("CANCELLED", order(order.id()).status());
        assertStatus(409, () -> trading.cancel("demo", order.id()));
    }

    @Test
    void reservedSharesBlockOversellingUntilCancellation() {
        submit("BUY", "MARKET", 3, null);
        var reserved = submit("SELL", "LIMIT", 2, "120.00");
        assertEquals(3, account().holdings().getFirst().quantity());
        assertStatus(400, () -> submit("SELL", "MARKET", 2, null));
        assertEquals("FILLED", submit("SELL", "MARKET", 1, null).status());

        trading.cancel("demo", reserved.id());
        assertEquals("FILLED", submit("SELL", "MARKET", 2, null).status());
        assertTrue(account().holdings().isEmpty());
    }

    @Test
    void sameIdReturnsExistingFillButConflictingTicketIsRejected() {
        var ticket = ticket("BUY", "MARKET", 2, null);
        var original = trading.submit("demo", ticket);
        quote(11_000);
        assertEquals(original, trading.submit("demo", ticket));
        var conflicting = new Trading.Ticket(ticket.id(), SYMBOL, "BUY", "MARKET", 3, null);
        assertStatus(409, () -> trading.submit("demo", conflicting));
        assertEquals(1, account().orders().size());
        assertEquals(OPENING_CASH - 20_010, account().cash());
    }

    @ParameterizedTest
    @CsvSource({"1,1", "1999,1", "2000,1", "2001,2", "4001,3"})
    void roundsFeesUpToTheNextPaisa(long notional, long expectedFee) {
        assertEquals(expectedFee, Trading.fee(notional));
    }

    @Test
    void partialSalesRetainRoundingRemainderUntilTheFinalShareIsSold() {
        quote(10_001);
        submit("BUY", "MARKET", 3, null);
        assertEquals(30_019L, account().holdings().getFirst().cost());

        quote(11_002);
        submit("SELL", "MARKET", 1, null);
        var remaining = account().holdings().getFirst();
        assertEquals(2, remaining.quantity());
        assertEquals(20_013L, remaining.cost());
        assertEquals(990L, account().realized());
        assertEquals(1_991L, account().unrealized());

        quote(12_003);
        submit("SELL", "MARKET", 2, null);
        assertEquals(4_970L, account().realized());
        assertEquals(OPENING_CASH + 4_970, account().cash());
        assertTrue(account().holdings().isEmpty());
    }

    @Test
    void modesHaveIndependentBalancesReservationsAndIdempotencyKeys() {
        var ticket = ticket("BUY", "LIMIT", 1, "95.00");
        trading.submit("demo", ticket);
        assertTrue(liveAccount().orders().isEmpty());
        trading.submit("live", ticket);
        assertEquals(OPENING_CASH - 9_505, liveAccount().available());

        quote(9_000);
        trading.match();
        assertEquals("FILLED", order(ticket.id()).status());
        assertEquals("OPEN", liveAccount().orders().getFirst().status());
        assertEquals(OPENING_CASH, liveAccount().cash());
        assertTrue(liveAccount().holdings().isEmpty());

        trading.cancel("live", ticket.id());
        assertEquals(OPENING_CASH, liveAccount().available());
        assertEquals("FILLED", order(ticket.id()).status());
        assertStatus(400, () -> trading.submit("live", ticket("BUY", "MARKET", 1, null)));
    }

    private void quote(long paise) {
        demo = new Market.Snapshot("demo", "Test quote",
            Map.of(SYMBOL, new Market.Quote(paise, 0, 1L)));
        when(market.snapshot("demo")).thenReturn(demo);
    }

    private Trading.Account account() { return trading.account(demo); }
    private Trading.Account liveAccount() { return trading.account(market.snapshot("live")); }

    private Trading.Order order(UUID id) {
        return account().orders().stream().filter(order -> id.equals(order.id())).findFirst().orElseThrow();
    }

    private Trading.Order submit(String side, String kind, int quantity, String trigger) {
        return trading.submit("demo", ticket(side, kind, quantity, trigger));
    }

    private static Trading.Ticket ticket(String side, String kind, int quantity, String trigger) {
        return new Trading.Ticket(UUID.randomUUID(), SYMBOL, side, kind, quantity,
            trigger == null ? null : new BigDecimal(trigger));
    }

    private static void assertStatus(int expected, Runnable operation) {
        var error = assertThrows(ResponseStatusException.class, operation::run);
        assertEquals(expected, error.getStatusCode().value());
    }
}
