package net.mysterria.translator.engine.openai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mysterria.translator.MysterriaTranslator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class OpenAIClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Gson gson;
    private final MysterriaTranslator plugin;
    private final int readTimeout;

    public OpenAIClient(MysterriaTranslator plugin, String baseUrl, String model, String apiKey) {
        this.plugin = plugin;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.gson = new Gson();

        // Get configurable timeouts
        int connectTimeout = plugin.getConfig().getInt("translation.openai.connectTimeout", 10);
        this.readTimeout = plugin.getConfig().getInt("translation.openai.readTimeout", 30);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .build();

        plugin.debug("OpenAI client initialized with model=" + model +
                     ", connectTimeout=" + connectTimeout + "s, readTimeout=" + readTimeout + "s");
    }

    public CompletableFuture<String> translateAsync(String text, String fromLang, String toLang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return translate(text, fromLang, toLang);
            } catch (Exception e) {
                plugin.debug("OpenAI translation failed - " + e.getClass().getSimpleName() + ": " +
                             (e.getMessage() != null ? e.getMessage() : "No error message") +
                             " (Cause: " + (e.getCause() != null ? e.getCause().toString() : "Unknown") + ")");
                throw new RuntimeException(e);
            }
        });
    }

    private String translate(String text, String fromLang, String toLang) throws IOException, InterruptedException {
        plugin.debug("Attempting translation to OpenAI at: " + baseUrl + "/chat/completions");

        // Build messages array
        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", buildSystemPrompt());
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", buildTranslationPrompt(text, fromLang, toLang));
        messages.add(userMessage);

        // Build request body
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.add("messages", messages);

        // Optional parameters - some models don't support them (e.g., o1, o3 series)
        if (plugin.getConfig().getBoolean("translation.openai.useTemperature", true)) {
            double temperature = plugin.getConfig().getDouble("translation.openai.temperature", 0.3);
            request.addProperty("temperature", temperature);
        }

        if (plugin.getConfig().getBoolean("translation.openai.useTopP", true)) {
            double topP = plugin.getConfig().getDouble("translation.openai.topP", 0.9);
            request.addProperty("top_p", topP);
        }

        // Use max_completion_tokens (newer API standard)
        // Only use max_tokens if explicitly configured for legacy models
        boolean useLegacyMaxTokens = plugin.getConfig().getBoolean("translation.openai.useLegacyMaxTokens", false);
        int maxTokens = plugin.getConfig().getInt("translation.openai.maxTokens", 1000);
        if (useLegacyMaxTokens) {
            request.addProperty("max_tokens", maxTokens);
        } else {
            request.addProperty("max_completion_tokens", maxTokens);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(readTimeout))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String errorMsg = "OpenAI responded with status: " + response.statusCode() + " - " + response.body();
                plugin.debug(errorMsg);
                throw new IOException(errorMsg);
            }

            JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);

            // Extract translation from response
            JsonArray choices = responseJson.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("OpenAI returned no choices in response");
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            String translatedText = message.get("content").getAsString();

            return extractTranslation(translatedText);

        } catch (java.net.ConnectException e) {
            String errorMsg = "Failed to connect to OpenAI server at " + baseUrl + ". Check your network connection.";
            plugin.debug(errorMsg);
            throw new IOException(errorMsg, e);
        } catch (java.net.SocketTimeoutException e) {
            String errorMsg = "Timeout connecting to OpenAI server at " + baseUrl;
            plugin.debug(errorMsg);
            throw new IOException(errorMsg, e);
        }
    }

    private String buildSystemPrompt() {
        return """
                You are a professional translator specializing in gaming terminology and casual Minecraft chat.
                Your role is to provide accurate, natural translations while preserving game-specific elements.
                Always respond with ONLY the translated text, without any explanations, notes, or additional content.
                """;
    }

    private String buildTranslationPrompt(String text, String fromLang, String toLang) {
        return String.format("""
                Translate from %s to %s. Follow these rules strictly:
                1) Directly translate content only; do not add, remove, or change anything.
                2) Do not translate placeholders/variables (like %%player%%, {player}, {item}, ${amount}, {0}) or command syntax (e.g. /warp, /msg).
                3) Only translate text values; do not modify JSON keys or structure.
                4) Preserve punctuation, spacing, capitalization, emoji, and the informal gaming tone.
                Return ONLY the translated text — no explanations, notes, quotes, or extra content.
                
                Text:
                %s
                """, fromLang, toLang, text);
    }

    private String extractTranslation(String response) {
        String cleaned = response.trim();

        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        if (cleaned.toLowerCase().startsWith("translation:")) {
            cleaned = cleaned.substring("translation:".length()).trim();
        }

        return cleaned;
    }

    public CompletableFuture<Boolean> isAvailable() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Simple ping to models endpoint
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/models"))
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        });
    }

}
