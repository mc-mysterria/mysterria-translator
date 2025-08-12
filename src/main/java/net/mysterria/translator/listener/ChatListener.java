package net.mysterria.translator.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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

        String prefix = isPrivate ? "[PM] " : "";
        Component hoverText = Component.text("Original: " + result.getOriginalText())
                .color(NamedTextColor.GRAY)
                .append(Component.newline())
                .append(Component.text("Translated from " + result.getSourceLanguage() + " to " + result.getTargetLanguage())
                        .color(NamedTextColor.DARK_GRAY));

        Component translationIndicator = Component.text("[T] ")
                .color(NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text("This message was automatically translated").color(NamedTextColor.GRAY)));

        Component messageContent = Component.text(result.getTranslatedText())
                .color(NamedTextColor.WHITE)
                .hoverEvent(HoverEvent.showText(hoverText));

        if (isPrivate) {
            return Component.text(prefix)
                    .color(NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(sender.getName() + ": ").color(NamedTextColor.LIGHT_PURPLE))
                    .append(translationIndicator)
                    .append(messageContent);
        } else {
            return Component.text("<" + sender.getName() + "> ")
                    .color(NamedTextColor.WHITE)
                    .append(translationIndicator)
                    .append(messageContent);
        }
    }

    private Component createTranslatedMessage(TranslationResult result, Player sender) {
        if (!result.wasTranslated()) {
            return null;
        }

        Component hoverText = Component.text("Original: " + result.getOriginalText())
                .color(NamedTextColor.GRAY)
                .append(Component.newline())
                .append(Component.text("Translated from " + result.getSourceLanguage() + " to " + result.getTargetLanguage())
                        .color(NamedTextColor.DARK_GRAY));

        Component translationIndicator = Component.text("[T] ")
                .color(NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text("This message was automatically translated").color(NamedTextColor.GRAY)));

        return Component.text("<" + sender.getName() + "> ")
                .color(NamedTextColor.WHITE)
                .append(translationIndicator)
                .append(Component.text(result.getTranslatedText())
                        .color(NamedTextColor.WHITE)
                        .hoverEvent(HoverEvent.showText(hoverText)));
    }

    private Component createOriginalMessage(Player sender, String message) {
        return Component.text("<" + sender.getName() + "> " + message)
                .color(NamedTextColor.WHITE);
    }
}