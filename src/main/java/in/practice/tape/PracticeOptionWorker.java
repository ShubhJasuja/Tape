package in.practice.tape;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

@Component
@Profile("accounts")
@ConditionalOnProperty(name = "tape.practice-options.enabled", havingValue = "true")
final class PracticeOptionWorker extends OperationsWorker {
    private final PracticeOptions options;
    private UUID cursor;
    private PracticeOptions.Work expiryCursor;
    private UUID markCursor;

    PracticeOptionWorker(PracticeOptions options) {
        super(options.isFutures() ? "tape-practice-futures" : "tape-practice-options", 2000, 1000);
        this.options = options;
    }

    @Override void runOnce() {
        try {
            var batch = options.openOrders(cursor);
            if (batch.size() < 100) cursor = null;
            else cursor = batch.getLast().item();
            for (var work : batch) attempt(options::match, work);
            if (options.isFutures()) {
                var due = options.dueFutures(markCursor);
                markCursor = due.size() < 100 ? null : due.getLast().account();
                for (var work : due) attempt(options::markFutures, work);
                return;
            }
            var expiries = options.expiredPositions(expiryCursor);
            expiryCursor = expiries.size() < 100 ? null : expiries.getLast();
            for (var work : expiries) attempt(options::settle, work);
        } catch (RuntimeException failure) {
            report(failure);
        }
    }

    private static void attempt(Consumer<PracticeOptions.Work> action, PracticeOptions.Work work) {
        if (Thread.currentThread().isInterrupted()) return;
        try { action.accept(work); }
        catch (RuntimeException failure) { report(failure); }
    }

    private static void report(RuntimeException failure) {
        LoggerFactory.getLogger(PracticeOptionWorker.class).atWarn()
            .addKeyValue("error_type", failure.getClass().getSimpleName()).log("Practice derivatives lifecycle operation failed");
    }
}
