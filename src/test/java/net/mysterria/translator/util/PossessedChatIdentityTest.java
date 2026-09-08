package net.mysterria.translator.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PossessedChatIdentityTest {
    @Test
    void replacesTheDeployedNamePlaceholderWithoutReplacingMessageContent() {
        String format = "[R] %lang_display_name% >> {message}";
        assertEquals("[R] <coi_identity> >> {message}", PossessedChatIdentity.localFormat(format, "Wolf"));
        assertEquals(format, PossessedChatIdentity.localFormat(format, null));
    }

    @Test
    void namesAreInsertedAsLiteralComponentsNotMiniMessageMarkup() {
        String name = "<red>Named Wolf</red>";
        String format = PossessedChatIdentity.localFormat("[R] %player_name% >> {message}", name)
                .replace("{message}", "<chat_message>");
        Component result = MiniMessage.miniMessage().deserialize(format,
                TagResolver.resolver("coi_identity", Tag.inserting(Component.text(name))),
                TagResolver.resolver("chat_message", Tag.inserting(Component.text("%player_name% hello"))));
        assertEquals("[R] <red>Named Wolf</red> >> %player_name% hello",
                PlainTextComponentSerializer.plainText().serialize(result));
    }
}
