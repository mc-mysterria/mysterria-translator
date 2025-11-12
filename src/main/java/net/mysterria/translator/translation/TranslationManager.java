package net.mysterria.translator.translation;

import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.engine.gemini.GeminiClient;
import net.mysterria.translator.engine.google.GoogleClient;
import net.mysterria.translator.engine.libretranslate.LibreTranslateClient;
import net.mysterria.translator.engine.ollama.OllamaClient;
import net.mysterria.translator.engine.openai.OpenAIClient;
import net.mysterria.translator.util.LanguageDetector;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates translation requests by coordinating between specialized components.
 * Delegates caching, rate limiting, and provider fallback to dedicated classes.
 */
public class TranslationManager {

    private final MysterriaTranslator plugin;
    private final TranslationCache cache;
    private final PlayerRateLimiter rateLimiter;
    private final ProviderFallbackHandler fallbackHandler;
    private final List<String> providers;
    private final int minMessageLength;

    public TranslationManager(MysterriaTranslator plugin, RateLimitManager suspensionManager,
                              OllamaClient ollamaClient, LibreTranslateClient libreTranslateClient,
                              GeminiClient geminiClient, OpenAIClient openAIClient, GoogleClient googleClient) {
        this.plugin = plugin;

        String providerConfig = plugin.getConfig().getString("translation.provider", "ollama");
        this.providers = Arrays.stream(providerConfig.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(p -> !p.isEmpty())
                .collect(Collectors.toList());

        int cacheExpirySeconds = plugin.getConfig().getInt("translation.cacheExpirySeconds", 30);
        int rateLimitMessages = plugin.getConfig().getInt("translation.rateLimitMessages", 2);
        int rateLimitWindowSeconds = plugin.getConfig().getInt("translation.rateLimitWindowSeconds", 10);
        int maxRetries = plugin.getConfig().getInt("translation.maxRetries", 2);
        this.minMessageLength = plugin.getConfig().getInt("translation.minMessageLength", 3);

        this.cache = new TranslationCache(cacheExpirySeconds);
        this.rateLimiter = new PlayerRateLimiter(rateLimitMessages, rateLimitWindowSeconds);

        TranslationExecutor executor = new TranslationExecutor(plugin, ollamaClient, libreTranslateClient,
                geminiClient, openAIClient, googleClient);
        this.fallbackHandler = new ProviderFallbackHandler(plugin, suspensionManager, executor,
                providers, maxRetries);
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

        if (!rateLimiter.canTranslate(player.getUniqueId())) {
            return CompletableFuture.completedFuture(
                    TranslationResult.rateLimited(message)
            );
        }

        String targetLang = LanguageDetector.getTargetLanguage(playerLocale);

        LanguageDetector.DetectedLanguage sourceLang = LanguageDetector.detectLanguage(message);

        String sourceLangCode = sourceLang.getLangCode();
        String sourceLangDisplay = sourceLang.getDisplayName();

        String cached = cache.get(message, sourceLangCode, targetLang);
        if (cached != null) {
            return CompletableFuture.completedFuture(
                    TranslationResult.success(cached, message, sourceLangDisplay, getLanguageDisplayName(targetLang))
            );
        }

        rateLimiter.recordUsage(player.getUniqueId());

        return fallbackHandler.translateWithFallback(message, sourceLangCode, targetLang)
                .thenApply(result -> {
                    if (result.translation() != null) {
                        cache.put(message, sourceLangCode, targetLang, result.translation());
                        plugin.debug("[" + result.providerName().toUpperCase() + "] Translation result: \"" + message + "\" -> \"" + result.translation() + "\"");
                        return TranslationResult.success(result.translation(), message, sourceLangDisplay, getLanguageDisplayName(targetLang));
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

        String sourceLangCode;
        String sourceLangDisplay;
        if (providers.contains("gemini")) {
            sourceLangCode = "auto";
            sourceLangDisplay = "Auto-detected";
        } else {
            LanguageDetector.DetectedLanguage sourceLang = LanguageDetector.detectLanguage(message);
            sourceLangCode = sourceLang.getLangCode();
            sourceLangDisplay = sourceLang.getDisplayName();
        }
        Map<String, Set<Player>> playersByTargetLang = new ConcurrentHashMap<>();
        Map<String, TranslationResult> results = new ConcurrentHashMap<>();


        for (Player player : players) {
            String playerLocale = player.locale().toString().toLowerCase();

            if (!LanguageDetector.needsTranslation(message, playerLocale)) {
                results.put(player.getUniqueId().toString(),
                        TranslationResult.noTranslation(message, "No translation needed"));
                continue;
            }

            if (!rateLimiter.canTranslate(player.getUniqueId())) {
                results.put(player.getUniqueId().toString(),
                        TranslationResult.rateLimited(message));
                continue;
            }

            String targetLang = LanguageDetector.getTargetLanguage(playerLocale);
            String cached = cache.get(message, sourceLangCode, targetLang);

            if (cached != null) {
                results.put(player.getUniqueId().toString(),
                        TranslationResult.success(cached, message, sourceLangDisplay, getLanguageDisplayName(targetLang)));
                continue;
            }

            playersByTargetLang.computeIfAbsent(targetLang, k -> ConcurrentHashMap.newKeySet()).add(player);
            rateLimiter.recordUsage(player.getUniqueId());
        }


        if (playersByTargetLang.isEmpty()) {
            return CompletableFuture.completedFuture(results);
        }

        CompletableFuture<Void> allTranslations = CompletableFuture.allOf(
                playersByTargetLang.entrySet().stream().map(entry -> {
                    String targetLang = entry.getKey();
                    Set<Player> playersForLang = entry.getValue();

                    return fallbackHandler.translateWithFallback(message, sourceLangCode, targetLang)
                            .thenAccept(result -> {
                                if (result.translation() != null) {
                                    cache.put(message, sourceLangCode, targetLang, result.translation());
                                    plugin.debug("[" + result.providerName().toUpperCase() + "] Translation result: \"" + message + "\" -> \"" + result.translation() + "\"");

                                    for (Player player : playersForLang) {
                                        results.put(player.getUniqueId().toString(),
                                                TranslationResult.success(result.translation(), message, sourceLangDisplay, getLanguageDisplayName(targetLang)));
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

    public void shutdown() {
        cache.shutdown();
        rateLimiter.shutdown();
    }

    public void clearCache() {
        cache.clear();
        rateLimiter.clear();
    }

    /**
     * Reloads the provider configuration from the plugin config.
     * This should be called when the plugin configuration is reloaded.
     */
    public void reloadProviders() {
        String providerConfig = plugin.getConfig().getString("translation.provider", "ollama");
        List<String> newProviders = Arrays.stream(providerConfig.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(p -> !p.isEmpty())
                .collect(Collectors.toList());

        if (!newProviders.equals(this.providers)) {
            this.providers.clear();
            this.providers.addAll(newProviders);
            fallbackHandler.updateProviders(this.providers);
            plugin.getLogger().info("Translation providers reloaded: " + String.join(", ", newProviders));
        }
    }
}