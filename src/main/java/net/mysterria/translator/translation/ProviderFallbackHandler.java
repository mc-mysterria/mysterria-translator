package net.mysterria.translator.translation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.exception.RateLimitException;
import net.mysterria.translator.util.Throwables;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Handles provider fallback logic with retry mechanism and player notifications.
 * Manages the logic for trying multiple translation providers in sequence.
 * <p>
 * The whole chain is non-blocking: every hand-off between providers and every retry
 * back-off is composed with {@code thenCompose}. An earlier version called
 * {@code join()} from inside a completion callback and slept on the callback thread,
 * which pinned a worker thread for the entire fallback chain — with a stalled provider
 * that is exactly what exhausted the pool.
 */
public class ProviderFallbackHandler {

    private final MysterriaTranslator plugin;
    private final RateLimitManager suspensionManager;
    private final TranslationExecutor executor;
    private volatile List<String> providers;
    private final int maxRetries;
    private final long retryDelayMillis;
    private final long overallTimeoutSeconds;

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
        this.retryDelayMillis = Math.max(0, plugin.getConfig().getLong("translation.retryDelayMillis", 1000L));
        this.overallTimeoutSeconds = Math.max(1, plugin.getConfig().getLong("translation.overallTimeoutSeconds", 60L));
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
     * <p>
     * The returned future always completes within {@code translation.overallTimeoutSeconds}
     * so callers waiting to deliver a chat message never hang indefinitely.
     *
     * @param message  The message to translate
     * @param fromLang Source language code
     * @param toLang   Target language code
     * @return CompletableFuture with the translation and provider name, or null if all failed
     */
    public CompletableFuture<TranslationWithProvider> translateWithFallback(String message, String fromLang, String toLang) {
        CompletableFuture<TranslationWithProvider> chain =
                translateWithProviderFallback(message, fromLang, toLang, 0, 0);

        return plugin.getTranslationPool()
                .withTimeout(chain, overallTimeoutSeconds, TimeUnit.SECONDS, "Translation request")
                .exceptionally(throwable -> {
                    plugin.debug("Translation chain failed: " + Throwables.describe(throwable));
                    return TranslationWithProvider.failed();
                });
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

        List<String> currentProviders = this.providers;

        if (providerIndex >= currentProviders.size()) {
            plugin.debug("All translation providers failed");
            return CompletableFuture.completedFuture(TranslationWithProvider.failed());
        }

        String currentProvider = currentProviders.get(providerIndex);

        if (suspensionManager.isSuspended(currentProvider)) {
            plugin.debug("Provider '" + currentProvider + "' is currently suspended due to rate limits, skipping to next provider");
            return translateWithProviderFallback(message, fromLang, toLang, providerIndex + 1, 0);
        }

        CompletableFuture<String> translationFuture = executor.execute(currentProvider, message, fromLang, toLang);

        return translationFuture
                .handle((result, throwable) -> {
                    if (throwable != null) {
                        return handleFailure(message, fromLang, toLang, providerIndex, retryAttempt, currentProvider, throwable);
                    }

                    if (result != null) {
                        updateSuccessfulProvider(currentProvider, providerIndex);
                        return CompletableFuture.completedFuture(TranslationWithProvider.of(result, currentProvider));
                    }

                    checkAndNotifyFallback(currentProvider, providerIndex);
                    return translateWithProviderFallback(message, fromLang, toLang, providerIndex + 1, 0);
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<TranslationWithProvider> handleFailure(
            String message, String fromLang, String toLang, int providerIndex, int retryAttempt,
            String currentProvider, Throwable throwable) {

        // The real cause is wrapped several layers deep (CompletionException → RuntimeException → cause),
        // so the whole chain has to be searched rather than only the immediate cause.
        RateLimitException rateLimitEx = Throwables.findCause(throwable, RateLimitException.class);
        if (rateLimitEx != null) {
            suspensionManager.suspend(rateLimitEx);
            plugin.debug("Provider '" + currentProvider + "' hit rate limit (429), suspended and moving to next provider");
            checkAndNotifyFallback(currentProvider, providerIndex);
            return translateWithProviderFallback(message, fromLang, toLang, providerIndex + 1, 0);
        }

        // A rejection means the worker pool is saturated. Retrying or falling through to the
        // next provider would only add load to the same pool, so give up on this message.
        if (Throwables.findCause(throwable, RejectedExecutionException.class) != null) {
            plugin.debug("Dropping translation: worker pool saturated (" + plugin.getTranslationPool().stats() + ")");
            return CompletableFuture.completedFuture(TranslationWithProvider.failed());
        }

        if (retryAttempt < maxRetries) {
            long delay = retryDelayMillis * (retryAttempt + 1L);
            plugin.debug("Provider '" + currentProvider + "' failed (" + Throwables.describe(throwable)
                    + "), retrying in " + delay + "ms");
            return CompletableFuture
                    .supplyAsync(
                            () -> translateWithProviderFallback(message, fromLang, toLang, providerIndex, retryAttempt + 1),
                            plugin.getTranslationPool().delayedExecutor(delay, TimeUnit.MILLISECONDS))
                    .thenCompose(Function.identity());
        }

        plugin.debug("Provider '" + currentProvider + "' failed (" + Throwables.describe(throwable) + "), trying next");
        checkAndNotifyFallback(currentProvider, providerIndex);
        return translateWithProviderFallback(message, fromLang, toLang, providerIndex + 1, 0);
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
        List<String> currentProviders = this.providers;
        if (providerIndex == 0 && providerIndex + 1 < currentProviders.size()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFallbackNotificationTime >= FALLBACK_NOTIFICATION_COOLDOWN_MS) {
                lastFallbackNotificationTime = currentTime;
                String nextProvider = currentProviders.get(providerIndex + 1);
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
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(message);
            }
        });
    }
}
