package net.mysterria.translator.translation;

import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.engine.gemini.GeminiClient;
import net.mysterria.translator.engine.google.GoogleClient;
import net.mysterria.translator.engine.libretranslate.LibreTranslateClient;
import net.mysterria.translator.engine.ollama.OllamaClient;
import net.mysterria.translator.engine.openai.OpenAIClient;

import java.util.concurrent.CompletableFuture;

/**
 * Executes translations using the configured provider clients.
 * Responsible for routing translation requests to the appropriate provider.
 */
public class TranslationExecutor {

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
     * Returns null future if the provider client is not initialized.
     *
     * @param provider The provider name (ollama, libretranslate, gemini, openai, google)
     * @param message  The message to translate
     * @param fromLang Source language code
     * @param toLang   Target language code
     * @return CompletableFuture with the translation, or null if provider unavailable
     */
    public CompletableFuture<String> execute(String provider, String message, String fromLang, String toLang) {
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
                boolean includeContext = plugin.getConfig().getBoolean("translation.gemini.includeContext", true);
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
}
