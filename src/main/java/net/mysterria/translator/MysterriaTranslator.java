package net.mysterria.translator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.translator.command.LangCommand;
import net.mysterria.translator.listener.ChatListener;
import net.mysterria.translator.listener.PlayerJoinListener;
import net.mysterria.translator.manager.LangManager;
import net.mysterria.translator.ollama.OllamaClient;
import net.mysterria.translator.libretranslate.LibreTranslateClient;
import net.mysterria.translator.placeholder.LangExpansion;
import net.mysterria.translator.storage.PlayerLangStorage;
import net.mysterria.translator.storage.impl.MySQLPlayerLangStorage;
import net.mysterria.translator.storage.impl.SQLitePlayerLangStorage;
import net.mysterria.translator.storage.impl.YamlPlayerLangStorage;
import net.mysterria.translator.translation.TranslationManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class MysterriaTranslator extends JavaPlugin {

    public static MysterriaTranslator plugin;
    private LangManager langManager;
    private FileConfiguration messagesConfig;
    PlayerLangStorage storage;
    private TranslationManager translationManager;
    private OllamaClient ollamaClient;
    private LibreTranslateClient libreTranslateClient;

    private final String pluginVersion = getDescription().getVersion();

    private void startingBanner() {
        String pluginName = "MysterriaTranslator";
        log("Starting " + pluginName + " v" + pluginVersion + " by Mysterria");
        String pluginDescription = "The Multi-Language Plugin";
        log(pluginDescription);
    }

    @Override
    public void onEnable() {
        plugin = this;

        long startTime = System.currentTimeMillis();

        startingBanner();

        saveDefaultConfig();
        saveDefaultMessagesConfig();
        saveDefaultExamples();

        initDatabase();

        this.ollamaClient = new OllamaClient(this, getConfig().getString("translation.ollama.url"), getConfig().getString("translation.ollama.model"), getConfig().getString("translation.ollama.apiKey"));
        this.libreTranslateClient = new LibreTranslateClient(this,
            getConfig().getString("translation.libretranslate.url"),
            getConfig().getString("translation.libretranslate.apiKey"),
            getConfig().getInt("translation.libretranslate.alternatives", 3),
            getConfig().getString("translation.libretranslate.format", "text"));
        this.langManager = new LangManager(this, storage);
        this.translationManager = new TranslationManager(this, ollamaClient, libreTranslateClient);

        langManager.loadAll();

        log("Configuration successfully loaded.");
        log("Loaded " + langManager.getAvailableLangs().size() + " languages! " + langManager.getAvailableLangs());
        log("Loaded " + langManager.getTotalTranslationsCount() + " total translations!");

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new LangExpansion(langManager).register();
            log("Registered PlaceholderAPI expansion.");
        } else {
            getLogger().warning("Could not find PlaceholderAPI! This plugin is required.");
            getLogger().warning("Disabling mtranslator...");
            Bukkit.getPluginManager().disablePlugin(this);
        }

        getCommand("lang").setExecutor(new LangCommand(langManager, this));
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(langManager, this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this, translationManager), this);

        long endTime = System.currentTimeMillis();
        log("\u001B[1;32mPlugin loaded successfully in " + (endTime - startTime) + "ms\u001B[0m");
    }

    private void initDatabase() {
        String storageType = getConfig().getString("storage.type", "yaml").toLowerCase();
        log("Loading storage...");
        if (storageType.equals("sqlite")) {
            try {
                storage = new SQLitePlayerLangStorage(getDataFolder() + "/players.db");
                migrateYamlToStorage(storage);
            } catch (Exception e) {
                getLogger().severe("Error on connecting to SQLite! Using YAML.");
                storage = new YamlPlayerLangStorage(new File(getDataFolder(), "players.yml"));
            }
        } else if (storageType.equals("mysql")) {
            try {
                String host = getConfig().getString("storage.mysql.host");
                int port = getConfig().getInt("storage.mysql.port");
                String db = getConfig().getString("storage.mysql.database");
                String user = getConfig().getString("storage.mysql.user");
                String pass = getConfig().getString("storage.mysql.password");
                storage = new MySQLPlayerLangStorage(host, port, db, user, pass);
                migrateYamlToStorage(storage);
            } catch (Exception e) {
                getLogger().severe("Error on connecting to MySQL! Using YAML.");
                storage = new YamlPlayerLangStorage(new File(getDataFolder(), "players.yml"));
            }
        } else {
            storage = new YamlPlayerLangStorage(new File(getDataFolder(), "players.yml"));
        }
        log("Database successfully initialized (" + storageType.toUpperCase() + ")");
    }

    private void migrateYamlToStorage(PlayerLangStorage targetStorage) {
        String storageType = getConfig().getString("storage.type").toLowerCase();
        File yamlFile = new File(getDataFolder(), "players.yml");
        if (!yamlFile.exists()) return;
        YamlPlayerLangStorage yamlStorage = new YamlPlayerLangStorage(yamlFile);
        for (java.util.Map.Entry<java.util.UUID, String> entry : yamlStorage.loadAll().entrySet()) {
            targetStorage.savePlayerLang(entry.getKey(), entry.getValue());
        }
        getLogger().info("Migrated " + yamlStorage.loadAll().size() + " players from YAML to " + storageType.toUpperCase());
    }

    public FileConfiguration getMessagesConfig() {
        if (messagesConfig == null) {
            File messagesFile = new File(getDataFolder(), "messages.yml");
            messagesConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(messagesFile);
        }
        return messagesConfig;
    }

    private void saveDefaultMessagesConfig() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
    }

    private void saveDefaultExamples() {
        File langsFolder = new File(getDataFolder(), "langs");
        if (!langsFolder.exists()) langsFolder.mkdirs();
        File exampleFolder = new File(langsFolder, "uk_ua");
        if (!exampleFolder.exists()) {
            exampleFolder.mkdirs();
            saveResource("langs/uk_ua/example.yml", false);
        }
        File exampleFolder2 = new File(langsFolder, "en_us");
        if (!exampleFolder2.exists()) {
            exampleFolder2.mkdirs();
            saveResource("langs/en_us/example.yml", false);
        }
    }

    public LangManager getLangManager() {
        return langManager;
    }

    public void log(String message) {
        Bukkit.getConsoleSender().sendMessage(Component.text("[MysterriaTranslator]").color(NamedTextColor.AQUA).append(Component.text(" " + message).color(NamedTextColor.WHITE)));
    }

    public void debug(String message) {
        if (getConfig().getBoolean("debug", false)) {
            Bukkit.getConsoleSender().sendMessage(Component.text("[MysterriaTranslator]").color(NamedTextColor.AQUA).append(Component.text(" [DEBUG] " + message).color(NamedTextColor.YELLOW)));
        }
    }

}
