package net.mysterria.translator.util;

import org.bukkit.entity.Player;

/** Reads COI's optional possession metadata; other servers keep their normal names. */
public final class PossessedChatIdentity {
    private PossessedChatIdentity() { }

    public static String capture(Player player) {
        for (var metadata : player.getMetadata("coi_possessed_chat_identity")) {
            var owner = metadata.getOwningPlugin();
            if (owner == null || !owner.isEnabled() || !owner.getName().equals("CircleOfImagination")) continue;
            Object value = metadata.value();
            if (value instanceof String name && !name.isBlank()) return name;
        }
        return null;
    }

    public static String localFormat(String format, String identity) {
        if (identity == null) return format;
        return format.replace("%lang_display_name%", "<coi_identity>")
                .replace("%player_name%", "<coi_identity>")
                .replace("%player_displayname%", "<coi_identity>")
                .replace("{player_name}", "<coi_identity>");
    }
}
