package net.mysterria.translator.listener;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.translation.TranslationManager;
import net.mysterria.translator.translation.TranslationResult;
import net.mysterria.translator.util.DisguiseUtil;
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
            return;
        }

        String message = event.getMessage();
        String channelName = event.getChannel().getName();
        boolean isGlobalChannel = isGlobalChannel(channelName);

        if (event.getSender() instanceof Player sender) {
            String messageKey = sender.getUniqueId() + ":" + message.hashCode();
            if (translatingMessages.contains(messageKey)) {
                return;
            }

            Set<Player> originalRecipients = Set.copyOf(event.getRecipients());

            event.getRecipients().removeIf(player ->
                    !player.equals(sender) && needsTranslationForPlayer(message, player)
            );

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                processTranslationForRemovedRecipients(sender, message, originalRecipients, event.getRecipients(), isGlobalChannel);
            }, 1L);
        } else {
            CommandSender sender = event.getSender();

            String messageKey = sender.getName() + ":" + message.hashCode();
            if (translatingMessages.contains(messageKey)) {
                return;
            }

            Set<Player> originalRecipients = Set.copyOf(event.getRecipients());

            event.getRecipients().removeIf(player ->
                    !player.equals(sender) && needsTranslationForPlayer(message, player)
            );

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                processTranslationForRemovedRecipients(sender, message, originalRecipients, event.getRecipients(), isGlobalChannel);
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrePrivateMessage(PrePrivateMessageEvent event) {
        if (!plugin.getConfig().getBoolean("translation.enabled", true)) {
            return;
        }

        if (event.isCancelled()) {
            return;
        }

        Player sender = event.getSender().getPlayer();
        Player target = event.getReceiver();
        String message = event.getMessage();

        if (sender != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                translatePrivateMessage(sender, target, message);
            }, 1L);
        }
    }

    private void processTranslationForRemovedRecipients(Player sender, String message, Set<Player> originalRecipients, Set<Player> currentRecipients, boolean isGlobalChannel) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode();
        translatingMessages.add(messageKey);

        Set<Player> needsTranslation = ConcurrentHashMap.newKeySet();
        for (Player recipient : originalRecipients) {
            if (recipient.equals(sender)) continue;
            if (currentRecipients.contains(recipient)) continue;

            if (needsTranslationForPlayer(message, recipient)) {
                needsTranslation.add(recipient);
            } else {

                Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                recipient.sendMessage(originalMessage);
            }
        }

        if (!needsTranslation.isEmpty()) {
            plugin.debug("Translating message from " + sender.getName() + " for " + needsTranslation.size() + " player(s)");
            translationManager.translateForMultiplePlayers(message, needsTranslation)
                    .whenComplete((results, throwable) -> {
                        translatingMessages.remove(messageKey);

                        if (throwable != null) {
                            plugin.debug("Translation error: " + throwable.getMessage());
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                for (Player recipient : needsTranslation) {
                                    recipient.sendMessage(originalMessage);
                                }
                            });
                            return;
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            int translatedCount = 0;
                            for (Player recipient : needsTranslation) {
                                TranslationResult result = results.get(recipient.getUniqueId().toString());
                                if (result != null && result.wasTranslated()) {
                                    translatedCount++;
                                    Component translatedMessage = createFormattedMessage(sender, result.getTranslatedText(), true, result, isGlobalChannel);
                                    recipient.sendMessage(translatedMessage);
                                } else {
                                    Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                    recipient.sendMessage(originalMessage);
                                }
                            }
                            if (translatedCount > 0) {
                                plugin.debug("Sent " + translatedCount + " translated message(s)");
                            }
                        });
                    });
        } else {
            translatingMessages.remove(messageKey);
        }
    }

    private void processTranslationForRemovedRecipients(CommandSender sender, String message, Set<Player> originalRecipients, Set<Player> currentRecipients, boolean isGlobalChannel) {
        String messageKey = sender.getName() + ":" + message.hashCode();
        translatingMessages.add(messageKey);

        Set<Player> needsTranslation = ConcurrentHashMap.newKeySet();
        for (Player recipient : originalRecipients) {
            if (currentRecipients.contains(recipient)) continue;

            if (needsTranslationForPlayer(message, recipient)) {
                needsTranslation.add(recipient);
            } else {

                Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                recipient.sendMessage(originalMessage);
            }
        }

        if (!needsTranslation.isEmpty()) {
            plugin.debug("Translating message from " + sender.getName() + " for " + needsTranslation.size() + " player(s)");
            translationManager.translateForMultiplePlayers(message, needsTranslation)
                    .whenComplete((results, throwable) -> {
                        translatingMessages.remove(messageKey);

                        if (throwable != null) {
                            plugin.debug("Translation error: " + throwable.getMessage());
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                for (Player recipient : needsTranslation) {
                                    recipient.sendMessage(originalMessage);
                                }
                            });
                            return;
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            int translatedCount = 0;
                            for (Player recipient : needsTranslation) {
                                TranslationResult result = results.get(recipient.getUniqueId().toString());
                                if (result != null && result.wasTranslated()) {
                                    translatedCount++;
                                    Component translatedMessage = createFormattedMessage(sender, result.getTranslatedText(), true, result, isGlobalChannel);
                                    recipient.sendMessage(translatedMessage);
                                } else {
                                    Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                    recipient.sendMessage(originalMessage);
                                }
                            }
                            if (translatedCount > 0) {
                                plugin.debug("Sent " + translatedCount + " translated message(s)");
                            }
                        });
                    });
        } else {
            translatingMessages.remove(messageKey);
        }
    }

    private void processPerPlayerTranslation(Player sender, String message, Set<Player> recipients, boolean isGlobalChannel) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode();
        translatingMessages.add(messageKey);


        Component senderMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
        sender.sendMessage(senderMessage);

        int translationCount = 0;

        for (Player recipient : recipients) {
            if (recipient.equals(sender)) continue;


            if (needsTranslationForPlayer(message, recipient)) {
                translationCount++;

                translationManager.translateForPlayer(message, recipient)
                        .whenComplete((result, throwable) -> {
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (throwable != null) {
                                    plugin.debug("Translation error: " + throwable.getMessage());

                                    Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                    recipient.sendMessage(originalMessage);
                                } else if (result.wasTranslated()) {
                                    Component translatedMessage = createFormattedMessage(sender, result.getTranslatedText(), true, result, isGlobalChannel);
                                    recipient.sendMessage(translatedMessage);
                                } else {
                                    Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                                    recipient.sendMessage(originalMessage);
                                }
                            });
                        });
            } else {

                Component originalMessage = createFormattedMessage(sender, message, false, null, isGlobalChannel);
                recipient.sendMessage(originalMessage);
            }
        }

        if (translationCount > 0) {
            plugin.debug("Translating message from " + sender.getName() + " for " + translationCount + " player(s)");
        }

        translatingMessages.remove(messageKey);
    }

    private void translatePrivateMessage(Player sender, Player target, String message) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode() + ":pm";
        translatingMessages.add(messageKey);

        if (needsTranslationForPlayer(message, target)) {
            plugin.debug("Translating private message from " + sender.getName() + " to " + target.getName());
            translationManager.translateForPlayer(message, target)
                    .whenComplete((result, throwable) -> {
                        translatingMessages.remove(messageKey);

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (throwable != null) {
                                plugin.debug("Translation error: " + throwable.getMessage());

                                Component originalMessage = createPrivateMessage(sender, message, false, null);
                                target.sendMessage(originalMessage);
                            } else if (result.wasTranslated()) {
                                Component translatedMessage = createPrivateMessage(sender, result.getTranslatedText(), true, result);
                                target.sendMessage(translatedMessage);
                            } else {
                                Component originalMessage = createPrivateMessage(sender, message, false, null);
                                target.sendMessage(originalMessage);
                            }
                        });
                    });
        } else {
            translatingMessages.remove(messageKey);

        }
    }

    private boolean needsTranslationForPlayer(String message, Player player) {
        return LanguageDetector.needsTranslation(message, player.locale().toString().toLowerCase());
    }

    private boolean isGlobalChannel(String channelName) {
        return channelName.equalsIgnoreCase("global") ||
               channelName.equalsIgnoreCase("g") ||
               channelName.equalsIgnoreCase("mundo") ||
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

            String prefix = isTranslated ? "[T] " : "";
            return Component.text("<" + DisguiseUtil.getChatName(sender) + "> " + prefix + message)
                    .color(NamedTextColor.WHITE);
        }
    }

    private Component createFormattedMessage(CommandSender sender, String message, boolean isTranslated, TranslationResult result, boolean isGlobalChannel) {
        String displayMode = plugin.getConfig().getString("translation.display.mode", "compact");

        if (displayMode.equals("custom")) {
            String format;
            if (isGlobalChannel) {
                format = plugin.getConfig().getString("translation.globalChat.consoleFormat",
                        "&8[&bГ&8] &f{player_name}&7 >> &f{translated_message}");
            } else {
                format = plugin.getConfig().getString("translation.rangeChat.consoleFormat",
                        "&8[&eР&8] &f{player_name}&7 >> &f{translated_message}");
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

            String prefix = isTranslated ? "[T] " : "";
            return Component.text("[PM] " + DisguiseUtil.getChatName(sender) + ": " + prefix + message)
                    .color(NamedTextColor.LIGHT_PURPLE);
        }
    }

    private Component createCustomFormattedMessage(Player sender, String message, String format, Component hoverText) {
        return createCustomFormattedMessage(sender, message, format, hoverText, null);
    }

    private Component createCustomFormattedMessage(Player sender, String message, String format, Component hoverText, TranslationResult result) {
        String formatted = format
                .replace("{player_name}", DisguiseUtil.getChatName(sender))
                .replace("{translated_message}", message)
                .replace("{original_message}", message);


        if (result != null) {
            formatted = formatted
                    .replace("{source_language}", result.getSourceLanguage())
                    .replace("{target_language}", result.getTargetLanguage());
        } else {

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


        if (result != null) {
            formatted = formatted
                    .replace("{source_language}", result.getSourceLanguage())
                    .replace("{target_language}", result.getTargetLanguage());
        } else {

            formatted = formatted
                    .replace("{source_language}", "")
                    .replace("{target_language}", "")
                    .replace("[{source_language} -> {target_language}]", "")
                    .replace("[ -> ]", "");
        }

        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);

        if (hoverText != null) {
            component = component.hoverEvent(HoverEvent.showText(hoverText));
        }

        return component;
    }
}