package net.mysterria.translator.translation;

import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.engine.gemini.GeminiClient;
import net.mysterria.translator.engine.google.GoogleClient;
import net.mysterria.translator.engine.libretranslate.LibreTranslateClient;
import net.mysterria.translator.engine.ollama.OllamaClient;
import net.mysterria.translator.engine.openai.OpenAIClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Executes translations using the configured provider clients.
 * Responsible for routing translation requests to the appropriate provider.
 */
public class TranslationExecutor {

    /** Extra head-room on top of a provider's own socket timeouts before the guard fires. */
    private static final int TIMEOUT_MARGIN_SECONDS = 5;

    private final MysterriaTranslator plugin;
    private final OllamaClient ollamaClient;
    private final LibreTranslateClient libreTranslateClient;
    private final GeminiClient geminiClient;
    private final OpenAIClient openAIClient;
    private final GoogleClient googleClient;

    public TranslationExecutor(MysterriaTranslator plugin,
                               OllamaClient ollamaClient,
                               LibreTranslateClient libreTranslateClient,
                               GeminiClient geminiClient,
                               OpenAIClient openAIClient,
                               GoogleClient googleClient) {
        this.plugin = plugin;
        this.ollamaClient = ollamaClient;
        this.libreTranslateClient = libreTranslateClient;
        this.geminiClient = geminiClient;
        this.openAIClient = openAIClient;
        this.googleClient = googleClient;
    }

    /**
     * Executes translation using the specified provider.
     * <p>
     * The returned future is guarded by {@link #timeoutSecondsFor(String)} so a provider
     * that never answers cannot stall the chat pipeline. It always completes — with the
     * translation, with {@code null} when the provider is not configured, or exceptionally.
     *
     * @param provider The provider name (ollama, libretranslate, gemini, openai, google)
     * @param message  The message to translate
     * @param fromLang Source language code
     * @param toLang   Target language code
     * @return CompletableFuture with the translation, or a future of null if the provider is unavailable
     */
    public CompletableFuture<String> execute(String provider, String message, String fromLang, String toLang) {
        CompletableFuture<String> future;
        try {
            future = dispatch(provider, message, fromLang, toLang);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }

        return plugin.getTranslationPool().withTimeout(
                future, timeoutSecondsFor(provider), TimeUnit.SECONDS, "Provider '" + provider + "'");
    }

    private CompletableFuture<String> dispatch(String provider, String message, String fromLang, String toLang) {
        switch (provider.toLowerCase()) {
            case "libretranslate":
                if (libreTranslateClient == null) {
                    plugin.debug("LibreTranslate client not initialized");
                    return CompletableFuture.completedFuture(null);
                }
                return libreTranslateClient.translateAsync(message, fromLang, toLang);

            case "gemini":
                if (geminiClient == null) {
                    plugin.debug("Gemini client not initialized");
                    return CompletableFuture.completedFuture(null);
                }
                boolean includeContext = plugin.getConfig().getBoolean("engines.gemini.includeContext", true);
                if (includeContext) {
                    return geminiClient.translateAsyncWithContext(message, fromLang, toLang);
                } else {
                    return geminiClient.translateAsync(message, fromLang, toLang);
                }

            case "openai":
                if (openAIClient == null) {
                    plugin.debug("OpenAI client not initialized");
                    return CompletableFuture.completedFuture(null);
                }
                return openAIClient.translateAsync(message, fromLang, toLang);

            case "google":
                if (googleClient == null) {
                    plugin.debug("Google Translate client not initialized");
                    return CompletableFuture.completedFuture(null);
                }
                return googleClient.translateAsync(message, fromLang, toLang);

            case "ollama":
            default:
                if (ollamaClient == null) {
                    plugin.debug("Ollama client not initialized");
                    return CompletableFuture.completedFuture(null);
                }
                return ollamaClient.translateAsync(message, fromLang, toLang);
        }
    }

    /**
     * Derives the guard timeout from the provider's own configured socket timeouts, so
     * raising e.g. {@code engines.ollama.requestTimeout} does not get cut short here.
     */
    private long timeoutSecondsFor(String provider) {
        var config = plugin.getConfig();
        return switch (provider.toLowerCase()) {
            case "libretranslate" -> config.getInt("engines.libretranslate.connectTimeout", 5)
                    + config.getInt("engines.libretranslate.readTimeout", 10)
                    + TIMEOUT_MARGIN_SECONDS;
            case "gemini" -> {
                // Gemini walks its API keys one at a time, so worst case is per-key cost × key count.
                int keys = Math.max(1, config.getStringList("engines.gemini.apiKeys").size());
                yield (long) keys * (config.getInt("engines.gemini.connectTimeout", 10)
                        + config.getInt("engines.gemini.readTimeout", 15))
                        + TIMEOUT_MARGIN_SECONDS;
            }
            case "openai" -> config.getInt("engines.openai.connectTimeout", 10)
                    + config.getInt("engines.openai.readTimeout", 30)
                    + TIMEOUT_MARGIN_SECONDS;
            case "google" -> config.getInt("engines.google.connectTimeout", 5)
                    + config.getInt("engines.google.readTimeout", 10)
                    + TIMEOUT_MARGIN_SECONDS;
            default -> config.getInt("engines.ollama.connectTimeout", 10)
                    + config.getInt("engines.ollama.requestTimeout", 90)
                    + TIMEOUT_MARGIN_SECONDS;
        };
    }
}
