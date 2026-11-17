package in.practice.tape;

import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Dedicated scheduling keeps the legacy H2 and live market workers disabled in accounts mode. */
abstract class OperationsWorker implements SmartLifecycle {
    private final String name;
    private final long delay;
    private final WorkerCoordinator coordinator;
    private volatile ScheduledExecutorService executor;

    OperationsWorker(String name, long delay, long minimumDelay, WorkerCoordinator coordinator) {
        if (delay < minimumDelay) throw new IllegalArgumentException("Worker delay is too short: " + name);
        this.name = name;
        this.delay = delay;
        this.coordinator = coordinator;
    }

    static int checkedBatch(int batch) {
        if (batch < 1 || batch > 1000) throw new IllegalArgumentException("Batch must be between 1 and 1000");
        return batch;
    }

    abstract void runOnce();

    final <T> java.util.Optional<T> coordinate(java.util.function.Supplier<T> task) {
        return coordinator.run(name, task);
    }

    @Override public synchronized void start() {
        if (isRunning()) return;
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(name).factory());
        executor.scheduleWithFixedDelay(() -> coordinator.scheduled(name, this::runOnce), 0, delay, TimeUnit.MILLISECONDS);
    }

    @Override public synchronized void stop() {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException failure) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override public boolean isRunning() { return executor != null && !executor.isShutdown(); }
}
