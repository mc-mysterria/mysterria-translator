package net.mysterria.translator.storage;

import java.util.Map;
import java.util.UUID;

public interface PlayerLangStorage {
    void savePlayerLang(UUID uuid, String lang);
    String getPlayerLang(UUID uuid);
    boolean hasPlayerLang(UUID uuid);
    Map<UUID, String> loadAll();
    void removePlayerLang(UUID uuid);

    void setTranslationEnabled(UUID uuid, boolean enabled);
    boolean isTranslationEnabled(UUID uuid);
    Map<UUID, Boolean> loadAllEnabledStatus();
}
