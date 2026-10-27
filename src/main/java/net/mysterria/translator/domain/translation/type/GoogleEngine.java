package net.mysterria.translator.domain.translation.type;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.mysterria.translator.config.ConfigReader;
import net.mysterria.translator.domain.translation.model.TranslationRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

public class GoogleEngine extends TranslationEngine {

    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t";

    public GoogleEngine(ConfigReader reader, Context context) {
        super("google", Duration.ofSeconds(reader.integer("engines.google.readTimeout", 1, 600)), context);
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        URI uri = URI.create(ENDPOINT
                + "&sl=" + languageCode(request.getSource())
                + "&tl=" + languageCode(request.getTarget())
                + "&q=" + URLEncoder.encode(request.getText(), StandardCharsets.UTF_8));
        JsonArray root = GSON.fromJson(send(HttpRequest.newBuilder(uri).header("User-Agent", "Mozilla/5.0").GET()), JsonArray.class);

        StringBuilder translated = new StringBuilder();
        if (root != null && !root.isEmpty() && root.get(0).isJsonArray()) {
            for (JsonElement segment : root.get(0).getAsJsonArray()) {
                if (segment.isJsonArray() && !segment.getAsJsonArray().isEmpty() && !segment.getAsJsonArray().get(0).isJsonNull()) {
                    translated.append(segment.getAsJsonArray().get(0).getAsString());
                }
            }
        }
        if (translated.isEmpty()) {
            throw new IOException("google returned no translation");
        }
        return translated.toString();
    }

    private static String languageCode(Locale locale) {
        if (locale == null) {
            return "auto";
        }
        return locale.getCountry().isEmpty() ? locale.getLanguage() : locale.toLanguageTag();
    }
}
