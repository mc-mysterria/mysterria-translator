package net.mysterria.translator.domain.chat.listener;

import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.mysterria.translator.config.models.ChatSettings;
import net.mysterria.translator.domain.chat.model.PendingTranslation;
import net.mysterria.translator.domain.chat.service.ChatTranslationService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.mineacademy.chatcontrol.api.ChannelPostChatEvent;
import org.mineacademy.chatcontrol.api.PrePrivateMessageEvent;

import java.util.List;

@RequiredArgsConstructor
public class ChatControlListener implements Listener {

    private final ChatTranslationService service;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChannelChat(ChannelPostChatEvent event) {
        CommandSender sender = event.getSender();
        String text = event.getMessage();
        PendingTranslation pending = service.plan(text, event.getRecipients(), sender);
        if (pending == null) {
            return;
        }
        event.getRecipients().removeAll(pending.recipients());

        ChatSettings chat = service.getChat();
        boolean global = isGlobalChannel(event.getChannel().getName());
        String format = sender instanceof Player
                ? global ? chat.getGlobalFormat() : chat.getRangeFormat()
                : global ? chat.getGlobalConsoleFormat() : chat.getRangeConsoleFormat();
        Component original = service.format(format, sender, text, null, false);
        service.deliver(pending, sender, original, result -> service.format(format, sender, text, result, chat.isFallbackHover()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrivateMessage(PrePrivateMessageEvent event) {
        Player sender = event.getSender().getPlayer();
        if (sender == null) {
            return;
        }
        String text = event.getMessage();
        PendingTranslation pending = service.plan(text, List.of(event.getReceiver()), sender);
        if (pending != null) {
            ChatSettings chat = service.getChat();
            service.deliver(pending, sender, null, result -> service.format(chat.getPrivateFormat(), sender, text, result, chat.isFallbackHover()));
        }
    }

    private static boolean isGlobalChannel(String channel) {
        String name = channel.toLowerCase();
        return name.equals("g") || name.equals("mundo") || name.contains("global") || name.contains("world");
    }
}
