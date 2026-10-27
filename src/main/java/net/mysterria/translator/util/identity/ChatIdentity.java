package net.mysterria.translator.util.identity;

import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ChatIdentity {

    private static final String POSSESSION_KEY = "coi_possessed_chat_identity";
    private static final String POSSESSION_OWNER = "CircleOfImagination";

    public static String displayName(CommandSender sender) {
        if (sender instanceof Player player
                && Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")
                && DisguiseAPI.getDisguise(player) instanceof PlayerDisguise disguise) {
            return disguise.getName();
        }
        return sender.getName();
    }

    public static String possessed(Player player) {
        for (var metadata : player.getMetadata(POSSESSION_KEY)) {
            var owner = metadata.getOwningPlugin();
            if (owner == null || !owner.isEnabled() || !owner.getName().equals(POSSESSION_OWNER)) {
                continue;
            }
            if (metadata.value() instanceof String name && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    public static String withPossessedName(String format) {
        return format.replace("%lang_display_name%", "<coi_identity>")
                .replace("%player_name%", "<coi_identity>")
                .replace("%player_displayname%", "<coi_identity>")
                .replace("{player_name}", "<coi_identity>");
    }
}
