package net.mysterria.translator.config.models;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Getter
@RequiredArgsConstructor
public class StorageSettings {
    private final String type;
    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final Map<String, Object> properties;
}
