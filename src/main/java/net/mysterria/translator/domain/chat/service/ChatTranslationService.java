package net.mysterria.translator.domain.chat.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.config.TranslatorConfig;
import net.mysterria.translator.config.models.ChatSettings;
import net.mysterria.translator.config.models.TranslationSettings;
import net.mysterria.translator.domain.chat.model.PendingTranslation;
import net.mysterria.translator.domain.preference.service.PreferenceService;
import net.mysterria.translator.domain.translation.model.TranslationResult;
import net.mysterria.translator.domain.translation.model.source.ResultType;
import net.mysterria.translator.domain.translation.service.TranslationService;
import net.mysterria.translator.util.identity.ChatIdentity;
import net.mysterria.translator.util.language.Languages;
import net.mysterria.translator.util.text.TextFormat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public class ChatTranslationService {

    private static final Duration RATE_NOTICE_COOLDOWN = Duration.ofSeconds(15);
    private static final int MAX_CONTEXT_NAMES = 20;

    private final MysterriaTranslator plugin;
    private final TranslationService translations;
    private final PreferenceService preferences;
    @Getter
    private final TranslationMarker marker;
    private final Cache<UUID, Boolean> rateNoticeShown = CacheBuilder.newBuilder().expireAfterWrite(RATE_NOTICE_COOLDOWN).build();
    @Getter
    private volatile ChatSettings chat;
    private volatile TranslationSettings translation;

    public ChatTranslationService(MysterriaTranslator plugin, TranslationService translations, PreferenceService preferences, TranslationMarker marker) {
        this.plugin = plugin;
        this.translations = translations;
        this.preferences = preferences;
        this.marker = marker;
    }

    public void configure(TranslatorConfig config) {
        this.chat = config.getChat();
        this.translation = config.getTranslation();
    }

    public boolean isTranslationEnabled() {
        return translations.isAvailable();
    }

    public PendingTranslation plan(String text, Collection<? extends Player> audience, CommandSender sender) {
        TranslationSettings current = translation;
        if (!translations.isAvailable() || text.length() < current.getMinMessageLength()) {
            return null;
        }
        Locale source = Languages.detect(text);
        if (source == null) {
            return null;
        }
        Map<Locale, List<Player>> byTarget = new HashMap<>();
        for (Player player : audience) {
            if (player.equals(sender) || !preferences.translationsEnabled(player.getUniqueId())) {
                continue;
            }
            Locale target = preferences.chatTarget(player);
            if (!Languages.sameLanguage(source, target)) {
                byTarget.computeIfAbsent(target, locale -> new ArrayList<>()).add(player);
            }
        }
        return byTarget.isEmpty() ? null : new PendingTranslation(text, source, byTarget);
    }

    public void deliver(PendingTranslation pending, CommandSender sender, Component fallback, Function<TranslationResult, Component> render) {
        UUID senderId = sender instanceof Player player ? player.getUniqueId() : null;
        translations.translate(pending.getText(), pending.getSource(), pending.getRecipientsByTarget().keySet(), senderId, mentionedNames(pending.getText()))
                .whenComplete((results, failure) -> {
                    if (failure != null) {
                        plugin.getSLF4JLogger().warn("Chat translation failed", failure);
                    }
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, () -> send(pending, sender, fallback, render, results == null ? Map.of() : results));
                    }
                });
    }

    public Component format(String format, CommandSender sender, String original, TranslationResult result, boolean hover) {
        Component message = result == null
                ? Component.text(original)
                : marker.decorate(Component.text(result.getTranslated()), result, false);
        String prepared = format.replace("{player_name}", "<mt_player>")
                .replace("{translated_message}", "<mt_message>")
                .replace("{original_message}", "<mt_original>")
                .replace("{source_language}", "<mt_source>")
                .replace("{target_language}", "<mt_target>");
        Component line = TextFormat.render(prepared, sender instanceof Player player ? player : null,
                TagResolver.resolver("mt_player", Tag.inserting(Component.text(ChatIdentity.displayName(sender)))),
                TagResolver.resolver("mt_message", Tag.inserting(message)),
                TagResolver.resolver("mt_original", Tag.inserting(Component.text(original))),
                TagResolver.resolver("mt_source", Tag.inserting(Component.text(result == null ? "" : Languages.displayName(result.getSource())))),
                TagResolver.resolver("mt_target", Tag.inserting(Component.text(result == null ? "" : Languages.displayName(result.getTarget())))));
        Component tooltip = hover && result != null ? marker.hover(result) : null;
        return tooltip == null ? line : line.hoverEvent(HoverEvent.showText(tooltip));
    }

    public Set<Player> rangeAudience(Player sender) {
        ChatSettings current = chat;
        double rangeSquared = current.getRange() * current.getRange();
        Location origin = sender.getLocation();
        Set<Player> audience = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.equals(sender)) {
                continue;
            }
            if (!player.getWorld().equals(origin.getWorld())) {
                if (current.isCrossWorld()) {
                    audience.add(player);
                }
                continue;
            }
            if (player.getLocation().distanceSquared(origin) <= rangeSquared) {
                audience.add(player);
            }
        }
        return audience;
    }

    public Set<Player> everyoneBut(Player sender) {
        Set<Player> audience = new HashSet<>(Bukkit.getOnlinePlayers());
        audience.remove(sender);
        return audience;
    }

    private void send(PendingTranslation pending, CommandSender sender, Component fallback,
                      Function<TranslationResult, Component> render, Map<Locale, TranslationResult> results) {
        boolean rateLimited = false;
        for (var group : pending.getRecipientsByTarget().entrySet()) {
            TranslationResult result = results.get(group.getKey());
            rateLimited |= result != null && result.getType() == ResultType.RATE_LIMITED;
            boolean translated = result != null && result.isTranslated();
            Component line = translated ? renderSafely(render, result, fallback) : fallback;
            if (line == null) {
                continue;
            }
            for (Player player : group.getValue()) {
                if (!player.isOnline()) {
                    continue;
                }
                player.sendMessage(line);
                if (translated && preferences.claimHint(player.getUniqueId())) {
                    plugin.getMessages().actionBar(player, "hint_first_translation");
                }
            }
        }
        if (rateLimited && sender instanceof Player player && player.isOnline()
                && rateNoticeShown.asMap().putIfAbsent(player.getUniqueId(), true) == null) {
            plugin.getMessages().actionBar(player, "translation.rate_limited");
        }
    }

    private Component renderSafely(Function<TranslationResult, Component> render, TranslationResult result, Component fallback) {
        try {
            return render.apply(result);
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().warn("Could not format a translated chat line, sending the original instead", e);
            return fallback;
        }
    }

    private List<String> mentionedNames(String text) {
        String lower = text.toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> lower.contains(name.toLowerCase()))
                .limit(MAX_CONTEXT_NAMES)
                .toList();
    }
}
