package net.mysterria.translator.config.models;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class ChatSettings {
    private final boolean globalChatEnabled;
    private final String globalPrefix;
    private final boolean removeGlobalPrefix;
    private final boolean rangeChatEnabled;
    private final double range;
    private final boolean crossWorld;

    private final boolean zelChatHover;
    private final boolean zelChatPrivateMessages;
    private final List<String> zelChatChannelPrefixes;
    private final String zelChatRangeFormat;
    private final String zelChatRangeHover;
    private final String zelChatRangeAction;

    private final boolean fallbackHover;
    private final String globalFormat;
    private final String globalConsoleFormat;
    private final String rangeFormat;
    private final String rangeConsoleFormat;
    private final String privateFormat;

    public boolean isGlobalMessage(String message) {
        return globalChatEnabled && message.startsWith(globalPrefix);
    }

    public String stripGlobalPrefix(String message) {
        return removeGlobalPrefix && message.startsWith(globalPrefix)
                ? message.substring(globalPrefix.length()).trim()
                : message;
    }
}
