package net.mysterria.translator.listener;

import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.manager.LangManager;
import net.mysterria.translator.storage.model.LangEnum;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.util.UUID;

public class PlayerJoinListener implements Listener {

    private final MysterriaTranslator plugin;
    private final LangManager langManager;

    public PlayerJoinListener(LangManager langManager, MysterriaTranslator plugin) {
        this.langManager = langManager;
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        plugin.debug("Setting language for player " + player.getName() + " on join.");
        String playerLocale = player.locale().toString().toLowerCase();
        String selectedLang = plugin.getConfig().getString("defaultLang");
        plugin.debug("Player locale: " + playerLocale);

        if (playerLocale.isEmpty()) {
            playerLocale = plugin.getConfig().getString("defaultLang");
        }

        if (playerLocale != null && LangEnum.isValidCode(playerLocale)) {
            File langsFolder = new File(plugin.getDataFolder(), "langs");
            File langFolder = new File(langsFolder, playerLocale);
            if (langFolder.exists()) {
                selectedLang = playerLocale;
            }
        }

        selectedLang = selectedLang.toLowerCase();
        plugin.debug("Selected language: " + selectedLang);
        langManager.setPlayerLang(uuid, selectedLang);
        langManager.savePlayerLang(uuid);
    }
}
