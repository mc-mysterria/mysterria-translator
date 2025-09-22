package net.mysterria.translator.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.translation.TranslationManager;
import net.mysterria.translator.translation.TranslationResult;
import net.mysterria.translator.util.LanguageDetector;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChatListener implements Listener {

    private final MysterriaTranslator plugin;
    private final TranslationManager translationManager;
    private final Set<String> translatingMessages;

    private static final Set<String> PRIVATE_MESSAGE_COMMANDS = Set.of(
            "msg", "tell", "w", "whisper", "message", "pm", "reply", "r"
    );

    public ChatListener(MysterriaTranslator plugin, TranslationManager translationManager) {
        this.plugin = plugin;
        this.translationManager = translationManager;
        this.translatingMessages = ConcurrentHashMap.newKeySet();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("translation.enabled", true)) {
            plugin.debug("Translation is disabled, skipping chat event");
            return;
        }

        Player sender = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.debug("Processing chat message from " + sender.getName() + ": " + message);

        String messageKey = sender.getUniqueId() + ":" + message.hashCode();
        if (translatingMessages.contains(messageKey)) {
            plugin.debug("Message already being translated, skipping: " + messageKey);
            return;
        }

        processMessageTranslation(event, sender, message);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPrivateMessage(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("translation.enabled", true)) {
            plugin.debug("Translation is disabled, skipping private message");
            return;
        }

        String[] args = event.getMessage().substring(1).split(" ");
        if (args.length < 3) {
            plugin.debug("Private message command has insufficient arguments: " + args.length);
            return;
        }

        String command = args[0].toLowerCase();
        if (!PRIVATE_MESSAGE_COMMANDS.contains(command)) {
            plugin.debug("Command '" + command + "' is not a private message command");
            return;
        }

        Player sender = event.getPlayer();
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            plugin.debug("Target player '" + args[1] + "' not found for private message");
            return;
        }

        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        plugin.debug("Processing private message from " + sender.getName() + " to " + target.getName() + ": " + message);

        translatePrivateMessage(sender, target, message);
    }

    private void translateForOtherPlayers(Player sender, String message, java.util.Collection<? extends net.kyori.adventure.audience.Audience> viewers) {
        for (net.kyori.adventure.audience.Audience viewer : viewers) {
            if (viewer instanceof Player player && !player.equals(sender)) {
                translateAndSendMessage(sender, player, message, false);
            }
        }
    }

    private void processMessageTranslation(AsyncChatEvent event, Player sender, String message) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode();
        translatingMessages.add(messageKey);
        plugin.debug("Added message to translation queue: " + messageKey);

        java.util.Set<Player> allPlayers = new java.util.HashSet<>(Bukkit.getOnlinePlayers());
        java.util.Set<Player> originalAudience = new java.util.HashSet<>();
        java.util.Set<Player> translationNeeded = new java.util.HashSet<>();

        java.util.Collection<net.kyori.adventure.audience.Audience> viewers = event.viewers();
        plugin.debug("Event viewers size: " + viewers.size());
        
        if (viewers.isEmpty()) {
            plugin.debug("No viewers in event, using all online players as fallback");
            for (Player player : allPlayers) {
                if (!player.equals(sender)) {
                    if (needsTranslationForPlayer(message, player)) {
                        translationNeeded.add(player);
                        plugin.debug("Player " + player.getName() + " needs translation (locale: " + player.locale() + ")");
                    } else {
                        originalAudience.add(player);
                        plugin.debug("Player " + player.getName() + " doesn't need translation (locale: " + player.locale() + ")");
                    }
                }
            }
        } else {
            for (net.kyori.adventure.audience.Audience viewer : viewers) {
                if (viewer instanceof Player player && !player.equals(sender)) {
                    if (needsTranslationForPlayer(message, player)) {
                        translationNeeded.add(player);
                        plugin.debug("Player " + player.getName() + " needs translation (locale: " + player.locale() + ")");
                    } else {
                        originalAudience.add(player);
                        plugin.debug("Player " + player.getName() + " doesn't need translation (locale: " + player.locale() + ")");
                    }
                } else if (viewer instanceof Player) {
                    originalAudience.add((Player) viewer);
                }
            }
            
            event.viewers().clear();
            event.viewers().addAll(originalAudience);
        }
        
        plugin.debug("Original audience size: " + originalAudience.size() + ", Translation needed for: " + translationNeeded.size() + " players");

        if (!translationNeeded.isEmpty()) {
            plugin.debug("Starting translation for " + translationNeeded.size() + " players");
            for (Player player : translationNeeded) {
                plugin.debug("Requesting translation for player: " + player.getName());
                translationManager.translateForPlayer(message, player)
                        .whenComplete((result, throwable) -> {
                            translatingMessages.remove(messageKey);
                            plugin.debug("Translation completed for " + player.getName() + ", removed from queue: " + messageKey);

                            if (throwable != null) {
                                plugin.debug("Translation error for " + player.getName() + ": " + throwable.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    Component originalMessage = createOriginalMessage(sender, message);
                                    player.sendMessage(originalMessage);
                                    plugin.debug("Sent original message to " + player.getName() + " due to translation error");
                                });
                                return;
                            }

                            plugin.debug("Translation result for " + player.getName() + ": " + result.getType() + ", translated: " + result.wasTranslated());
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Component messageComponent = createTranslatedMessage(result, sender);
                                if (messageComponent != null) {
                                    player.sendMessage(messageComponent);
                                    plugin.debug("Sent translated message to " + player.getName());
                                } else {
                                    Component originalMessage = createOriginalMessage(sender, message);
                                    player.sendMessage(originalMessage);
                                    plugin.debug("Sent original message to " + player.getName() + " (no translation needed)");
                                }
                            });
                        });
            }
        } else {
            translatingMessages.remove(messageKey);
            plugin.debug("No translation needed, removed from queue: " + messageKey);
        }
    }

    private boolean needsTranslationForPlayer(String message, Player player) {
        boolean needs = LanguageDetector.needsTranslation(message, player.locale().toString().toLowerCase());
        plugin.debug("Translation check for " + player.getName() + " (locale: " + player.locale() + "): " + needs);
        return needs;
    }

    private void translatePrivateMessage(Player sender, Player target, String message) {
        translateAndSendMessage(sender, target, message, true);
    }

    private void translateAndSendMessage(Player sender, Player target, String message, boolean isPrivate) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode();
        translatingMessages.add(messageKey);
        plugin.debug("Added private message to translation queue: " + messageKey + " (isPrivate: " + isPrivate + ")");

        plugin.debug("Requesting translation for private message to " + target.getName());
        translationManager.translateForPlayer(message, target)
                .whenComplete((result, throwable) -> {
                    translatingMessages.remove(messageKey);
                    plugin.debug("Private message translation completed for " + target.getName() + ", removed from queue: " + messageKey);

                    if (throwable != null) {
                        plugin.debug("Translation error for " + target.getName() + ": " + throwable.getMessage());
                        return;
                    }

                    plugin.debug("Private message translation result for " + target.getName() + ": " + result.getType() + ", translated: " + result.wasTranslated());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Component messageComponent = createMessageComponent(result, sender, isPrivate);
                        if (messageComponent != null) {
                            target.sendMessage(messageComponent);
                            plugin.debug("Sent translated private message to " + target.getName());

                            if (plugin.getConfig().getBoolean("translation.debug", false)) {
                                plugin.debug("Sent translation to " + target.getName() + ": " + result.getType());
                            }
                        } else {
                            plugin.debug("No message component created for " + target.getName() + " (translation not needed)");
                        }
                    });
                });
    }

    private Component createMessageComponent(TranslationResult result, Player sender, boolean isPrivate) {
        if (!result.wasTranslated()) {
            return null;
        }

        String displayMode = plugin.getConfig().getString("translation.display.mode", "compact");
        String prefix = plugin.getConfig().getString("translation.display.prefix", "[T]");
        boolean showHover = plugin.getConfig().getBoolean("translation.display.showHover", true);
        String indicatorColor = plugin.getConfig().getString("translation.display.indicatorColor", "aqua");

        NamedTextColor color = parseColor(indicatorColor);

        Component hoverText = null;
        if (showHover) {
            hoverText = Component.text("Original: " + result.getOriginalText())
                    .color(NamedTextColor.GRAY)
                    .append(Component.newline())
                    .append(Component.text("Translated from " + result.getSourceLanguage() + " to " + result.getTargetLanguage())
                            .color(NamedTextColor.DARK_GRAY));
        }

        String pmPrefix = isPrivate ? "[PM] " : "";
        NamedTextColor baseColor = isPrivate ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.WHITE;
        String senderFormat = isPrivate ? sender.getName() + ": " : "<" + sender.getName() + "> ";

        Component messageContent = Component.text(result.getTranslatedText())
                .color(NamedTextColor.WHITE);
        if (showHover && hoverText != null) {
            messageContent = messageContent.hoverEvent(HoverEvent.showText(hoverText));
        }

        switch (displayMode.toLowerCase()) {
            case "replace":
                // Show only translated text without indicator
                return Component.text(pmPrefix + senderFormat)
                        .color(baseColor)
                        .append(messageContent);

            case "separate":
                // Separate message with indicator
                Component translationIndicator = Component.text(prefix + " ")
                        .color(color);
                if (showHover) {
                    translationIndicator = translationIndicator.hoverEvent(HoverEvent.showText(
                            Component.text("This message was automatically translated").color(NamedTextColor.GRAY)));
                }
                return Component.text(pmPrefix + senderFormat)
                        .color(baseColor)
                        .append(translationIndicator)
                        .append(messageContent);

            case "custom":
                // Custom format with PlaceholderAPI support for private messages
                String customFormat = plugin.getConfig().getString("translation.display.customFormat",
                        "&8[&eТ&8] {luckperms_prefix}&f{player_name}&7 >> &f{player_chat_color}{player_chat_decoration}{translated_message}");
                return createCustomFormattedMessage(result, sender, customFormat, showHover ? hoverText : null);

            case "compact":
            default:
                // Compact mode with small indicator
                Component indicator = Component.text("ᵀ ")
                        .color(color);
                if (showHover) {
                    indicator = indicator.hoverEvent(HoverEvent.showText(
                            Component.text("Translated message").color(NamedTextColor.GRAY)));
                }
                return Component.text(pmPrefix + senderFormat)
                        .color(baseColor)
                        .append(indicator)
                        .append(messageContent);
        }
    }

    private Component createTranslatedMessage(TranslationResult result, Player sender) {
        if (!result.wasTranslated()) {
            return null;
        }

        String displayMode = plugin.getConfig().getString("translation.display.mode", "compact");
        String prefix = plugin.getConfig().getString("translation.display.prefix", "[T]");
        boolean showHover = plugin.getConfig().getBoolean("translation.display.showHover", true);
        String indicatorColor = plugin.getConfig().getString("translation.display.indicatorColor", "aqua");

        NamedTextColor color = parseColor(indicatorColor);

        Component hoverText = null;
        if (showHover) {
            hoverText = Component.text("Original: " + result.getOriginalText())
                    .color(NamedTextColor.GRAY)
                    .append(Component.newline())
                    .append(Component.text("Translated from " + result.getSourceLanguage() + " to " + result.getTargetLanguage())
                            .color(NamedTextColor.DARK_GRAY));
        }

        switch (displayMode.toLowerCase()) {
            case "replace":
                // Show only translated text without indicator
                Component translatedText = Component.text(result.getTranslatedText())
                        .color(NamedTextColor.WHITE);
                if (showHover && hoverText != null) {
                    translatedText = translatedText.hoverEvent(HoverEvent.showText(hoverText));
                }
                return Component.text("<" + sender.getName() + "> ")
                        .color(NamedTextColor.WHITE)
                        .append(translatedText);

            case "separate":
                // Current behavior - separate message with indicator
                Component translationIndicator = Component.text(prefix + " ")
                        .color(color);
                if (showHover) {
                    translationIndicator = translationIndicator.hoverEvent(HoverEvent.showText(
                            Component.text("This message was automatically translated").color(NamedTextColor.GRAY)));
                }
                return Component.text("<" + sender.getName() + "> ")
                        .color(NamedTextColor.WHITE)
                        .append(translationIndicator)
                        .append(Component.text(result.getTranslatedText())
                                .color(NamedTextColor.WHITE)
                                .hoverEvent(showHover ? HoverEvent.showText(hoverText) : null));

            case "custom":
                // Custom format with PlaceholderAPI support
                String customFormat = plugin.getConfig().getString("translation.display.customFormat",
                        "&8[&eТ&8] {luckperms_prefix}&f{player_name}&7 >> &f{player_chat_color}{player_chat_decoration}{translated_message}");
                return createCustomFormattedMessage(result, sender, customFormat, showHover ? hoverText : null);

            case "compact":
            default:
                // Compact mode - show translation with small indicator
                Component indicator = Component.text("ᵀ ")
                        .color(color);
                if (showHover) {
                    indicator = indicator.hoverEvent(HoverEvent.showText(
                            Component.text("Translated message").color(NamedTextColor.GRAY)));
                }
                Component message = Component.text(result.getTranslatedText())
                        .color(NamedTextColor.WHITE);
                if (showHover && hoverText != null) {
                    message = message.hoverEvent(HoverEvent.showText(hoverText));
                }
                return Component.text("<" + sender.getName() + "> ")
                        .color(NamedTextColor.WHITE)
                        .append(indicator)
                        .append(message);
        }
    }

    private NamedTextColor parseColor(String colorString) {
        try {
            return NamedTextColor.NAMES.value(colorString.toLowerCase());
        } catch (Exception e) {
            return NamedTextColor.AQUA; // Default fallback
        }
    }

    private Component createOriginalMessage(Player sender, String message) {
        return Component.text("<" + sender.getName() + "> " + message)
                .color(NamedTextColor.WHITE);
    }

    private Component createCustomFormattedMessage(TranslationResult result, Player sender, String customFormat, Component hoverText) {
        String formatted = customFormat
                .replace("{player_name}", sender.getName())
                .replace("{translated_message}", result.getTranslatedText())
                .replace("{original_message}", result.getOriginalText())
                .replace("{source_language}", result.getSourceLanguage())
                .replace("{target_language}", result.getTargetLanguage());

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            formatted = PlaceholderAPI.setPlaceholders(sender, formatted);
        }

        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);

        if (hoverText != null) {
            component = component.hoverEvent(HoverEvent.showText(hoverText));
        }

        return component;
    }
}