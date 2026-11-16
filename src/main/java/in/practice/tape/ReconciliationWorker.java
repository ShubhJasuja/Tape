package in.practice.tape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("accounts")
class ReconciliationWorker extends OperationsWorker {
    record Account(UUID owner, UUID id) {}
    record Result(boolean busy, int checked, int mismatches, int errors, boolean scanComplete) {}
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationWorker.class);
    private final JdbcTemplate db;
    private final PaperOrders orders;
    private final TradingMetrics metrics;
    private final boolean scheduled;
    private final int batch;
    private final AtomicBoolean busy = new AtomicBoolean();
    private UUID cursor;

    ReconciliationWorker(JdbcTemplate db, PaperOrders orders, TradingMetrics metrics, WorkerCoordinator coordinator,
                         @Value("${tape.reconciliation.scheduled:true}") boolean scheduled,
                         @Value("${tape.reconciliation.batch-size:50}") int batch,
                         @Value("${tape.reconciliation.delay-ms:60000}") long delay) {
        super("tape-reconciliation", delay, 1000, coordinator);
        this.db = db;
        this.orders = orders;
        this.metrics = metrics;
        this.scheduled = scheduled;
        this.batch = checkedBatch(batch);
    }

    @Override public boolean isAutoStartup() { return scheduled; }
    @Override void runOnce() { runBatch(); }

    Result reconcileBatch() {
        return coordinate(this::runBatch).orElseGet(() -> new Result(true, 0, 0, 0, false));
    }

    private Result runBatch() {
        if (!busy.compareAndSet(false, true)) return new Result(true, 0, 0, 0, false);
        try (var correlation = OperationsLog.correlation()) {
            int checked = 0, mismatches = 0, errors = 0;
            boolean complete = false;
            var sample = metrics.start();
            try {
                for (Account account : accounts()) {
                    if (Thread.currentThread().isInterrupted()) break;
                    cursor = account.id();
                    checked++;
                    try {
                        var issues = orders.reconcile(account.owner(), account.id());
                        if (issues.isEmpty()) {
                            metrics.count("tape.reconciliation.accounts", "ok");
                        } else {
                            mismatches++;
                            metrics.count("tape.reconciliation.accounts", "mismatch");
                            LOG.atWarn().addKeyValue("account_ref", OperationsLog.reference(account.id()))
                                .addKeyValue("checks", issues.stream().map(issue -> issue.split(":", 2)[0]).distinct().toList())
                                .log("Synthetic account reconciliation mismatch");
                        }
                    } catch (RuntimeException failure) {
                        errors++;
                        metrics.count("tape.reconciliation.accounts", "error");
                        LOG.atWarn().addKeyValue("account_ref", OperationsLog.reference(account.id()))
                            .addKeyValue("error_type", failure.getClass().getSimpleName())
                            .log("Synthetic account reconciliation failed");
                    }
                }
                if (checked < batch && !Thread.currentThread().isInterrupted()) {
                    cursor = null;
                    complete = true;
                }
            } catch (RuntimeException failure) {
                errors++;
                metrics.count("tape.reconciliation.failures", "batch");
                LOG.atWarn().addKeyValue("error_type", failure.getClass().getSimpleName())
                    .log("Synthetic reconciliation batch failed");
            } finally {
                metrics.stop(sample, "tape.reconciliation.duration", errors > 0 ? "error" : mismatches > 0 ? "mismatch" : "ok");
            }
            return new Result(false, checked, mismatches, errors, complete);
        } finally { busy.set(false); }
    }

    private List<Account> accounts() {
        var map = (org.springframework.jdbc.core.RowMapper<Account>) (rs, row) ->
            new Account(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
        if (cursor == null) return db.query("""
            SELECT user_id,id FROM trading_accounts WHERE mode='DEMO' ORDER BY id LIMIT ?
            """, map, batch);
        return db.query("""
            SELECT user_id,id FROM trading_accounts WHERE mode='DEMO' AND id>? ORDER BY id LIMIT ?
            """, map, cursor, batch);
    }
}
