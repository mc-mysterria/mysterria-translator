package net.mysterria.translator.domain.preference.type;

import net.mysterria.translator.domain.preference.model.LanguagePreference;

import java.util.Map;
import java.util.UUID;

public interface PreferenceStorage {

    Map<UUID, LanguagePreference> loadAll();

    void save(UUID uuid, LanguagePreference preference);

    void close();
}
