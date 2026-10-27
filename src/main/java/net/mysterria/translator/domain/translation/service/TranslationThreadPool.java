package net.mysterria.translator.domain.translation.service;

import net.mysterria.translator.config.models.TranslationSettings;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class TranslationThreadPool {

    private static final long REJECTION_LOG_COOLDOWN_MS = 60_000L;

    private final Logger logger;
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService timer;
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong lastRejectionLog = new AtomicLong();

    public TranslationThreadPool(Logger logger, TranslationSettings settings) {
        this.logger = logger;
        this.worker = new ThreadPoolExecutor(settings.getThreadPoolSize(), settings.getThreadPoolSize(), 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.getQueueCapacity()),
                Thread.ofPlatform().name("MysterriaTranslator-Worker-", 1).daemon().factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.worker.allowCoreThreadTimeOut(true);
        this.timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("MysterriaTranslator-Timer").daemon().factory());
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, worker);
        } catch (RejectedExecutionException e) {
            noteRejection();
            return CompletableFuture.failedFuture(e);
        }
    }

    public <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, Duration timeout, String operation) {
        if (future.isDone()) {
            return future;
        }
        CompletableFuture<T> guarded = new CompletableFuture<>();
        ScheduledFuture<?> deadline;
        try {
            deadline = timer.schedule(() -> guarded.completeExceptionally(
                    new TimeoutException(operation + " timed out after " + timeout.toSeconds() + "s")), timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return future;
        }
        future.whenComplete((value, failure) -> {
            deadline.cancel(false);
            if (failure != null) {
                guarded.completeExceptionally(failure);
            } else {
                guarded.complete(value);
            }
        });
        return guarded;
    }

    public Executor delayed(long delayMillis) {
        return command -> {
            try {
                timer.schedule(command, delayMillis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                command.run();
            }
        };
    }

    public void resize(TranslationSettings settings) {
        int size = settings.getThreadPoolSize();
        if (size > worker.getMaximumPoolSize()) {
            worker.setMaximumPoolSize(size);
            worker.setCorePoolSize(size);
        } else {
            worker.setCorePoolSize(size);
            worker.setMaximumPoolSize(size);
        }
    }

    public void shutdown() {
        worker.shutdownNow();
        timer.shutdownNow();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Translation workers did not stop within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String stats() {
        return "active " + worker.getActiveCount() + "/" + worker.getMaximumPoolSize()
                + ", queued " + worker.getQueue().size()
                + ", completed " + worker.getCompletedTaskCount()
                + ", dropped " + rejected.get();
    }

    private void noteRejection() {
        long total = rejected.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastRejectionLog.get();
        if (now - last >= REJECTION_LOG_COOLDOWN_MS && lastRejectionLog.compareAndSet(last, now)) {
            logger.warn("Translation queue is full, messages go out untranslated ({} dropped so far, {}). Raise translation.threadPool.size or check engine latency.", total, stats());
        }
    }
}
