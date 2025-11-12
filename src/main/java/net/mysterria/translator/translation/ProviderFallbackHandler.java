package net.mysterria.translator.translation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.exception.RateLimitException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handles provider fallback logic with retry mechanism and player notifications.
 * Manages the logic for trying multiple translation providers in sequence.
 */
public class ProviderFallbackHandler {

    private final MysterriaTranslator plugin;
    private final RateLimitManager suspensionManager;
    private final TranslationExecutor executor;
    private volatile List<String> providers;
    private final int maxRetries;

    private volatile String lastSuccessfulProvider = null;
    private volatile long lastFallbackNotificationTime = 0;
    private static final long FALLBACK_NOTIFICATION_COOLDOWN_MS = 15 * 60 * 1000;

    public ProviderFallbackHandler(MysterriaTranslator plugin,
                                   RateLimitManager suspensionManager,
                                   TranslationExecutor executor,
                                   List<String> providers,
                                   int maxRetries) {
        this.plugin = plugin;
        this.suspensionManager = suspensionManager;
        this.executor = executor;
        this.providers = providers;
        this.maxRetries = maxRetries;
    }

    /**
     * Updates the list of providers to use for translation fallback.
     * This should be called when the plugin configuration is reloaded.
     *
     * @param newProviders The new list of provider names
     */
    public void updateProviders(List<String> newProviders) {
        this.providers = newProviders;
        plugin.debug("ProviderFallbackHandler: Updated providers list to: " + newProviders);
    }

    /**
     * Attempts translation with automatic provider fallback and retry logic.
     *
     * @param message  The message to translate
     * @param fromLang Source language code
     * @param toLang   Target language code
     * @return CompletableFuture with the translation and provider name, or null if all failed
     */
    public CompletableFuture<TranslationWithProvider> translateWithFallback(String message, String fromLang, String toLang) {
        return translateWithProviderFallback(message, fromLang, toLang, 0, 0);
    }

    /**
     * Internal record to track translation result with the provider that generated it.
     */
    public record TranslationWithProvider(String translation, String providerName) {
        public static TranslationWithProvider of(String translation, String provider) {
            return new TranslationWithProvider(translation, provider);
        }

        public static TranslationWithProvider failed() {
            return new TranslationWithProvider(null, null);
        }
    }

    /**
     * Attempts translation using multiple providers with automatic fallback.
     *
     * @param message       The message to translate
     * @param fromLang      Source language code
     * @param toLang        Target language code
     * @param providerIndex Index of current provider being tried
     * @param retryAttempt  Current retry attempt for the current provider
     * @return CompletableFuture with the translated text, or null if all providers failed
     */
    private CompletableFuture<TranslationWithProvider> translateWithProviderFallback(
            String message, String fromLang, String toLang, int providerIndex, int retryAttempt) {

        if (providerIndex >= providers.size()) {
            plugin.debug("All translation providers failed");
            return CompletableFuture.completedFuture(TranslationWithProvider.failed());
        }

        String currentProvider = providers.get(providerIndex);

        if (suspensionManager.isSuspended(currentProvider)) {
            plugin.debug("Provider '" + currentProvider + "' is currently suspended due to rate limits, skipping to next provider");
            return translateWithProviderFallback(message, fromLang, toLang, providerIndex + 1, 0);
        }

        CompletableFuture<String> translationFuture = executor.execute(currentProvider, message, fromLang, toLang);

        return translationFuture.handle((result, throwable) -> {
            if (throwable != null) {

                Throwable cause = throwable.getCause();
                if (cause instanceof RateLimitException rateLimitEx) {
                    suspensionManager.suspend(rateLimitEx);

                    plugin.debug("Provider '" + currentProvider + "' hit rate limit (429), suspended and moving to next provider");
                    checkAndNotifyFallback(currentProvider, providerIndex);
                    return translateWithProviderFallback(message, fromLang, toLang, providerIndex + 1, 0).join();
                }

                // Retry on other errors
                if (retryAttempt < maxRetries) {
                    try {
                        Thread.sleep(1000L * (retryAttempt + 1));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return translateWithProviderFallback(message, fromLang, toLang, providerIndex, retryAttempt + 1).join();
                }

                plugin.debug("Provider '" + currentProvider + "' failed, trying next");
                checkAndNotifyFallback(currentProvider, providerIndex);
                return translateWithProviderFallback(message, fromLang, toLang, providerIndex + 1, 0).join();
            }

            if (result != null) {
                updateSuccessfulProvider(currentProvider, providerIndex);
                return TranslationWithProvider.of(result, currentProvider);
            }

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
}
