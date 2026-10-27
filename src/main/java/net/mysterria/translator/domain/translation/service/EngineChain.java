package net.mysterria.translator.domain.translation.service;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.config.models.TranslationSettings;
import net.mysterria.translator.domain.translation.model.TranslationRequest;
import net.mysterria.translator.domain.translation.type.EngineRateLimitedException;
import net.mysterria.translator.domain.translation.type.GeminiEngine;
import net.mysterria.translator.domain.translation.type.TranslationEngine;
import net.mysterria.translator.util.throwable.Throwables;
import org.bukkit.Bukkit;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public class EngineChain {

    private static final long NOTICE_COOLDOWN_MS = Duration.ofMinutes(15).toMillis();
    private static final String ADMIN_PERMISSION = "mtranslator.admin";

    private final MysterriaTranslator plugin;
    private final TranslationThreadPool pool;
    private final Map<String, Instant> suspendedUntil = new ConcurrentHashMap<>();
    private final AtomicLong lastNotice = new AtomicLong();
    private volatile List<TranslationEngine> engines = List.of();
    private volatile TranslationSettings settings;
    private volatile String lastEngine;

    public EngineChain(MysterriaTranslator plugin, TranslationThreadPool pool) {
        this.plugin = plugin;
        this.pool = pool;
    }

    public void configure(TranslationSettings settings, List<TranslationEngine> engines) {
        this.settings = settings;
        this.engines = List.copyOf(engines);
        this.lastEngine = null;
        suspendedUntil.clear();
    }

    public CompletableFuture<String> translate(TranslationRequest request) {
        TranslationSettings current = settings;
        return pool.withTimeout(attempt(engines, 0, 0, request, current), Duration.ofSeconds(current.getOverallTimeoutSeconds()), "Translation")
                .exceptionally(failure -> {
                    plugin.debug("Translation gave up: " + Throwables.describe(failure));
                    return null;
                });
    }

    public boolean hasEngines() {
        return !engines.isEmpty();
    }

    public Map<String, String> status() {
        Map<String, String> status = new LinkedHashMap<>();
        for (TranslationEngine engine : engines) {
            Instant until = suspendedUntil.get(engine.getName());
            String state = until != null && until.isAfter(Instant.now())
                    ? "suspended for " + Duration.between(Instant.now(), until).toMinutes() + "m"
                    : "ready";
            if (engine instanceof GeminiEngine gemini) {
                state += ", " + gemini.availableKeys() + " key(s) usable";
            }
            status.put(engine.getName(), state + (engine.getName().equals(lastEngine) ? ", last used" : ""));
        }
        return status;
    }

    private CompletableFuture<String> attempt(List<TranslationEngine> chain, int index, int retry, TranslationRequest request, TranslationSettings current) {
        if (index >= chain.size()) {
            return CompletableFuture.completedFuture(null);
        }
        TranslationEngine engine = chain.get(index);
        if (isSuspended(engine.getName())) {
            return attempt(chain, index + 1, 0, request, current);
        }
        return pool.withTimeout(pool.submit(() -> engine.translate(request)), engine.guardTimeout(), "Engine " + engine.getName())
                .handle((translated, failure) -> failure == null && translated != null && !translated.isBlank()
                        ? succeeded(chain, index, engine, translated)
                        : failed(chain, index, retry, request, current, engine, failure))
                .thenCompose(Function.identity());
    }

    private CompletableFuture<String> succeeded(List<TranslationEngine> chain, int index, TranslationEngine engine, String translated) {
        String previous = lastEngine;
        lastEngine = engine.getName();
        if (index == 0 && previous != null && !previous.equals(engine.getName()) && chain.size() > 1) {
            notifyAdmins("engine_recovered", "{engine}", engine.getName());
        }
        plugin.debug("[" + engine.getName() + "] \"" + translated + "\"");
        return CompletableFuture.completedFuture(translated);
    }

    private CompletableFuture<String> failed(List<TranslationEngine> chain, int index, int retry, TranslationRequest request,
                                             TranslationSettings current, TranslationEngine engine, Throwable failure) {
        String reason = failure == null ? "empty answer" : Throwables.describe(failure);
        if (failure != null && Throwables.findCause(failure, RejectedExecutionException.class) != null) {
            return CompletableFuture.completedFuture(null);
        }
        if (failure != null && Throwables.findCause(failure, EngineRateLimitedException.class) != null) {
            suspendedUntil.put(engine.getName(), Instant.now().plus(Duration.ofMinutes(current.getSuspensionMinutes())));
            plugin.getSLF4JLogger().warn("Engine {} is rate limited, skipping it for {} minutes", engine.getName(), current.getSuspensionMinutes());
            return fallBack(chain, index, request, current, engine);
        }
        if (retry < current.getMaxRetries()) {
            long delay = current.getRetryDelayMillis() * (retry + 1L);
            plugin.debug("Engine " + engine.getName() + " failed (" + reason + "), retrying in " + delay + "ms");
            return CompletableFuture.supplyAsync(() -> attempt(chain, index, retry + 1, request, current), pool.delayed(delay))
                    .thenCompose(Function.identity());
        }
        plugin.debug("Engine " + engine.getName() + " failed (" + reason + ")");
        return fallBack(chain, index, request, current, engine);
    }

    private CompletableFuture<String> fallBack(List<TranslationEngine> chain, int index, TranslationRequest request,
                                               TranslationSettings current, TranslationEngine engine) {
        if (index == 0 && chain.size() > 1) {
            notifyAdmins("engine_fallback", "{engine}", engine.getName(), "{next}", chain.get(1).getName());
        }
        return attempt(chain, index + 1, 0, request, current);
    }

    private boolean isSuspended(String engine) {
        Instant until = suspendedUntil.get(engine);
        if (until == null) {
            return false;
        }
        if (until.isBefore(Instant.now())) {
            suspendedUntil.remove(engine);
            plugin.getSLF4JLogger().info("Engine {} is back in rotation", engine);
            return false;
        }
        return true;
    }

    private void notifyAdmins(String messageKey, String... placeholders) {
        long now = System.currentTimeMillis();
        long last = lastNotice.get();
        if (now - last < NOTICE_COOLDOWN_MS || !lastNotice.compareAndSet(last, now) || !plugin.isEnabled()) {
            return;
        }
        plugin.getSLF4JLogger().warn(PlainTextComponentSerializer.plainText().serialize(plugin.getMessages().get(messageKey, placeholders)));
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission(ADMIN_PERMISSION))
                .forEach(player -> plugin.getMessages().send(player, messageKey, placeholders)));
    }
}
