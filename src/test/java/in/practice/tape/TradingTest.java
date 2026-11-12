package in.practice.tape;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:test-tape;DB_CLOSE_DELAY=-1",
    "UPSTOX_ANALYTICS_TOKEN=",
    "OLLAMA_MODEL="
})
class TradingTest {
    private static final String SYMBOL = "NSE:RELIANCE";
    @Autowired Trading trading;
    @Autowired Market market;
    @Autowired Research research;

    @Test
    void reservesAndReleasesFundsAndAccountsForFillsExactlyOnce() {
        var resting = ticket("BUY", "LIMIT", 10, new BigDecimal("1.00"));
        assertEquals("OPEN", trading.submit("demo", resting).status());
        assertEquals(Trading.STARTING_CASH - 1001, account().available());
        trading.cancel("demo", resting.id());
        assertEquals(Trading.STARTING_CASH, account().available());

        var buy = ticket("BUY", "MARKET", 3, null);
        var fill = trading.submit("demo", buy);
        assertEquals(fill, trading.submit("demo", buy));
        assertEquals(3, account().holdings().getFirst().quantity());
        long cost = fill.fill() * 3 + fill.fee();
        assertEquals(Trading.STARTING_CASH - cost, account().cash());

        var sell = ticket("SELL", "MARKET", 3, null);
        var sale = trading.submit("demo", sell);
        long proceeds = sale.fill() * 3 - sale.fee();
        assertEquals(proceeds - cost, account().realized());
        assertEquals(Trading.STARTING_CASH + proceeds - cost, account().equity());
        assertTrue(account().holdings().isEmpty());
        var limit = ticket("BUY", "LIMIT", 1, new BigDecimal("10000.00"));
        trading.submit("demo", limit);
        trading.match();
        assertEquals("FILLED", trading.submit("demo", limit).status());
        var stop = ticket("SELL", "STOP", 1, new BigDecimal("10000.00"));
        trading.submit("demo", stop);
        trading.match();
        assertEquals("FILLED", trading.submit("demo", stop).status());
        assertTrue(account().holdings().isEmpty());
        assertThrows(ResponseStatusException.class, () -> trading.submit("demo", ticket("SELL", "MARKET", 1, null)));
        assertEquals(Trading.STARTING_CASH, trading.account(market.snapshot("live")).cash());
        assertThrows(ResponseStatusException.class, () -> trading.submit("live", ticket("BUY", "MARKET", 1, null)));
    }

    @Test
    void computesObservationsFromDemoCandles() {
        var instrument = Market.instrument(SYMBOL);
        var report = research.stock("demo", instrument, market.candles("demo", instrument));
        assertEquals(3, report.observations().size());
        assertTrue(report.observations().stream().anyMatch(o -> o.id().equals(report.focus())));
    }

    private Trading.Account account() { return trading.account(market.snapshot("demo")); }

    private static Trading.Ticket ticket(String side, String kind, int shares, BigDecimal trigger) {
        return new Trading.Ticket(UUID.randomUUID(), SYMBOL, side, kind, shares, trigger);
    }
}
