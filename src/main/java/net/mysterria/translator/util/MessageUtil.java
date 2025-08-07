package net.mysterria.translator.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.List;

public class MessageUtil {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    
    public static Component getMessage(FileConfiguration messageConfig, String path, String... placeholders) {
        String messageString = getMessageString(messageConfig, path, placeholders);
        return parseMessage(messageString);
    }
    
    public static Component parseMessage(String message) {
        if (containsMiniMessageTags(message)) {
            try {
                return MINI_MESSAGE.deserialize(message);
            } catch (Exception e) {
                return LEGACY_SERIALIZER.deserialize(message);
            }
        } else {
            return LEGACY_SERIALIZER.deserialize(message);
        }
    }
    
    private static boolean containsMiniMessageTags(String message) {
        return message.contains("<") && message.contains(">") && 
               (message.contains("<gradient:") || message.contains("<rainbow") || 
                message.contains("<color:") || message.contains("<#") ||
                message.contains("<bold>") || message.contains("<italic>") ||
                message.contains("<underlined>") || message.contains("<strikethrough>") ||
                message.contains("<obfuscated>") || message.contains("<reset>"));
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

        String finalMessage = prefix + message;
        return finalMessage;
    }
}