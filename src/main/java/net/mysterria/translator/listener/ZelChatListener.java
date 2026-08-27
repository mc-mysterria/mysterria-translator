package net.mysterria.translator.listener;

import it.pino.zelchat.api.ZelChatAPI;
import it.pino.zelchat.api.message.ChatMessage;
import it.pino.zelchat.api.message.channel.ChannelType;
import it.pino.zelchat.api.message.format.Format;
import it.pino.zelchat.api.message.state.MessageState;
import it.pino.zelchat.api.module.ChatModule;
import it.pino.zelchat.api.module.annotation.ChatModuleSettings;
import it.pino.zelchat.api.module.priority.ModulePriority;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.translation.TranslationManager;
import net.mysterria.translator.translation.TranslationResult;
import net.mysterria.translator.util.LanguageDetector;
import net.mysterria.translator.util.MessageSerializer;
import net.mysterria.translator.util.TranslationMarker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ZelChatListener {

    // ZelChat special tokens that expand to rich Components — translation is skipped for these
    // because the resulting Component cannot be reconstructed from a plain translated string.
    private static final Pattern ZELCHAT_TOKEN_PATTERN =
            Pattern.compile("\\[(inv|item|ender|enderchest)\\]", Pattern.CASE_INSENSITIVE);

    private final MysterriaTranslator plugin;
    private final TranslationManager translationManager;
    private final Set<String> translatingMessages;
    private final ChatModule module;
    private final TranslationMarker marker;

    public ZelChatListener(MysterriaTranslator plugin, TranslationManager translationManager) {
        this.plugin = plugin;
        this.translationManager = translationManager;
        this.translatingMessages = ConcurrentHashMap.newKeySet();
        this.module = new TranslationChatModule();
        this.marker = new TranslationMarker(plugin);
    }

    public void register() {
        ZelChatAPI.get().getModuleManager().register(plugin, module);
        module.load();
    }

    public void unregister() {
        module.unload();
        ZelChatAPI.get().getModuleManager().unregister(plugin, module);
    }

    // Run last so all ZelChat internal modules have already processed chatMessage.
    @ChatModuleSettings(pluginOwner = "MysterriaTranslator", priority = ModulePriority.HIGH)
    private class TranslationChatModule implements ChatModule {

        @Override
        public void handleChatMessage(final @NotNull ChatMessage chatMessage) {
            if (chatMessage.doNothing()
                    || chatMessage.getState() == MessageState.CANCELLED
                    || chatMessage.getState() == MessageState.FILTERED_CANCELLED) {
                return;
            }

            ChannelType initialType = chatMessage.getChannel().getType();
            if (initialType == ChannelType.PRIVATE || initialType == ChannelType.STAFF || initialType == ChannelType.CUSTOM) {
                // PRIVATE = DM; CUSTOM = ZelChat already routed this to a named channel (staff, etc.)
                return;
            }

            if (!plugin.getConfig().getBoolean("translation.enabled", true)) {
                return;
            }

            Player sender = chatMessage.getBukkitPlayer();
            String rawMessage = chatMessage.getRawMessage();

            // Config-based prefix exclusions — e.g. "#" for staff chat, as a fallback
            // for ZelChat versions that don't use ChannelType.CUSTOM for named channels.
            List<String> channelPrefixes = plugin.getConfig().getStringList("zelchat.channelPrefixes");
            for (String cp : channelPrefixes) {
                if (!cp.isEmpty() && rawMessage.startsWith(cp)) return;
            }

            String messageKey = sender.getUniqueId() + ":" + rawMessage.hashCode();
            if (translatingMessages.contains(messageKey)) {
                return;
            }

            boolean globalChatEnabled = plugin.getConfig().getBoolean("chat.globalChat.enabled", false);
            boolean rangeChatEnabled = plugin.getConfig().getBoolean("chat.rangeChat.enabled", true);
            String globalPrefix = plugin.getConfig().getString("chat.globalChat.prefix", "!");

            boolean isGlobal = globalChatEnabled && rawMessage.startsWith(globalPrefix);
            boolean isRange = globalChatEnabled && !isGlobal && rangeChatEnabled;

            if (globalChatEnabled && !isGlobal && !rangeChatEnabled) {
                return;
            }

            // --- Step 1: Strip the global prefix ---
            final String messageText;
            if (isGlobal && plugin.getConfig().getBoolean("chat.globalChat.removePrefix", true)) {
                String stripped = rawMessage.substring(globalPrefix.length()).trim();
                if (stripped.isEmpty()) return;
                // Strip only the prefix text from the already-processed Component so that
                // rich content expanded by ZelChat's modules (e.g. [item] hover/click) is preserved.
                Component strippedComponent = chatMessage.getMessage()
                        .replaceText(b -> b.matchLiteral(globalPrefix).once().replacement(Component.empty()));
                chatMessage.setMessage(strippedComponent);
                messageText = stripped;
            } else {
                messageText = rawMessage;
            }

            // --- Step 2: Determine the full audience ---
            Set<Player> audience;
            if (isRange) {
                audience = getPlayersInRange(sender);
            } else {
                audience = new HashSet<>(Bukkit.getOnlinePlayers());
                audience.remove(sender);
            }

            // --- Step 3: Split into translated vs. direct recipients ---
            Set<net.kyori.adventure.audience.Audience> directViewers = new HashSet<>();
            directViewers.add(sender);

            Set<Player> translationNeeded = ConcurrentHashMap.newKeySet();

            for (Player player : audience) {
                if (needsTranslationForPlayer(messageText, player)) {
                    translationNeeded.add(player);
                } else {
                    directViewers.add(player);
                }
            }

            // Messages containing ZelChat rich tokens ([inv], [item], [ender], [enderchest])
            // are already expanded to clickable Components in chatMessage.getMessage().
            // We cannot reconstruct that Component from a translated plain string, so we
            // skip translation for those recipients and let them see the original Component.
            final boolean hasRichContent = ZELCHAT_TOKEN_PATTERN.matcher(messageText).find();

            // --- Step 4: Custom range format (optional) ---
            // When configured, we cancel ZelChat's delivery entirely and build the Component
            // ourselves. chatMessage.getMessage() already has [inv], [item], etc. expanded
            // because ZelChat's internal HIGHEST-priority modules run before ours.
            if (isRange && hasCustomRangeFormat()) {
                chatMessage.setState(MessageState.CANCELLED);

                // Capture the processed message Component now, while still in this thread
                final Component directComponent = buildCustomRangeComponent(chatMessage, null, null);

                // Send to sender + non-translated players
                Bukkit.getScheduler().runTask(plugin, () ->
                        directViewers.forEach(a -> {
                            if (a instanceof Player p) p.sendMessage(directComponent);
                        }));

                if (translationNeeded.isEmpty()) return;

                translatingMessages.add(messageKey);
                plugin.debug("ZelChat: translating from " + sender.getName() + " for " + translationNeeded.size() + " player(s)");

                translationManager.translateForMultiplePlayers(messageText, translationNeeded)
                        .whenComplete((results, throwable) -> {
                            translatingMessages.remove(messageKey);

                            if (throwable != null) {
                                plugin.debug("ZelChat translation error: " + throwable.getMessage());
                                Bukkit.getScheduler().runTask(plugin, () ->
                                        translationNeeded.forEach(p -> p.sendMessage(directComponent)));
                                return;
                            }

                            Bukkit.getScheduler().runTask(plugin, () -> {
                                for (Player player : translationNeeded) {
                                    TranslationResult result = results.get(player.getUniqueId().toString());
                                    if (result != null && result.wasTranslated() && !hasRichContent) {
                                        player.sendMessage(buildCustomRangeComponent(chatMessage, result.getTranslatedText(), result));
                                    } else {
                                        player.sendMessage(directComponent);
                                    }
                                }
                            });
                        });
                return;
            }

            // --- Step 5: Standard ZelChat flow ---
            // Restrict channel for range chat or when some players need translation
            if (isRange || !translationNeeded.isEmpty()) {
                chatMessage.getChannel().setType(ChannelType.CUSTOM);
                chatMessage.getChannel().setViewers(directViewers);
            }

            if (translationNeeded.isEmpty()) return;

            // Capture ZelChat's fully-formatted Component (with hover/actions from ZelChat's format)
            final Component zelFormatted = ZelChatAPI.get().getFormatDecorator().retrieveFormatted(chatMessage);

            translatingMessages.add(messageKey);
            plugin.debug("ZelChat: translating from " + sender.getName() + " for " + translationNeeded.size() + " player(s)");

            translationManager.translateForMultiplePlayers(messageText, translationNeeded)
                    .whenComplete((results, throwable) -> {
                        translatingMessages.remove(messageKey);

                        if (throwable != null) {
                            plugin.debug("ZelChat translation error: " + throwable.getMessage());
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    translationNeeded.forEach(p -> p.sendMessage(zelFormatted)));
                            return;
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            boolean showHover = plugin.getConfig().getBoolean("zelchat.display.showHover", true);
                            for (Player player : translationNeeded) {
                                TranslationResult result = results.get(player.getUniqueId().toString());
                                if (result != null && result.wasTranslated() && !hasRichContent) {
                                    // Mark and hover the replaced text only, so ZelChat's own
                                    // format hover/click on the rest of the line is preserved.
                                    Component replacement = marker.decorate(
                                            Component.text(result.getTranslatedText()), result, showHover);
                                    Component translated = zelFormatted.replaceText(b ->
                                            b.matchLiteral(messageText).replacement(replacement));
                                    player.sendMessage(translated);
                                } else {
                                    player.sendMessage(zelFormatted);
                                }
                            }
                        });
                    });
        }
    }

    // -------------------------------------------------------------------------

    private boolean hasCustomRangeFormat() {
        return !plugin.getConfig().getString("zelchat.rangeChat.format.message", "").trim().isEmpty();
    }

    /**
     * Builds the range-chat Component using the custom format defined in config.
     *
     * @param chatMessage     The ZelChat message (already processed by internal modules).
     * @param messageOverride If non-null, used as the message text (for translations).
     *                        If null, chatMessage.getMessage() is used — preserving [inv] etc.
     * @param result          The translation behind {@code messageOverride}, used to add the
     *                        machine-translation marker. Null for the untranslated component.
     */
    private Component buildCustomRangeComponent(@NotNull ChatMessage chatMessage, @Nullable String messageOverride,
                                                @Nullable TranslationResult result) {
        Player sender = chatMessage.getBukkitPlayer();
        boolean hasPapi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;

        // ── Message ──────────────────────────────────────────────────────────
        String formatStr = plugin.getConfig().getString("zelchat.rangeChat.format.message", "{message}");

        // Resolve all PAPI placeholders in the full format string first, then convert
        // everything (§ codes, &#hex, &x&hex, &c…) to MiniMessage syntax in one pass.
        if (hasPapi) formatStr = PlaceholderAPI.setPlaceholders(sender, formatStr);
        formatStr = MessageSerializer.prepareForMiniMessage(formatStr);

        // Replace {message} with a unique MiniMessage tag so the surrounding color scopes
        // (e.g. the <red> produced by %cc_full_color%) actually wrap the message Component
        // instead of just sitting next to it as a sibling node.
        formatStr = formatStr.replace("{message}", "<cc_msg>");

        Component messageContent;
        if (messageOverride != null) {
            messageContent = Component.text(messageOverride);
            if (result != null) {
                boolean showHover = plugin.getConfig().getBoolean("zelchat.display.showHover", true);
                messageContent = marker.decorate(messageContent, result, showHover);
            }
        } else {
            messageContent = chatMessage.getMessage();   // already processed by ZelChat: [inv], [item], etc.
        }

        TagResolver msgTag = TagResolver.resolver("cc_msg", Tag.inserting(messageContent));
        Component full = MessageSerializer.getMiniMessage().deserialize(formatStr, msgTag);

        // ── Hover ────────────────────────────────────────────────────────────
        String hoverStr = plugin.getConfig().getString("zelchat.rangeChat.format.hover", "").trim();
        if (!hoverStr.isEmpty()) {
            if (hasPapi) hoverStr = PlaceholderAPI.setPlaceholders(sender, hoverStr);
            // Split on real newlines; each line is parsed independently so color codes reset properly
            String[] lines = hoverStr.split("\n");
            Component hoverComp = Component.empty();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) hoverComp = hoverComp.append(Component.newline());
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    hoverComp = hoverComp.append(MessageSerializer.parseMessage(line));
                }
            }
            full = full.hoverEvent(HoverEvent.showText(hoverComp));
        }

        // ── Click action ─────────────────────────────────────────────────────
        String actionStr = plugin.getConfig().getString("zelchat.rangeChat.format.action", "").trim();
        if (!actionStr.isEmpty()) {
            if (hasPapi) actionStr = PlaceholderAPI.setPlaceholders(sender, actionStr);
            if (actionStr.startsWith("SUGGEST_COMMAND:")) {
                full = full.clickEvent(ClickEvent.suggestCommand(actionStr.substring("SUGGEST_COMMAND:".length())));
            } else if (actionStr.startsWith("RUN_COMMAND:")) {
                full = full.clickEvent(ClickEvent.runCommand(actionStr.substring("RUN_COMMAND:".length())));
            } else if (actionStr.startsWith("OPEN_URL:")) {
                full = full.clickEvent(ClickEvent.openUrl(actionStr.substring("OPEN_URL:".length())));
            }
        }

        return full;
    }

    private Set<Player> getPlayersInRange(Player sender) {
        double range = plugin.getConfig().getDouble("chat.rangeChat.range", 100.0);
        boolean crossWorld = plugin.getConfig().getBoolean("chat.rangeChat.crossWorld", false);
        Set<Player> inRange = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.equals(sender)) continue;
            if (!player.getWorld().equals(sender.getWorld())) {
                if (crossWorld) inRange.add(player);
                continue;
            }
            if (player.getLocation().distance(sender.getLocation()) <= range) {
                inRange.add(player);
            }
        }
        return inRange;
    }

    private boolean needsTranslationForPlayer(String message, Player player) {
        if (!plugin.getLangManager().isTranslationEnabled(player.getUniqueId())) {
            return false;
        }
        return LanguageDetector.needsTranslation(message, player.locale().toString().toLowerCase());
    }

    /**
     * Looks up a ZelChat Format by name using reflection on ChatFormatFactory.
     * The public API has no format registry, but ChatFormatDecorator is backed by
     * ChatFormatFactory which exposes customFormats() and internalFormats().
     */
    @Nullable
    @SuppressWarnings("unused")
    private Format resolveFormatByName(String name) {
        try {
            Object decorator = ZelChatAPI.get().getFormatDecorator();
            for (String methodName : new String[]{"customFormats", "internalFormats"}) {
                try {
                    Method method = decorator.getClass().getMethod(methodName);
                    @SuppressWarnings("unchecked")
                    Collection<Format> formats = (Collection<Format>) method.invoke(decorator);
                    for (Format format : formats) {
                        if (format.getName().equalsIgnoreCase(name)) {
                            return format;
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Exception e) {
            plugin.debug("ZelChat: failed to look up format '" + name + "': " + e.getMessage());
        }
        return null;
    }
}
