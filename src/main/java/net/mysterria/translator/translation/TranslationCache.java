package net.mysterria.translator.translation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages caching of translation results with automatic expiry.
 * Uses a concurrent hash map for thread-safe access and scheduled cleanup.
 */
public class TranslationCache {

    private final Map<String, CachedTranslation> cache;
    private final ScheduledExecutorService scheduler;
    private final int cacheExpirySeconds;

    public TranslationCache(int cacheExpirySeconds) {
        this.cache = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.cacheExpirySeconds = cacheExpirySeconds;
        startCleanup();
    }

    /**
     * Retrieves a cached translation if it exists and hasn't expired.
     *
     * @param message   The original message
     * @param fromLang  Source language code
     * @param toLang    Target language code
     * @return The cached translation, or null if not found or expired
     */
    public String get(String message, String fromLang, String toLang) {
        String cacheKey = generateCacheKey(message, fromLang, toLang);
        CachedTranslation cached = cache.get(cacheKey);

        if (cached != null && !cached.isExpired(cacheExpirySeconds)) {
            return cached.translation();
        }

        return null;
    }

    /**
     * Stores a translation in the cache.
     *
     * @param message      The original message
     * @param fromLang     Source language code
     * @param toLang       Target language code
     * @param translation  The translated text
     */
    public void put(String message, String fromLang, String toLang, String translation) {
        String cacheKey = generateCacheKey(message, fromLang, toLang);
        cache.put(cacheKey, new CachedTranslation(translation, System.currentTimeMillis()));
    }

    /**
     * Clears all cached translations.
     */
    public void clear() {
        cache.clear();
    }

    /**
     * Shuts down the cache cleanup scheduler.
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

    private String generateCacheKey(String message, String fromLang, String toLang) {
        return fromLang + ":" + toLang + ":" + message.hashCode();
    }

    private void startCleanup() {
        scheduler.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired(currentTime, cacheExpirySeconds));
        }, cacheExpirySeconds, cacheExpirySeconds, TimeUnit.SECONDS);
    }

    private record CachedTranslation(String translation, long timestamp) {

        boolean isExpired(int expirySeconds) {
            return isExpired(System.currentTimeMillis(), expirySeconds);
        }

        boolean isExpired(long currentTime, int expirySeconds) {
            return (currentTime - timestamp) > (expirySeconds * 1000L);
        }
    }
}
