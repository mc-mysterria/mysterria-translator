package net.mysterria.translator.domain.language.service;

import me.clip.placeholderapi.PlaceholderAPI;
import net.mysterria.translator.config.Messages;
import net.mysterria.translator.domain.language.model.LanguageEntry;
import net.mysterria.translator.domain.language.model.source.Language;
import net.mysterria.translator.domain.preference.service.PreferenceService;
import net.mysterria.translator.util.text.TextFormat;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LanguageService {

    private final File langsFolder;
    private final Logger logger;
    private final PreferenceService preferences;
    private final Messages messages;
    private volatile Map<String, Map<String, LanguageEntry>> languages = Map.of();
    private volatile String defaultLanguage;

    public LanguageService(File dataFolder, Logger logger, PreferenceService preferences, Messages messages) {
        this.langsFolder = new File(dataFolder, "langs");
        this.logger = logger;
        this.preferences = preferences;
        this.messages = messages;
    }

    public void load(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
        Map<String, Map<String, LanguageEntry>> loaded = new HashMap<>();
        File[] folders = langsFolder.listFiles(File::isDirectory);
        for (File folder : folders == null ? new File[0] : folders) {
            String code = folder.getName().toLowerCase();
            if (!Language.isValidCode(code)) {
                logger.warn("Ignoring langs/{}: not a Minecraft language code (e.g. en_us, uk_ua)", folder.getName());
                continue;
            }
            Map<String, LanguageEntry> entries = new HashMap<>();
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
            for (File file : files == null ? new File[0] : files) {
                flatten(YamlConfiguration.loadConfiguration(file), "", entries);
            }
            loaded.put(code, Map.copyOf(entries));
        }
        if (!loaded.containsKey(defaultLanguage)) {
            logger.warn("defaultLang '{}' has no folder in langs/, missing keys will show as not found", defaultLanguage);
        }
        this.languages = Map.copyOf(loaded);
    }

    public String translate(OfflinePlayer player, String key) {
        String language = player == null ? defaultLanguage : preferences.language(player.getUniqueId());
        String normalizedKey = key.toLowerCase();
        LanguageEntry entry = lookup(language, normalizedKey);
        if (entry == null) {
            entry = lookup(defaultLanguage, normalizedKey);
        }
        if (entry == null) {
            return TextFormat.toLegacy(messages.get("translation_not_found", "{key}", key));
        }
        if (!entry.hasPlaceholders()) {
            return entry.getRendered();
        }
        String resolved = player == null ? entry.getRaw() : PlaceholderAPI.setPlaceholders(player, entry.getRaw());
        return TextFormat.toLegacy(TextFormat.parse(resolved));
    }

    public List<String> available() {
        return languages.keySet().stream().sorted().toList();
    }

    public boolean isAvailable(String language) {
        return languages.containsKey(language);
    }

    public int entryCount() {
        return languages.values().stream().mapToInt(Map::size).sum();
    }

    private LanguageEntry lookup(String language, String key) {
        Map<String, LanguageEntry> entries = languages.get(language);
        return entries == null ? null : entries.get(key);
    }

    private static void flatten(ConfigurationSection section, String prefix, Map<String, LanguageEntry> entries) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            String path = (prefix + key).toLowerCase();
            if (value instanceof ConfigurationSection child) {
                flatten(child, path + "_", entries);
            } else if (value != null) {
                String raw = value.toString();
                String rendered = raw.indexOf('%') >= 0 ? null : TextFormat.toLegacy(TextFormat.parse(raw));
                entries.put(path, new LanguageEntry(raw, rendered));
            }
        }
    }
}
