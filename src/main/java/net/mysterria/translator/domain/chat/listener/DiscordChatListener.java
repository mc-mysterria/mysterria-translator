package net.mysterria.translator.domain.chat.listener;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreBroadcastEvent;
import lombok.RequiredArgsConstructor;
import net.mysterria.translator.domain.preference.service.PreferenceService;
import org.bukkit.entity.Player;

@RequiredArgsConstructor
public class DiscordChatListener {

    private final PreferenceService preferences;

    public void register() {
        DiscordSRV.api.subscribe(this);
    }

    public void unregister() {
        DiscordSRV.api.unsubscribe(this);
    }

    @Subscribe
    public void onBroadcast(DiscordGuildMessagePreBroadcastEvent event) {
        event.getRecipients().removeIf(recipient -> recipient instanceof Player player && !preferences.discordChatShown(player.getUniqueId()));
    }
}
