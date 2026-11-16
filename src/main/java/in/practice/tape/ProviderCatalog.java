
package in.practice.tape;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static in.practice.tape.MarketDataAccess.failure;

@Service
@Profile("accounts")
class ProviderCatalog {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    ProviderCatalog(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    void synchronize(UUID owner, String source, List<MarketDataProvider.Instrument> rows) {
        if (!"UPSTOX".equals(source)) throw conflict();
        for (var row : rows) {
            if (!source.equals(row.source()) || !UpstoxMarketData.validKey(row.key())) throw conflict();
            String identity = String.join("|", row.type().name(), row.segment(), Objects.toString(row.isin(), ""),
                Objects.toString(row.underlyingKey(), ""), Objects.toString(row.expiry(), ""),
                row.strike() == null ? "" : row.strike().stripTrailingZeros().toPlainString(), Objects.toString(row.side(), ""));
            try {
                int changed = db.update("""
                    INSERT INTO market_instrument_cache(user_id, source, instrument_key, identity, instrument)
                    VALUES (?,?,?,?,?::jsonb)
                    ON CONFLICT (user_id, source, instrument_key) DO UPDATE
                    SET instrument=excluded.instrument, synchronized_at=clock_timestamp()
                    WHERE market_instrument_cache.identity=excluded.identity
                    """, owner, source, row.key(), identity, json.writeValueAsString(row));
                if (changed != 1) throw conflict();
            } catch (JsonProcessingException e) {
                throw failure(HttpStatus.BAD_GATEWAY, "MARKET_INVALID_RESPONSE");
            }
        }
    }

    MarketDataProvider.Instrument instrument(UUID owner, String source, String key) {
        return db.queryForList("""
            SELECT instrument::text FROM market_instrument_cache WHERE user_id=? AND source=? AND instrument_key=?
            """, String.class, owner, source, key).stream()
            .findFirst().map(this::parse).orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "MARKET_INSTRUMENT_UNAVAILABLE"));
    }

    private MarketDataProvider.Instrument parse(String row) {
        try {
            var i = json.readValue(row, MarketDataProvider.Instrument.class);
            if (i.expiry() != null && i.expiry().isBefore(LocalDate.now(Clock.system(UpstoxMarketData.IST)))) {
                return new MarketDataProvider.Instrument(i.source(), i.key(), i.exchange(), i.segment(), i.symbol(), i.name(),
                    i.isin(), i.type(), "EXPIRED", i.expiry(), i.underlyingKey(), i.underlyingType(), i.strike(), i.side(),
                    i.lotSize(), i.tickSizePaise(), i.quantityMultiplier(), i.minimumLot(), i.freezeQuantity(), i.weekly());
            }
            return i;
        } catch (JsonProcessingException e) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_STORAGE_UNAVAILABLE");
        }
    }

    private static MarketDataAccess.Rejected conflict() {
        return failure(HttpStatus.CONFLICT, "MARKET_INSTRUMENT_IDENTITY_CONFLICT");
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("accounts & accounts-local")
class ProviderCatalogImport {
    @Bean
    ApplicationRunner syncProviderCatalog(Environment env, MarketDataAccess access, MarketDataProvider provider, ProviderCatalog catalog) {
        return arguments -> {
            String query = env.getProperty("TAPE_MARKET_CATALOG_SYNC_QUERY", "");
            if (query.isBlank()) return;
            try {
                var owner = access.catalogOwner();
                query = MarketDataAccess.query(query);
                var segment = MarketDataProvider.Segment.valueOf(env.getProperty("TAPE_MARKET_CATALOG_SYNC_SEGMENT", "EQUITY"));
                if (segment == MarketDataProvider.Segment.FUTURES || segment == MarketDataProvider.Segment.OPTIONS) {
                    access.require(owner, MarketDataProvider.Capability.DERIVATIVES);
                }
                int page = 1;
                var instruments = new java.util.LinkedHashMap<String, MarketDataProvider.Instrument>();
                while (true) {
                    var result = provider.search(query, "", segment, null, page);
                    for (var item : result.instruments()) {
                        var previous = instruments.putIfAbsent(item.key(), item);
                        if (previous != null && !previous.equals(item)) throw MarketDataAccess.invalid();
                    }
                    if (result.nextPage() == null) break;
                    if (page == 10 || result.nextPage() != page + 1) throw MarketDataAccess.invalid();
                    page++;
                }
                catalog.synchronize(owner, provider.source(), List.copyOf(instruments.values()));
            } catch (MarketDataAccess.Rejected error) {
                throw new IllegalStateException("Provider catalog sync failed: " + error.failure.code());
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException("Provider catalog sync configuration is invalid");
            }
        };
    }
}
