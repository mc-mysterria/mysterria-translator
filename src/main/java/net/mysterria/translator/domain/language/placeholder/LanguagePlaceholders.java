package net.mysterria.translator.domain.language.placeholder;

import lombok.RequiredArgsConstructor;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.mysterria.translator.domain.language.service.LanguageService;
import net.mysterria.translator.domain.preference.service.PreferenceService;
import net.mysterria.translator.util.identity.ChatIdentity;
import net.mysterria.translator.util.language.Languages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

@RequiredArgsConstructor
public class LanguagePlaceholders extends PlaceholderExpansion {

    private static final String PLAYER_PREFIX = "player_";

    private final LanguageService languages;
    private final PreferenceService preferences;
    private final String version;

    @Override
    public @NotNull String getIdentifier() {
        return "lang";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mysterria";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String lower = params.toLowerCase();
        return switch (lower) {
            case "player" -> player == null ? null : preferences.language(player.getUniqueId());
            case "display_name" -> player instanceof Player online ? ChatIdentity.displayName(online) : null;
            case "chat" -> player instanceof Player online ? Languages.displayName(preferences.chatTarget(online)) : null;
            case "translations" -> player == null ? null : preferences.translationsEnabled(player.getUniqueId()) ? "on" : "off";
            default -> lower.startsWith(PLAYER_PREFIX) ? languageOf(params.substring(PLAYER_PREFIX.length())) : languages.translate(player, params);
        };
    }

    private String languageOf(String name) {
        OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(name);
        }
        return target == null ? "unknown" : preferences.language(target.getUniqueId());
    }
}
