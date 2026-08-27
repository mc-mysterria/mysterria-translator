package net.mysterria.translator.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.translation.TranslationResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Builds the "this was machine-translated" marker shown on translated chat messages.
 * <p>
 * Without it a clumsy automatic translation reads as if the sender actually typed it,
 * so recipients blame the player rather than the translator. The marker is a small
 * glyph in front of the message plus a hover carrying the original text.
 * <p>
 * Wording and glyph live in {@code config.yml} under {@code translation.indicator}.
 */
public class TranslationMarker {

    private static final String DEFAULT_INDICATOR = "&bᵀ ";
    private static final List<String> DEFAULT_HOVER = List.of(
            "&7Original: &f{original}",
            "&8Automatically translated ({source} → {target})",
            "&8Wording may be imperfect."
    );

    private final MysterriaTranslator plugin;

    public TranslationMarker(MysterriaTranslator plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("translation.indicator.enabled", true);
    }

    /**
     * The indicator as a raw format string, for callers that build their message by
     * substituting into a legacy/MiniMessage format template.
     */
    @NotNull
    public String rawIndicator() {
        if (!isEnabled()) {
            return "";
        }
        return plugin.getConfig().getString("translation.indicator.text", DEFAULT_INDICATOR);
    }

    @NotNull
    public Component indicator() {
        String raw = rawIndicator();
        return raw.isEmpty() ? Component.empty() : MessageSerializer.parseMessage(raw);
    }

    /**
     * The hover explaining that the message was machine-translated, or {@code null}
     * when the indicator is disabled.
     */
    @Nullable
    public Component hover(@NotNull TranslationResult result) {
        return hover(result.getOriginalText(), result.getSourceLanguage(), result.getTargetLanguage());
    }

    @Nullable
    public Component hover(String original, String source, String target) {
        if (!isEnabled()) {
            return null;
        }

        List<String> lines = plugin.getConfig().getStringList("translation.indicator.hover");
        if (lines.isEmpty()) {
            lines = DEFAULT_HOVER;
        }

        Component hover = Component.empty();
        boolean first = true;
        for (String line : lines) {
            if (!first) {
                hover = hover.append(Component.newline());
            }
            first = false;
            hover = hover.append(renderLine(line, original, source, target));
        }
        return hover;
    }

    /**
     * Prefixes {@code message} with the indicator and attaches the translation hover to
     * the message itself.
     * <p>
     * The hover is scoped to this component rather than the whole chat line on purpose:
     * the surrounding format (ZelChat's name/prefix hover and click action, expanded
     * {@code [item]} previews, …) keeps its own events.
     *
     * @param showHover honours the integration's own {@code showHover} setting
     */
    @NotNull
    public Component decorate(@NotNull Component message, @NotNull TranslationResult result, boolean showHover) {
        Component decorated = message;
        if (showHover) {
            Component hover = hover(result);
            if (hover != null) {
                decorated = decorated.hoverEvent(HoverEvent.showText(hover));
            }
        }
        return isEnabled() ? indicator().append(decorated) : decorated;
    }

    private Component renderLine(String line, String original, String source, String target) {
        String prepared = line
                .replace("{source}", source != null ? source : "?")
                .replace("{target}", target != null ? target : "?");

        // The original is player-typed text, so insert it as a literal Component via a
        // tag instead of string-substituting it into a format that then gets parsed —
        // otherwise a message containing &c or <red> would recolour the hover.
        prepared = MessageSerializer.prepareForMiniMessage(prepared)
                .replace("{original}", "<mt_original>");

        TagResolver originalTag = TagResolver.resolver("mt_original",
                Tag.inserting(Component.text(original != null ? original : "")));

        try {
            return MessageSerializer.getMiniMessage().deserialize(prepared, originalTag);
        } catch (Exception e) {
            return Component.text(line.replace("{original}", original != null ? original : ""));
        }
    }
}
