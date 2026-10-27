package net.mysterria.translator.domain.chat.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mysterria.translator.config.models.ChatSettings;
import net.mysterria.translator.domain.chat.model.PendingTranslation;
import net.mysterria.translator.domain.chat.service.ChatTranslationService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BukkitChatListener implements Listener {

    private static final Set<String> MESSAGE_COMMANDS = Set.of("msg", "tell", "w", "whisper", "message", "pm");
    private static final Set<String> REPLY_COMMANDS = Set.of("reply", "r");

    private final ChatTranslationService service;
    private final Map<UUID, UUID> lastPartners = new ConcurrentHashMap<>();

    public BukkitChatListener(ChatTranslationService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent event) {
        if (!service.isTranslationEnabled()) {
            return;
        }
        ChatSettings chat = service.getChat();
        Player sender = event.getPlayer();
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        boolean global = chat.isGlobalMessage(raw);
        if (event.isCancelled()) {
            if (global) {
                translateOnly(sender, chat.stripGlobalPrefix(raw), service.everyoneBut(sender), chat.getGlobalFormat());
            }
            return;
        }
        if (global) {
            broadcastGlobal(event, sender, chat.stripGlobalPrefix(raw), chat);
        } else if (!chat.isGlobalChatEnabled()) {
            splitViewers(event, sender, raw, event.viewers().stream().filter(Player.class::isInstance).map(Player.class::cast).toList(), chat.getGlobalFormat());
        } else if (chat.isRangeChatEnabled()) {
            Set<Player> range = service.rangeAudience(sender);
            event.viewers().removeIf(viewer -> viewer instanceof Player player && !player.equals(sender) && !range.contains(player));
            splitViewers(event, sender, raw, range, chat.getRangeFormat());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!service.isTranslationEnabled()) {
            return;
        }
        String[] args = event.getMessage().substring(1).split(" ");
        String command = args[0].toLowerCase();
        Player sender = event.getPlayer();
        Player target;
        int textStart;
        if (MESSAGE_COMMANDS.contains(command) && args.length >= 3) {
            target = Bukkit.getPlayerExact(args[1]);
            textStart = 2;
        } else if (REPLY_COMMANDS.contains(command) && args.length >= 2) {
            UUID partner = lastPartners.get(sender.getUniqueId());
            target = partner == null ? null : Bukkit.getPlayer(partner);
            textStart = 1;
        } else {
            return;
        }
        if (target == null) {
            return;
        }
        lastPartners.put(sender.getUniqueId(), target.getUniqueId());
        lastPartners.put(target.getUniqueId(), sender.getUniqueId());
        translateOnly(sender, String.join(" ", List.of(args).subList(textStart, args.length)), List.of(target), service.getChat().getPrivateFormat());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastPartners.remove(event.getPlayer().getUniqueId());
    }

    private void broadcastGlobal(AsyncChatEvent event, Player sender, String text, ChatSettings chat) {
        event.setCancelled(true);
        Set<Player> audience = service.everyoneBut(sender);
        PendingTranslation pending = service.plan(text, audience, sender);
        Set<Player> translated = pending == null ? Set.of() : pending.recipients();
        Component original = service.format(chat.getGlobalFormat(), sender, text, null, false);
        sender.sendMessage(original);
        audience.stream().filter(player -> !translated.contains(player)).forEach(player -> player.sendMessage(original));
        if (pending != null) {
            service.deliver(pending, sender, original, result -> service.format(chat.getGlobalFormat(), sender, text, result, chat.isFallbackHover()));
        }
    }

    private void splitViewers(AsyncChatEvent event, Player sender, String text, Collection<Player> audience, String format) {
        PendingTranslation pending = service.plan(text, audience, sender);
        if (pending == null) {
            return;
        }
        Set<Player> translated = pending.recipients();
        event.viewers().removeIf(viewer -> viewer instanceof Player player && translated.contains(player));
        ChatSettings chat = service.getChat();
        service.deliver(pending, sender, service.format(format, sender, text, null, false),
                result -> service.format(format, sender, text, result, chat.isFallbackHover()));
    }

    private void translateOnly(Player sender, String text, Collection<Player> audience, String format) {
        PendingTranslation pending = service.plan(text, audience, sender);
        if (pending != null) {
            boolean hover = service.getChat().isFallbackHover();
            service.deliver(pending, sender, null, result -> service.format(format, sender, text, result, hover));
        }
    }
}
