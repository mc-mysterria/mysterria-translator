package net.mysterria.translator.engine.libretranslate;

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

public class LibreTranslateClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final int alternatives;
    private final String format;
    private final Gson gson;
    private final MysterriaTranslator plugin;
    private final int readTimeout;

    public LibreTranslateClient(MysterriaTranslator plugin, String baseUrl, String apiKey, int alternatives, String format) {
        this.plugin = plugin;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.alternatives = alternatives;
        this.format = format;
        this.gson = new Gson();

        // Get configurable timeouts
        int connectTimeout = plugin.getConfig().getInt("translation.libretranslate.connectTimeout", 5);
        this.readTimeout = plugin.getConfig().getInt("translation.libretranslate.readTimeout", 10);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .build();

        plugin.debug("LibreTranslate client initialized with connectTimeout=" + connectTimeout + "s, readTimeout=" + readTimeout + "s");
    }

    public CompletableFuture<String> translateAsync(String text, String fromLang, String toLang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return translate(text, fromLang, toLang);
            } catch (Exception e) {
                plugin.debug("LibreTranslate translation failed: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    private String translate(String text, String fromLang, String toLang) throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("q", text);
        request.addProperty("source", mapLanguageCode(fromLang));
        request.addProperty("target", mapLanguageCode(toLang));
        request.addProperty("format", format);
        request.addProperty("alternatives", alternatives);
        if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("your-api-key-here")) {
            request.addProperty("api_key", apiKey);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(readTimeout))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("LibreTranslate responded with status: " + response.statusCode() + " - " + response.body());
        }

        JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);

        if (responseJson.has("translatedText")) {
            return responseJson.get("translatedText").getAsString();
        } else if (responseJson.has("alternatives") && responseJson.get("alternatives").isJsonArray()) {
            JsonArray alternatives = responseJson.getAsJsonArray("alternatives");
            if (alternatives.size() > 0) {
                return alternatives.get(0).getAsString();
            }
        }

        throw new IOException("Invalid response format from LibreTranslate");
    }

    private String mapLanguageCode(String langCode) {
        return switch (langCode.toLowerCase()) {
            case "ukrainian", "uk_ua", "uk" -> "uk";
            case "english", "en_us", "en" -> "en";
            case "russian", "ru_ru", "ru" -> "ru";
            case "spanish", "es_es", "es" -> "es";
            case "french", "fr_fr", "fr" -> "fr";
            case "german", "de_de", "de" -> "de";
            case "italian", "it_it", "it" -> "it";
            case "portuguese", "pt_pt", "pt" -> "pt";
            case "chinese", "zh_cn", "zh" -> "zh";
            case "japanese", "ja_jp", "ja" -> "ja";
            case "korean", "ko_kr", "ko" -> "ko";
            case "arabic", "ar_sa", "ar" -> "ar";
            case "hindi", "hi_in", "hi" -> "hi";
            default -> langCode.length() > 2 ? langCode.substring(0, 2) : langCode;
        };
    }

    public CompletableFuture<Boolean> isAvailable() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject testRequest = new JsonObject();
                testRequest.addProperty("q", "test");
                testRequest.addProperty("source", "en");
                testRequest.addProperty("target", "es");
                testRequest.addProperty("format", "text");
                if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("your-api-key-here")) {
                    testRequest.addProperty("api_key", apiKey);
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(testRequest)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * Closes the HTTP client and releases resources.
     */
    public void close() {
        // HttpClient doesn't need explicit closing in Java 11+
        // Resources are automatically managed
        plugin.debug("LibreTranslate client closed");
    }
}