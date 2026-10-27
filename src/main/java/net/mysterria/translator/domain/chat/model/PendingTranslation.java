package net.mysterria.translator.domain.chat.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Getter
@RequiredArgsConstructor
public class PendingTranslation {
    private final String text;
    private final Locale source;
    private final Map<Locale, List<Player>> recipientsByTarget;

    public Set<Player> recipients() {
        Set<Player> recipients = new HashSet<>();
        recipientsByTarget.values().forEach(recipients::addAll);
        return recipients;
    }
}
