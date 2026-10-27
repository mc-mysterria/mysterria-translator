package net.mysterria.translator.domain.chat.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.mysterria.translator.config.models.TranslationSettings;
import net.mysterria.translator.domain.translation.model.TranslationResult;
import net.mysterria.translator.util.language.Languages;
import net.mysterria.translator.util.text.TextFormat;

import java.util.List;

public class TranslationMarker {

    private volatile Component indicator = Component.empty();
    private volatile List<String> hoverLines = List.of();
    private volatile boolean enabled;

    public void configure(TranslationSettings settings) {
        this.enabled = settings.isIndicatorEnabled();
        this.indicator = enabled ? TextFormat.parse(settings.getIndicatorText()) : Component.empty();
        this.hoverLines = settings.getIndicatorHover().stream()
                .map(line -> TextFormat.prepare(line)
                        .replace("{original}", "<mt_original>")
                        .replace("{source}", "<mt_source>")
                        .replace("{target}", "<mt_target>"))
                .toList();
    }

    public Component hover(TranslationResult result) {
        if (!enabled || hoverLines.isEmpty()) {
            return null;
        }
        TagResolver tags = TagResolver.resolver(
                TagResolver.resolver("mt_original", Tag.inserting(Component.text(result.getOriginal()))),
                TagResolver.resolver("mt_source", Tag.inserting(Component.text(Languages.displayName(result.getSource())))),
                TagResolver.resolver("mt_target", Tag.inserting(Component.text(Languages.displayName(result.getTarget())))));
        return Component.join(JoinConfiguration.newlines(),
                hoverLines.stream().map(line -> TextFormat.miniMessage().deserialize(line, tags)).toList());
    }

    public Component decorate(Component message, TranslationResult result, boolean showHover) {
        Component decorated = message;
        if (showHover) {
            Component hover = hover(result);
            if (hover != null) {
                decorated = decorated.hoverEvent(HoverEvent.showText(hover));
            }
        }
        return enabled ? indicator.append(decorated) : decorated;
    }
}
