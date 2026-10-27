package net.mysterria.translator.domain.translation.service;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class PromptService {

    private final JavaPlugin plugin;
    private volatile YamlConfiguration prompts;

    public PromptService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "prompts.yml");
        if (!file.exists()) {
            plugin.saveResource("prompts.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (var reader = new InputStreamReader(plugin.getResource("prompts.yml"), StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
        } catch (Exception e) {
            throw new IllegalStateException("prompts.yml is missing from the plugin jar", e);
        }
        this.prompts = loaded;
    }

    public String render(String path, Map<String, String> variables) {
        String prompt = prompts.getString(path);
        if (prompt == null) {
            throw new IllegalStateException("prompts.yml has no '" + path + "'");
        }
        for (var variable : variables.entrySet()) {
            prompt = prompt.replace("{" + variable.getKey() + "}", variable.getValue());
        }
        return prompt;
    }
}
