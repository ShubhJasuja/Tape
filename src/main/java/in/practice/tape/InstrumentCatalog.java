package in.practice.tape;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@Profile("accounts")
class InstrumentCatalog {
    private static final RowMapper<Listing> ROW = (rs, row) -> {
        var synced = rs.getTimestamp("synchronized_at");
        return new Listing(rs.getObject("id", UUID.class), rs.getString("exchange"), rs.getString("symbol"),
            rs.getString("name"), rs.getString("isin"), rs.getString("instrument_type"),
            rs.getString("listing_status"), rs.getObject("tick_size_paise", Long.class), rs.getString("source"),
            rs.getString("source_key"), synced == null ? null : synced.toInstant(), rs.getBoolean("fictional"),
            rs.getString("synthetic_key") != null && "ACTIVE".equals(rs.getString("listing_status")));
    };

    record Listing(UUID id, String exchange, String tradingSymbol, String name, String isin, String instrumentType,
                   String status, Long tickSizePaise, String source, String sourceKey, Instant synchronizedAt,
                   boolean fictional, boolean demoTradable) {
        @JsonProperty public String symbol() { return exchange + ":" + tradingSymbol; }
        @JsonProperty public String priceAvailability() { return demoTradable ? "SYNTHETIC" : "UNAVAILABLE"; }
        @JsonProperty public String historicalAvailability() { return "UNAVAILABLE"; }
    }
    record Page(List<Listing> instruments, Integer nextOffset) {}

    private final JdbcTemplate db;

    InstrumentCatalog(JdbcTemplate db) { this.db = db; }

    Listing detail(UUID id) {
        return db.query("SELECT * FROM instruments WHERE id=?", ROW, id).stream().findFirst()
            .orElseThrow(() -> PaperOrders.error(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND"));
    }

    Page search(String q, String exchange, String type, UUID watchlistOwner, int offset, int limit) {
        if (q.length() > 100 || offset < 0 || offset > 10_000 || limit < 1 || limit > 100 ||
            !(exchange.isEmpty() || Set.of("NSE", "BSE").contains(exchange)) ||
            !(type.isEmpty() || Set.of("EQUITY", "SME_EQUITY").contains(type))) {
            throw PaperOrders.error(HttpStatus.BAD_REQUEST, "INVALID_CATALOG_FILTER");
        }
        String needle = q.strip().toLowerCase(Locale.ROOT);
        String pattern = "%" + needle.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var sql = new StringBuilder("SELECT i.* FROM instruments i WHERE 1=1");
        var parameters = new ArrayList<>();
        if (!needle.isEmpty()) {
            sql.append("""
                 AND (lower(i.symbol) LIKE ? ESCAPE '!' OR lower(i.name) LIKE ? ESCAPE '!'
                      OR lower(i.isin) LIKE ? ESCAPE '!' OR lower(i.exchange || ':' || i.symbol) LIKE ? ESCAPE '!')
                """);
            parameters.addAll(List.of(pattern, pattern, pattern, pattern));
        }
        if (!exchange.isEmpty()) { sql.append(" AND i.exchange=?"); parameters.add(exchange); }
        if (!type.isEmpty()) { sql.append(" AND i.instrument_type=?"); parameters.add(type); }
        if (watchlistOwner != null) {
            sql.append("""
                 AND EXISTS (SELECT 1 FROM watchlist_items wi JOIN watchlists w ON w.id=wi.watchlist_id
                    WHERE wi.instrument_id=i.id AND w.user_id=? AND w.name='Default')
                """);
            parameters.add(watchlistOwner);
        }
        sql.append("""
             ORDER BY CASE WHEN lower(i.symbol)=? OR lower(i.isin)=? THEN 0 ELSE 1 END,
                 CASE WHEN i.synthetic_key IS NOT NULL THEN 0 ELSE 1 END, i.symbol, i.exchange, i.id
             LIMIT ? OFFSET ?
            """);
        parameters.addAll(List.of(needle, needle, limit + 1, offset));
        var listings = db.query(sql.toString(), ROW, parameters.toArray());
        return listings.size() <= limit ? new Page(listings, null) :
            new Page(List.copyOf(listings.subList(0, limit)), offset + limit <= 10_000 ? offset + limit : null);
    }

    List<Listing> syntheticListings() {
        return db.query("SELECT * FROM instruments WHERE synthetic_key IS NOT NULL AND listing_status='ACTIVE' ORDER BY symbol,exchange", ROW);
    }

    Map<String, Market.Quote> quotes(SyntheticQuotes prices, Instant now) {
        var result = new LinkedHashMap<String, Market.Quote>();
        db.query("""
            SELECT exchange || ':' || symbol,synthetic_key FROM instruments
            WHERE synthetic_key IS NOT NULL AND listing_status='ACTIVE'
            """, rs -> {
            var quote = prices.latest(rs.getString(2), now);
            if (SyntheticQuotes.eligible(quote, now)) result.put(rs.getString(1), quote);
        });
        return result;
    }
}
