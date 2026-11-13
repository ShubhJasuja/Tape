package in.practice.tape;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

interface CatalogFeed {
    record Entry(String sourceKey, String exchange, String symbol, String name, String isin,
                 String instrumentType, String status, Long tickSizePaise) {}
    record Snapshot(String source, Instant asOf, boolean complete, List<Entry> instruments) {}

    Snapshot read() throws IOException;
}
