package in.practice.tape;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile("accounts")
class TradingMetrics {
    private final MeterRegistry registry;
    private final AtomicLong oldestQuote = new AtomicLong();

    TradingMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("tape.synthetic.quote.age", oldestQuote, tick ->
            tick.get() == 0 ? Double.POSITIVE_INFINITY : Math.max(0, (System.currentTimeMillis() - tick.get()) / 1000.0));
        for (String outcome : new String[]{"ok", "mismatch", "error"}) {
            registry.counter("tape.reconciliation.accounts", "outcome", outcome);
        }
    }

    void count(String name, String outcome) { registry.counter(name, "outcome", outcome).increment(); }

    void workerRun(String worker, String outcome) {
        registry.counter("tape.worker.runs", "worker", worker, "outcome", outcome).increment();
    }

    Timer.Sample start() { return Timer.start(registry); }

    void stop(Timer.Sample sample, String name, String outcome) {
        sample.stop(registry.timer(name, "outcome", outcome));
    }

    void quoteSample(long oldest, Instant now) {
        oldestQuote.set(oldest);
        if (oldest <= 0 || oldest > now.toEpochMilli() || now.toEpochMilli() - oldest > 6_000) {
            count("tape.synthetic.quote.samples", "stale");
        } else count("tape.synthetic.quote.samples", "fresh");
    }

    void executionCommitted() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { count("tape.orders.executions", "committed"); }
        });
    }
}
