package net.mysterria.translator.engine.google;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.exception.RateLimitException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Google Translate client using the unofficial free API.
 * No API key required, but may have rate limits.
 */
public class GoogleClient {

    private final MysterriaTranslator plugin;
    private final Gson gson;
    private final int connectTimeout;
    private final int readTimeout;
    private static final String BASE_URL = "http://translate.googleapis.com/translate_a/single";

    public GoogleClient(MysterriaTranslator plugin) {
        this.plugin = plugin;
        this.gson = new Gson();

        this.connectTimeout = plugin.getConfig().getInt("translation.google.connectTimeout", 5);
        this.readTimeout = plugin.getConfig().getInt("translation.google.readTimeout", 10);

        plugin.debug("Google Translate client initialized with connectTimeout=" + connectTimeout + "s, readTimeout=" + readTimeout + "s");
    }

    public CompletableFuture<String> translateAsync(String text, String fromLang, String toLang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return translate(text, fromLang, toLang);
            } catch (Exception e) {
                plugin.debug("Google Translate translation failed: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    private String translate(String text, String fromLang, String toLang) throws IOException, InterruptedException, RateLimitException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .build();

        // Map language codes to Google's format
        String sourceLang = mapLanguageCode(fromLang);
        String targetLang = mapLanguageCode(toLang);

        // URL encode the text
        String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);

        // Build the URL with query parameters
        String url = BASE_URL + "?client=gtx&sl=" + sourceLang + "&tl=" + targetLang + "&dt=t&q=" + encodedText;

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(readTimeout))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        // Handle rate limiting
        if (response.statusCode() == 429) {
            String errorMsg = "Google Translate rate limit exceeded (HTTP 429)";
            plugin.debug(errorMsg);
            throw new RateLimitException("google", 429, errorMsg);
        }

        if (response.statusCode() != 200) {
            throw new IOException("Google Translate responded with status: " + response.statusCode() + " - " + response.body());
        }

        // Parse the complex nested array response
        return parseGoogleResponse(response.body());
    }

    /**
     * Parses Google Translate's complex nested array response.
     * Response format: [[[translated_text, original_text, ...], ...], null, detected_lang, ...]
     *
     * @param jsonResponse The raw JSON response
     * @return The translated text
     * @throws IOException if parsing fails
     */
    private String parseGoogleResponse(String jsonResponse) throws IOException {
        try {
            JsonArray root = gson.fromJson(jsonResponse, JsonArray.class);

            if (root == null || root.isEmpty()) {
                throw new IOException("Empty response from Google Translate");
            }

            JsonElement firstElement = root.get(0);
            if (!firstElement.isJsonArray()) {
                throw new IOException("Unexpected response format: first element is not an array");
            }

            JsonArray translationsArray = firstElement.getAsJsonArray();
            if (translationsArray.isEmpty()) {
                throw new IOException("No translations in response");
            }

            // Concatenate all translation segments
            StringBuilder translatedText = new StringBuilder();
            for (JsonElement segment : translationsArray) {
                if (segment.isJsonArray()) {
                    JsonArray segmentArray = segment.getAsJsonArray();
                    if (!segmentArray.isEmpty() && !segmentArray.get(0).isJsonNull()) {
                        translatedText.append(segmentArray.get(0).getAsString());
                    }
                }
            }

            if (translatedText.isEmpty()) {
                throw new IOException("Could not extract translation from response");
            }

            plugin.debug("Google Translate: " + translatedText.toString());
            return translatedText.toString();

        } catch (Exception e) {
            plugin.debug("Failed to parse Google Translate response: " + e.getMessage());
            throw new IOException("Failed to parse Google Translate response", e);
        }
    }

    /**
     * Maps Minecraft locale codes to Google Translate language codes.
     */
    private String mapLanguageCode(String langCode) {
        if (langCode == null || langCode.equalsIgnoreCase("auto")) {
            return "auto";
        }

        return switch (langCode.toLowerCase()) {
            case "ukrainian", "uk_ua", "uk" -> "uk";
            case "english", "en_us", "en" -> "en";
            case "russian", "ru_ru", "ru" -> "ru";
            case "spanish", "es_es", "es" -> "es";
            case "french", "fr_fr", "fr" -> "fr";
            case "german", "de_de", "de" -> "de";
            case "italian", "it_it", "it" -> "it";
            case "portuguese", "pt_pt", "pt" -> "pt";
            case "chinese", "zh_cn", "zh" -> "zh-CN";
            case "japanese", "ja_jp", "ja" -> "ja";
            case "korean", "ko_kr", "ko" -> "ko";
            case "arabic", "ar_sa", "ar" -> "ar";
            case "hindi", "hi_in", "hi" -> "hi";
            case "polish", "pl_pl", "pl" -> "pl";
            case "dutch", "nl_nl", "nl" -> "nl";
            case "swedish", "sv_se", "sv" -> "sv";
            case "norwegian", "no_no", "no" -> "no";
            case "danish", "da_dk", "da" -> "da";
            case "finnish", "fi_fi", "fi" -> "fi";
            case "czech", "cs_cz", "cs" -> "cs";
            case "hungarian", "hu_hu", "hu" -> "hu";
            case "romanian", "ro_ro", "ro" -> "ro";
            case "bulgarian", "bg_bg", "bg" -> "bg";
            case "greek", "el_gr", "el" -> "el";
            case "turkish", "tr_tr", "tr" -> "tr";
            case "hebrew", "he_il", "he" -> "iw"; // Google uses 'iw' for Hebrew
            case "thai", "th_th", "th" -> "th";
            case "vietnamese", "vi_vn", "vi" -> "vi";
            default -> langCode.length() > 2 ? langCode.substring(0, 2) : langCode;
        };
    }

    public CompletableFuture<Boolean> isAvailable() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String testTranslation = translate("test", "auto", "en");
                return testTranslation != null && !testTranslation.isEmpty();
            } catch (Exception e) {
                return false;
            }
        });
    }
}
