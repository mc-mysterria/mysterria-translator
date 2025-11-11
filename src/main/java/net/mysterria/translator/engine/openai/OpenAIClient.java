package net.mysterria.translator.engine.openai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.exception.RateLimitException;
import net.mysterria.translator.manager.PromptManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class OpenAIClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Gson gson;
    private final MysterriaTranslator plugin;
    private final PromptManager promptManager;
    private final int readTimeout;

    public OpenAIClient(MysterriaTranslator plugin, PromptManager promptManager, String baseUrl, String model, String apiKey) {
        this.plugin = plugin;
        this.promptManager = promptManager;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.gson = new Gson();


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

    private String translate(String text, String fromLang, String toLang) throws IOException, InterruptedException, RateLimitException {
        plugin.debug("Attempting translation to OpenAI at: " + baseUrl + "/chat/completions");


        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", buildSystemPrompt());
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", buildTranslationPrompt(text, fromLang, toLang));
        messages.add(userMessage);


        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.add("messages", messages);


        if (plugin.getConfig().getBoolean("translation.openai.useTemperature", true)) {
            double temperature = plugin.getConfig().getDouble("translation.openai.temperature", 0.3);
            request.addProperty("temperature", temperature);
        }

        if (plugin.getConfig().getBoolean("translation.openai.useTopP", true)) {
            double topP = plugin.getConfig().getDouble("translation.openai.topP", 0.9);
            request.addProperty("top_p", topP);
        }


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


            if (response.statusCode() == 429) {
                String errorMsg = "OpenAI rate limit exceeded (HTTP 429): " + response.body();
                plugin.debug(errorMsg);
                throw new RateLimitException("openai", 429, errorMsg);
            }

            if (response.statusCode() != 200) {
                String errorMsg = "OpenAI responded with status: " + response.statusCode() + " - " + response.body();
                plugin.debug(errorMsg);
                throw new IOException(errorMsg);
            }

            JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);


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
        return promptManager.getPrompt("openai.systemPrompt", new HashMap<>());
    }

    private String buildTranslationPrompt(String text, String fromLang, String toLang) {
        Map<String, String> variables = new HashMap<>();
        variables.put("sourceLang", fromLang);
        variables.put("targetLang", toLang);
        variables.put("message", text);

        return promptManager.getPrompt("openai.userPrompt", variables);
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

}
