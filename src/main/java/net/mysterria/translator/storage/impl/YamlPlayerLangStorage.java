package net.mysterria.translator.storage.impl;

import net.mysterria.translator.storage.PlayerLangStorage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class YamlPlayerLangStorage implements PlayerLangStorage {
    private final File file;
    private final YamlConfiguration config;

    public YamlPlayerLangStorage(File file) {
        this.file = file;
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    @Override
    public void savePlayerLang(UUID uuid, String lang) {
        config.set(uuid.toString() + ".lang", lang);
        try {
            config.save(file);
        } catch (IOException ignored) {}
    }

    @Override
    public String getPlayerLang(UUID uuid) {
        if (config.isConfigurationSection(uuid.toString())) {
            return config.getString(uuid.toString() + ".lang");
        }
        return config.getString(uuid.toString());
    }

    @Override
    public boolean hasPlayerLang(UUID uuid) {
        return config.contains(uuid.toString());
    }

    @Override
    public Map<UUID, String> loadAll() {
        Map<UUID, String> map = new HashMap<>();
        for (String key : config.getKeys(false)) {
            if (config.isConfigurationSection(key)) {
                map.put(UUID.fromString(key), config.getString(key + ".lang"));
            } else {
                map.put(UUID.fromString(key), config.getString(key));
            }
        }
        return map;
    }

    @Override
    public void removePlayerLang(UUID uuid) {
        config.set(uuid.toString(), null);
        try {
            config.save(file);
        } catch (IOException ignored) {}
    }

    @Override
    public void setTranslationEnabled(UUID uuid, boolean enabled) {
        config.set(uuid.toString() + ".enabled", enabled);
        try {
            config.save(file);
        } catch (IOException ignored) {}
    }

    @Override
    public boolean isTranslationEnabled(UUID uuid) {
        return config.getBoolean(uuid.toString() + ".enabled", true);
    }

    @Override
    public Map<UUID, Boolean> loadAllEnabledStatus() {
        Map<UUID, Boolean> map = new HashMap<>();
        for (String key : config.getKeys(false)) {
            if (config.isConfigurationSection(key)) {
                map.put(UUID.fromString(key), config.getBoolean(key + ".enabled", true));
            } else {
                map.put(UUID.fromString(key), true);
            }
        }
        return map;
    }
}
