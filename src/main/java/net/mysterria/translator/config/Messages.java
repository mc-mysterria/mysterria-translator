package net.mysterria.translator.config;

import net.kyori.adventure.text.Component;
import net.mysterria.translator.util.text.TextFormat;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Messages {

    private final JavaPlugin plugin;
    private volatile YamlConfiguration messages;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (var reader = new InputStreamReader(plugin.getResource("messages.yml"), StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
        } catch (Exception e) {
            throw new IllegalStateException("messages.yml is missing from the plugin jar", e);
        }
        this.messages = loaded;
    }

    public String raw(String path, String... placeholders) {
        String message = messages.isList(path)
                ? String.join("\n", messages.getStringList(path))
                : messages.getString(path, "<red>Missing message '" + path + "'");
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            message = message.replace(placeholders[i], placeholders[i + 1]);
        }
        return message;
    }

    public Component get(String path, String... placeholders) {
        return TextFormat.parse(raw(path, placeholders));
    }

    public void send(CommandSender sender, String path, String... placeholders) {
        String prefix = messages.isList(path) ? "" : messages.getString("prefix", "");
        sender.sendMessage(TextFormat.parse(prefix + raw(path, placeholders)));
    }

    public void sendLines(CommandSender sender, String path, String... placeholders) {
        for (String line : raw(path, placeholders).split("\n", -1)) {
            sender.sendMessage(TextFormat.parse(line));
        }
    }

    public void actionBar(Player player, String path, String... placeholders) {
        player.sendActionBar(get(path, placeholders));
    }
}
