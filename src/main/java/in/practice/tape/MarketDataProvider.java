
package in.practice.tape;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

interface MarketDataProvider {
    enum Capability { CATALOG, CATALOG_STORAGE, QUOTES, HISTORY, INTRADAY, STREAM, DERIVATIVES, OPTION_CHAIN, WATCHLIST, CALENDAR }
    enum Segment { EQUITY, INDEX, FUTURES, OPTIONS }
    enum Interval {
        MINUTE("minutes/1", 7), FIVE_MINUTES("minutes/5", 28), DAILY("days/1", 365);
        final String path;
        final int days;
        Interval(String path, int days) { this.path = path; this.days = days; }
    }

    record Instrument(String source, String key, String exchange, String segment, String symbol, String name,
                      String isin, Segment type, String status, LocalDate expiry, String underlyingKey,
                      String underlyingType, BigDecimal strike, String side, Long lotSize, BigDecimal tickSizePaise,
                      BigDecimal quantityMultiplier, Long minimumLot, Long freezeQuantity, Boolean weekly) {}
    record Page(List<Instrument> instruments, Integer nextPage) {}
    record Quote(String source, String exchange, String key, Instant exchangeAt, Instant providerAt, Instant receivedAt,
                 String freshness, Long ageMillis, String marketStatus, BigDecimal last, BigDecimal change,
                 BigDecimal percentChange, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                 Long volume) {}
    record Candle(Instant exchangeAt, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume) {}
    record History(String source, String exchange, String key, Instant receivedAt, Interval interval, List<Candle> candles) {}
    record Greeks(BigDecimal iv, BigDecimal delta, BigDecimal gamma, BigDecimal theta, BigDecimal vega,
                  BigDecimal probabilityOfProfit) {}
    record Leg(String key, BigDecimal last, Long volume, Long openInterest, BigDecimal bid, BigDecimal ask,
               Long bidQuantity, Long askQuantity, BigDecimal close, Long previousOpenInterest, Greeks greeks) {}
    record Strike(BigDecimal strike, Leg call, Leg put, BigDecimal underlyingSpot, BigDecimal putCallRatio) {}
    record Chain(String source, String exchange, String underlying, LocalDate expiry, Instant receivedAt, List<Strike> strikes) {}
    record Stream(String status, Quote quote) {}
    record Streams(String status, Map<String, Quote> quotes) {}
    record TradingWindow(Instant opensAt, Instant closesAt) {}
    record Session(String source, String exchange, LocalDate date, String status, String holiday, List<TradingWindow> windows) {}

    Page search(String query, String exchange, Segment segment, LocalDate expiry, int page);
    Quote quote(String key);
    History candles(String key, Interval interval, LocalDate from, LocalDate to);
    History intraday(String key, Interval interval);
    List<Instrument> options(String underlying, LocalDate expiry);
    Chain chain(String underlying, LocalDate expiry);
    String marketStatus(String exchange);
    Session session(String exchange, LocalDate date);
    Stream stream(String key);
    Streams streams(Set<String> keys);
    String source();

    default String exchange(String key) { return key.substring(0, 3); }
}
