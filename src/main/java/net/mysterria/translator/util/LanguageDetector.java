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