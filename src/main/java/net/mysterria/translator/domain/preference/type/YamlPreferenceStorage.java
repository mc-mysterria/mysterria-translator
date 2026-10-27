package net.mysterria.translator.domain.preference.type;

import net.mysterria.translator.domain.preference.model.LanguagePreference;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class YamlPreferenceStorage implements PreferenceStorage {

    private final File file;
    private final Logger logger;
    private final YamlConfiguration yaml;

    public YamlPreferenceStorage(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    @Override
    public synchronized Map<UUID, LanguagePreference> loadAll() {
        Map<UUID, LanguagePreference> preferences = new HashMap<>();
        for (String key : yaml.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                logger.warn("Skipping malformed entry '{}' in {}", key, file.getName());
                continue;
            }
            ConfigurationSection section = yaml.getConfigurationSection(key);
            preferences.put(uuid, section == null
                    ? new LanguagePreference(yaml.getString(key), null, true, false)
                    : new LanguagePreference(section.getString("lang"), section.getString("chat"),
                    section.getBoolean("enabled", true), section.getBoolean("hint", false)));
        }
        return preferences;
    }

    @Override
    public synchronized void save(UUID uuid, LanguagePreference preference) {
        String key = uuid.toString();
        yaml.set(key, null);
        yaml.set(key + ".lang", preference.getLanguage());
        yaml.set(key + ".chat", preference.getChatLanguage());
        yaml.set(key + ".enabled", preference.isTranslationsEnabled());
        yaml.set(key + ".hint", preference.isHintShown());
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.error("Could not save language preference of {} to {}", uuid, file.getName(), e);
        }
    }

    @Override
    public void close() {
    }
}
