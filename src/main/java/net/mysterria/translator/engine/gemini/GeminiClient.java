package net.mysterria.translator.engine.gemini;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mysterria.translator.MysterriaTranslator;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class GeminiClient {

    private final List<String> apiKeys;
    private final MysterriaTranslator plugin;
    private final Gson gson;
    private final String model;
    private final int connectTimeout;
    private final int readTimeout;

    public GeminiClient(MysterriaTranslator plugin, List<String> apiKeys) {
        this.plugin = plugin;
        this.apiKeys = apiKeys;
        this.gson = new Gson();

        // Get configurable model and timeouts
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

    private String translate(String text, String fromLang, String toLang) throws IOException, InterruptedException {
        JsonObject jsonPayload = createTranslationPayload(text, fromLang, toLang, false);
        return executeRequest(jsonPayload);
    }

    private String translateWithContext(String text, String fromLang, String toLang) throws IOException, InterruptedException {
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
        StringBuilder prompt = new StringBuilder();

        if (includeContext) {
            List<String> onlinePlayerNames = plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());

            if (!onlinePlayerNames.isEmpty()) {
                prompt.append("Online players: ").append(String.join(", ", onlinePlayerNames)).append("\n\n");
            }
        }

        // Check if Gemini should auto-detect source language
        if ("auto".equalsIgnoreCase(fromLang) || fromLang == null) {
            prompt.append("Automatically detect the language of the following text and translate it to ")
                  .append(mapLanguageForGemini(toLang)).append(":\n\n")
                  .append(text);
        } else {
            prompt.append("Translate the following text from ").append(mapLanguageForGemini(fromLang))
                  .append(" to ").append(mapLanguageForGemini(toLang)).append(":\n\n")
                  .append(text);
        }

        return prompt.toString();
    }

    private String mapLanguageForGemini(String langCode) {
        if (langCode == null) return "English";
        return switch (langCode.toLowerCase()) {
            case "ukrainian", "uk_ua", "uk" -> "Ukrainian";
            case "english", "en_us", "en" -> "English";
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
        StringBuilder instruction = new StringBuilder();
        instruction.append("You are a professional translator for a Minecraft server chat system. ");
        instruction.append("Your task is to translate messages between players accurately while preserving gaming context and informal tone. ");
        instruction.append("Guidelines:\n");
        instruction.append("- Preserve gaming terminology, slang, and Minecraft-specific terms\n");
        instruction.append("- Keep the informal, casual tone typical of gaming chat\n");
        instruction.append("- Don't translate proper nouns unless contextually necessary\n");
        if (includeContext) {
            instruction.append("- Player names in the 'Online players' list should not be translated\n");
        }
        instruction.append("- For ambiguous words, choose meaning based on gaming/chat context\n");
        instruction.append("- Return ONLY the translated text, no explanations or extra formatting\n");
        instruction.append("- If the text is already in the target language, return it unchanged\n");
        instruction.append("- Preserve any special characters or formatting symbols");

        return instruction.toString();
    }

    private String executeRequest(JsonObject jsonPayload) throws IOException, InterruptedException {
        Exception lastException = null;
        int failedKeys = 0;
        boolean allRateLimited = true;

        for (String apiKey : apiKeys) {
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

                if (connection.getResponseCode() != 200) {
                    int statusCode = connection.getResponseCode();
                    if (statusCode != 429) {
                        allRateLimited = false;
                    }
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
                failedKeys++;
                if (!e.getMessage().contains("Status 429")) {
                    allRateLimited = false;
                }
                continue;
            }
        }

        // Only log once after all keys fail, and suppress rate limit spam
        if (plugin.getConfig().getBoolean("debug") && !allRateLimited) {
            plugin.getLogger().warning("All " + apiKeys.size() + " Gemini API key(s) failed: " +
                (lastException != null ? lastException.getMessage() : "Unknown error"));
        } else if (allRateLimited) {
            plugin.debug("All Gemini API keys are rate-limited (429)");
        }

        throw new RuntimeException("All Gemini API keys failed", lastException);
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
        // HttpURLConnection doesn't need explicit closing
        // Resources are automatically managed
        plugin.debug("Gemini client closed");
    }
}