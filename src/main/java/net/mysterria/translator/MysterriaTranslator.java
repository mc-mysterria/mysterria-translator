package net.mysterria.translator;

import lombok.AccessLevel;
import lombok.Getter;
import net.mysterria.translator.command.player.LangCommand;
import net.mysterria.translator.config.Messages;
import net.mysterria.translator.config.TranslatorConfig;
import net.mysterria.translator.config.models.TranslationSettings;
import net.mysterria.translator.domain.chat.listener.BukkitChatListener;
import net.mysterria.translator.domain.chat.listener.ChatControlListener;
import net.mysterria.translator.domain.chat.listener.ZelChatListener;
import net.mysterria.translator.domain.chat.service.ChatTranslationService;
import net.mysterria.translator.domain.chat.service.TranslationMarker;
import net.mysterria.translator.domain.language.placeholder.LanguagePlaceholders;
import net.mysterria.translator.domain.language.service.LanguageService;
import net.mysterria.translator.domain.preference.listener.PreferenceListener;
import net.mysterria.translator.domain.preference.service.PreferenceService;
import net.mysterria.translator.domain.translation.service.EngineChain;
import net.mysterria.translator.domain.translation.service.PromptService;
import net.mysterria.translator.domain.translation.service.TranslationService;
import net.mysterria.translator.domain.translation.service.TranslationThreadPool;
import net.mysterria.translator.domain.translation.type.TranslationEngine;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Getter
public class MysterriaTranslator extends JavaPlugin {

    private Messages messages;
    private PromptService prompts;
    private PreferenceService preferences;
    private LanguageService languages;
    private TranslationThreadPool threadPool;
    private EngineChain engines;
    private TranslationService translations;
    private ChatTranslationService chat;
    @Getter(AccessLevel.NONE)
    private volatile TranslatorConfig settings;
    @Getter(AccessLevel.NONE)
    private HttpClient http;
    @Getter(AccessLevel.NONE)
    private ZelChatListener zelChat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultLanguages();
        TranslatorConfig config = new TranslatorConfig(getConfig());

        messages = new Messages(this);
        prompts = new PromptService(this);
        preferences = new PreferenceService(getDataFolder(), getSLF4JLogger(), config.getStorage(), config.getDefaultLang());
        languages = new LanguageService(getDataFolder(), getSLF4JLogger(), preferences, messages);
        threadPool = new TranslationThreadPool(getSLF4JLogger(), config.getTranslation());
        engines = new EngineChain(this, threadPool);
        translations = new TranslationService(engines);
        chat = new ChatTranslationService(this, translations, preferences, new TranslationMarker());

        List<String> errors = apply(config, true);
        if (!errors.isEmpty()) {
            getSLF4JLogger().error("config.yml has {} problem(s), chat translation stays off until they are fixed and /lang reload is run:", errors.size());
            errors.forEach(error -> getSLF4JLogger().error("  - {}", error));
        }
        languages.load(config.getDefaultLang());

        new LanguagePlaceholders(languages, preferences, getPluginMeta().getVersion()).register();
        registerListeners();
        registerCommand("lang", "Choose your languages and chat translation", List.of("translator", "language"), new LangCommand(this));

        getSLF4JLogger().info("Loaded {} language(s) with {} entries, translating through {}",
                languages.available().size(), languages.entryCount(), String.join(" -> ", config.getTranslation().getProviders()));
    }

    @Override
    public void onDisable() {
        if (zelChat != null) {
            zelChat.unregister();
        }
        if (threadPool != null) {
            threadPool.shutdown();
        }
        if (http != null) {
            http.shutdownNow();
        }
        if (preferences != null) {
            preferences.close();
        }
    }

    public List<String> reload() {
        reloadConfig();
        messages.reload();
        prompts.reload();
        TranslatorConfig config = new TranslatorConfig(getConfig());
        List<String> errors = apply(config, false);
        if (errors.isEmpty()) {
            languages.load(config.getDefaultLang());
        }
        return errors;
    }

    public void debug(String message) {
        TranslatorConfig current = settings;
        if (current != null && current.isDebug()) {
            getSLF4JLogger().info("[debug] {}", message);
        }
    }

    private List<String> apply(TranslatorConfig config, boolean startup) {
        TranslationSettings translation = config.getTranslation();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(translation.getConnectTimeoutSeconds()))
                .build();
        List<TranslationEngine> created = config.createEngines(new TranslationEngine.Context(client, prompts,
                Duration.ofSeconds(translation.getConnectTimeoutSeconds()), Duration.ofMinutes(translation.getSuspensionMinutes())));
        List<String> errors = List.copyOf(config.getErrors());
        if (!errors.isEmpty() && !startup) {
            client.shutdownNow();
            return errors;
        }

        HttpClient previous = http;
        http = client;
        settings = config;
        threadPool.resize(translation);
        engines.configure(translation, errors.isEmpty() ? created : List.of());
        translations.configure(translation);
        chat.getMarker().configure(translation);
        chat.configure(config);
        preferences.setDefaultLanguage(config.getDefaultLang());
        if (previous != null) {
            previous.shutdown();
        }
        return errors;
    }

    private void registerListeners() {
        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(new PreferenceListener(preferences, languages), this);
        if (plugins.isPluginEnabled("ZelChat")) {
            zelChat = new ZelChatListener(this, chat);
            zelChat.register();
            getSLF4JLogger().info("Translating chat through ZelChat");
        } else if (plugins.isPluginEnabled("ChatControl")) {
            plugins.registerEvents(new ChatControlListener(chat), this);
            getSLF4JLogger().info("Translating chat through ChatControl");
        } else {
            plugins.registerEvents(new BukkitChatListener(chat), this);
            getSLF4JLogger().info("Translating chat through Paper chat events");
        }
    }

    private void saveDefaultLanguages() {
        if (!new File(getDataFolder(), "langs").exists()) {
            saveResource("langs/en_us/example.yml", false);
            saveResource("langs/uk_ua/example.yml", false);
        }
    }
}
