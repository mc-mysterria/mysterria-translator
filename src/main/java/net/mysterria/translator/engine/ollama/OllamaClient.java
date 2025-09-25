package net.mysterria.translator.engine.ollama;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.mysterria.translator.MysterriaTranslator;

import java.io.IOException;
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

    public OllamaClient(MysterriaTranslator plugin, String baseUrl, String model, String apiKey) {
        this.plugin = plugin;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
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

    private String translate(String text, String fromLang, String toLang) throws IOException, InterruptedException {
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
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)));

        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest httpRequest = requestBuilder.build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

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
        return String.format(
                "You are a professional translator specializing in gaming terminology and casual chat. " +
                "Translate the following %s text to %s with these guidelines:\n" +
                "- Preserve gaming terms, slang, and context\n" +
                "- Keep the informal tone and style\n" +
                "- Don't translate proper nouns, player names, or game-specific terms unless contextually necessary\n" +
                "- For ambiguous words, choose meaning based on gaming/casual chat context\n" +
                "- Return ONLY the translated text, no explanations or quotation marks\n\n" +
                "Text to translate: %s",
                fromLang, toLang, text
        );
    }

    private String extractTranslation(String response) {
        String cleaned = response.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned;
    }

    public CompletableFuture<Boolean> isAvailable() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/tags"))
                        .timeout(Duration.ofSeconds(3))
                        .GET();

                if (apiKey != null && !apiKey.isEmpty()) {
                    requestBuilder.header("Authorization", "Bearer " + apiKey);
                }

                HttpRequest request = requestBuilder.build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        });
    }
}