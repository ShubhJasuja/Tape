package in.practice.tape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("accounts")
@ConditionalOnProperty(name = {"tape.synthetic-trading.enabled", "tape.synthetic-trading.matcher"}, havingValue = "true")
class PaperOrderMatcher extends OperationsWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaperOrderMatcher.class);
    private final PaperOrders orders;
    private final SyntheticQuotes quotes;
    private final TradingMetrics metrics;
    private final int batch;
    private final AtomicBoolean busy = new AtomicBoolean();
    private PaperOrders.Candidate cursor;

    PaperOrderMatcher(PaperOrders orders, SyntheticQuotes quotes, TradingMetrics metrics, WorkerCoordinator coordinator,
                      @Value("${tape.synthetic-trading.batch-size:100}") int batch,
                      @Value("${tape.synthetic-trading.match-delay-ms:2000}") long delay) {
        super("tape-synthetic-matcher", delay, 1000, coordinator);
        this.orders = orders;
        this.quotes = quotes;
        this.metrics = metrics;
        this.batch = checkedBatch(batch);
    }

    @Override void runOnce() {
        if (!busy.compareAndSet(false, true)) return;
        try (var correlation = OperationsLog.correlation()) {
            var sample = metrics.start();
            String outcome = "ok";
            try {
                Instant now = Instant.now();
                var snapshot = quotes.snapshot(now);
                metrics.quoteSample(snapshot.size() != Market.INSTRUMENTS.size() ||
                    snapshot.values().stream().anyMatch(quote -> !SyntheticQuotes.eligible(quote, now))
                    ? 0 : snapshot.values().stream().mapToLong(Market.Quote::time).min().orElse(0), now);
                var candidates = orders.candidates(cursor, batch);
                if (candidates.isEmpty()) cursor = null;
                for (var candidate : candidates) {
                    if (Thread.currentThread().isInterrupted()) break;
                    cursor = candidate;
                    try {
                        orders.match(candidate);
                    } catch (RuntimeException failure) {
                        outcome = "error";
                        metrics.count("tape.matcher.failures", "order");
                        LOG.atWarn().addKeyValue("order_ref", OperationsLog.reference(candidate.order()))
                            .addKeyValue("error_type", failure.getClass().getSimpleName())
                            .log("Synthetic order match failed");
                    }
                }
            } catch (RuntimeException failure) {
                outcome = "error";
                metrics.count("tape.matcher.failures", "batch");
                LOG.atWarn().addKeyValue("error_type", failure.getClass().getSimpleName())
                    .log("Synthetic matcher batch failed");
            } finally {
                metrics.stop(sample, "tape.matcher.duration", outcome);
            }
        } finally { busy.set(false); }
    }
}
