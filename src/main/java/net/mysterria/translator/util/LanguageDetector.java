package net.mysterria.translator.util;

import java.util.regex.Pattern;

public class LanguageDetector {
    
    private static final Pattern CYRILLIC_PATTERN = Pattern.compile("[\\u0400-\\u04FF]+");
    private static final Pattern LATIN_PATTERN = Pattern.compile("[a-zA-Z]+");
    
    private static final double CYRILLIC_THRESHOLD = 0.3;
    private static final double LATIN_THRESHOLD = 0.3;
    
    public enum DetectedLanguage {
        UKRAINIAN("Ukrainian", "uk_ua"),
        ENGLISH("English", "en_us"),
        UNKNOWN("Unknown", null);
        
        private final String displayName;
        private final String langCode;
        
        DetectedLanguage(String displayName, String langCode) {
            this.displayName = displayName;
            this.langCode = langCode;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public String getLangCode() {
            return langCode;
        }
    }
    
    public static DetectedLanguage detectLanguage(String text) {
        if (text == null || text.trim().isEmpty()) {
            return DetectedLanguage.UNKNOWN;
        }
        
        text = text.trim();
        if (text.length() < 3) {
            return DetectedLanguage.UNKNOWN;
        }
        
        int totalChars = text.length();
        int cyrillicChars = countMatches(text, CYRILLIC_PATTERN);
        int latinChars = countMatches(text, LATIN_PATTERN);
        
        double cyrillicRatio = (double) cyrillicChars / totalChars;
        double latinRatio = (double) latinChars / totalChars;
        
        if (cyrillicRatio >= CYRILLIC_THRESHOLD) {
            return DetectedLanguage.UKRAINIAN;
        } else if (latinRatio >= LATIN_THRESHOLD) {
            return DetectedLanguage.ENGLISH;
        }
        
        return DetectedLanguage.UNKNOWN;
    }
    
    public static boolean needsTranslation(String text, String playerLocale) {
        DetectedLanguage detected = detectLanguage(text);
        
        if (detected == DetectedLanguage.UNKNOWN) {
            return false;
        }
        
        String targetLang = getTargetLanguage(playerLocale);
        return !detected.getLangCode().equals(targetLang);
    }
    
    public static String getTargetLanguage(String playerLocale) {
        if (playerLocale == null) {
            return "en_us";
        }

        playerLocale = playerLocale.toLowerCase();

        if (playerLocale.startsWith("uk") || playerLocale.contains("ua")) {
            return "uk_ua";
        } else if (playerLocale.startsWith("ru") || playerLocale.contains("ru")) {
            return "ru_ru";
        } else if (playerLocale.startsWith("es") || playerLocale.contains("es")) {
            return "es_es";
        } else if (playerLocale.startsWith("fr") || playerLocale.contains("fr")) {
            return "fr_fr";
        } else if (playerLocale.startsWith("de") || playerLocale.contains("de")) {
            return "de_de";
        } else if (playerLocale.startsWith("it") || playerLocale.contains("it")) {
            return "it_it";
        } else if (playerLocale.startsWith("pt") || playerLocale.contains("pt") || playerLocale.contains("br")) {
            return "pt_pt";
        } else if (playerLocale.startsWith("zh") || playerLocale.contains("cn")) {
            return "zh_cn";
        } else if (playerLocale.startsWith("ja") || playerLocale.contains("jp")) {
            return "ja_jp";
        } else if (playerLocale.startsWith("ko") || playerLocale.contains("kr")) {
            return "ko_kr";
        } else if (playerLocale.startsWith("ar") || playerLocale.contains("sa")) {
            return "ar_sa";
        } else if (playerLocale.startsWith("hi") || playerLocale.contains("in")) {
            return "hi_in";
        } else if (playerLocale.startsWith("pl") || playerLocale.contains("pl")) {
            return "pl_pl";
        } else if (playerLocale.startsWith("nl") || playerLocale.contains("nl")) {
            return "nl_nl";
        } else if (playerLocale.startsWith("sv") || playerLocale.contains("se")) {
            return "sv_se";
        } else if (playerLocale.startsWith("no") || playerLocale.contains("no")) {
            return "no_no";
        } else if (playerLocale.startsWith("da") || playerLocale.contains("dk")) {
            return "da_dk";
        } else if (playerLocale.startsWith("fi") || playerLocale.contains("fi")) {
            return "fi_fi";
        } else if (playerLocale.startsWith("cs") || playerLocale.contains("cz")) {
            return "cs_cz";
        } else if (playerLocale.startsWith("hu") || playerLocale.contains("hu")) {
            return "hu_hu";
        } else if (playerLocale.startsWith("ro") || playerLocale.contains("ro")) {
            return "ro_ro";
        } else if (playerLocale.startsWith("bg") || playerLocale.contains("bg")) {
            return "bg_bg";
        } else if (playerLocale.startsWith("el") || playerLocale.contains("gr")) {
            return "el_gr";
        } else if (playerLocale.startsWith("tr") || playerLocale.contains("tr")) {
            return "tr_tr";
        } else if (playerLocale.startsWith("he") || playerLocale.contains("il")) {
            return "he_il";
        } else if (playerLocale.startsWith("th") || playerLocale.contains("th")) {
            return "th_th";
        } else if (playerLocale.startsWith("vi") || playerLocale.contains("vn")) {
            return "vi_vn";
        }

        return "en_us";
    }
    
    private static int countMatches(String text, Pattern pattern) {
        int count = 0;
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count += matcher.group().length();
        }
        return count;
    }
}