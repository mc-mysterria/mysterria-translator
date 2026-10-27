package net.mysterria.translator.config.models;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class TranslationSettings {
    private final boolean enabled;
    private final List<String> providers;
    private final int minMessageLength;
    private final int maxRetries;
    private final int cacheExpirySeconds;
    private final int rateLimitMessages;
    private final int rateLimitWindowSeconds;
    private final int suspensionMinutes;
    private final long retryDelayMillis;
    private final int overallTimeoutSeconds;
    private final int connectTimeoutSeconds;
    private final int threadPoolSize;
    private final int queueCapacity;
    private final boolean indicatorEnabled;
    private final String indicatorText;
    private final List<String> indicatorHover;

    public boolean detectsSourceItself() {
        return providers.contains("gemini");
    }
}
