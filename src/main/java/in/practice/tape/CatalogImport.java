package in.practice.tape;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@Profile("catalog-import")
class CatalogImport {
    @Bean
    ApplicationRunner importCatalog(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, Environment environment,
                                    @Value("${tape.catalog.file}") String file) {
        if (!Set.of(environment.getActiveProfiles()).equals(Set.of("catalog-import"))) {
            throw new IllegalStateException("Run catalog-import without another profile");
        }
        return ignored -> {
            var result = new CatalogSync(db, tx).synchronize(() -> read(Path.of(file), json));
            LoggerFactory.getLogger(CatalogImport.class).info("Fictional catalog sync applied={}, rows={}, inactivated={}",
                result.applied(), result.rows(), result.inactivated());
        };
    }

    static CatalogFeed.Snapshot read(Path path, ObjectMapper json) throws IOException {
        try (var input = Files.newInputStream(path)) {
            int maxBytes = 4 * 1024 * 1024;
            byte[] content = input.readNBytes(maxBytes + 1);
            if (content.length > maxBytes) throw new IOException("Catalog file exceeds 4 MiB");
            return json.readValue(content, CatalogFeed.Snapshot.class);
        }
    }
}
