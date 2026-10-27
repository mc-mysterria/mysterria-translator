package net.mysterria.translator.domain.language.model;

import lombok.Data;

@Data
public class LanguageEntry {
    private final String raw;
    private final String rendered;

    public boolean hasPlaceholders() {
        return rendered == null;
    }
}
