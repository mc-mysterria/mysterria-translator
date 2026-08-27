package net.mysterria.translator.translation;

import net.mysterria.translator.MysterriaTranslator;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Owns every thread the translation pipeline is allowed to use.
 * <p>
 * Translation providers are called over blocking HTTP. Running those calls on
 * {@link java.util.concurrent.ForkJoinPool#commonPool()} (the default for a bare
 * {@code CompletableFuture.supplyAsync}) is unsafe: the common pool detects blocked
 * workers and keeps spawning compensation threads, so a stalled provider turns every
 * chat message into more threads while the old ones sit in {@code SocketRead}.
 * <p>
 * This pool bounds both the thread count <em>and</em> the number of in-flight provider
 * requests with a single knob. When the queue is full new requests are rejected
 * immediately rather than queued forever — callers fall back to delivering the
 * untranslated message, which is the correct behaviour under load.
 */
public final class TranslationThreadPool {

    private static final int MIN_POOL_SIZE = 1;
    private static final int MAX_POOL_SIZE = 64;
    private static final int MIN_QUEUE_CAPACITY = 16;
    private static final long REJECTION_LOG_COOLDOWN_MS = 60_000L;

    private final MysterriaTranslator plugin;
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService scheduler;

    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong lastRejectionLog = new AtomicLong();

    public TranslationThreadPool(MysterriaTranslator plugin) {
        this.plugin = plugin;

        int size = clampPoolSize(plugin.getConfig().getInt("translation.threadPool.size", 6));
        int queueCapacity = Math.max(MIN_QUEUE_CAPACITY,
                plugin.getConfig().getInt("translation.threadPool.queueCapacity", 256));

        this.worker = new ThreadPoolExecutor(
                size, size,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedFactory("MysterriaTranslator-Worker"),
                new ThreadPoolExecutor.AbortPolicy());
        this.worker.allowCoreThreadTimeOut(true);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(namedFactory("MysterriaTranslator-Timer"));

        plugin.debug("Translation thread pool initialized with size=" + size + ", queueCapacity=" + queueCapacity);
    }

    /**
     * Runs a blocking provider call on the bounded pool.
     * <p>
     * If the pool is saturated the returned future fails immediately with
     * {@link RejectedExecutionException} instead of blocking the caller.
     */
    public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        try {
            return CompletableFuture.supplyAsync(supplier, worker);
        } catch (RejectedExecutionException e) {
            noteRejection();
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Completes the returned future exceptionally with a {@link TimeoutException} if
     * {@code future} has not finished in time.
     * <p>
     * This is a safety net that keeps the chat pipeline moving; it cannot interrupt a
     * thread already blocked in a socket read, which is why every provider client also
     * sets its own connect/read timeouts.
     *
     * @param description human-readable name of the operation, used in the timeout message
     */
    public <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, long timeout, TimeUnit unit, String description) {
        if (future.isDone() || timeout <= 0) {
            return future;
        }

        CompletableFuture<T> guarded = new CompletableFuture<>();
        ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = scheduler.schedule(
                    () -> guarded.completeExceptionally(new TimeoutException(
                            description + " timed out after " + unit.toSeconds(timeout) + "s")),
                    timeout, unit);
        } catch (RejectedExecutionException e) {
            // Pool is shutting down — let the original future decide.
            return future;
        }

        future.whenComplete((value, throwable) -> {
            timeoutTask.cancel(false);
            if (throwable != null) {
                guarded.completeExceptionally(throwable);
            } else {
                guarded.complete(value);
            }
        });
        return guarded;
    }

    /**
     * An executor that runs the task on the worker pool after a delay, used for retry
     * back-off. Replaces {@code Thread.sleep} inside a completion callback, which would
     * otherwise hold a pool thread for the whole back-off.
     */
    public Executor delayedExecutor(long delay, TimeUnit unit) {
        return command -> {
            try {
                scheduler.schedule(() -> {
                    try {
                        worker.execute(command);
                    } catch (RejectedExecutionException e) {
                        noteRejection();
                        // Run inline rather than dropping the task: the continuation only
                        // builds the next future, it does not perform blocking I/O itself.
                        command.run();
                    }
                }, delay, unit);
            } catch (RejectedExecutionException e) {
                command.run();
            }
        };
    }

    /** Applies a new pool size from config without discarding in-flight work. */
    public void reconfigure() {
        int size = clampPoolSize(plugin.getConfig().getInt("translation.threadPool.size", 6));
        if (size == worker.getCorePoolSize()) {
            return;
        }
        // Grow before shrinking so the executor never sees max < core.
        if (size > worker.getMaximumPoolSize()) {
            worker.setMaximumPoolSize(size);
            worker.setCorePoolSize(size);
        } else {
            worker.setCorePoolSize(size);
            worker.setMaximumPoolSize(size);
        }
        plugin.getLogger().info("Translation thread pool resized to " + size + " thread(s)");
    }

    public void shutdown() {
        worker.shutdownNow();
        scheduler.shutdownNow();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Translation worker pool did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String stats() {
        return "active=" + worker.getActiveCount()
                + ", poolSize=" + worker.getPoolSize()
                + ", queued=" + worker.getQueue().size()
                + ", completed=" + worker.getCompletedTaskCount()
                + ", rejected=" + rejectedCount.get();
    }

    private void noteRejection() {
        long total = rejectedCount.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastRejectionLog.get();
        if (now - last >= REJECTION_LOG_COOLDOWN_MS && lastRejectionLog.compareAndSet(last, now)) {
            plugin.getLogger().warning("Translation request queue is saturated — messages are being delivered "
                    + "untranslated (" + total + " dropped so far). " + stats()
                    + ". Consider raising translation.threadPool.size or checking provider latency.");
        }
    }

    private static int clampPoolSize(int configured) {
        return Math.max(MIN_POOL_SIZE, Math.min(MAX_POOL_SIZE, configured));
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
