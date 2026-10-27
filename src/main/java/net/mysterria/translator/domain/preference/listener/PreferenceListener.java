package net.mysterria.translator.domain.preference.listener;

import lombok.RequiredArgsConstructor;
import net.mysterria.translator.domain.language.service.LanguageService;
import net.mysterria.translator.domain.preference.service.PreferenceService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

@RequiredArgsConstructor
public class PreferenceListener implements Listener {

    private final PreferenceService preferences;
    private final LanguageService languages;

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (preferences.hasLanguage(player.getUniqueId())) {
            return;
        }
        String clientLanguage = player.locale().toString().toLowerCase();
        preferences.assignLanguage(player.getUniqueId(), languages.isAvailable(clientLanguage) ? clientLanguage : null);
    }
}
