package net.mysterria.translator.manager;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.storage.PlayerLangStorage;
import net.mysterria.translator.storage.model.LangEnum;
import net.mysterria.translator.util.MessageSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

public class LangManager {

    private final MysterriaTranslator plugin;
    private final PlayerLangStorage playerLangStorage;

    public final Map<UUID, String> playerLang = new HashMap<>();
    private final Map<String, Map<String, String>> translations = new HashMap<>();
    private final Map<String, String> translationCache;

    private final String defaultLang;

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    public LangManager(MysterriaTranslator plugin, PlayerLangStorage storage) {
        this.plugin = plugin;
        this.playerLangStorage = storage;
        this.defaultLang = plugin.getConfig().getString("defaultLang");
        loadPlayerLanguages();
        int cacheSize = plugin.getConfig().getInt("translationCacheSize", 500);
        this.translationCache = new LinkedHashMap<String, String>(cacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > cacheSize;
            }
        };
    }

    public void loadAll() {
        translations.clear();
        File langsFolder = new File(plugin.getDataFolder(), "langs");
        if (!langsFolder.exists()) langsFolder.mkdirs();

        File[] langDirs = langsFolder.listFiles(File::isDirectory);
        if (langDirs != null) {
            for (File langDir : langDirs) {
                String lang = langDir.getName().toLowerCase();
                if (!LangEnum.isValidCode(lang)) {
                    plugin.getLogger().warning("Invalid language folder: " + langDir.getName());
                    plugin.getLogger().warning("Please use a valid language code as the folder name. Codes avaliable: " + LangEnum.getAllCodes());
                    continue;
                }
                Map<String, String> langMap = new HashMap<>();
                File[] files = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
                if (files != null) {
                    for (File file : files) {
                        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                        for (String key : config.getKeys(false)) {
                            Object value = config.get(key);
                            if (value instanceof ConfigurationSection) {
                                flattenSectionUnderscore((ConfigurationSection) value, key + "_", langMap);
                            } else if (value != null) {
                                langMap.put(key.toLowerCase(), value.toString());
                            }
                        }
                    }
                }
                translations.put(lang, langMap);
            }
        }
        loadPlayerLanguages();
    }

    private void flattenSectionUnderscore(ConfigurationSection section, String prefix, Map<String, String> map) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection) {
                flattenSectionUnderscore((ConfigurationSection) value, prefix + key + "_", map);
            } else if (value != null) {
                map.put((prefix + key).toLowerCase(), value.toString());
            }
        }
    }

    public void loadPlayerLanguages() {
        playerLang.clear();
        playerLang.putAll(playerLangStorage.loadAll());
    }

    public void savePlayerLanguages() {
        for (Map.Entry<UUID, String> entry : playerLang.entrySet()) {
            playerLangStorage.savePlayerLang(entry.getKey(), entry.getValue());
        }
    }

    public void setPlayerLang(UUID uuid, String lang) {
        lang.toLowerCase();
        playerLang.put(uuid, lang);
        playerLangStorage.savePlayerLang(uuid, lang);
    }

    public String getPlayerLang(UUID uuid) {
        return playerLang.get(uuid);
    }

    public boolean hasPlayerLang(UUID uuid) {
        return playerLangStorage.hasPlayerLang(uuid);
    }

    public void savePlayerLang(UUID uuid) {
        String lang = playerLang.get(uuid);
        if (lang != null) playerLangStorage.savePlayerLang(uuid, lang);
        plugin.debug("Saved player language " + lang);
    }

    public String getDefaultLang() {
        return defaultLang;
    }

    public Map<String, Map<String, String>> getTranslations() {
        return translations;
    }

    public List<String> getAvailableLangs() {
        return new ArrayList<>(translations.keySet());
    }

    public int getTotalTranslationsCount() {
        int total = 0;
        for (Map<String, String> langMap : translations.values()) {
            total += langMap.size();
        }
        return total;
    }

    public String getTranslation(Player player, String key) {
        String lang = playerLang.getOrDefault(player.getUniqueId(), defaultLang);
        String cacheKey = lang + ":" + key.toLowerCase();

        if (translationCache.containsKey(cacheKey)) {
            String cached = translationCache.get(cacheKey);
            String processedCached = PlaceholderAPI.setPlaceholders(player, cached);
            return LEGACY_SERIALIZER.serialize(MessageSerializer.parseMessage(processedCached));
        }

        Map<String, String> langMap = translations.getOrDefault(lang, Collections.emptyMap());
        Map<String, String> defaultMap = translations.getOrDefault(defaultLang, Collections.emptyMap());
        String translation = langMap.getOrDefault(key.toLowerCase(), defaultMap.get(key.toLowerCase()));

        if (translation == null) {
            translation = MessageSerializer.getMessageString(plugin.getMessagesConfig(), "translation_not_found", "{key}", key);
        }

        translationCache.put(cacheKey, translation);

        String processedTranslation = PlaceholderAPI.setPlaceholders(player, translation);
        return LEGACY_SERIALIZER.serialize(MessageSerializer.parseMessage(processedTranslation));
    }

    public String getLangTranslation(String lang, String key) {
        String cacheKey = lang + ":" + key.toLowerCase();

        if (translationCache.containsKey(cacheKey)) {
            return translationCache.get(cacheKey);
        }

        Map<String, String> langMap = translations.getOrDefault(lang, Collections.emptyMap());
        Map<String, String> defaultMap = translations.getOrDefault(defaultLang, Collections.emptyMap());
        String translation = langMap.getOrDefault(key.toLowerCase(), defaultMap.get(key.toLowerCase()));

        if (translation == null) {
            translation = MessageSerializer.getMessageString(plugin.getMessagesConfig(), "translation_not_found", "{key}", key);
        }

        translationCache.put(cacheKey, translation);

        return LEGACY_SERIALIZER.serialize(MessageSerializer.parseMessage(translation));
    }

    public void clearCache() {
        translationCache.clear();
    }
}
