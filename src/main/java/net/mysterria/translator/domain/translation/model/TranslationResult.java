package net.mysterria.translator.domain.translation.model;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.mysterria.translator.domain.translation.model.source.ResultType;

import java.util.Locale;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class TranslationResult {
    private final ResultType type;
    private final String original;
    private final String translated;
    private final Locale source;
    private final Locale target;

    public static TranslationResult success(String original, String translated, Locale source, Locale target) {
        return new TranslationResult(ResultType.SUCCESS, original, translated, source, target);
    }

    public static TranslationResult failed(String original, Locale source, Locale target) {
        return new TranslationResult(ResultType.FAILED, original, original, source, target);
    }

    public static TranslationResult rateLimited(String original, Locale source, Locale target) {
        return new TranslationResult(ResultType.RATE_LIMITED, original, original, source, target);
    }

    public boolean isTranslated() {
        return type == ResultType.SUCCESS;
    }
}
