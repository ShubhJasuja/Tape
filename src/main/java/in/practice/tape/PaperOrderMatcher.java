package in.practice.tape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Profile("accounts")
@ConditionalOnProperty(name = {"tape.synthetic-trading.enabled", "tape.synthetic-trading.matcher"}, havingValue = "true")
class PaperOrderMatcher implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(PaperOrderMatcher.class);
    private final PaperOrders orders;
    private volatile ScheduledExecutorService worker;

    PaperOrderMatcher(PaperOrders orders) { this.orders = orders; }

    // Scheduling just this worker keeps the legacy Trading and Upstox schedulers dormant.
    @Override public void start() {
        if (isRunning()) return;
        worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("tape-synthetic-matcher").factory());
        worker.scheduleWithFixedDelay(this::match, 0, 2, TimeUnit.SECONDS);
    }

    @Override public void stop() {
        var running = worker;
        if (running == null) return;
        running.shutdown();
        try {
            if (!running.awaitTermination(10, TimeUnit.SECONDS)) running.shutdownNow();
        } catch (InterruptedException failure) {
            running.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            worker = null;
        }
    }

    @Override public boolean isRunning() { return worker != null && !worker.isShutdown(); }

    private void match() {
        try {
            for (var candidate : orders.candidates()) {
                if (!isRunning() || Thread.currentThread().isInterrupted()) break;
                try {
                    orders.match(candidate);
                } catch (RuntimeException failure) {
                    LOG.error("Synthetic match failed for order {}", candidate.order(), failure);
                }
            }
        } catch (RuntimeException failure) {
            LOG.error("Synthetic matcher could not read open orders", failure);
        }
    }
}
