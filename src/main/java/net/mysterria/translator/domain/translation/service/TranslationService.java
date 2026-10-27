package net.mysterria.translator.domain.translation.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.mysterria.translator.config.models.TranslationSettings;
import net.mysterria.translator.domain.translation.model.TranslationRequest;
import net.mysterria.translator.domain.translation.model.TranslationResult;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class TranslationService {

    private static final int CACHE_SIZE = 5000;

    private final EngineChain engines;
    private volatile TranslationSettings settings;
    private volatile Cache<String, String> cache;
    private volatile Cache<UUID, Deque<Long>> senderWindows;

    public TranslationService(EngineChain engines) {
        this.engines = engines;
    }

    public void configure(TranslationSettings settings) {
        this.settings = settings;
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(CACHE_SIZE)
                .expireAfterWrite(Duration.ofSeconds(settings.getCacheExpirySeconds()))
                .build();
        this.senderWindows = CacheBuilder.newBuilder()
                .expireAfterAccess(Duration.ofSeconds(settings.getRateLimitWindowSeconds()))
                .build();
    }

    public CompletableFuture<Map<Locale, TranslationResult>> translate(String text, Locale detected, Collection<Locale> targets,
                                                                     UUID sender, List<String> playerNames) {
        TranslationSettings current = settings;
        Cache<String, String> currentCache = cache;
        Locale engineSource = current.detectsSourceItself() ? null : detected;
        Map<Locale, TranslationResult> results = new ConcurrentHashMap<>();
        List<Locale> misses = new ArrayList<>();
        for (Locale target : targets) {
            String cached = currentCache.getIfPresent(cacheKey(text, engineSource, target));
            if (cached != null) {
                results.put(target, TranslationResult.success(text, cached, detected, target));
            } else {
                misses.add(target);
            }
        }
        if (misses.isEmpty()) {
            return CompletableFuture.completedFuture(results);
        }
        if (sender != null && !consumeAllowance(sender, current)) {
            misses.forEach(target -> results.put(target, TranslationResult.rateLimited(text, detected, target)));
            return CompletableFuture.completedFuture(results);
        }
        CompletableFuture<?>[] pending = misses.stream()
                .map(target -> engines.translate(new TranslationRequest(text, engineSource, target, playerNames))
                        .thenAccept(translated -> {
                            if (translated == null) {
                                results.put(target, TranslationResult.failed(text, detected, target));
                                return;
                            }
                            currentCache.put(cacheKey(text, engineSource, target), translated);
                            results.put(target, TranslationResult.success(text, translated, detected, target));
                        }))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(pending).thenApply(ignored -> results);
    }

    public boolean isAvailable() {
        return settings.isEnabled() && engines.hasEngines();
    }

    public long cachedTranslations() {
        return cache.size();
    }

    private boolean consumeAllowance(UUID sender, TranslationSettings current) {
        Deque<Long> window = senderWindows.asMap().computeIfAbsent(sender, id -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        long windowStart = now - current.getRateLimitWindowSeconds() * 1000L;
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < windowStart) {
                window.pollFirst();
            }
            if (window.size() >= current.getRateLimitMessages()) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    private static String cacheKey(String text, Locale source, Locale target) {
        return (source == null ? "auto" : source.toLanguageTag()) + '\u0000' + target.toLanguageTag() + '\u0000' + text;
    }
}
