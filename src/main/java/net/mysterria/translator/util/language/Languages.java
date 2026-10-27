package net.mysterria.translator.util.language;

import java.util.Locale;

public class Languages {

    private static final Locale UKRAINIAN = Locale.of("uk");
    private static final double SCRIPT_THRESHOLD = 0.3;
    private static final int MIN_DETECTABLE_LENGTH = 3;

    public static Locale detect(String text) {
        String trimmed = text.trim();
        if (trimmed.length() < MIN_DETECTABLE_LENGTH) {
            return null;
        }
        int cyrillic = 0;
        int latin = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= 'Ѐ' && c <= 'ӿ') {
                cyrillic++;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latin++;
            }
        }
        if (cyrillic >= trimmed.length() * SCRIPT_THRESHOLD) {
            return UKRAINIAN;
        }
        return latin >= trimmed.length() * SCRIPT_THRESHOLD ? Locale.ENGLISH : null;
    }

    public static Locale normalize(Locale locale) {
        return locale.getLanguage().equals("zh") && !locale.getCountry().isEmpty()
                ? Locale.of("zh", locale.getCountry())
                : Locale.of(locale.getLanguage());
    }

    public static Locale fromCode(String code) {
        return normalize(Locale.forLanguageTag(code.replace('_', '-')));
    }

    public static boolean sameLanguage(Locale first, Locale second) {
        return first.getLanguage().equals(second.getLanguage());
    }

    public static String displayName(Locale locale) {
        return locale == null ? "auto" : locale.getDisplayLanguage(Locale.ENGLISH);
    }
}
