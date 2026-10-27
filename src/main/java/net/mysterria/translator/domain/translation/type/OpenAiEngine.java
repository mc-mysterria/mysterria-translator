package net.mysterria.translator.domain.translation.type;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mysterria.translator.config.ConfigReader;
import net.mysterria.translator.domain.translation.model.TranslationRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

public class OpenAiEngine extends TranslationEngine {

    private static final String ANSWER_LABEL = "translation:";

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final boolean legacyMaxTokens;
    private final Double temperature;
    private final Double topP;

    public OpenAiEngine(ConfigReader reader, Context context) {
        super("openai", Duration.ofSeconds(reader.integer("engines.openai.readTimeout", 1, 600)), context);
        this.endpoint = URI.create(reader.url("engines.openai.baseUrl").replaceAll("/+$", "") + "/chat/completions");
        this.apiKey = reader.secret("engines.openai.apiKey", "openai");
        this.model = reader.string("engines.openai.model");
        this.maxTokens = reader.integer("engines.openai.maxTokens", 1, 100000);
        this.legacyMaxTokens = reader.bool("engines.openai.useLegacyMaxTokens");
        this.temperature = reader.bool("engines.openai.useTemperature") ? reader.decimal("engines.openai.temperature", 0, 2) : null;
        this.topP = reader.bool("engines.openai.useTopP") ? reader.decimal("engines.openai.topP", 0, 1) : null;
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        JsonArray messages = new JsonArray();
        messages.add(message("system", prompt("openai.systemPrompt", request)));
        messages.add(message("user", prompt("openai.userPrompt", request)));

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty(legacyMaxTokens ? "max_tokens" : "max_completion_tokens", maxTokens);
        if (temperature != null) {
            body.addProperty("temperature", temperature);
        }
        if (topP != null) {
            body.addProperty("top_p", topP);
        }

        JsonObject response = GSON.fromJson(send(HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))), JsonObject.class);

        JsonArray choices = response.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("openai returned no choices");
        }
        String answer = stripWrappingQuotes(choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString());
        return answer.toLowerCase().startsWith(ANSWER_LABEL) ? answer.substring(ANSWER_LABEL.length()).trim() : answer;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }
}
