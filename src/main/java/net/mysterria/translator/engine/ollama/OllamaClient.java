package net.mysterria.translator.engine.ollama;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.exception.RateLimitException;
import net.mysterria.translator.manager.PromptManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class OllamaClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Gson gson;
    private final MysterriaTranslator plugin;
    private final PromptManager promptManager;
    private final int requestTimeout;

    public OllamaClient(MysterriaTranslator plugin, PromptManager promptManager, String baseUrl, String model, String apiKey) {
        this.plugin = plugin;
        this.promptManager = promptManager;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.gson = new Gson();

        
        int connectTimeout = plugin.getConfig().getInt("translation.ollama.connectTimeout", 10);
        this.requestTimeout = plugin.getConfig().getInt("translation.ollama.requestTimeout", 90);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .build();

        plugin.debug("Ollama client initialized with connectTimeout=" + connectTimeout + "s, requestTimeout=" + requestTimeout + "s");
    }

    public CompletableFuture<String> translateAsync(String text, String fromLang, String toLang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return translate(text, fromLang, toLang);
            } catch (Exception e) {
                plugin.debug("Translation failed - " + e.getClass().getSimpleName() + ": " +
                             (e.getMessage() != null ? e.getMessage() : "No error message") +
                             " (Cause: " + (e.getCause() != null ? e.getCause().toString() : "Unknown") + ")");
                throw new RuntimeException(e);
            }
        });
    }

    private String translate(String text, String fromLang, String toLang) throws IOException, InterruptedException, RateLimitException {
        plugin.debug("Attempting translation to Ollama at: " + baseUrl + "/api/generate");

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.3);
        options.addProperty("top_p", 0.9);

        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("prompt", buildTranslationPrompt(text, fromLang, toLang));
        request.addProperty("stream", false);
        request.add("options", options);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(requestTimeout))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)));

        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest httpRequest = requestBuilder.build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            
            if (response.statusCode() == 429) {
                String errorMsg = "Ollama rate limit exceeded (HTTP 429)";
                plugin.debug(errorMsg);
                throw new RateLimitException("ollama", 429, errorMsg);
            }

            if (response.statusCode() != 200) {
                String errorMsg = "Ollama responded with status: " + response.statusCode() + " - " + response.body();
                plugin.debug(errorMsg);
                throw new IOException(errorMsg);
            }

            JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);
            String fullResponse = responseJson.get("response").getAsString();

            return extractTranslation(fullResponse);
        } catch (java.net.ConnectException e) {
            String errorMsg = "Failed to connect to Ollama server at " + baseUrl + ". Is Ollama running?";
            plugin.debug(errorMsg);
            throw new IOException(errorMsg, e);
        } catch (java.net.SocketTimeoutException e) {
            String errorMsg = "Timeout connecting to Ollama server at " + baseUrl;
            plugin.debug(errorMsg);
            throw new IOException(errorMsg, e);
        }
    }

    private String buildTranslationPrompt(String text, String fromLang, String toLang) {
        Map<String, String> variables = new HashMap<>();
        variables.put("sourceLang", fromLang);
        variables.put("targetLang", toLang);
        variables.put("message", text);

        return promptManager.getPrompt("ollama.prompt", variables);
    }

    private String extractTranslation(String response) {
        String cleaned = response.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        cleaned = cleaned.replaceAll("\\s*\\([^)]*\\)", "").trim();

        cleaned = cleaned.replaceAll("\\s{2,}", " ");

        return cleaned;
    }

    /**
     * Closes the HTTP client and releases resources.
     */
    public void close() {
        httpClient.close();
        plugin.debug("Ollama client closed");
    }
}