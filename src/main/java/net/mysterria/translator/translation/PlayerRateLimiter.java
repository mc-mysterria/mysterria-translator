package net.mysterria.translator.translation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages per-player rate limiting for translation requests.
 * Uses a sliding window algorithm to track usage within a time window.
 */
public class PlayerRateLimiter {

    private final Map<UUID, PlayerRateLimit> rateLimits;
    private final ScheduledExecutorService scheduler;
    private final int rateLimitMessages;
    private final int rateLimitWindowSeconds;

    public PlayerRateLimiter(int rateLimitMessages, int rateLimitWindowSeconds) {
        this.rateLimits = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.rateLimitMessages = rateLimitMessages;
        this.rateLimitWindowSeconds = rateLimitWindowSeconds;
        startCleanup();
    }

    /**
     * Checks if a player can make a translation request.
     *
     * @param playerId The player's UUID
     * @return true if the player is within rate limits, false otherwise
     */
    public boolean canTranslate(UUID playerId) {
        PlayerRateLimit limit = rateLimits.computeIfAbsent(playerId, k -> new PlayerRateLimit());

        long currentTime = System.currentTimeMillis();
        long windowStart = currentTime - (rateLimitWindowSeconds * 1000L);

        limit.timestamps.removeIf(timestamp -> timestamp < windowStart);

        return limit.timestamps.size() < rateLimitMessages;
    }

    /**
     * Records a translation request for a player.
     *
     * @param playerId The player's UUID
     */
    public void recordUsage(UUID playerId) {
        PlayerRateLimit limit = rateLimits.computeIfAbsent(playerId, k -> new PlayerRateLimit());
        limit.timestamps.add(System.currentTimeMillis());
    }

    /**
     * Clears all rate limit data.
     */
    public void clear() {
        rateLimits.clear();
    }

    /**
     * Shuts down the rate limit cleanup scheduler.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void startCleanup() {
        scheduler.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            long windowStart = currentTime - (rateLimitWindowSeconds * 1000L);

            rateLimits.values().forEach(limit ->
                    limit.timestamps.removeIf(timestamp -> timestamp < windowStart)
            );
        }, rateLimitWindowSeconds, rateLimitWindowSeconds, TimeUnit.SECONDS);
    }

    private static class PlayerRateLimit {
        final List<Long> timestamps = new CopyOnWriteArrayList<>();
    }
}
