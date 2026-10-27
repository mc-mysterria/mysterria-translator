package net.mysterria.translator.domain.translation.type;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mysterria.translator.config.ConfigReader;
import net.mysterria.translator.domain.translation.model.TranslationRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

public class LibreTranslateEngine extends TranslationEngine {

    private final URI endpoint;
    private final String apiKey;
    private final int alternatives;
    private final String format;

    public LibreTranslateEngine(ConfigReader reader, Context context) {
        super("libretranslate", Duration.ofSeconds(reader.integer("engines.libretranslate.readTimeout", 1, 600)), context);
        this.endpoint = URI.create(reader.url("engines.libretranslate.url"));
        String key = reader.string("engines.libretranslate.apiKey").trim();
        this.apiKey = key.startsWith("your-") ? "" : key;
        this.alternatives = reader.integer("engines.libretranslate.alternatives", 0, 10);
        this.format = reader.string("engines.libretranslate.format");
        if (!format.equals("text") && !format.equals("html")) {
            reader.fail("libretranslate: engines.libretranslate.format must be text or html");
        }
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("q", request.getText());
        body.addProperty("source", isoCode(request.getSource()));
        body.addProperty("target", isoCode(request.getTarget()));
        body.addProperty("format", format);
        body.addProperty("alternatives", alternatives);
        if (!apiKey.isEmpty()) {
            body.addProperty("api_key", apiKey);
        }

        JsonObject response = GSON.fromJson(send(HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))), JsonObject.class);

        if (response.has("translatedText")) {
            return response.get("translatedText").getAsString();
        }
        JsonArray fallback = response.getAsJsonArray("alternatives");
        if (fallback != null && !fallback.isEmpty()) {
            return fallback.get(0).getAsString();
        }
        throw new IOException("libretranslate returned no translation");
    }
}
