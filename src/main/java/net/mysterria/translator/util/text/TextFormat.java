package net.mysterria.translator.util.text;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.Nullable;

public class TextFormat {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    public static MiniMessage miniMessage() {
        return MINI_MESSAGE;
    }

    public static Component parse(String message) {
        try {
            return MINI_MESSAGE.deserialize(prepare(message));
        } catch (RuntimeException e) {
            return LEGACY.deserialize(message);
        }
    }

    public static String toLegacy(Component component) {
        return LEGACY.serialize(component);
    }

    public static Component render(String format, @Nullable OfflinePlayer subject, TagResolver... resolvers) {
        String resolved = format;
        if (subject != null) {
            resolved = PlaceholderAPI.setBracketPlaceholders(subject, PlaceholderAPI.setPlaceholders(subject, resolved));
        }
        return MINI_MESSAGE.deserialize(prepare(resolved), resolvers);
    }

    public static String prepare(String message) {
        int length = message.length();
        StringBuilder out = new StringBuilder(length + 16);
        for (int i = 0; i < length; i++) {
            char c = message.charAt(i);
            if (!isCodeMarker(c) || i + 1 >= length) {
                out.append(c);
                continue;
            }
            char code = Character.toLowerCase(message.charAt(i + 1));
            if (code == '#' && i + 7 < length && isHex(message, i + 2, i + 8)) {
                out.append("<#").append(message, i + 2, i + 8).append('>');
                i += 7;
            } else if (code == 'x' && i + 13 < length && isLegacyHex(message, i + 2)) {
                out.append("<#");
                for (int digit = 0; digit < 6; digit++) {
                    out.append(message.charAt(i + 3 + digit * 2));
                }
                out.append('>');
                i += 13;
            } else if (legacyTag(code) != null) {
                out.append('<').append(legacyTag(code)).append('>');
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isCodeMarker(char c) {
        return c == '&' || c == '§';
    }

    private static boolean isHex(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLegacyHex(String text, int from) {
        for (int digit = 0; digit < 6; digit++) {
            int marker = from + digit * 2;
            if (!isCodeMarker(text.charAt(marker)) || Character.digit(text.charAt(marker + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String legacyTag(char code) {
        return switch (code) {
            case '0' -> "black";
            case '1' -> "dark_blue";
            case '2' -> "dark_green";
            case '3' -> "dark_aqua";
            case '4' -> "dark_red";
            case '5' -> "dark_purple";
            case '6' -> "gold";
            case '7' -> "gray";
            case '8' -> "dark_gray";
            case '9' -> "blue";
            case 'a' -> "green";
            case 'b' -> "aqua";
            case 'c' -> "red";
            case 'd' -> "light_purple";
            case 'e' -> "yellow";
            case 'f' -> "white";
            case 'k' -> "obfuscated";
            case 'l' -> "bold";
            case 'm' -> "strikethrough";
            case 'n' -> "underlined";
            case 'o' -> "italic";
            case 'r' -> "reset";
            default -> null;
        };
    }
}
