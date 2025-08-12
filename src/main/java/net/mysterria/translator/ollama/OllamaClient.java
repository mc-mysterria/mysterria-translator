package net.mysterria.translator.ollama;

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
    private final Gson gson;
    private final MysterriaTranslator plugin;

    public OllamaClient(MysterriaTranslator plugin, String baseUrl, String model) {
        this.plugin = plugin;
        this.baseUrl = baseUrl;
        this.model = model;
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
                plugin.debug("Translation failed: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    private String translate(String text, String fromLang, String toLang) throws IOException, InterruptedException {
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.3);
        options.addProperty("top_p", 0.9);

        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("prompt", buildTranslationPrompt(text, fromLang, toLang));
        request.addProperty("stream", false);
        request.add("options", options);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Ollama responded with status: " + response.statusCode() + " - " + response.body());
        }

        JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);
        String fullResponse = responseJson.get("response").getAsString();

        return extractTranslation(fullResponse);
    }

    private String buildTranslationPrompt(String text, String fromLang, String toLang) {
        return String.format(
                "Translate this %s text to %s. Only return the translation, no explanations:\n\n%s",
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
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/tags"))
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