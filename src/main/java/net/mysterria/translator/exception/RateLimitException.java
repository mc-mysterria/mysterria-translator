package net.mysterria.translator.exception;

/**
 * Exception thrown when a translation API returns HTTP 429 (Too Many Requests).
 * Contains information about which engine and API key (if applicable) triggered the rate limit.
 */
public class RateLimitException extends Exception {
    private final String engineName;
    private final String apiKeyIdentifier;
    private final int statusCode;

    /**
     * Creates a RateLimitException for a single-key engine.
     *
     * @param engineName The name of the translation engine (e.g., "ollama", "openai")
     * @param statusCode The HTTP status code (should be 429)
     * @param message    Detailed error message
     */
    public RateLimitException(String engineName, int statusCode, String message) {
        super(message);
        this.engineName = engineName;
        this.apiKeyIdentifier = null;
        this.statusCode = statusCode;
    }

    /**
     * Creates a RateLimitException for a multi-key engine (like Gemini).
     *
     * @param engineName       The name of the translation engine
     * @param apiKeyIdentifier Identifier for the specific API key (e.g., "key-0", "key-1")
     * @param statusCode       The HTTP status code (should be 429)
     * @param message          Detailed error message
     */
    public RateLimitException(String engineName, String apiKeyIdentifier, int statusCode, String message) {
        super(message);
        this.engineName = engineName;
        this.apiKeyIdentifier = apiKeyIdentifier;
        this.statusCode = statusCode;
    }

    public String getEngineName() {
        return engineName;
    }

    public String getApiKeyIdentifier() {
        return apiKeyIdentifier;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns true if this exception is for a specific API key (multi-key engine).
     */
    public boolean hasApiKeyIdentifier() {
        return apiKeyIdentifier != null;
    }

    /**
     * Gets a unique suspension key for the RateLimitSuspensionManager.
     * For single-key engines: "engineName"
     * For multi-key engines: "engineName:keyIdentifier"
     */
    public String getSuspensionKey() {
        if (apiKeyIdentifier != null) {
            return engineName + ":" + apiKeyIdentifier;
        }
        return engineName;
    }
}
