package net.mysterria.translator.domain.translation.type;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mysterria.translator.config.ConfigReader;
import net.mysterria.translator.domain.translation.model.TranslationRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

public class GeminiEngine extends TranslationEngine {

    private final List<String> apiKeys;
    private final URI endpoint;
    private final boolean includeContext;
    private final Map<Integer, Instant> suspendedKeys = new ConcurrentHashMap<>();

    public GeminiEngine(ConfigReader reader, Context context) {
        super("gemini", Duration.ofSeconds(reader.integer("engines.gemini.readTimeout", 1, 600)), context);
        this.apiKeys = reader.strings("engines.gemini.apiKeys").stream().map(String::trim).toList();
        if (apiKeys.isEmpty() || apiKeys.stream().anyMatch(key -> key.isEmpty() || key.startsWith("your-"))) {
            reader.fail("gemini: engines.gemini.apiKeys contains an empty or placeholder key");
        }
        this.endpoint = URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                + reader.string("engines.gemini.model") + ":generateContent");
        this.includeContext = reader.bool("engines.gemini.includeContext");
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        String body = payload(request).toString();
        Exception lastFailure = null;
        for (int index = 0; index < apiKeys.size(); index++) {
            if (isSuspended(index)) {
                continue;
            }
            try {
                HttpResponse<String> response = exchange(HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", apiKeys.get(index))
                        .POST(HttpRequest.BodyPublishers.ofString(body)));
                if (response.statusCode() == 429) {
                    suspendedKeys.put(index, Instant.now().plus(getContext().getSuspension()));
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new IOException("gemini key #" + (index + 1) + " responded with HTTP " + response.statusCode());
                }
                return extractText(response.body());
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        if (lastFailure == null) {
            throw new EngineRateLimitedException(getName(), "every gemini API key is rate limited");
        }
        throw lastFailure;
    }

    @Override
    protected int requestsPerCall() {
        return Math.max(1, apiKeys.size());
    }

    public long availableKeys() {
        return IntStream.range(0, apiKeys.size()).filter(index -> !isSuspended(index)).count();
    }

    private boolean isSuspended(int index) {
        Instant until = suspendedKeys.get(index);
        if (until == null) {
            return false;
        }
        if (until.isBefore(Instant.now())) {
            suspendedKeys.remove(index);
            return false;
        }
        return true;
    }

    private JsonObject payload(TranslationRequest request) {
        boolean withContext = includeContext && !request.getPlayerNames().isEmpty();
        boolean autoDetect = request.getSource() == null;
        String promptPath = "gemini." + (autoDetect ? "autoDetectPrompt" : "translationPrompt") + (withContext ? "WithContext" : "");

        JsonObject payload = new JsonObject();
        payload.add("contents", content("user", prompt(promptPath, request)));
        payload.add("systemInstruction", content("user", prompt(withContext ? "gemini.systemInstructionWithContext" : "gemini.systemInstruction", request)).get(0));

        JsonObject generation = new JsonObject();
        generation.addProperty("temperature", 0.2);
        generation.addProperty("maxOutputTokens", 500);
        generation.addProperty("topP", 0.8);
        generation.addProperty("topK", 20);
        payload.add("generationConfig", generation);
        return payload;
    }

    private static JsonArray content(String role, String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(message);
        return contents;
    }

    private static String extractText(String body) throws IOException {
        try {
            return GSON.fromJson(body, JsonObject.class)
                    .getAsJsonArray("candidates").get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts").get(0).getAsJsonObject()
                    .get("text").getAsString().trim();
        } catch (RuntimeException e) {
            throw new IOException("gemini returned an unexpected response", e);
        }
    }
}
