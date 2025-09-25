package net.mysterria.translator.translation;

import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.engine.ollama.OllamaClient;
import net.mysterria.translator.engine.libretranslate.LibreTranslateClient;
import net.mysterria.translator.engine.gemini.GeminiClient;
import net.mysterria.translator.util.LanguageDetector;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TranslationManager {

    private final MysterriaTranslator plugin;
    private final OllamaClient ollamaClient;
    private final LibreTranslateClient libreTranslateClient;
    private final GeminiClient geminiClient;
    private final String provider;
    private final ScheduledExecutorService scheduler;
    
    private final Map<String, CachedTranslation> translationCache;
    private final Map<UUID, PlayerRateLimit> rateLimits;
    
    private final int cacheExpirySeconds;
    private final int rateLimitMessages;
    private final int rateLimitWindowSeconds;
    private final int minMessageLength;
    private final int maxRetries;
    
    public TranslationManager(MysterriaTranslator plugin, OllamaClient ollamaClient, LibreTranslateClient libreTranslateClient, GeminiClient geminiClient) {
        this.plugin = plugin;
        this.ollamaClient = ollamaClient;
        this.libreTranslateClient = libreTranslateClient;
        this.geminiClient = geminiClient;
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

        // For Gemini, use automatic language detection instead of manual detection
        String sourceLangCode;
        String sourceLangDisplay;
        if ("gemini".equalsIgnoreCase(provider)) {
            sourceLangCode = "auto";
            sourceLangDisplay = "Auto-detected";
        } else {
            LanguageDetector.DetectedLanguage sourceLang = LanguageDetector.detectLanguage(message);
            sourceLangCode = sourceLang.getLangCode();
            sourceLangDisplay = sourceLang.getDisplayName();
        }

        String cacheKey = generateCacheKey(message, sourceLangCode, targetLang);
        CachedTranslation cached = translationCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(
                TranslationResult.success(cached.translation, message, sourceLangDisplay, getLanguageDisplayName(targetLang))
            );
        }

        incrementPlayerUsage(player.getUniqueId());

        return translateWithRetry(message, sourceLangCode, targetLang, 0)
                .thenApply(translation -> {
                    if (translation != null) {
                        translationCache.put(cacheKey, new CachedTranslation(translation, System.currentTimeMillis()));
                        plugin.debug("Translated: " + translation);
                        return TranslationResult.success(translation, message, sourceLangDisplay, getLanguageDisplayName(targetLang));
                    } else {
                        return TranslationResult.failed(message, "Translation service unavailable");
                    }
                });
    }

    public CompletableFuture<Map<String, TranslationResult>> translateForMultiplePlayers(String message, Set<Player> players) {
        if (message.length() < minMessageLength) {
            Map<String, TranslationResult> results = new ConcurrentHashMap<>();
            for (Player player : players) {
                results.put(player.getUniqueId().toString(),
                    TranslationResult.noTranslation(message, "Message too short"));
            }
            return CompletableFuture.completedFuture(results);
        }

        // For Gemini, use automatic language detection instead of manual detection
        String sourceLangCode;
        String sourceLangDisplay;
        if ("gemini".equalsIgnoreCase(provider)) {
            sourceLangCode = "auto";
            sourceLangDisplay = "Auto-detected";
        } else {
            LanguageDetector.DetectedLanguage sourceLang = LanguageDetector.detectLanguage(message);
            sourceLangCode = sourceLang.getLangCode();
            sourceLangDisplay = sourceLang.getDisplayName();
        }
        Map<String, Set<Player>> playersByTargetLang = new ConcurrentHashMap<>();
        Map<String, TranslationResult> results = new ConcurrentHashMap<>();

        // Group players by target language and filter out those who don't need translation
        for (Player player : players) {
            String playerLocale = player.locale().toString().toLowerCase();

            if (!LanguageDetector.needsTranslation(message, playerLocale)) {
                results.put(player.getUniqueId().toString(),
                    TranslationResult.noTranslation(message, "No translation needed"));
                continue;
            }

            if (!canPlayerTranslate(player.getUniqueId())) {
                results.put(player.getUniqueId().toString(),
                    TranslationResult.rateLimited(message));
                continue;
            }

            String targetLang = LanguageDetector.getTargetLanguage(playerLocale);
            String cacheKey = generateCacheKey(message, sourceLangCode, targetLang);
            CachedTranslation cached = translationCache.get(cacheKey);

            if (cached != null && !cached.isExpired()) {
                results.put(player.getUniqueId().toString(),
                    TranslationResult.success(cached.translation, message, sourceLangDisplay, getLanguageDisplayName(targetLang)));
                continue;
            }

            // Group by target language for batch translation
            playersByTargetLang.computeIfAbsent(targetLang, k -> ConcurrentHashMap.newKeySet()).add(player);
            incrementPlayerUsage(player.getUniqueId());
        }

        // If all players already have results, return immediately
        if (playersByTargetLang.isEmpty()) {
            return CompletableFuture.completedFuture(results);
        }

        // Translate once per unique target language
        CompletableFuture<Void> allTranslations = CompletableFuture.allOf(
            playersByTargetLang.entrySet().stream().map(entry -> {
                String targetLang = entry.getKey();
                Set<Player> playersForLang = entry.getValue();

                return translateWithRetry(message, sourceLangCode, targetLang, 0)
                    .thenAccept(translation -> {
                        if (translation != null) {
                            String cacheKey = generateCacheKey(message, sourceLangCode, targetLang);
                            translationCache.put(cacheKey, new CachedTranslation(translation, System.currentTimeMillis()));
                            plugin.debug("Translated: " + translation);

                            // Apply the same translation to all players with this target language
                            for (Player player : playersForLang) {
                                results.put(player.getUniqueId().toString(),
                                    TranslationResult.success(translation, message, sourceLangDisplay, getLanguageDisplayName(targetLang)));
                            }
                        } else {
                            for (Player player : playersForLang) {
                                results.put(player.getUniqueId().toString(),
                                    TranslationResult.failed(message, "Translation service unavailable"));
                            }
                        }
                    });
            }).toArray(CompletableFuture[]::new)
        );

        return allTranslations.thenApply(v -> results);
    }
    
    private CompletableFuture<String> translateWithRetry(String message, String fromLang, String toLang, int attempt) {
        CompletableFuture<String> translationFuture;

        if ("libretranslate".equalsIgnoreCase(provider)) {
            translationFuture = libreTranslateClient.translateAsync(message, fromLang, toLang);
        } else if ("gemini".equalsIgnoreCase(provider)) {
            boolean includeContext = plugin.getConfig().getBoolean("translation.gemini.includeContext", true);
            if (includeContext) {
                translationFuture = geminiClient.translateAsyncWithContext(message, fromLang, toLang);
            } else {
                translationFuture = geminiClient.translateAsync(message, fromLang, toLang);
            }
        } else {
            translationFuture = ollamaClient.translateAsync(message, fromLang, toLang);
        }

        return translationFuture.exceptionally(throwable -> {
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
            case "ru_ru" -> "Russian";
            case "es_es" -> "Spanish";
            case "fr_fr" -> "French";
            case "de_de" -> "German";
            case "it_it" -> "Italian";
            case "pt_pt" -> "Portuguese";
            case "zh_cn" -> "Chinese";
            case "ja_jp" -> "Japanese";
            case "ko_kr" -> "Korean";
            case "ar_sa" -> "Arabic";
            case "hi_in" -> "Hindi";
            case "pl_pl" -> "Polish";
            case "nl_nl" -> "Dutch";
            case "sv_se" -> "Swedish";
            case "no_no" -> "Norwegian";
            case "da_dk" -> "Danish";
            case "fi_fi" -> "Finnish";
            case "cs_cz" -> "Czech";
            case "hu_hu" -> "Hungarian";
            case "ro_ro" -> "Romanian";
            case "bg_bg" -> "Bulgarian";
            case "el_gr" -> "Greek";
            case "tr_tr" -> "Turkish";
            case "he_il" -> "Hebrew";
            case "th_th" -> "Thai";
            case "vi_vn" -> "Vietnamese";
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