package in.practice.tape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@Profile("accounts")
final class WorkerCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerCoordinator.class);
    private final DataSource source;
    private final TradingMetrics metrics;

    WorkerCoordinator(DataSource source, TradingMetrics metrics) {
        this.source = source;
        this.metrics = metrics;
    }

    <T> Optional<T> run(String worker, Supplier<T> task) {
        // A separate transaction owns the lock. Trading methods keep their own transaction boundaries.
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(1413566533, ?)")) {
                    statement.setInt(1, worker.hashCode());
                    try (var result = statement.executeQuery()) {
                        if (!result.next() || !result.getBoolean(1)) {
                            metrics.workerRun(worker, "busy");
                            return Optional.empty();
                        }
                    }
                }
                T value = task.get();
                metrics.workerRun(worker, "completed");
                return Optional.of(value);
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new DataAccessResourceFailureException("Worker coordination unavailable", failure);
        }
    }

    void scheduled(String worker, Runnable task) {
        try {
            run(worker, () -> { task.run(); return true; });
        } catch (RuntimeException error) {
            metrics.workerRun(worker, "error");
            LOG.atWarn().addKeyValue("worker", worker).addKeyValue("error_type", error.getClass().getSimpleName())
                .log("Scheduled worker failed");
        }
    }
}
