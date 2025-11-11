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

public class BukkitChatListener implements Listener {

    private final MysterriaTranslator plugin;
    private final TranslationManager translationManager;
    private final Set<String> translatingMessages;
    private final java.util.Map<java.util.UUID, java.util.UUID> lastMessagePartners;

    private static final Set<String> PRIVATE_MESSAGE_COMMANDS = Set.of(
            "msg", "tell", "w", "whisper", "message", "pm"
    );

    private static final Set<String> REPLY_COMMANDS = Set.of(
            "reply", "r"
    );

    public BukkitChatListener(MysterriaTranslator plugin, TranslationManager translationManager) {
        this.plugin = plugin;
        this.translationManager = translationManager;
        this.translatingMessages = ConcurrentHashMap.newKeySet();
        this.lastMessagePartners = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
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

        
        if (event.isCancelled()) {
            plugin.debug("Chat event was cancelled by another plugin, checking for global chat processing");

            
            if (isGlobalChatEnabled() && isGlobalChatMessage(message)) {
                plugin.debug("Processing cancelled global chat message from " + sender.getName());
                processGlobalChatMessageCancelled(sender, message);
                return;
            }

            plugin.debug("Event cancelled but not a global chat message, skipping");
            return;
        }

        
        if (isGlobalChatEnabled()) {
            if (isGlobalChatMessage(message)) {
                plugin.debug("Processing global chat message from " + sender.getName());
                processGlobalChatMessage(event, sender, message);
                return;
            } else if (isRangeChatEnabled()) {
                plugin.debug("Processing range chat message from " + sender.getName());
                processRangeChatMessage(event, sender, message);
                return;
            } else {
                plugin.debug("Message doesn't start with global chat prefix and range chat is disabled, skipping translation");
                return; 
            }
        }

        processMessageTranslation(event, sender, message);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPrivateMessage(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("translation.enabled", true)) {
            plugin.debug("Translation is disabled, skipping private message");
            return;
        }

        String[] args = event.getMessage().substring(1).split(" ");
        if (args.length < 2) {
            plugin.debug("Private message command has insufficient arguments: " + args.length);
            return;
        }

        String command = args[0].toLowerCase();
        Player sender = event.getPlayer();

        
        if (PRIVATE_MESSAGE_COMMANDS.contains(command)) {
            if (args.length < 3) {
                plugin.debug("Private message command has insufficient arguments for target: " + args.length);
                return;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                plugin.debug("Target player '" + args[1] + "' not found for private message");
                return;
            }

            String message = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            plugin.debug("Processing private message from " + sender.getName() + " to " + target.getName() + ": " + message);

            
            lastMessagePartners.put(sender.getUniqueId(), target.getUniqueId());
            lastMessagePartners.put(target.getUniqueId(), sender.getUniqueId());

            translatePrivateMessage(sender, target, message);
            return;
        }

        
        if (REPLY_COMMANDS.contains(command)) {
            plugin.debug("Detected reply command from " + sender.getName());

            
            java.util.UUID targetUUID = lastMessagePartners.get(sender.getUniqueId());
            if (targetUUID == null) {
                plugin.debug("No last message partner found for " + sender.getName());
                return;
            }

            Player target = Bukkit.getPlayer(targetUUID);
            if (target == null) {
                plugin.debug("Last message partner for " + sender.getName() + " is no longer online");
                return;
            }

            String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            plugin.debug("Processing reply from " + sender.getName() + " to " + target.getName() + ": " + message);

            
            lastMessagePartners.put(sender.getUniqueId(), target.getUniqueId());
            lastMessagePartners.put(target.getUniqueId(), sender.getUniqueId());

            translatePrivateMessage(sender, target, message);
            return;
        }

        plugin.debug("Command '" + command + "' is not a private message command");
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
            plugin.debug("Starting optimized translation for " + translationNeeded.size() + " players");
            translationManager.translateForMultiplePlayers(message, translationNeeded)
                    .whenComplete((results, throwable) -> {
                        translatingMessages.remove(messageKey);
                        plugin.debug("Batch translation completed for " + translationNeeded.size() + " players, removed from queue: " + messageKey);

                        if (throwable != null) {
                            plugin.debug("Batch translation error: " + throwable.getMessage());
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Component originalMessage = createOriginalMessage(sender, message);
                                for (Player player : translationNeeded) {
                                    player.sendMessage(originalMessage);
                                    plugin.debug("Sent original message to " + player.getName() + " due to translation error");
                                }
                            });
                            return;
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            for (Player player : translationNeeded) {
                                TranslationResult result = results.get(player.getUniqueId().toString());
                                if (result != null) {
                                    plugin.debug("Translation result for " + player.getName() + ": " + result.getType() + ", translated: " + result.wasTranslated());
                                    Component messageComponent = createTranslatedMessage(result, sender);
                                    if (messageComponent != null) {
                                        player.sendMessage(messageComponent);
                                        plugin.debug("Sent translated message to " + player.getName());
                                    } else {
                                        Component originalMessage = createOriginalMessage(sender, message);
                                        player.sendMessage(originalMessage);
                                        plugin.debug("Sent original message to " + player.getName() + " (no translation needed)");
                                    }
                                } else {
                                    Component originalMessage = createOriginalMessage(sender, message);
                                    player.sendMessage(originalMessage);
                                    plugin.debug("Sent original message to " + player.getName() + " (no result)");
                                }
                            }
                        });
                    });
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

        plugin.debug("Checking if private message needs translation for " + target.getName());

        if (!needsTranslationForPlayer(message, target)) {
            plugin.debug("Private message doesn't need translation for " + target.getName() + ", not sending duplicate");
            return; 
        }

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
        if (showHover) {
            messageContent = messageContent.hoverEvent(HoverEvent.showText(hoverText));
        }

        switch (displayMode.toLowerCase()) {
            case "replace":
                
                return Component.text(pmPrefix + senderFormat)
                        .color(baseColor)
                        .append(messageContent);

            case "separate":
                
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
                
                String customFormat = plugin.getConfig().getString("translation.display.customFormat",
                        "&8[&eТ&8] {luckperms_prefix}&f{player_name}&7 >> &f{player_chat_color}{player_chat_decoration}{translated_message}");
                return createCustomFormattedMessage(result, sender, customFormat, showHover ? hoverText : null);

            case "compact":
            default:
                
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
                
                Component translatedText = Component.text(result.getTranslatedText())
                        .color(NamedTextColor.WHITE);
                if (showHover) {
                    translatedText = translatedText.hoverEvent(HoverEvent.showText(hoverText));
                }
                return Component.text("<" + sender.getName() + "> ")
                        .color(NamedTextColor.WHITE)
                        .append(translatedText);

            case "separate":
                
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
                
                String customFormat = plugin.getConfig().getString("translation.display.customFormat",
                        "&8[&eТ&8] {luckperms_prefix}&f{player_name}&7 >> &f{player_chat_color}{player_chat_decoration}{translated_message}");
                return createCustomFormattedMessage(result, sender, customFormat, showHover ? hoverText : null);

            case "compact":
            default:
                
                Component indicator = Component.text("ᵀ ")
                        .color(color);
                if (showHover) {
                    indicator = indicator.hoverEvent(HoverEvent.showText(
                            Component.text("Translated message").color(NamedTextColor.GRAY)));
                }
                Component message = Component.text(result.getTranslatedText())
                        .color(NamedTextColor.WHITE);
                if (showHover) {
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
            return NamedTextColor.AQUA; 
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

    private boolean isGlobalChatEnabled() {
        return plugin.getConfig().getBoolean("translation.globalChat.enabled", false);
    }

    private boolean isRangeChatEnabled() {
        return plugin.getConfig().getBoolean("translation.rangeChat.enabled", true);
    }

    private boolean isGlobalChatMessage(String message) {
        String prefix = plugin.getConfig().getString("translation.globalChat.prefix", "!");
        return message.startsWith(prefix);
    }

    private String processGlobalChatPrefix(String message) {
        String prefix = plugin.getConfig().getString("translation.globalChat.prefix", "!");
        boolean removePrefix = plugin.getConfig().getBoolean("translation.globalChat.removePrefix", true);

        if (removePrefix && message.startsWith(prefix)) {
            return message.substring(prefix.length()).trim();
        }
        return message;
    }

    private void processGlobalChatMessageCancelled(Player sender, String message) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode();
        translatingMessages.add(messageKey);
        plugin.debug("Added cancelled global chat message to translation queue: " + messageKey);

        String processedMessage = processGlobalChatPrefix(message);
        plugin.debug("Processed cancelled global chat message: '" + message + "' -> '" + processedMessage + "'");

        java.util.Set<Player> allPlayers = new java.util.HashSet<>(Bukkit.getOnlinePlayers());
        java.util.Set<Player> translationNeeded = new java.util.HashSet<>();

        for (Player player : allPlayers) {
            if (!player.equals(sender)) {
                if (needsTranslationForPlayer(processedMessage, player)) {
                    translationNeeded.add(player);
                    plugin.debug("Player " + player.getName() + " needs translation for cancelled global chat (locale: " + player.locale() + ")");
                }
            }
        }

        if (!translationNeeded.isEmpty()) {
            plugin.debug("Starting optimized cancelled global chat translation for " + translationNeeded.size() + " players");
            translationManager.translateForMultiplePlayers(processedMessage, translationNeeded)
                    .whenComplete((results, throwable) -> {
                        translatingMessages.remove(messageKey);
                        plugin.debug("Cancelled global chat batch translation completed for " + translationNeeded.size() + " players, removed from queue: " + messageKey);

                        if (throwable != null) {
                            plugin.debug("Cancelled global chat batch translation error: " + throwable.getMessage());
                            return;
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            for (Player player : translationNeeded) {
                                TranslationResult result = results.get(player.getUniqueId().toString());
                                if (result != null && result.wasTranslated()) {
                                    plugin.debug("Cancelled global chat translation result for " + player.getName() + ": " + result.getType() + ", translated: " + result.wasTranslated());
                                    Component messageComponent = createGlobalChatMessage(sender, result.getTranslatedText(), true, result);
                                    player.sendMessage(messageComponent);
                                    plugin.debug("Sent translated cancelled global chat message to " + player.getName());
                                }
                            }
                        });
                    });
        } else {
            translatingMessages.remove(messageKey);
            plugin.debug("No translation needed for cancelled global chat, removed from queue: " + messageKey);
        }
    }

    private void processGlobalChatMessage(AsyncChatEvent event, Player sender, String message) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode();
        translatingMessages.add(messageKey);
        plugin.debug("Added global chat message to translation queue: " + messageKey);

        String processedMessage = processGlobalChatPrefix(message);
        plugin.debug("Processed global chat message: '" + message + "' -> '" + processedMessage + "'");

        event.setCancelled(true);
        plugin.debug("Cancelled original chat event for global chat message");

        java.util.Set<Player> allPlayers = new java.util.HashSet<>(Bukkit.getOnlinePlayers());
        java.util.Set<Player> translationNeeded = new java.util.HashSet<>();

        for (Player player : allPlayers) {
            if (!player.equals(sender)) {
                if (needsTranslationForPlayer(processedMessage, player)) {
                    translationNeeded.add(player);
                    plugin.debug("Player " + player.getName() + " needs translation for global chat (locale: " + player.locale() + ")");
                }
            }
        }

        Component originalMessage = createGlobalChatMessage(sender, processedMessage, false, null);
        sender.sendMessage(originalMessage);
        for (Player player : allPlayers) {
            if (!player.equals(sender) && !translationNeeded.contains(player)) {
                player.sendMessage(originalMessage);
                plugin.debug("Sent original global chat message to " + player.getName() + " (no translation needed)");
            }
        }

        if (!translationNeeded.isEmpty()) {
            plugin.debug("Starting optimized global chat translation for " + translationNeeded.size() + " players");
            translationManager.translateForMultiplePlayers(processedMessage, translationNeeded)
                    .whenComplete((results, throwable) -> {
                        translatingMessages.remove(messageKey);
                        plugin.debug("Global chat batch translation completed for " + translationNeeded.size() + " players, removed from queue: " + messageKey);

                        if (throwable != null) {
                            plugin.debug("Global chat batch translation error: " + throwable.getMessage());
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Component fallbackMessage = createGlobalChatMessage(sender, processedMessage, false, null);
                                for (Player player : translationNeeded) {
                                    player.sendMessage(fallbackMessage);
                                    plugin.debug("Sent original global chat message to " + player.getName() + " due to translation error");
                                }
                            });
                            return;
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            for (Player player : translationNeeded) {
                                TranslationResult result = results.get(player.getUniqueId().toString());
                                if (result != null) {
                                    plugin.debug("Global chat translation result for " + player.getName() + ": " + result.getType() + ", translated: " + result.wasTranslated());
                                    Component messageComponent = createGlobalChatMessage(sender, result.getTranslatedText(), true, result);
                                    player.sendMessage(messageComponent);
                                    plugin.debug("Sent translated global chat message to " + player.getName());
                                } else {
                                    Component fallbackMessage = createGlobalChatMessage(sender, processedMessage, false, null);
                                    player.sendMessage(fallbackMessage);
                                    plugin.debug("Sent original global chat message to " + player.getName() + " (no result)");
                                }
                            }
                        });
                    });
        } else {
            translatingMessages.remove(messageKey);
            plugin.debug("No translation needed for global chat, removed from queue: " + messageKey);
        }
    }

    private Component createGlobalChatMessage(Player sender, String message, boolean isTranslated, TranslationResult result) {
        String displayMode = plugin.getConfig().getString("translation.display.mode", "compact");

        if (displayMode.equals("custom")) {
            String format = plugin.getConfig().getString("translation.globalChat.globalFormat",
                    "&8[&bГ&8] {luckperms_prefix}&f{player_name}&7 >> &f{player_chat_color}{player_chat_decoration}{translated_message}");

            if (isTranslated && result != null) {
                boolean showHover = plugin.getConfig().getBoolean("translation.display.showHover", true);
                Component hoverText = null;
                if (showHover) {
                    hoverText = Component.text("Original: " + result.getOriginalText())
                            .color(NamedTextColor.GRAY)
                            .append(Component.newline())
                            .append(Component.text("Translated from " + result.getSourceLanguage() + " to " + result.getTargetLanguage())
                                    .color(NamedTextColor.DARK_GRAY));
                }
                return createCustomGlobalMessage(sender, message, format, hoverText);
            } else {
                
                String originalFormat = format.replace("&bГ", "&eG"); 
                return createCustomGlobalMessage(sender, message, originalFormat, null);
            }
        } else {
            
            if (isTranslated && result != null) {
                return createTranslatedMessage(result, sender);
            } else {
                return createOriginalMessage(sender, message);
            }
        }
    }

    private Component createCustomGlobalMessage(Player sender, String message, String format, Component hoverText) {
        String formatted = format
                .replace("{player_name}", sender.getName())
                .replace("{translated_message}", message)
                .replace("{original_message}", message);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            formatted = PlaceholderAPI.setPlaceholders(sender, formatted);
        }

        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);

        if (hoverText != null) {
            component = component.hoverEvent(HoverEvent.showText(hoverText));
        }

        return component;
    }

    private java.util.Set<Player> getPlayersInRange(Player sender) {
        double range = plugin.getConfig().getDouble("translation.rangeChat.range", 100.0);
        boolean crossWorld = plugin.getConfig().getBoolean("translation.rangeChat.crossWorld", false);
        java.util.Set<Player> playersInRange = new java.util.HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.equals(sender)) continue;

            
            if (!crossWorld && !player.getWorld().equals(sender.getWorld())) {
                continue;
            }

            
            if (crossWorld || player.getWorld().equals(sender.getWorld())) {
                double distance = player.getLocation().distance(sender.getLocation());

                if (distance <= range) {
                    playersInRange.add(player);
                    plugin.debug("Player " + player.getName() + " is in range (" + String.format("%.1f", distance) + " blocks)");
                }
            }
        }

        plugin.debug("Found " + playersInRange.size() + " players in range of " + sender.getName());
        return playersInRange;
    }

    private void processRangeChatMessage(AsyncChatEvent event, Player sender, String message) {
        String messageKey = sender.getUniqueId() + ":" + message.hashCode();
        translatingMessages.add(messageKey);
        plugin.debug("Added range chat message to translation queue: " + messageKey);

        
        java.util.Set<Player> playersInRange = getPlayersInRange(sender);
        java.util.Set<Player> translationNeeded = new java.util.HashSet<>();

        
        for (Player player : playersInRange) {
            if (needsTranslationForPlayer(message, player)) {
                translationNeeded.add(player);
                plugin.debug("Player " + player.getName() + " needs translation for range chat (locale: " + player.locale() + ")");
            }
        }

        plugin.debug("Range chat - Original audience size: " + (playersInRange.size() - translationNeeded.size()) +
                    ", Translation needed for: " + translationNeeded.size() + " players");

        
        event.viewers().clear();
        for (Player player : playersInRange) {
            if (!translationNeeded.contains(player)) {
                event.viewers().add(player);
            }
        }

        
        if (!translationNeeded.isEmpty()) {
            plugin.debug("Starting optimized range chat translation for " + translationNeeded.size() + " players");
            translationManager.translateForMultiplePlayers(message, translationNeeded)
                    .whenComplete((results, throwable) -> {
                        translatingMessages.remove(messageKey);
                        plugin.debug("Range chat batch translation completed for " + translationNeeded.size() + " players, removed from queue: " + messageKey);

                        if (throwable != null) {
                            plugin.debug("Range chat batch translation error: " + throwable.getMessage());
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Component fallbackMessage = createRangeChatMessage(sender, message, false, null);
                                for (Player player : translationNeeded) {
                                    player.sendMessage(fallbackMessage);
                                    plugin.debug("Sent original range chat message to " + player.getName() + " due to translation error");
                                }
                            });
                            return;
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            for (Player player : translationNeeded) {
                                TranslationResult result = results.get(player.getUniqueId().toString());
                                if (result != null) {
                                    plugin.debug("Range chat translation result for " + player.getName() + ": " + result.getType() + ", translated: " + result.wasTranslated());
                                    Component messageComponent = createRangeChatMessage(sender, result.getTranslatedText(), true, result);
                                    if (messageComponent != null) {
                                        player.sendMessage(messageComponent);
                                        plugin.debug("Sent translated range chat message to " + player.getName());
                                    } else {
                                        Component originalMessage = createRangeChatMessage(sender, message, false, null);
                                        player.sendMessage(originalMessage);
                                        plugin.debug("Sent original range chat message to " + player.getName() + " (no translation needed)");
                                    }
                                } else {
                                    Component originalMessage = createRangeChatMessage(sender, message, false, null);
                                    player.sendMessage(originalMessage);
                                    plugin.debug("Sent original range chat message to " + player.getName() + " (no result)");
                                }
                            }
                        });
                    });
        } else {
            translatingMessages.remove(messageKey);
            plugin.debug("No translation needed for range chat, removed from queue: " + messageKey);
        }
    }

    private Component createRangeChatMessage(Player sender, String message, boolean isTranslated, TranslationResult result) {
        String displayMode = plugin.getConfig().getString("translation.display.mode", "compact");

        if (displayMode.equals("custom")) {
            
            String format = plugin.getConfig().getString("translation.rangeChat.rangeFormat",
                    "&8[&eР&8] {luckperms_prefix}&f{player_name}&7 >> &f{player_chat_color}{player_chat_decoration}{translated_message}");

            if (isTranslated && result != null) {
                boolean showHover = plugin.getConfig().getBoolean("translation.display.showHover", true);
                Component hoverText = null;
                if (showHover) {
                    hoverText = Component.text("Original: " + result.getOriginalText())
                            .color(NamedTextColor.GRAY)
                            .append(Component.newline())
                            .append(Component.text("Translated from " + result.getSourceLanguage() + " to " + result.getTargetLanguage())
                                    .color(NamedTextColor.DARK_GRAY));
                }
                return createCustomRangeMessage(sender, message, format, hoverText);
            } else {
                
                String originalFormat = format.replace("&eР", "&7R"); 
                return createCustomRangeMessage(sender, message, originalFormat, null);
            }
        } else {
            
            if (isTranslated && result != null) {
                return createTranslatedMessage(result, sender);
            } else {
                return createOriginalMessage(sender, message);
            }
        }
    }

    private Component createCustomRangeMessage(Player sender, String message, String format, Component hoverText) {
        String formatted = format
                .replace("{player_name}", sender.getName())
                .replace("{translated_message}", message)
                .replace("{original_message}", message);

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