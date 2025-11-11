package net.mysterria.translator.util;

import net.mysterria.translator.MysterriaTranslator;
import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Validates configuration settings for translation providers and plugin settings.
 * Provides detailed feedback about configuration issues during reload operations.
 */
public class ConfigValidator {

    private static final List<String> VALID_PROVIDERS = Arrays.asList("ollama", "libretranslate", "gemini", "openai");
    private final MysterriaTranslator plugin;
    private final List<String> errors;
    private final List<String> warnings;

    public ConfigValidator(MysterriaTranslator plugin) {
        this.plugin = plugin;
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }

    /**
     * Validates all translation configuration settings.
     * @return ValidationResult containing errors, warnings, and success status
     */
    public ValidationResult validate() {
        errors.clear();
        warnings.clear();


        boolean translationEnabled = plugin.getConfig().getBoolean("translation.enabled", true);
        if (!translationEnabled) {
            warnings.add("Translation is disabled in config");
        }


        String providerString = plugin.getConfig().getString("translation.provider", "ollama");
        List<String> providers = parseProviders(providerString);

        if (providers.isEmpty()) {
            errors.add("No valid translation providers configured");
        } else {
            for (String provider : providers) {
                validateProvider(provider);
            }
        }


        validateNumericSetting("translation.cacheExpirySeconds", 1, 3600);
        validateNumericSetting("translation.rateLimitMessages", 1, 100);
        validateNumericSetting("translation.rateLimitWindowSeconds", 1, 300);
        validateNumericSetting("translation.minMessageLength", 0, 100);
        validateNumericSetting("translation.maxRetries", 0, 10);
        validateNumericSetting("translationCacheSize", 10, 10000);

        return new ValidationResult(errors, warnings);
    }

    /**
     * Parses provider string (comma-separated) and validates provider names.
     */
    private List<String> parseProviders(String providerString) {
        List<String> validProviders = new ArrayList<>();
        String[] providers = providerString.split(",");

        for (String provider : providers) {
            String trimmed = provider.trim().toLowerCase();
            if (VALID_PROVIDERS.contains(trimmed)) {
                validProviders.add(trimmed);
            } else {
                errors.add("Invalid provider name: '" + provider.trim() + "'. Valid options: " + String.join(", ", VALID_PROVIDERS));
            }
        }

        return validProviders;
    }

    /**
     * Validates configuration for a specific provider.
     */
    private void validateProvider(String provider) {
        switch (provider) {
            case "ollama":
                validateOllama();
                break;
            case "libretranslate":
                validateLibreTranslate();
                break;
            case "gemini":
                validateGemini();
                break;
            case "openai":
                validateOpenAI();
                break;
        }
    }

    private void validateOllama() {
        String url = plugin.getConfig().getString("translation.ollama.url");
        if (url == null || url.isEmpty()) {
            errors.add("Ollama: URL is not configured");
        } else if (!isValidUrl(url)) {
            errors.add("Ollama: Invalid URL format: " + url);
        }

        String model = plugin.getConfig().getString("translation.ollama.model");
        if (model == null || model.isEmpty()) {
            errors.add("Ollama: Model is not configured");
        }

        validateNumericSetting("translation.ollama.connectTimeout", 1, 300);
        validateNumericSetting("translation.ollama.requestTimeout", 1, 600);
    }

    private void validateLibreTranslate() {
        String url = plugin.getConfig().getString("translation.libretranslate.url");
        if (url == null || url.isEmpty()) {
            errors.add("LibreTranslate: URL is not configured");
        } else if (!isValidUrl(url)) {
            errors.add("LibreTranslate: Invalid URL format: " + url);
        }

        String apiKey = plugin.getConfig().getString("translation.libretranslate.apiKey", "");
        if (apiKey.isEmpty()) {
            warnings.add("LibreTranslate: No API key configured (may be required by some instances)");
        }

        validateNumericSetting("translation.libretranslate.alternatives", 1, 10);
        validateNumericSetting("translation.libretranslate.connectTimeout", 1, 300);
        validateNumericSetting("translation.libretranslate.readTimeout", 1, 600);
    }

    private void validateGemini() {
        ConfigurationSection geminiSection = plugin.getConfig().getConfigurationSection("translation.gemini");
        if (geminiSection == null) {
            errors.add("Gemini: Configuration section not found");
            return;
        }

        List<String> apiKeys = geminiSection.getStringList("apiKeys");
        if (apiKeys.isEmpty()) {
            errors.add("Gemini: No API keys configured");
        } else {
            for (int i = 0; i < apiKeys.size(); i++) {
                String key = apiKeys.get(i);
                if (key == null || key.trim().isEmpty() || key.contains("your-gemini-api-key")) {
                    errors.add("Gemini: API key #" + (i + 1) + " is not configured or contains placeholder text");
                }
            }
        }

        String model = plugin.getConfig().getString("translation.gemini.model");
        if (model == null || model.isEmpty()) {
            warnings.add("Gemini: No model specified, will use default (gemini-2.0-flash)");
        }

        validateNumericSetting("translation.gemini.connectTimeout", 1, 300);
        validateNumericSetting("translation.gemini.readTimeout", 1, 600);
    }

    private void validateOpenAI() {
        String apiKey = plugin.getConfig().getString("translation.openai.apiKey", "");
        if (apiKey.isEmpty() || apiKey.contains("sk-...") || apiKey.contains("your-api-key")) {
            errors.add("OpenAI: API key is not configured or contains placeholder text");
        }

        String model = plugin.getConfig().getString("translation.openai.model");
        if (model == null || model.isEmpty()) {
            warnings.add("OpenAI: No model specified, will use default (gpt-4o-mini)");
        }

        String baseUrl = plugin.getConfig().getString("translation.openai.baseUrl");
        if (baseUrl == null || baseUrl.isEmpty()) {
            warnings.add("OpenAI: No base URL specified, will use default (https://api.openai.com/v1)");
        } else if (!isValidUrl(baseUrl)) {
            errors.add("OpenAI: Invalid base URL format: " + baseUrl);
        }

        validateNumericSetting("translation.openai.connectTimeout", 1, 300);
        validateNumericSetting("translation.openai.readTimeout", 1, 600);
    }

    /**
     * Validates that a numeric setting exists and is within acceptable range.
     */
    private void validateNumericSetting(String path, int min, int max) {
        if (!plugin.getConfig().contains(path)) {
            warnings.add("Setting '" + path + "' not found, using default");
            return;
        }

        int value = plugin.getConfig().getInt(path, min);
        if (value < min || value > max) {
            warnings.add("Setting '" + path + "' value " + value + " is outside recommended range [" + min + "-" + max + "]");
        }
    }

    /**
     * Validates URL format.
     */
    private boolean isValidUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equals("http") || scheme.equals("https"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Result of configuration validation.
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {
        public ValidationResult(List<String> errors, List<String> warnings) {
            this.errors = new ArrayList<>(errors);
            this.warnings = new ArrayList<>(warnings);
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public String getSummary() {
            StringBuilder sb = new StringBuilder();

            if (!errors.isEmpty()) {
                sb.append("§c✗ Configuration Errors (").append(errors.size()).append("):\n");
                for (String error : errors) {
                    sb.append("  §c- ").append(error).append("\n");
                }
            }

            if (!warnings.isEmpty()) {
                sb.append("§e⚠ Configuration Warnings (").append(warnings.size()).append("):\n");
                for (String warning : warnings) {
                    sb.append("  §e- ").append(warning).append("\n");
                }
            }

            if (errors.isEmpty() && warnings.isEmpty()) {
                sb.append("§a✓ Configuration is valid\n");
            }

            return sb.toString();
        }
    }
}
