package net.mysterria.translator.domain.chat.listener;

import it.pino.zelchat.api.ZelChatAPI;
import it.pino.zelchat.api.message.ChatMessage;
import it.pino.zelchat.api.message.channel.ChannelType;
import it.pino.zelchat.api.message.state.MessageState;
import it.pino.zelchat.api.module.ChatModule;
import it.pino.zelchat.api.module.annotation.ChatModuleSettings;
import it.pino.zelchat.api.module.priority.ModulePriority;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.config.models.ChatSettings;
import net.mysterria.translator.domain.chat.model.PendingTranslation;
import net.mysterria.translator.domain.chat.service.ChatTranslationService;
import net.mysterria.translator.util.identity.ChatIdentity;
import net.mysterria.translator.util.text.TextFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public class ZelChatListener {

    private static final Pattern RICH_TOKEN = Pattern.compile("\\[(inv|item|ender|enderchest)]", Pattern.CASE_INSENSITIVE);

    private final MysterriaTranslator plugin;
    private final ChatTranslationService service;
    private final ChatModule module = new TranslationModule();

    public ZelChatListener(MysterriaTranslator plugin, ChatTranslationService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void register() {
        ZelChatAPI.get().getModuleManager().register(plugin, module);
        module.load();
    }

    public void unregister() {
        module.unload();
        ZelChatAPI.get().getModuleManager().unregister(plugin, module);
    }

    @ChatModuleSettings(pluginOwner = "MysterriaTranslator", priority = ModulePriority.HIGH)
    private class TranslationModule implements ChatModule {

        @Override
        public void handleChatMessage(@NotNull ChatMessage message) {
            if (!service.isTranslationEnabled() || message.doNothing()
                    || message.getState() == MessageState.CANCELLED || message.getState() == MessageState.FILTERED_CANCELLED) {
                return;
            }
            ChannelType type = message.getChannel().getType();
            ChatSettings chat = service.getChat();
            if (type == ChannelType.PRIVATE && chat.isZelChatPrivateMessages()) {
                translatePrivate(message, chat);
            } else if (type == ChannelType.EVERYONE) {
                translatePublic(message, chat);
            }
        }
    }

    private void translatePublic(ChatMessage message, ChatSettings chat) {
        Player sender = message.getBukkitPlayer();
        String raw = message.getRawMessage();
        if (chat.getZelChatChannelPrefixes().stream().anyMatch(raw::startsWith)) {
            return;
        }
        boolean global = chat.isGlobalMessage(raw);
        boolean range = chat.isGlobalChatEnabled() && !global;
        if (range && !chat.isRangeChatEnabled()) {
            return;
        }

        String text = raw;
        if (global && chat.isRemoveGlobalPrefix()) {
            text = chat.stripGlobalPrefix(raw);
            if (text.isEmpty()) {
                return;
            }
            message.setMessage(message.getMessage().replaceText(builder -> builder.matchLiteral(chat.getGlobalPrefix()).once().replacement(Component.empty())));
        }

        Set<Player> audience = range ? service.rangeAudience(sender) : service.everyoneBut(sender);
        PendingTranslation pending = RICH_TOKEN.matcher(text).find() ? null : service.plan(text, audience, sender);
        Set<Player> translated = pending == null ? Set.of() : pending.recipients();
        Set<Audience> direct = new HashSet<>();
        direct.add(sender);
        audience.stream().filter(player -> !translated.contains(player)).forEach(direct::add);

        String identity = range ? ChatIdentity.possessed(sender) : null;
        if (range && (!chat.getZelChatRangeFormat().isEmpty() || identity != null)) {
            message.setState(MessageState.CANCELLED);
            Function<Component, Component> line = rangeLine(sender, identity, chat);
            Component original = line.apply(message.getMessage());
            Bukkit.getScheduler().runTask(plugin, () -> direct.forEach(viewer -> viewer.sendMessage(original)));
            if (pending != null) {
                service.deliver(pending, sender, original, result -> line.apply(
                        service.getMarker().decorate(Component.text(result.getTranslated()), result, chat.isZelChatHover())));
            }
            return;
        }

        if (range || pending != null) {
            message.getChannel().setType(ChannelType.CUSTOM);
            message.getChannel().setViewers(direct);
        }
        if (pending == null) {
            return;
        }
        Component formatted = ZelChatAPI.get().getFormatDecorator().retrieveFormatted(message);
        String literal = text;
        service.deliver(pending, sender, formatted, result -> formatted.replaceText(builder -> builder.matchLiteral(literal).replacement(
                service.getMarker().decorate(Component.text(result.getTranslated()), result, chat.isZelChatHover()))));
    }

    private void translatePrivate(ChatMessage message, ChatSettings chat) {
        Player sender = message.getBukkitPlayer();
        String text = message.getRawMessage();
        if (RICH_TOKEN.matcher(text).find()) {
            return;
        }
        List<Player> receivers = message.getChannel().getViewers().stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .toList();
        PendingTranslation pending = service.plan(text, receivers, sender);
        if (pending != null) {
            service.deliver(pending, sender, null, result -> plugin.getMessages().get("private_translation", "{player}", ChatIdentity.displayName(sender))
                    .append(service.getMarker().decorate(Component.text(result.getTranslated()), result, chat.isZelChatHover())));
        }
    }

    private Function<Component, Component> rangeLine(Player sender, String identity, ChatSettings chat) {
        String format = chat.getZelChatRangeFormat();
        if (identity != null) {
            format = ChatIdentity.withPossessedName(format.isEmpty() ? "[R] <coi_identity> >> {message}" : format);
        }
        String prepared = TextFormat.prepare(PlaceholderAPI.setPlaceholders(sender, format)).replace("{message}", "<mt_message>");
        TagResolver identityTag = TagResolver.resolver("coi_identity", Tag.inserting(Component.text(identity != null ? identity : sender.getName())));
        HoverEvent<Component> hover = identity == null && !chat.getZelChatRangeHover().isEmpty() ? HoverEvent.showText(rangeHover(sender, chat)) : null;
        ClickEvent click = identity == null ? rangeClick(sender, chat) : null;
        return content -> {
            Component line = TextFormat.miniMessage().deserialize(prepared, TagResolver.resolver("mt_message", Tag.inserting(content)), identityTag);
            if (hover != null) {
                line = line.hoverEvent(hover);
            }
            return click == null ? line : line.clickEvent(click);
        };
    }

    private static Component rangeHover(Player sender, ChatSettings chat) {
        String[] lines = PlaceholderAPI.setPlaceholders(sender, chat.getZelChatRangeHover()).split("\n");
        Component hover = Component.empty();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                hover = hover.append(Component.newline());
            }
            if (!lines[i].isBlank()) {
                hover = hover.append(TextFormat.parse(lines[i].trim()));
            }
        }
        return hover;
    }

    private static ClickEvent rangeClick(Player sender, ChatSettings chat) {
        String action = PlaceholderAPI.setPlaceholders(sender, chat.getZelChatRangeAction());
        int separator = action.indexOf(':');
        if (separator < 0) {
            return null;
        }
        String value = action.substring(separator + 1);
        return switch (action.substring(0, separator)) {
            case "SUGGEST_COMMAND" -> ClickEvent.suggestCommand(value);
            case "RUN_COMMAND" -> ClickEvent.runCommand(value);
            case "OPEN_URL" -> ClickEvent.openUrl(value);
            default -> null;
        };
    }
}
