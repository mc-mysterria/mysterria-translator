package net.mysterria.translator.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.mysterria.translator.manager.LangManager;
import net.mysterria.translator.util.DisguiseUtil;
import org.bukkit.entity.Player;

public class LangExpansion extends PlaceholderExpansion {
    private final LangManager langManager;

    public LangExpansion(LangManager langManager) {
        this.langManager = langManager;
    }

    @Override
    public String getIdentifier() {
        return "lang";
    }

    @Override
    public String getAuthor() {
        return "Mysterria";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player p, String params) {
        if (params.equalsIgnoreCase("player")) {
            return langManager.getPlayerLang(p.getUniqueId());
        }
        if (params.equalsIgnoreCase("display_name")) {
            return DisguiseUtil.getChatName(p);
        }
        if (params.equalsIgnoreCase("gradient_test")) {
            return "<gradient:blue:green:yellow>MysterriaTranslator</gradient>";
        }
        if (params.toLowerCase().startsWith("player_")) {
            String targetName = params.substring("player_".length());
            Player target = org.bukkit.Bukkit.getPlayerExact(targetName);
            if (target != null) {
                return langManager.getPlayerLang(target.getUniqueId());
            } else {
                java.util.UUID uuid = null;
                for (java.util.UUID id : langManager.playerLang.keySet()) {
                    org.bukkit.OfflinePlayer off = org.bukkit.Bukkit.getOfflinePlayer(id);
                    if (off.getName() != null && off.getName().equalsIgnoreCase(targetName)) {
                        uuid = id;
                        break;
                    }
                }
                if (uuid != null) {
                    return langManager.getPlayerLang(uuid);
                } else {
                    return "&cUnknown player!";
                }
            }
        }
        return langManager.getTranslation(p, params);
    }
}
