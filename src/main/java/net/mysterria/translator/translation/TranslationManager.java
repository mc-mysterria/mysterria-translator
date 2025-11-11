package net.mysterria.translator.translation;

import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.engine.ollama.OllamaClient;
import net.mysterria.translator.engine.libretranslate.LibreTranslateClient;
import net.mysterria.translator.engine.gemini.GeminiClient;
import net.mysterria.translator.engine.openai.OpenAIClient;
import net.mysterria.translator.util.LanguageDetector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class TranslationManager {

    private final MysterriaTranslator plugin;
    private final OllamaClient ollamaClient;
    private final LibreTranslateClient libreTranslateClient;
    private final GeminiClient geminiClient;
    private final OpenAIClient openAIClient;
    private final List<String> providers; // Ordered list of providers to try
    private final ScheduledExecutorService scheduler;
    
    private final Map<String, CachedTranslation> translationCache;
    private final Map<UUID, PlayerRateLimit> rateLimits;

    private final int cacheExpirySeconds;
    private final int rateLimitMessages;
    private final int rateLimitWindowSeconds;
    private final int minMessageLength;
    private final int maxRetries;

    // Fallback notification tracking
    private volatile String lastSuccessfulProvider = null;
    private volatile long lastFallbackNotificationTime = 0;
    private static final long FALLBACK_NOTIFICATION_COOLDOWN_MS = 15 * 60 * 1000; // 15 minutes
    
    public TranslationManager(MysterriaTranslator plugin, OllamaClient ollamaClient, LibreTranslateClient libreTranslateClient, GeminiClient geminiClient, OpenAIClient openAIClient) {
        this.plugin = plugin;
        this.ollamaClient = ollamaClient;
        this.libreTranslateClient = libreTranslateClient;
        this.geminiClient = geminiClient;
        this.openAIClient = openAIClient;

        String providerConfig = plugin.getConfig().getString("translation.provider", "ollama");
        this.providers = Arrays.stream(providerConfig.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(p -> !p.isEmpty())
                .collect(Collectors.toList());

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
        if (providers.contains("gemini")) {
            // If Gemini is in the provider list, use auto-detection
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
                        plugin.debug("Translation result: \"" + message + "\" -> \"" + translation + "\"");
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
        if (providers.contains("gemini")) {
            // If Gemini is in the provider list, use auto-detection
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
                            plugin.debug("Translation result: \"" + message + "\" -> \"" + translation + "\"");

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
        return translateWithProviderFallback(message, fromLang, toLang, 0, attempt);
    }

    /**
     * Attempts translation using multiple providers with automatic fallback.
     * @param message The message to translate
     * @param fromLang Source language code
     * @param toLang Target language code
     * @param providerIndex Index of current provider being tried
     * @param retryAttempt Current retry attempt for the current provider
     * @return CompletableFuture with the translated text, or null if all providers failed
     */
    private CompletableFuture<String> translateWithProviderFallback(String message, String fromLang, String toLang, int providerIndex, int retryAttempt) {
        if (providerIndex >= providers.size()) {
            plugin.debug("All translation providers failed");
            return CompletableFuture.completedFuture(null);
        }

        String currentProvider = providers.get(providerIndex);
        CompletableFuture<String> translationFuture = executeTranslation(currentProvider, message, fromLang, toLang);

        return translationFuture.handle((result, throwable) -> {
            if (throwable != null) {
                // Retry with the same provider if we haven't exhausted retries
                if (retryAttempt < maxRetries) {
                    try {
                        Thread.sleep(1000L * (retryAttempt + 1));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return translateWithProviderFallback(message, fromLang, toLang, providerIndex, retryAttempt + 1).join();
                }

                // Move to the next provider
                plugin.debug("Provider '" + currentProvider + "' failed, trying next");
                checkAndNotifyFallback(currentProvider, providerIndex);
                return translateWithProviderFallback(message, fromLang, toLang, providerIndex + 1, 0).join();
            }

            if (result != null) {
                updateSuccessfulProvider(currentProvider, providerIndex);
                return result;
            }

            // Result is null, try next provider
            checkAndNotifyFallback(currentProvider, providerIndex);
            return translateWithProviderFallback(message, fromLang, toLang, providerIndex + 1, 0).join();
        });
    }

    /**
     * Updates the last successful provider and checks if we've recovered from a fallback.
     */
    private void updateSuccessfulProvider(String provider, int providerIndex) {
        String previousProvider = lastSuccessfulProvider;
        lastSuccessfulProvider = provider;

        // If we've recovered back to the primary provider, notify
        if (previousProvider != null && !previousProvider.equals(provider) && providerIndex == 0) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFallbackNotificationTime >= FALLBACK_NOTIFICATION_COOLDOWN_MS) {
                lastFallbackNotificationTime = currentTime;
                plugin.getLogger().info("Translation engine recovered: now using primary provider '" + provider + "'");
                notifyAllPlayers(Component.text("Translation engine recovered: now using ")
                        .color(NamedTextColor.GREEN)
                        .append(Component.text(provider).color(NamedTextColor.YELLOW)));
            }
        }
    }

    /**
     * Checks if we're falling back from the primary provider and notifies if needed.
     */
    private void checkAndNotifyFallback(String failedProvider, int providerIndex) {
        // Only notify if:
        // 1. This is the first provider (primary) that failed
        // 2. We have more providers to try
        // 3. Enough time has passed since the last notification
        if (providerIndex == 0 && providerIndex + 1 < providers.size()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFallbackNotificationTime >= FALLBACK_NOTIFICATION_COOLDOWN_MS) {
                lastFallbackNotificationTime = currentTime;
                String nextProvider = providers.get(providerIndex + 1);
                plugin.getLogger().warning("Primary translation provider '" + failedProvider + "' is unavailable. " +
                        "Falling back to '" + nextProvider + "'. Translation quality may be degraded.");
                notifyAllPlayers(Component.text("Primary translation provider ")
                        .color(NamedTextColor.GOLD)
                        .append(Component.text(failedProvider).color(NamedTextColor.YELLOW))
                        .append(Component.text(" is unavailable. Falling back to ").color(NamedTextColor.GOLD))
                        .append(Component.text(nextProvider).color(NamedTextColor.YELLOW))
                        .append(Component.text(". Translation quality may be degraded.").color(NamedTextColor.GOLD)));
            }
        }
    }

    /**
     * Notifies all online players with a message.
     */
    private void notifyAllPlayers(Component message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(message);
            }
        });
    }

    /**
     * Executes translation using the specified provider.
     */
    private CompletableFuture<String> executeTranslation(String provider, String message, String fromLang, String toLang) {
        switch (provider.toLowerCase()) {
            case "libretranslate":
                return libreTranslateClient.translateAsync(message, fromLang, toLang);

            case "gemini":
                boolean includeContext = plugin.getConfig().getBoolean("translation.gemini.includeContext", true);
                if (includeContext) {
                    return geminiClient.translateAsyncWithContext(message, fromLang, toLang);
                } else {
                    return geminiClient.translateAsync(message, fromLang, toLang);
                }

            case "openai":
                return openAIClient.translateAsync(message, fromLang, toLang);

            case "ollama":
            default:
                return ollamaClient.translateAsync(message, fromLang, toLang);
        }
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