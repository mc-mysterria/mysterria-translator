package net.mysterria.translator.domain.preference.service;

import net.mysterria.translator.config.models.StorageSettings;
import net.mysterria.translator.domain.preference.model.LanguagePreference;
import net.mysterria.translator.domain.preference.type.PreferenceStorage;
import net.mysterria.translator.domain.preference.type.SqlPreferenceStorage;
import net.mysterria.translator.domain.preference.type.YamlPreferenceStorage;
import net.mysterria.translator.util.language.Languages;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.io.File;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class PreferenceService {

    private final File dataFolder;
    private final Logger logger;
    private final Map<UUID, LanguagePreference> preferences = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("MysterriaTranslator-Storage").factory());
    private final PreferenceStorage storage;
    private volatile String defaultLanguage;

    public PreferenceService(File dataFolder, Logger logger, StorageSettings settings, String defaultLanguage) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.defaultLanguage = defaultLanguage;
        this.storage = open(settings);
        preferences.putAll(storage.loadAll());
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    public LanguagePreference find(UUID uuid) {
        return preferences.get(uuid);
    }

    public String language(UUID uuid) {
        LanguagePreference preference = preferences.get(uuid);
        return preference == null || preference.getLanguage() == null ? defaultLanguage : preference.getLanguage();
    }

    public boolean translationsEnabled(UUID uuid) {
        LanguagePreference preference = preferences.get(uuid);
        return preference == null || preference.isTranslationsEnabled();
    }

    public Locale chatTarget(Player player) {
        LanguagePreference preference = preferences.get(player.getUniqueId());
        return preference == null || preference.getChatLanguage() == null
                ? Languages.normalize(player.locale())
                : Languages.fromCode(preference.getChatLanguage());
    }

    public boolean hasLanguage(UUID uuid) {
        LanguagePreference preference = preferences.get(uuid);
        return preference != null && preference.getLanguage() != null;
    }

    public void update(UUID uuid, Consumer<LanguagePreference> change) {
        LanguagePreference updated = preferences.compute(uuid, (id, current) -> {
            LanguagePreference preference = current == null ? new LanguagePreference(defaultLanguage, null, true, false) : current;
            change.accept(preference);
            return preference;
        });
        LanguagePreference snapshot = updated.copy();
        writer.execute(() -> storage.save(uuid, snapshot));
    }

    public void assignLanguage(UUID uuid, String language) {
        update(uuid, preference -> preference.setLanguage(language == null ? defaultLanguage : language));
    }

    public boolean claimHint(UUID uuid) {
        LanguagePreference preference = preferences.get(uuid);
        if (preference != null && preference.isHintShown()) {
            return false;
        }
        update(uuid, current -> current.setHintShown(true));
        return true;
    }

    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Pending language preference writes did not finish within 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        storage.close();
    }

    private PreferenceStorage open(StorageSettings settings) {
        File yamlFile = new File(dataFolder, "players.yml");
        if (settings.getType().equals("yaml")) {
            return new YamlPreferenceStorage(yamlFile, logger);
        }
        try {
            PreferenceStorage database = settings.getType().equals("mysql")
                    ? new SqlPreferenceStorage(mysqlUrl(settings), settings.getUser(), settings.getPassword(), true, logger)
                    : new SqlPreferenceStorage("jdbc:sqlite:" + new File(dataFolder, "players.db"), null, null, false, logger);
            migrate(yamlFile, database);
            return database;
        } catch (SQLException e) {
            logger.error("Could not open {} storage, falling back to players.yml", settings.getType(), e);
            return new YamlPreferenceStorage(yamlFile, logger);
        }
    }

    private void migrate(File yamlFile, PreferenceStorage database) {
        if (!yamlFile.exists()) {
            return;
        }
        Map<UUID, LanguagePreference> legacy = new YamlPreferenceStorage(yamlFile, logger).loadAll();
        legacy.forEach(database::save);
        File migrated = new File(dataFolder, "players.yml.migrated");
        if (!yamlFile.renameTo(migrated)) {
            logger.warn("Migrated players.yml but could not rename it; it will be imported again on next start");
        }
        logger.info("Migrated {} language preferences from players.yml", legacy.size());
    }

    private static String mysqlUrl(StorageSettings settings) {
        String properties = settings.getProperties().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        return "jdbc:mysql://" + settings.getHost() + ":" + settings.getPort() + "/" + settings.getDatabase()
                + (properties.isEmpty() ? "" : "?" + properties);
    }
}
