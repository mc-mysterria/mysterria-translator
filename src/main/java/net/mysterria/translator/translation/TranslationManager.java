package net.mysterria.translator.translation;

import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.ollama.OllamaClient;
import net.mysterria.translator.libretranslate.LibreTranslateClient;
import net.mysterria.translator.util.LanguageDetector;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TranslationManager {

    private final MysterriaTranslator plugin;
    private final OllamaClient ollamaClient;
    private final LibreTranslateClient libreTranslateClient;
    private final String provider;
    private final ScheduledExecutorService scheduler;
    
    private final Map<String, CachedTranslation> translationCache;
    private final Map<UUID, PlayerRateLimit> rateLimits;
    
    private final int cacheExpirySeconds;
    private final int rateLimitMessages;
    private final int rateLimitWindowSeconds;
    private final int minMessageLength;
    private final int maxRetries;
    
    public TranslationManager(MysterriaTranslator plugin, OllamaClient ollamaClient, LibreTranslateClient libreTranslateClient) {
        this.plugin = plugin;
        this.ollamaClient = ollamaClient;
        this.libreTranslateClient = libreTranslateClient;
        this.provider = plugin.getConfig().getString("translation.provider", "ollama");
        this.scheduler = Executors.newScheduledThreadPool(2);
        
        this.translationCache = new ConcurrentHashMap<>();
        this.rateLimits = new ConcurrentHashMap<>();
        
        this.cacheExpirySeconds = plugin.getConfig().getInt("translation.cacheExpirySeconds", 30);
        this.rateLimitMessages = plugin.getConfig().getInt("translation.rateLimitMessages", 2);
        this.rateLimitWindowSeconds = plugin.getConfig().getInt("translation.rateLimitWindowSeconds", 10);
        this.minMessageLength = plugin.getConfig().getInt("translation.minMessageLength", 3);
        this.maxRetries = plugin.getConfig().getInt("translation.maxRetries", 2);
        
        startCacheCleanup();
        startRateLimitCleanup();
    }
    
    public CompletableFuture<TranslationResult> translateForPlayer(String message, Player player) {
        if (message.length() < minMessageLength) {
            return CompletableFuture.completedFuture(
                TranslationResult.noTranslation(message, "Message too short")
            );
        }
        
        String playerLocale = player.locale().toString().toLowerCase();
        
        if (!LanguageDetector.needsTranslation(message, playerLocale)) {
            return CompletableFuture.completedFuture(
                TranslationResult.noTranslation(message, "No translation needed")
            );
        }
        
        if (!canPlayerTranslate(player.getUniqueId())) {
            return CompletableFuture.completedFuture(
                TranslationResult.rateLimited(message)
            );
        }
        
        String targetLang = LanguageDetector.getTargetLanguage(playerLocale);
        LanguageDetector.DetectedLanguage sourceLang = LanguageDetector.detectLanguage(message);
        
        String cacheKey = generateCacheKey(message, sourceLang.getLangCode(), targetLang);
        CachedTranslation cached = translationCache.get(cacheKey);
        
        if (cached != null && !cached.isExpired()) {
            plugin.debug("Using cached translation for: " + message.substring(0, Math.min(20, message.length())));
            return CompletableFuture.completedFuture(
                TranslationResult.success(cached.translation, message, sourceLang.getDisplayName(), getLanguageDisplayName(targetLang))
            );
        }
        
        incrementPlayerUsage(player.getUniqueId());
        
        return translateWithRetry(message, sourceLang.getDisplayName(), getLanguageDisplayName(targetLang), 0)
                .thenApply(translation -> {
                    if (translation != null) {
                        translationCache.put(cacheKey, new CachedTranslation(translation, System.currentTimeMillis()));
                        plugin.debug("Translated and cached: " + message.substring(0, Math.min(20, message.length())));
                        return TranslationResult.success(translation, message, sourceLang.getDisplayName(), getLanguageDisplayName(targetLang));
                    } else {
                        return TranslationResult.failed(message, "Translation service unavailable");
                    }
                });
    }
    
    private CompletableFuture<String> translateWithRetry(String message, String fromLang, String toLang, int attempt) {
        CompletableFuture<String> translationFuture;

        if ("libretranslate".equalsIgnoreCase(provider)) {
            translationFuture = libreTranslateClient.translateAsync(message, fromLang, toLang);
        } else {
            translationFuture = ollamaClient.translateAsync(message, fromLang, toLang);
        }

        return translationFuture.exceptionally(throwable -> {
            plugin.debug("Translation attempt " + (attempt + 1) + " failed with " + provider + ": " + throwable.getMessage());
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(1000 * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return translateWithRetry(message, fromLang, toLang, attempt + 1).join();
            }
            return null;
        });
    }
    
    private boolean canPlayerTranslate(UUID playerId) {
        PlayerRateLimit limit = rateLimits.computeIfAbsent(playerId, k -> new PlayerRateLimit());
        
        long currentTime = System.currentTimeMillis();
        long windowStart = currentTime - (rateLimitWindowSeconds * 1000L);
        
        limit.timestamps.removeIf(timestamp -> timestamp < windowStart);
        
        return limit.timestamps.size() < rateLimitMessages;
    }
    
    private void incrementPlayerUsage(UUID playerId) {
        PlayerRateLimit limit = rateLimits.computeIfAbsent(playerId, k -> new PlayerRateLimit());
        limit.timestamps.add(System.currentTimeMillis());
    }
    
    private String generateCacheKey(String message, String fromLang, String toLang) {
        return fromLang + ":" + toLang + ":" + message.hashCode();
    }
    
    private String getLanguageDisplayName(String langCode) {
        return switch (langCode) {
            case "uk_ua" -> "Ukrainian";
            case "en_us" -> "English";
            default -> "Unknown";
        };
    }
    
    private void startCacheCleanup() {
        scheduler.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            translationCache.entrySet().removeIf(entry -> entry.getValue().isExpired(currentTime, cacheExpirySeconds));
        }, cacheExpirySeconds, cacheExpirySeconds, TimeUnit.SECONDS);
    }
    
    private void startRateLimitCleanup() {
        scheduler.scheduleAtFixedRate(() -> {
            long currentTime = System.currentTimeMillis();
            long windowStart = currentTime - (rateLimitWindowSeconds * 1000L);
            
            rateLimits.values().forEach(limit -> 
                limit.timestamps.removeIf(timestamp -> timestamp < windowStart)
            );
        }, rateLimitWindowSeconds, rateLimitWindowSeconds, TimeUnit.SECONDS);
    }
    
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
    
    public void clearCache() {
        translationCache.clear();
        rateLimits.clear();
    }
    
    private static class CachedTranslation {
        final String translation;
        final long timestamp;
        
        CachedTranslation(String translation, long timestamp) {
            this.translation = translation;
            this.timestamp = timestamp;
        }
        
        boolean isExpired() {
            return isExpired(System.currentTimeMillis(), 30);
        }
        
        boolean isExpired(long currentTime, int expirySeconds) {
            return (currentTime - timestamp) > (expirySeconds * 1000L);
        }
    }
    
    private static class PlayerRateLimit {
        final java.util.List<Long> timestamps = new java.util.concurrent.CopyOnWriteArrayList<>();
    }
}