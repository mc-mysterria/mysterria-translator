package net.mysterria.translator.translation;

import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.exception.RateLimitException;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages temporary suspension of translation engines and API keys that encounter rate limits (HTTP 429).
 * When an engine/key is suspended, it will be completely skipped during translation attempts until
 * the suspension period expires.
 * <p>
 * For multi-key engines like Gemini, each API key is tracked separately.
 * For single-key engines like OpenAI/Ollama, the entire engine is suspended.
 */
public class RateLimitManager {
    private final MysterriaTranslator plugin;
    private final ConcurrentHashMap<String, Instant> suspensions;
    private final int suspensionMinutes;

    /**
     * Creates a new RateLimitSuspensionManager.
     *
     * @param plugin            The plugin instance
     * @param suspensionMinutes How long to suspend engines/keys after a 429 error
     */
    public RateLimitManager(MysterriaTranslator plugin, int suspensionMinutes) {
        this.plugin = plugin;
        this.suspensions = new ConcurrentHashMap<>();
        this.suspensionMinutes = suspensionMinutes;
    }

    /**
     * Suspends an engine or specific API key after encountering a rate limit.
     *
     * @param exception The RateLimitException containing engine and key information
     */
    public void suspend(RateLimitException exception) {
        String suspensionKey = exception.getSuspensionKey();
        Instant expiryTime = Instant.now().plusSeconds(suspensionMinutes * 60L);
        suspensions.put(suspensionKey, expiryTime);

        String logMessage;
        if (exception.hasApiKeyIdentifier()) {
            logMessage = String.format(
                    "Suspended %s (key: %s) for %d minutes due to rate limit (HTTP %d)",
                    exception.getEngineName(),
                    exception.getApiKeyIdentifier(),
                    suspensionMinutes,
                    exception.getStatusCode()
            );
        } else {
            logMessage = String.format(
                    "Suspended %s engine for %d minutes due to rate limit (HTTP %d)",
                    exception.getEngineName(),
                    suspensionMinutes,
                    exception.getStatusCode()
            );
        }

        plugin.getLogger().warning(logMessage);
    }

    /**
     * Checks if an engine is currently suspended.
     * For single-key engines, checks if the engine is suspended.
     * For multi-key engines, checks if ANY key for that engine is NOT suspended.
     *
     * @param engineName The name of the engine (e.g., "ollama", "gemini", "openai")
     * @return true if the engine is completely suspended and should not be used
     */
    public boolean isSuspended(String engineName) {
        cleanExpiredSuspensions();


        if (suspensions.containsKey(engineName)) {
            return true;
        }

        return suspensions.keySet().stream()
                .anyMatch(key -> key.startsWith(engineName + ":"));
    }

    /**
     * Checks if a specific API key is suspended.
     *
     * @param engineName       The engine name
     * @param apiKeyIdentifier The key identifier (e.g., "key-0", "key-1")
     * @return true if this specific key is suspended
     */
    public boolean isKeySuspended(String engineName, String apiKeyIdentifier) {
        cleanExpiredSuspensions();
        String suspensionKey = engineName + ":" + apiKeyIdentifier;
        return suspensions.containsKey(suspensionKey);
    }

    /**
     * Gets the first available (non-suspended) API key index for a multi-key engine.
     *
     * @param engineName     The engine name
     * @param totalKeyCount  Total number of API keys available
     * @return The index of an available key, or -1 if all keys are suspended
     */
    public int getAvailableKeyIndex(String engineName, int totalKeyCount) {
        cleanExpiredSuspensions();

        for (int i = 0; i < totalKeyCount; i++) {
            String keyIdentifier = "key-" + i;
            if (!isKeySuspended(engineName, keyIdentifier)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Manually removes a suspension (for testing or manual intervention).
     *
     * @param engineName       The engine name
     * @param apiKeyIdentifier The key identifier, or null for single-key engines
     */
    public void removeSuspension(String engineName, String apiKeyIdentifier) {
        String suspensionKey = apiKeyIdentifier != null
                ? engineName + ":" + apiKeyIdentifier
                : engineName;

        Instant removed = suspensions.remove(suspensionKey);
        if (removed != null) {
            plugin.getLogger().info(String.format("Manually removed suspension for %s", suspensionKey));
        }
    }

    /**
     * Clears all suspensions.
     */
    public void clearAll() {
        int count = suspensions.size();
        suspensions.clear();
        if (count > 0) {
            plugin.getLogger().info(String.format("Cleared %d rate limit suspensions", count));
        }
    }

    /**
     * Removes expired suspensions from the map.
     */
    private void cleanExpiredSuspensions() {
        Instant now = Instant.now();
        suspensions.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(now)) {
                plugin.getLogger().info(String.format(
                        "Rate limit suspension expired for %s, re-enabling",
                        entry.getKey()
                ));
                return true;
            }
            return false;
        });
    }

    /**
     * Gets the number of currently active suspensions.
     */
    public int getActiveSuspensionCount() {
        cleanExpiredSuspensions();
        return suspensions.size();
    }

    /**
     * Gets the expiry time for a suspension, or null if not suspended.
     */
    public Instant getSuspensionExpiry(String engineName, String apiKeyIdentifier) {
        cleanExpiredSuspensions();
        String suspensionKey = apiKeyIdentifier != null
                ? engineName + ":" + apiKeyIdentifier
                : engineName;
        return suspensions.get(suspensionKey);
    }
}
