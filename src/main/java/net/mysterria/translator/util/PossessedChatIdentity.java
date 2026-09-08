package net.mysterria.translator.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Optional COI API bridge; older COI builds and servers without COI keep their normal names. */
public final class PossessedChatIdentity {
    private PossessedChatIdentity() { }

    public static String capture(Player player) {
        var plugin = Bukkit.getPluginManager().getPlugin("CircleOfImagination");
        if (plugin == null || !plugin.isEnabled()) return null;
        try {
            Class<?> api = Class.forName("dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI", false,
                    plugin.getClass().getClassLoader());
            Object provider = Bukkit.getServicesManager().load(api);
            if (provider == null) return null;
            Object value = api.getMethod("getPossessedChatIdentity", Player.class).invoke(provider, player);
            return value instanceof String name && !name.isBlank() ? name : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    public static String localFormat(String format, String identity) {
        if (identity == null) return format;
        return format.replace("%lang_display_name%", "<coi_identity>")
                .replace("%player_name%", "<coi_identity>")
                .replace("%player_displayname%", "<coi_identity>")
                .replace("{player_name}", "<coi_identity>");
    }
}
