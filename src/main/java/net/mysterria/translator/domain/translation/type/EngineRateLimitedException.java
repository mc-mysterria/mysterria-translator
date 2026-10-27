package net.mysterria.translator.domain.translation.type;

import lombok.Getter;

@Getter
public class EngineRateLimitedException extends Exception {

    private final String engine;

    public EngineRateLimitedException(String engine, String message) {
        super(message);
        this.engine = engine;
    }
}
