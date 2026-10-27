package net.mysterria.translator.domain.translation.type;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.mysterria.translator.config.ConfigReader;
import net.mysterria.translator.domain.translation.model.TranslationRequest;
import net.mysterria.translator.domain.translation.service.PromptService;
import net.mysterria.translator.util.language.Languages;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@Getter
public abstract class TranslationEngine {

    protected static final Gson GSON = new Gson();
    private static final Duration GUARD_MARGIN = Duration.ofSeconds(5);
    private static final int MAX_ERROR_BODY = 200;

    private final String name;
    private final Duration timeout;
    private final Context context;

    protected TranslationEngine(String name, Duration timeout, Context context) {
        this.name = name;
        this.timeout = timeout;
        this.context = context;
    }

    public static TranslationEngine create(String name, ConfigReader reader, Context context) {
        return switch (name) {
            case "ollama" -> new OllamaEngine(reader, context);
            case "libretranslate" -> new LibreTranslateEngine(reader, context);
            case "gemini" -> new GeminiEngine(reader, context);
            case "google" -> new GoogleEngine(reader, context);
            case "openai" -> new OpenAiEngine(reader, context);
            default -> throw new IllegalArgumentException("Unknown engine " + name);
        };
    }

    public abstract String translate(TranslationRequest request) throws Exception;

    public Duration guardTimeout() {
        return timeout.plus(context.getConnectTimeout()).multipliedBy(requestsPerCall()).plus(GUARD_MARGIN);
    }

    protected int requestsPerCall() {
        return 1;
    }

    protected HttpResponse<String> exchange(HttpRequest.Builder request) throws IOException, InterruptedException {
        return context.getHttp().send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString());
    }

    protected String send(HttpRequest.Builder request) throws IOException, InterruptedException, EngineRateLimitedException {
        HttpResponse<String> response = exchange(request);
        if (response.statusCode() == 429) {
            throw new EngineRateLimitedException(name, name + " rate limit reached (HTTP 429)");
        }
        if (response.statusCode() != 200) {
            throw new IOException(name + " responded with HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return response.body();
    }

    protected String prompt(String path, TranslationRequest request) {
        return context.getPrompts().render(path, Map.of(
                "sourceLang", request.getSource() == null ? "auto-detected" : Languages.displayName(request.getSource()),
                "targetLang", Languages.displayName(request.getTarget()),
                "message", request.getText(),
                "playerContext", String.join(", ", request.getPlayerNames())));
    }

    protected static String isoCode(Locale locale) {
        return locale == null ? "auto" : locale.getLanguage();
    }

    protected static String stripWrappingQuotes(String text) {
        String trimmed = text.trim();
        return trimmed.length() > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
    }

    private static String abbreviate(String body) {
        return body.length() <= MAX_ERROR_BODY ? body : body.substring(0, MAX_ERROR_BODY) + "...";
    }

    @Getter
    @RequiredArgsConstructor
    public static class Context {
        private final HttpClient http;
        private final PromptService prompts;
        private final Duration connectTimeout;
        private final Duration suspension;
    }
}
