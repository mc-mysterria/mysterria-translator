package net.mysterria.translator.translation;

public class TranslationResult {
    
    private final String translatedText;
    private final String originalText;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final ResultType type;
    private final String reason;
    
    private TranslationResult(String translatedText, String originalText, String sourceLanguage, 
                             String targetLanguage, ResultType type, String reason) {
        this.translatedText = translatedText;
        this.originalText = originalText;
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.type = type;
        this.reason = reason;
    }
    
    public static TranslationResult success(String translatedText, String originalText, 
                                          String sourceLanguage, String targetLanguage) {
        return new TranslationResult(translatedText, originalText, sourceLanguage, targetLanguage, 
                                   ResultType.SUCCESS, null);
    }
    
    public static TranslationResult noTranslation(String originalText, String reason) {
        return new TranslationResult(originalText, originalText, null, null, 
                                   ResultType.NO_TRANSLATION_NEEDED, reason);
    }
    
    public static TranslationResult failed(String originalText, String reason) {
        return new TranslationResult(originalText, originalText, null, null, 
                                   ResultType.FAILED, reason);
    }
    
    public static TranslationResult rateLimited(String originalText) {
        return new TranslationResult(originalText, originalText, null, null, 
                                   ResultType.RATE_LIMITED, "Rate limit exceeded");
    }
    
    public String getTranslatedText() {
        return translatedText;
    }
    
    public String getOriginalText() {
        return originalText;
    }
    
    public String getSourceLanguage() {
        return sourceLanguage;
    }
    
    public String getTargetLanguage() {
        return targetLanguage;
    }
    
    public ResultType getType() {
        return type;
    }
    
    public String getReason() {
        return reason;
    }
    
    public boolean wasTranslated() {
        return type == ResultType.SUCCESS;
    }
    
    public boolean shouldShowOriginal() {
        return type == ResultType.FAILED || type == ResultType.RATE_LIMITED;
    }
    
    public enum ResultType {
        SUCCESS,
        NO_TRANSLATION_NEEDED,
        FAILED,
        RATE_LIMITED
    }
}