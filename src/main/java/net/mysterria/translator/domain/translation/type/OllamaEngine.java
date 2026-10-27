package net.mysterria.translator.domain.translation.type;

import com.google.gson.JsonObject;
import net.mysterria.translator.config.ConfigReader;
import net.mysterria.translator.domain.translation.model.TranslationRequest;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.regex.Pattern;

public class OllamaEngine extends TranslationEngine {

    private static final Pattern MODEL_NOTE = Pattern.compile("\\s*\\([^)]*\\)");
    private static final Pattern REPEATED_SPACES = Pattern.compile("\\s{2,}");

    private final URI endpoint;
    private final String model;
    private final String apiKey;

    public OllamaEngine(ConfigReader reader, Context context) {
        super("ollama", Duration.ofSeconds(reader.integer("engines.ollama.requestTimeout", 1, 600)), context);
        this.endpoint = URI.create(reader.url("engines.ollama.url").replaceAll("/+$", "") + "/api/generate");
        this.model = reader.string("engines.ollama.model");
        this.apiKey = reader.string("engines.ollama.apiKey").trim();
        if (model.isBlank()) {
            reader.fail("ollama: engines.ollama.model is not set");
        }
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.3);
        options.addProperty("top_p", 0.9);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", prompt("ollama.prompt", request));
        body.addProperty("stream", false);
        body.add("options", options);

        HttpRequest.Builder http = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!apiKey.isEmpty()) {
            http.header("Authorization", "Bearer " + apiKey);
        }

        String translated = stripWrappingQuotes(GSON.fromJson(send(http), JsonObject.class).get("response").getAsString());
        if (request.getText().indexOf('(') < 0) {
            translated = MODEL_NOTE.matcher(translated).replaceAll("");
        }
        return REPEATED_SPACES.matcher(translated).replaceAll(" ").trim();
    }
}
