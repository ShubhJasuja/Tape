package in.practice.tape;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class MatcherReliabilityTest {
    @Test
    void matcherDoesNotOverlapAndRedactsFailureWhileProcessingNextOrder(CapturedOutput output) throws Exception {
        var engine = mock(PaperOrders.class);
        var first = candidate();
        var second = candidate();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String sensitive = "private-password=" + UUID.randomUUID();
        when(engine.candidates(null, 2)).thenReturn(List.of(first, second));
        doAnswer(ignored -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
            throw new IllegalStateException(sensitive);
        }).when(engine).match(first);
        var registry = new SimpleMeterRegistry();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var matcher = new PaperOrderMatcher(engine, new SyntheticQuotes(), new TradingMetrics(registry), 2, 1000);
            var run = executor.submit(matcher::runOnce);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                matcher.runOnce();
                verify(engine, times(1)).candidates(null, 2);
            } finally { release.countDown(); }
            run.get(5, TimeUnit.SECONDS);
            verify(engine).match(second);
            matcher.runOnce();
            verify(engine).candidates(second, 2);
            assertEquals(1, registry.get("tape.matcher.failures").tag("outcome", "order").counter().count());
            assertEquals(1, registry.get("tape.matcher.duration").tag("outcome", "error").timer().count());
            assertTrue(output.getAll().contains("Synthetic order match failed"));
            assertFalse(output.getAll().contains(sensitive));
            assertFalse(output.getAll().contains(first.order().toString()));
        } finally { registry.close(); }
    }

    @Test
    void missingAndStaleSyntheticSamplesRaiseFreshnessSignals() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new TradingMetrics(registry);
            var now = java.time.Instant.now();
            metrics.quoteSample(now.toEpochMilli(), now);
            assertTrue(registry.get("tape.synthetic.quote.age").gauge().value() < 6);
            metrics.quoteSample(now.toEpochMilli() - 7000, now);
            assertTrue(registry.get("tape.synthetic.quote.age").gauge().value() >= 7);
            metrics.quoteSample(0, now);
            assertTrue(Double.isInfinite(registry.get("tape.synthetic.quote.age").gauge().value()));
            assertEquals(2, registry.get("tape.synthetic.quote.samples").tag("outcome", "stale").counter().count());
        } finally { registry.close(); }
    }

    private static PaperOrders.Candidate candidate() {
        return new PaperOrders.Candidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
