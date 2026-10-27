package net.mysterria.translator.domain.translation.model;

import lombok.Data;

import java.util.List;
import java.util.Locale;

@Data
public class TranslationRequest {
    private final String text;
    private final Locale source;
    private final Locale target;
    private final List<String> playerNames;
}
