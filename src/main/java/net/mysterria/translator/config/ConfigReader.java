package net.mysterria.translator.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class ConfigReader {

    private final ConfigurationSection root;
    @Getter
    private final List<String> errors = new ArrayList<>();

    public String string(String path) {
        if (!root.isString(path)) {
            errors.add(path + " must be text");
            return "";
        }
        return root.getString(path);
    }

    public String url(String path) {
        String value = string(path);
        try {
            String scheme = URI.create(value).getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                return value;
            }
        } catch (IllegalArgumentException ignored) {
        }
        errors.add(path + " must be an http(s) URL, got '" + value + "'");
        return value;
    }

    public boolean bool(String path) {
        if (!root.isBoolean(path)) {
            errors.add(path + " must be true or false");
            return false;
        }
        return root.getBoolean(path);
    }

    public int integer(String path, int min, int max) {
        if (!root.isInt(path)) {
            errors.add(path + " must be a whole number");
            return min;
        }
        int value = root.getInt(path);
        if (value < min || value > max) {
            errors.add(path + " must be between " + min + " and " + max + ", got " + value);
        }
        return Math.clamp(value, min, max);
    }

    public double decimal(String path, double min, double max) {
        if (!root.isDouble(path) && !root.isInt(path)) {
            errors.add(path + " must be a number");
            return min;
        }
        double value = root.getDouble(path);
        if (value < min || value > max) {
            errors.add(path + " must be between " + min + " and " + max + ", got " + value);
        }
        return Math.clamp(value, min, max);
    }

    public List<String> strings(String path) {
        if (!root.isList(path)) {
            errors.add(path + " must be a list");
            return List.of();
        }
        return root.getStringList(path);
    }

    public String secret(String path, String engine) {
        String value = string(path).trim();
        if (value.isEmpty() || value.startsWith("your-") || value.equals("sk-...")) {
            errors.add(engine + ": " + path + " is not set");
        }
        return value;
    }

    public Map<String, Object> values(String path) {
        ConfigurationSection section = root.getConfigurationSection(path);
        return section == null ? Map.of() : new LinkedHashMap<>(section.getValues(false));
    }

    public void fail(String error) {
        errors.add(error);
    }
}
