package net.mysterria.translator.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.List;

public class MessageSerializer {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public static Component getMessage(FileConfiguration messageConfig, String path, String... placeholders) {
        String messageString = getMessageString(messageConfig, path, placeholders);
        return parseMessage(messageString);
    }

    /**
     * Parses a format string that may contain any mix of:
     *  - Legacy & codes (&c, &l, &r…)
     *  - § (section-sign) codes returned by some PAPI placeholders
     *  - Bukkit 6-byte legacy hex §x§R§R§G§G§B§B (→ after § normalisation: &x&R&R&G&G&B&B)
     *  - Shorthand hex &#RRGGBB
     *  - Full MiniMessage tags (<gradient:…>, <rainbow>, <#RRGGBB>, <hover:…>, etc.)
     */
    public static Component parseMessage(String message) {
        try {
            return MINI_MESSAGE.deserialize(prepareForMiniMessage(message));
        } catch (Exception e) {
            return LEGACY_SERIALIZER.deserialize(message);
        }
    }

    /**
     * Normalises all color-code formats to MiniMessage syntax without deserializing.
     * Use this when embedding the result in a larger MiniMessage expression (e.g. with
     * custom TagResolvers), so that §c, &#RRGGBB, &x&..., &c etc. all become proper tags.
     */
    public static String prepareForMiniMessage(String message) {
        String s = message.replace('§', '&');
        s = convertLegacyHexCodes(s);
        s = convertHexColors(s);
        s = convertLegacyToMiniMessage(s);
        return s;
    }

    public static MiniMessage getMiniMessage() {
        return MINI_MESSAGE;
    }

    /** &x&R&R&G&G&B&B (Bukkit/BungeeCord legacy hex, after § → & normalisation) → <#RRGGBB>. */
    private static String convertLegacyHexCodes(String message) {
        return message.replaceAll(
                "&x&([0-9A-Fa-f])&([0-9A-Fa-f])&([0-9A-Fa-f])&([0-9A-Fa-f])&([0-9A-Fa-f])&([0-9A-Fa-f])",
                "<#$1$2$3$4$5$6>");
    }

    /** &#RRGGBB (common shorthand) → <#RRGGBB>. */
    private static String convertHexColors(String message) {
        return message.replaceAll("&#([0-9A-Fa-f]{6})", "<#$1>");
    }

    private static String convertLegacyToMiniMessage(String message) {
        return message
                .replace("&0", "<black>")
                .replace("&1", "<dark_blue>")
                .replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>")
                .replace("&4", "<dark_red>")
                .replace("&5", "<dark_purple>")
                .replace("&6", "<gold>")
                .replace("&7", "<gray>")
                .replace("&8", "<dark_gray>")
                .replace("&9", "<blue>")
                .replace("&a", "<green>").replace("&A", "<green>")
                .replace("&b", "<aqua>").replace("&B", "<aqua>")
                .replace("&c", "<red>").replace("&C", "<red>")
                .replace("&d", "<light_purple>").replace("&D", "<light_purple>")
                .replace("&e", "<yellow>").replace("&E", "<yellow>")
                .replace("&f", "<white>").replace("&F", "<white>")
                .replace("&k", "<obfuscated>").replace("&K", "<obfuscated>")
                .replace("&l", "<bold>").replace("&L", "<bold>")
                .replace("&m", "<strikethrough>").replace("&M", "<strikethrough>")
                .replace("&n", "<underlined>").replace("&N", "<underlined>")
                .replace("&o", "<italic>").replace("&O", "<italic>")
                .replace("&r", "<reset>").replace("&R", "<reset>");
    }

    public static String getMessageString(FileConfiguration messageConfig, String path, String... placeholders) {
        Object messageObj = messageConfig.get(path);
        String message;
        String prefix = messageConfig.getString("prefix", "");

        if (messageObj instanceof String) {
            message = (String) messageObj;
        } else if (messageObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> messageList = (List<String>) messageObj;
            message = String.join("\n", messageList);
        } else {
            message = "&cMessage '" + path + "' not found in messages.yml.";
            return message;
        }

        for (int i = 0; i < placeholders.length; i += 2) {
            String key = placeholders[i];
            String value = (i + 1 < placeholders.length && placeholders[i + 1] != null) ? placeholders[i + 1] : "";
            message = message.replace(key, value);
        }

        return prefix + message;
    }
}
