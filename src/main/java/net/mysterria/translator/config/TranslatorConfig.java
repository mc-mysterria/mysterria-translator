package net.mysterria.translator.config;

import lombok.Getter;
import net.mysterria.translator.config.models.ChatSettings;
import net.mysterria.translator.config.models.StorageSettings;
import net.mysterria.translator.config.models.TranslationSettings;
import net.mysterria.translator.domain.translation.type.TranslationEngine;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Getter
public class TranslatorConfig {

    private static final List<String> PROVIDERS = List.of("ollama", "libretranslate", "gemini", "google", "openai");

    private final ConfigReader reader;
    private final boolean debug;
    private final String defaultLang;
    private final StorageSettings storage;
    private final TranslationSettings translation;
    private final ChatSettings chat;

    public TranslatorConfig(FileConfiguration file) {
        this.reader = new ConfigReader(file);
        this.debug = reader.bool("debug");
        this.defaultLang = reader.string("defaultLang").toLowerCase();
        this.storage = readStorage();
        this.translation = readTranslation();
        this.chat = readChat();
    }

    public List<String> getErrors() {
        return reader.getErrors();
    }

    public List<TranslationEngine> createEngines(TranslationEngine.Context context) {
        List<TranslationEngine> engines = new ArrayList<>();
        for (String name : translation.getProviders()) {
            try {
                engines.add(TranslationEngine.create(name, reader, context));
            } catch (IllegalArgumentException e) {
                reader.fail(name + ": " + e.getMessage());
            }
        }
        return engines;
    }

    private StorageSettings readStorage() {
        String type = reader.string("storage.type").toLowerCase();
        if (!List.of("yaml", "sqlite", "mysql").contains(type)) {
            reader.fail("storage.type must be yaml, sqlite or mysql, got '" + type + "'");
        }
        return new StorageSettings(type,
                reader.string("storage.mysql.host"),
                reader.integer("storage.mysql.port", 1, 65535),
                reader.string("storage.mysql.database"),
                reader.string("storage.mysql.user"),
                reader.string("storage.mysql.password"),
                reader.values("storage.mysql.properties"));
    }

    private TranslationSettings readTranslation() {
        List<String> providers = Arrays.stream(reader.string("translation.provider").split(","))
                .map(provider -> provider.trim().toLowerCase())
                .filter(provider -> !provider.isEmpty())
                .distinct()
                .toList();
        if (providers.isEmpty()) {
            reader.fail("translation.provider lists no engines");
        }
        providers.stream()
                .filter(provider -> !PROVIDERS.contains(provider))
                .forEach(provider -> reader.fail("translation.provider: unknown engine '" + provider + "', use " + String.join(", ", PROVIDERS)));

        return new TranslationSettings(
                reader.bool("translation.enabled"),
                providers.stream().filter(PROVIDERS::contains).toList(),
                reader.integer("translation.minMessageLength", 0, 100),
                reader.integer("translation.maxRetries", 0, 10),
                reader.integer("translation.cacheExpirySeconds", 1, 86400),
                reader.integer("translation.rateLimitMessages", 1, 100),
                reader.integer("translation.rateLimitWindowSeconds", 1, 600),
                reader.integer("translation.rateLimitSuspensionMinutes", 1, 1440),
                reader.integer("translation.retryDelayMillis", 0, 60000),
                reader.integer("translation.overallTimeoutSeconds", 1, 600),
                reader.integer("translation.connectTimeoutSeconds", 1, 120),
                reader.integer("translation.threadPool.size", 1, 64),
                reader.integer("translation.threadPool.queueCapacity", 16, 100000),
                reader.bool("translation.indicator.enabled"),
                reader.string("translation.indicator.text"),
                reader.strings("translation.indicator.hover"));
    }

    private ChatSettings readChat() {
        return new ChatSettings(
                reader.bool("chat.globalChat.enabled"),
                reader.string("chat.globalChat.prefix"),
                reader.bool("chat.globalChat.removePrefix"),
                reader.bool("chat.rangeChat.enabled"),
                reader.decimal("chat.rangeChat.range", 1, 100000),
                reader.bool("chat.rangeChat.crossWorld"),
                reader.bool("zelchat.display.showHover"),
                reader.bool("zelchat.translatePrivateMessages"),
                reader.strings("zelchat.channelPrefixes").stream().filter(prefix -> !prefix.isEmpty()).toList(),
                reader.string("zelchat.rangeChat.format.message").trim(),
                reader.string("zelchat.rangeChat.format.hover").trim(),
                reader.string("zelchat.rangeChat.format.action").trim(),
                reader.bool("chatcontrol.display.showHover"),
                reader.string("chatcontrol.globalChat.format"),
                reader.string("chatcontrol.globalChat.consoleFormat"),
                reader.string("chatcontrol.rangeChat.format"),
                reader.string("chatcontrol.rangeChat.consoleFormat"),
                reader.string("chatcontrol.privateMessage.format"));
    }
}
