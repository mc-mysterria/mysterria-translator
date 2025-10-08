package net.mysterria.translator.listener;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.translation.TranslationManager;
import net.mysterria.translator.translation.TranslationResult;
import net.mysterria.translator.util.LanguageDetector;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.mineacademy.chatcontrol.api.ChannelPostChatEvent;
import org.mineacademy.chatcontrol.api.PrePrivateMessageEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChatControlListener implements Listener {

    private final MysterriaTranslator plugin;
    private final TranslationManager translationManager;
    private final Set<String> translatingMessages;

    public ChatControlListener(MysterriaTranslator plugin, TranslationManager translationManager) {
        this.plugin = plugin;
        this.translationManager = translationManager;
        this.translatingMessages = ConcurrentHashMap.newKeySet();
    }

    @EventHandler
    public void onChannelPostChat(ChannelPostChatEvent event) {
        if (!plugin.getConfig().getBoolean("translation.enabled", true)) {
            plugin.debug("Translation is disabled, skipping ChatControl event");
            return;
        }

        plugin.debug("Sender is " + event.getSender().getName());

        String message = event.getMessage();
        String channelName = event.getChannel().getName();
        boolean isGlobalChannel = isGlobalChannel(channelName);

        if (event.getSender() instanceof Player sender) {
            plugin.debug("Processing ChatControl message from " + sender.getName() +
                         " in channel '" + channelName + "': " + message);

            String messageKey = sender.getUniqueId() + ":" + message.hashCode();
            if (translatingMessages.contains(messageKey)) {
                plugin.debug("Message already being translated, skipping: " + messageKey);
                return;
            }

            plugin.debug("Channel type - Global: " + isGlobalChannel + ", Range: " + !isGlobalChannel);

            Set<Player> originalRecipients = Set.copyOf(event.getRecipients());

            event.getRecipients().removeIf(player ->
                    !player.equals(sender) && needsTranslationForPlayer(message, player)
            );

            plugin.debug("Modified recipients for ChatControl delivery. Original: " +
                         originalRecipients.size() + ", Modified: " + event.getRecipients().size());

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                processTranslationForRemovedRecipients(sender, message, originalRecipients, event.getRecipients(), isGlobalChannel);
            }, 1L);
        } else {
            CommandSender sender = event.getSender();

            plugin.debug("Processing ChatControl message from " + sender.getName() +
                         " in channel '" + channelName + "': " + message);

            String messageKey = sender.getName() + ":" + message.hashCode();
            if (translatingMessages.contains(messageKey)) {
                plugin.debug("Message already being translated, skipping: " + messageKey);
                return;
            }

            Set<Player> originalRecipients = Set.copyOf(event.getRecipients());

            event.getRecipients().removeIf(player ->
                    !player.equals(sender) && needsTranslationForPlayer(message, player)
            );

            plugin.debug("Modified recipients for ChatControl delivery. Original: " +
                         originalRecipients.size() + ", Modified: " + event.getRecipients().size());

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                processTranslationForRemovedRecipients(sender, message, originalRecipients, event.getRecipients(), isGlobalChannel);
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrePrivateMessage(PrePrivateMessageEvent event) {
        if (!plugin.getConfig().getBoolean("translation.enabled", true)) {
            plugin.debug("Translation is disabled, skipping private message");
            return;
        }

        if (event.isCancelled()) {
            plugin.debug("Private message event was already cancelled, skipping");
            return;
        }

        Player sender = event.getSender().getPlayer();
        Player target = event.getReceiver();
        String message = event.getMessage();

        plugin.debug("Processing ChatControl private message from " + sender.getName() +
                     " to " + target.getName() + ": " + message);

        // DON'T cancel the event - let ChatControl handle it for reply tracking
        // Instead, schedule translation to happen after ChatControl processes it
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            translatePrivateMessage(sender, target, message);
        }, 1L); // 1 tick delay to let ChatControl finish
    }

    private void processTranslationForRemovedRecipients(Player sender, String message, Set<Player> originalRecipients, Set<Player> currentRecipients, boolean isGlobalChannel) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode();
        translatingMessages.add(messageKey);

        plugin.debug("Processing translation for recipients removed from ChatControl delivery");

        Set<Player> needsTranslation = ConcurrentHashMap.newKeySet();
        for (Player recipient : originalRecipients) {
            if (recipient.equals(sender)) continue;
            if (currentRecipients.contains(recipient)) continue; // This player got the original message

            if (needsTranslationForPlayer(message, recipient)) {
                needsTranslation.add(recipient);
                plugin.debug("Recipient " + recipient.getName() + " needs translation");
            } else {
                // Send original message immediately to those who don't need translation
                Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                recipient.sendMessage(originalMessage);
                plugin.debug("Sent original message to " + recipient.getName() + " (no translation needed)");
            }
        }

        if (!needsTranslation.isEmpty()) {
            plugin.debug("Starting optimized ChatControl translation for " + needsTranslation.size() + " removed recipients");
            translationManager.translateForMultiplePlayers(message, needsTranslation)
                    .whenComplete((results, throwable) -> {
                        translatingMessages.remove(messageKey);
                        plugin.debug("ChatControl batch translation completed for " + needsTranslation.size() + " recipients, removed from queue: " + messageKey);

                        if (throwable != null) {
                            plugin.debug("ChatControl batch translation error: " + throwable.getMessage());
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                for (Player recipient : needsTranslation) {
                                    recipient.sendMessage(originalMessage);
                                    plugin.debug("Sent original message to " + recipient.getName() + " due to translation error");
                                }
                            });
                            return;
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            for (Player recipient : needsTranslation) {
                                TranslationResult result = results.get(recipient.getUniqueId().toString());
                                if (result != null && result.wasTranslated()) {
                                    plugin.debug("Sending translated message to " + recipient.getName());
                                    Component translatedMessage = createFormattedMessage(sender, result.getTranslatedText(), true, result, isGlobalChannel);
                                    recipient.sendMessage(translatedMessage);
                                } else {
                                    plugin.debug("No translation result, sending original to " + recipient.getName());
                                    Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                    recipient.sendMessage(originalMessage);
                                }
                            }
                        });
                    });
        } else {
            translatingMessages.remove(messageKey);
            plugin.debug("No translation needed for removed recipients, removed from queue: " + messageKey);
        }
    }

    private void processTranslationForRemovedRecipients(CommandSender sender, String message, Set<Player> originalRecipients, Set<Player> currentRecipients, boolean isGlobalChannel) {
        String messageKey = sender.getName() + ":" + message.hashCode();
        translatingMessages.add(messageKey);

        plugin.debug("Processing translation for recipients removed from ChatControl delivery (non-player sender)");

        Set<Player> needsTranslation = ConcurrentHashMap.newKeySet();
        for (Player recipient : originalRecipients) {
            if (currentRecipients.contains(recipient)) continue; // This player got the original message

            if (needsTranslationForPlayer(message, recipient)) {
                needsTranslation.add(recipient);
                plugin.debug("Recipient " + recipient.getName() + " needs translation");
            } else {
                // Send original message immediately to those who don't need translation
                Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                recipient.sendMessage(originalMessage);
                plugin.debug("Sent original message to " + recipient.getName() + " (no translation needed)");
            }
        }

        if (!needsTranslation.isEmpty()) {
            plugin.debug("Starting optimized ChatControl translation for " + needsTranslation.size() + " removed recipients (non-player sender)");
            translationManager.translateForMultiplePlayers(message, needsTranslation)
                    .whenComplete((results, throwable) -> {
                        translatingMessages.remove(messageKey);
                        plugin.debug("ChatControl batch translation completed for " + needsTranslation.size() + " recipients, removed from queue: " + messageKey);

                        if (throwable != null) {
                            plugin.debug("ChatControl batch translation error: " + throwable.getMessage());
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                for (Player recipient : needsTranslation) {
                                    recipient.sendMessage(originalMessage);
                                    plugin.debug("Sent original message to " + recipient.getName() + " due to translation error");
                                }
                            });
                            return;
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            for (Player recipient : needsTranslation) {
                                TranslationResult result = results.get(recipient.getUniqueId().toString());
                                if (result != null && result.wasTranslated()) {
                                    plugin.debug("Sending translated message to " + recipient.getName());
                                    Component translatedMessage = createFormattedMessage(sender, result.getTranslatedText(), true, result, isGlobalChannel);
                                    recipient.sendMessage(translatedMessage);
                                } else {
                                    plugin.debug("No translation result, sending original to " + recipient.getName());
                                    Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                    recipient.sendMessage(originalMessage);
                                }
                            }
                        });
                    });
        } else {
            translatingMessages.remove(messageKey);
            plugin.debug("No translation needed for removed recipients, removed from queue: " + messageKey);
        }
    }

    private void processPerPlayerTranslation(Player sender, String message, Set<Player> recipients, boolean isGlobalChannel) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode();
        translatingMessages.add(messageKey);

        plugin.debug("Processing per-player translation for " + recipients.size() + " recipients");

        // Send original message to sender
        Component senderMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
        sender.sendMessage(senderMessage);

        // Process each recipient
        for (Player recipient : recipients) {
            if (recipient.equals(sender)) continue;

            // Check if this player needs translation
            if (needsTranslationForPlayer(message, recipient)) {
                plugin.debug("Player " + recipient.getName() + " needs translation");

                // Translate for this specific player
                translationManager.translateForPlayer(message, recipient)
                        .whenComplete((result, throwable) -> {
                            plugin.debug("Translation completed for " + recipient.getName());

                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (throwable != null) {
                                    plugin.debug("Translation error for " + recipient.getName() + ": " + throwable.getMessage());
                                    // Send original message on error
                                    Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                    recipient.sendMessage(originalMessage);
                                } else if (result.wasTranslated()) {
                                    plugin.debug("Sending translated message to " + recipient.getName());
                                    Component translatedMessage = createFormattedMessage(sender, result.getTranslatedText(), true, result, isGlobalChannel);
                                    recipient.sendMessage(translatedMessage);
                                } else {
                                    plugin.debug("No translation needed for " + recipient.getName());
                                    Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                    recipient.sendMessage(originalMessage);
                                }
                            });
                        });
            } else {
                plugin.debug("Player " + recipient.getName() + " doesn't need translation");
                // Send original message directly
                Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                recipient.sendMessage(originalMessage);
            }
        }

        translatingMessages.remove(messageKey);
        plugin.debug("Removed message from translation queue: " + messageKey);
    }

    private void translatePrivateMessage(Player sender, Player target, String message) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode() + ":pm";
        translatingMessages.add(messageKey);

        plugin.debug("Translating private message for " + target.getName());

        if (needsTranslationForPlayer(message, target)) {
            translationManager.translateForPlayer(message, target)
                    .whenComplete((result, throwable) -> {
                        translatingMessages.remove(messageKey);

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (throwable != null) {
                                plugin.debug("Private message translation error: " + throwable.getMessage());
                                // Send original message on error
                                Component originalMessage = createPrivateMessage(sender, message, false, null);
                                target.sendMessage(originalMessage);
                            } else if (result.wasTranslated()) {
                                plugin.debug("Sending translated private message to " + target.getName());
                                Component translatedMessage = createPrivateMessage(sender, result.getTranslatedText(), true, result);
                                target.sendMessage(translatedMessage);
                            } else {
                                plugin.debug("No translation needed for private message");
                                Component originalMessage = createPrivateMessage(sender, message, false, null);
                                target.sendMessage(originalMessage);
                            }
                        });
                    });
        } else {
            translatingMessages.remove(messageKey);
            plugin.debug("Private message doesn't need translation, not sending duplicate (ChatControl already handled it)");
            // Don't send anything - ChatControl already sent the original private message
        }
    }

    private boolean needsTranslationForPlayer(String message, Player player) {
        boolean needs = LanguageDetector.needsTranslation(message, player.locale().toString().toLowerCase());
        plugin.debug("Translation check for " + player.getName() + " (locale: " + player.locale() + "): " + needs);
        return needs;
    }

    private boolean isGlobalChannel(String channelName) {
        // Common global channel names - adjust based on your ChatControl setup
        return channelName.equalsIgnoreCase("global") ||
               channelName.equalsIgnoreCase("g") ||
               channelName.equalsIgnoreCase("mundo") || // Spanish for world
               channelName.equalsIgnoreCase("world") ||
               channelName.toLowerCase().contains("global") ||
               channelName.toLowerCase().contains("world");
    }

    private Component createFormattedMessage(Player sender, String message, boolean isTranslated, TranslationResult result, boolean isGlobalChannel) {
        String displayMode = plugin.getConfig().getString("translation.display.mode", "compact");

        if (displayMode.equals("custom")) {
            String format;
            if (isGlobalChannel) {
                format = plugin.getConfig().getString("translation.globalChat.globalFormat",
                        "&8[&bГ&8] {luckperms_prefix}&f{player_name}&7 >> &f{translated_message}");
            } else {
                format = plugin.getConfig().getString("translation.rangeChat.rangeFormat",
                        "&8[&eР&8] {luckperms_prefix}&f{player_name}&7 >> &f{translated_message}");
            }

            Component hoverText = null;
            if (isTranslated && result != null && plugin.getConfig().getBoolean("translation.display.showHover", true)) {
                hoverText = Component.text("Original: " + result.getOriginalText())
                        .color(NamedTextColor.GRAY)
                        .append(Component.newline())
                        .append(Component.text("Translated from " + result.getSourceLanguage() + " to " + result.getTargetLanguage())
                                .color(NamedTextColor.DARK_GRAY));
            }

            return createCustomFormattedMessage(sender, message, format, hoverText, result);
        } else {
            // Use standard chat format for other modes
            String prefix = isTranslated ? "[T] " : "";
            return Component.text("<" + sender.getName() + "> " + prefix + message)
                    .color(NamedTextColor.WHITE);
        }
    }

    private Component createFormattedMessage(CommandSender sender, String message, boolean isTranslated, TranslationResult result, boolean isGlobalChannel) {
        String displayMode = plugin.getConfig().getString("translation.display.mode", "compact");

        if (displayMode.equals("custom")) {
            String format;
            if (isGlobalChannel) {
                format = plugin.getConfig().getString("translation.globalChat.globalFormat",
                        "&8[&bГ&8] {luckperms_prefix}&f{player_name}&7 >> &f{translated_message}");
            } else {
                format = plugin.getConfig().getString("translation.rangeChat.rangeFormat",
                        "&8[&eР&8] {luckperms_prefix}&f{player_name}&7 >> &f{translated_message}");
            }

            Component hoverText = null;
            if (isTranslated && result != null && plugin.getConfig().getBoolean("translation.display.showHover", true)) {
                hoverText = Component.text("Original: " + result.getOriginalText())
                        .color(NamedTextColor.GRAY)
                        .append(Component.newline())
                        .append(Component.text("Translated from " + result.getSourceLanguage() + " to " + result.getTargetLanguage())
                                .color(NamedTextColor.DARK_GRAY));
            }

            return createCustomFormattedMessage(sender, message, format, hoverText, result);
        } else {
            // Use standard chat format for other modes
            String prefix = isTranslated ? "[T] " : "";
            return Component.text("<" + sender.getName() + "> " + prefix + message)
                    .color(NamedTextColor.WHITE);
        }
    }

    private Component createPrivateMessage(Player sender, String message, boolean isTranslated, TranslationResult result) {
        String displayMode = plugin.getConfig().getString("translation.display.mode", "compact");

        if (displayMode.equals("custom")) {
            String format = plugin.getConfig().getString("translation.display.customFormat",
                    "&8[&eТ&8] {luckperms_prefix}&f{player_name}&7 >> &f{translated_message}");

            Component hoverText = null;
            if (isTranslated && result != null && plugin.getConfig().getBoolean("translation.display.showHover", true)) {
                hoverText = Component.text("Original: " + result.getOriginalText())
                        .color(NamedTextColor.GRAY)
                        .append(Component.newline())
                        .append(Component.text("Translated from " + result.getSourceLanguage() + " to " + result.getTargetLanguage())
                                .color(NamedTextColor.DARK_GRAY));
            }

            return createCustomFormattedMessage(sender, message, format, hoverText, result);
        } else {
            // Use standard private message format for other modes
            String prefix = isTranslated ? "[T] " : "";
            return Component.text("[PM] " + sender.getName() + ": " + prefix + message)
                    .color(NamedTextColor.LIGHT_PURPLE);
        }
    }

    private Component createCustomFormattedMessage(Player sender, String message, String format, Component hoverText) {
        return createCustomFormattedMessage(sender, message, format, hoverText, null);
    }

    private Component createCustomFormattedMessage(Player sender, String message, String format, Component hoverText, TranslationResult result) {
        String formatted = format
                .replace("{player_name}", sender.getName())
                .replace("{translated_message}", message)
                .replace("{original_message}", message);

        // Add translation language placeholders if available
        if (result != null) {
            formatted = formatted
                    .replace("{source_language}", result.getSourceLanguage())
                    .replace("{target_language}", result.getTargetLanguage());
        } else {
            // For non-translated messages, use placeholders that indicate no translation
            formatted = formatted
                    .replace("{source_language}", "")
                    .replace("{target_language}", "")
                    .replace("[{source_language} -> {target_language}]", "")
                    .replace("[ -> ]", "");
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            formatted = PlaceholderAPI.setPlaceholders(sender, formatted);
        }

        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);

        if (hoverText != null) {
            component = component.hoverEvent(HoverEvent.showText(hoverText));
        }

        return component;
    }

    private Component createCustomFormattedMessage(CommandSender sender, String message, String format, Component hoverText) {
        return createCustomFormattedMessage(sender, message, format, hoverText, null);
    }

    private Component createCustomFormattedMessage(CommandSender sender, String message, String format, Component hoverText, TranslationResult result) {
        String formatted = format
                .replace("{player_name}", sender.getName())
                .replace("{translated_message}", message)
                .replace("{original_message}", message);

        // Add translation language placeholders if available
        if (result != null) {
            formatted = formatted
                    .replace("{source_language}", result.getSourceLanguage())
                    .replace("{target_language}", result.getTargetLanguage());
        } else {
            // For non-translated messages, use placeholders that indicate no translation
            formatted = formatted
                    .replace("{source_language}", "")
                    .replace("{target_language}", "")
                    .replace("[{source_language} -> {target_language}]", "")
                    .replace("[ -> ]", "");
        }

        // Note: PlaceholderAPI cannot be used with non-player senders (like Discord)
        // If sender is a Player, we could cast it, but for non-players we skip placeholder expansion
        // This is expected behavior for messages from Discord SRV and similar plugins

        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);

        if (hoverText != null) {
            component = component.hoverEvent(HoverEvent.showText(hoverText));
        }

        return component;
    }
}