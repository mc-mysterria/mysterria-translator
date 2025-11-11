package net.mysterria.translator.engine.gemini;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.exception.RateLimitException;
import net.mysterria.translator.manager.PromptManager;
import net.mysterria.translator.translation.RateLimitManager;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class GeminiClient {

    private final List<String> apiKeys;
    private final MysterriaTranslator plugin;
    private final PromptManager promptManager;
    private final RateLimitManager suspensionManager;
    private final Gson gson;
    private final String model;
    private final int connectTimeout;
    private final int readTimeout;

    public GeminiClient(MysterriaTranslator plugin, PromptManager promptManager,
                        RateLimitManager suspensionManager, List<String> apiKeys) {
        this.plugin = plugin;
        this.promptManager = promptManager;
        this.suspensionManager = suspensionManager;
        this.apiKeys = apiKeys;
        this.gson = new Gson();


        this.model = plugin.getConfig().getString("translation.gemini.model", "gemini-2.0-flash");
        this.connectTimeout = plugin.getConfig().getInt("translation.gemini.connectTimeout", 10);
        this.readTimeout = plugin.getConfig().getInt("translation.gemini.readTimeout", 15);

        plugin.debug("Gemini client initialized with model=" + model +
                     ", connectTimeout=" + connectTimeout + "s, readTimeout=" + readTimeout + "s");
    }

    public CompletableFuture<String> translateAsync(String text, String fromLang, String toLang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return translate(text, fromLang, toLang);
            } catch (Exception e) {
                plugin.debug("Gemini translation failed: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<String> translateAsyncWithContext(String text, String fromLang, String toLang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return translateWithContext(text, fromLang, toLang);
            } catch (Exception e) {
                plugin.debug("Gemini translation with context failed: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    private String translate(String text, String fromLang, String toLang) throws RateLimitException {
        JsonObject jsonPayload = createTranslationPayload(text, fromLang, toLang, false);
        return executeRequest(jsonPayload);
    }

    private String translateWithContext(String text, String fromLang, String toLang) throws RateLimitException {
        JsonObject jsonPayload = createTranslationPayload(text, fromLang, toLang, true);
        return executeRequest(jsonPayload);
    }

    private JsonObject createTranslationPayload(String text, String fromLang, String toLang, boolean includeContext) {
        JsonObject jsonPayload = new JsonObject();
        JsonArray contentsArray = new JsonArray();

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        JsonArray parts = new JsonArray();

        JsonObject textPart = new JsonObject();
        String prompt = buildTranslationPrompt(text, fromLang, toLang, includeContext);
        textPart.addProperty("text", prompt);
        parts.add(textPart);

        userMessage.add("parts", parts);
        contentsArray.add(userMessage);
        jsonPayload.add("contents", contentsArray);

        JsonObject systemInstruction = new JsonObject();
        systemInstruction.addProperty("role", "user");
        JsonArray systemParts = new JsonArray();
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", getSystemInstruction(includeContext));
        systemParts.add(systemPart);
        systemInstruction.add("parts", systemParts);
        jsonPayload.add("systemInstruction", systemInstruction);

        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("temperature", 0.2);
        genConfig.addProperty("maxOutputTokens", 500);
        genConfig.addProperty("topP", 0.8);
        genConfig.addProperty("topK", 20);
        jsonPayload.add("generationConfig", genConfig);

        return jsonPayload;
    }

    private String buildTranslationPrompt(String text, String fromLang, String toLang, boolean includeContext) {
        Map<String, String> variables = new HashMap<>();
        variables.put("sourceLang", mapLanguageForGemini(fromLang));
        variables.put("targetLang", mapLanguageForGemini(toLang));
        variables.put("message", text);


        if (includeContext) {
            List<String> onlinePlayerNames = plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
            variables.put("playerContext", String.join(", ", onlinePlayerNames));
        }


        String promptKey;
        boolean isAutoDetect = "auto".equalsIgnoreCase(fromLang) || fromLang == null;

        if (includeContext) {
            promptKey = isAutoDetect ? "gemini.autoDetectPromptWithContext" : "gemini.translationPromptWithContext";
        } else {
            promptKey = isAutoDetect ? "gemini.autoDetectPrompt" : "gemini.translationPrompt";
        }

        return promptManager.getPrompt(promptKey, variables);
    }

    private String mapLanguageForGemini(String langCode) {
        if (langCode == null) return "English";
        return switch (langCode.toLowerCase()) {
            case "ukrainian", "uk_ua", "uk" -> "Ukrainian";
            case "russian", "ru_ru", "ru" -> "Russian";
            case "spanish", "es_es", "es" -> "Spanish";
            case "french", "fr_fr", "fr" -> "French";
            case "german", "de_de", "de" -> "German";
            case "italian", "it_it", "it" -> "Italian";
            case "portuguese", "pt_pt", "pt" -> "Portuguese";
            case "chinese", "zh_cn", "zh" -> "Chinese";
            case "japanese", "ja_jp", "ja" -> "Japanese";
            case "korean", "ko_kr", "ko" -> "Korean";
            case "arabic", "ar_sa", "ar" -> "Arabic";
            case "hindi", "hi_in", "hi" -> "Hindi";
            case "polish", "pl_pl", "pl" -> "Polish";
            case "dutch", "nl_nl", "nl" -> "Dutch";
            case "swedish", "sv_se", "sv" -> "Swedish";
            case "norwegian", "no_no", "no" -> "Norwegian";
            case "danish", "da_dk", "da" -> "Danish";
            case "finnish", "fi_fi", "fi" -> "Finnish";
            case "czech", "cs_cz", "cs" -> "Czech";
            case "hungarian", "hu_hu", "hu" -> "Hungarian";
            case "romanian", "ro_ro", "ro" -> "Romanian";
            case "bulgarian", "bg_bg", "bg" -> "Bulgarian";
            case "greek", "el_gr", "el" -> "Greek";
            case "turkish", "tr_tr", "tr" -> "Turkish";
            case "hebrew", "he_il", "he" -> "Hebrew";
            case "thai", "th_th", "th" -> "Thai";
            case "vietnamese", "vi_vn", "vi" -> "Vietnamese";
            default -> "English";
        };
    }

    private String getSystemInstruction(boolean includeContext) {
        String promptKey = includeContext ? "gemini.systemInstructionWithContext" : "gemini.systemInstruction";
        return promptManager.getPrompt(promptKey, new HashMap<>());
    }

    private String executeRequest(JsonObject jsonPayload) throws RateLimitException {
        Exception lastException = null;
        int attemptedKeys = 0;
        int suspendedKeys = 0;

        for (int keyIndex = 0; keyIndex < apiKeys.size(); keyIndex++) {
            String keyIdentifier = "key-" + keyIndex;


            if (suspensionManager != null && suspensionManager.isKeySuspended("gemini", keyIdentifier)) {
                plugin.debug("Skipping Gemini key #" + keyIndex + " (suspended due to rate limit)");
                suspendedKeys++;
                continue;
            }

            String apiKey = apiKeys.get(keyIndex);
            attemptedKeys++;

            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(connectTimeout * 1000);
                connection.setReadTimeout(readTimeout * 1000);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(jsonPayload.toString().getBytes());
                    os.flush();
                }

                int statusCode = connection.getResponseCode();


                if (statusCode == 429) {
                    String errorMsg = "Gemini key #" + keyIndex + " rate limit exceeded (HTTP 429)";
                    plugin.debug(errorMsg);
                    throw new RateLimitException("gemini", keyIdentifier, 429, errorMsg);
                }

                if (statusCode != 200) {
                    throw new IOException("Status " + statusCode);
                }

                StringBuilder response = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                }

                return extractTextFromResponse(response.toString());

            } catch (Exception e) {
                lastException = e;


                if (e instanceof RateLimitException) {
                    throw (RateLimitException) e;
                }
            }
        }


        String errorMsg;
        if (suspendedKeys == apiKeys.size()) {
            errorMsg = "All " + apiKeys.size() + " Gemini API key(s) are currently suspended due to rate limits";
        } else if (attemptedKeys == 0) {
            errorMsg = "No Gemini API keys available (all suspended)";
        } else {
            errorMsg = "All available Gemini API keys failed (attempted: " + attemptedKeys + ", suspended: " + suspendedKeys + ")";
        }

        plugin.debug(errorMsg);
        throw new RuntimeException(errorMsg, lastException);
    }

    private String extractTextFromResponse(String jsonResponse) {
        try {
            JsonObject jsonObject = gson.fromJson(jsonResponse, JsonObject.class);
            JsonArray candidates = jsonObject.getAsJsonArray("candidates");
            JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            JsonArray parts = content.getAsJsonArray("parts");
            String translation = parts.get(0).getAsJsonObject().get("text").getAsString().trim();

            if (plugin.getConfig().getBoolean("debug")) {
                plugin.debug("Gemini: " + translation);
            }

            return translation;
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract text from Gemini response", e);
        }
    }

    public CompletableFuture<Boolean> isAvailable() {
        return CompletableFuture.supplyAsync(() -> {
            if (apiKeys.isEmpty()) {
                return false;
            }

            try {
                JsonObject testPayload = createTranslationPayload("test", "English", "Spanish", false);
                executeRequest(testPayload);
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * Closes the client and releases resources.
     */
    public void close() {
        plugin.debug("Gemini client closed");
    }
}