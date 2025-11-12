package net.mysterria.translator.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.manager.LangManager;
import net.mysterria.translator.util.ConfigValidator;
import net.mysterria.translator.util.MessageSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class LangCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION_ADMIN = "mtranslator.admin";
    private static final List<String> SUBCOMMANDS = Arrays.asList("help", "reload", "set", "get", "list");

    private final LangManager langManager;
    private final MysterriaTranslator plugin;

    public LangCommand(LangManager langManager, MysterriaTranslator plugin) {
        this.langManager = langManager;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION_ADMIN) && args.length == 0) {
            sendVersionInfo(sender);
            return true;
        }
        
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "no_permission"));
            return true;
        }

        
        String subcommand = (args.length == 0) ? "help" : args[0].toLowerCase();

        switch (subcommand) {
            case "help":
                handleHelp(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "set":
                handleSet(sender, args);
                break;
            case "get":
                handleGet(sender, args);
                break;
            case "list":
                handleList(sender);
                break;
            default:
                sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "unknown_subcommand"));
                break;
        }

        return true;
    }

    /**
     * Displays plugin version information
     */
    private void sendVersionInfo(@NotNull CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("• ").color(NamedTextColor.AQUA)
                .append(Component.text("Running ").color(NamedTextColor.WHITE))
                .append(Component.text("MysterriaTranslator ").color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text("v. " + plugin.getDescription().getVersion()).color(NamedTextColor.AQUA))
                .append(Component.text(" by ").color(NamedTextColor.WHITE))
                .append(Component.text("Mysterria").color(NamedTextColor.AQUA)));
        sender.sendMessage(Component.empty());
    }

    /**
     * Handles /lang help command
     */
    private void handleHelp(@NotNull CommandSender sender) {
        Object helpObj = plugin.getMessagesConfig().get("help");
        if (helpObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> helpList = (List<String>) helpObj;
            helpList.forEach(line ->
                sender.sendMessage(Component.text(line.replace("&", "")).color(NamedTextColor.WHITE))
            );
        } else if (helpObj instanceof String) {
            sender.sendMessage(Component.text(((String) helpObj).replace("&", "")).color(NamedTextColor.WHITE));
        }
    }

    /**
     * Handles /lang reload command
     * Reloads static translations and translation engine configurations
     */
    private void handleReload(@NotNull CommandSender sender) {
        sender.sendMessage(Component.text("Starting reload...").color(NamedTextColor.YELLOW));

        reloadStaticTranslations(sender);

        ConfigValidator.ValidationResult result = plugin.reloadTranslationManager();

        displayValidationResults(sender, result);

        displayWarnings(sender, result);

        sender.sendMessage(Component.empty());
        if (result.isValid()) {
            sender.sendMessage(Component.text("Reload completed successfully!").color(NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Reload completed with errors. Check logs for details.").color(NamedTextColor.RED));
        }
    }

    /**
     * Reloads static translations from language files
     */
    private void reloadStaticTranslations(@NotNull CommandSender sender) {
        langManager.loadAll();
        langManager.clearCache();

        plugin.log("Reloaded " + langManager.getAvailableLangs().size() + " languages! " + langManager.getAvailableLangs());
        plugin.log("Reloaded " + langManager.getTotalTranslationsCount() + " total translations!");

        sender.sendMessage(Component.text("✓ Static translations reloaded").color(NamedTextColor.GREEN));
    }

    /**
     * Displays validation results for configuration reload
     */
    private void displayValidationResults(@NotNull CommandSender sender, @NotNull ConfigValidator.ValidationResult result) {
        if (!result.isValid()) {
            sender.sendMessage(Component.text("✗ Configuration validation failed:").color(NamedTextColor.RED));
            result.errors().forEach(error ->
                sender.sendMessage(Component.text("  - " + error).color(NamedTextColor.RED))
            );
            sender.sendMessage(Component.text("Using previous configuration. Please fix errors and reload again.")
                    .color(NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text("✓ Translation engines reloaded").color(NamedTextColor.GREEN));

            String provider = plugin.getConfig().getString("translation.provider", "ollama");
            sender.sendMessage(Component.text("  Active providers: " + provider).color(NamedTextColor.GRAY));
        }
    }

    /**
     * Displays configuration warnings if any exist
     */
    private void displayWarnings(@NotNull CommandSender sender, @NotNull ConfigValidator.ValidationResult result) {
        if (result.hasWarnings()) {
            sender.sendMessage(Component.text("⚠ Configuration warnings:").color(NamedTextColor.YELLOW));
            result.warnings().forEach(warning ->
                sender.sendMessage(Component.text("  - " + warning).color(NamedTextColor.YELLOW))
            );
        }
    }

    /**
     * Handles /lang set <player> <lang> command
     * Sets a player's language preference
     */
    private void handleSet(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length != 3) {
            sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "set_usage"));
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "player_not_found"));
            return;
        }

        String lang = args[2];
        List<String> availableLangs = langManager.getAvailableLangs();

        if (!availableLangs.contains(lang)) {
            sender.sendMessage(MessageSerializer.getMessage(
                plugin.getMessagesConfig(),
                "invalid_lang",
                "{lang}", lang,
                "{langs}", String.join(", ", availableLangs)
            ));
            return;
        }

        langManager.setPlayerLang(target.getUniqueId(), lang);
        langManager.savePlayerLang(target.getUniqueId());

        sender.sendMessage(MessageSerializer.getMessage(
            plugin.getMessagesConfig(),
            "set_success",
            "{player}", target.getName(),
            "{lang}", lang
        ));
    }

    /**
     * Handles /lang get <player> command
     * Retrieves a player's current language preference
     */
    private void handleGet(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length != 2) {
            sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "get_usage"));
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "player_not_found"));
            return;
        }

        String lang = langManager.getPlayerLang(target.getUniqueId());
        sender.sendMessage(MessageSerializer.getMessage(
            plugin.getMessagesConfig(),
            "get_success",
            "{player}", target.getName(),
            "{lang}", lang
        ));
    }

    /**
     * Handles /lang list command
     * Lists all available languages
     */
    private void handleList(@NotNull CommandSender sender) {
        List<String> langs = langManager.getAvailableLangs();
        sender.sendMessage(MessageSerializer.getMessage(
            plugin.getMessagesConfig(),
            "list_languages",
            "{langs}", String.join(", ", langs)
        ));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                    .filter(cmd -> cmd.startsWith(partial))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String subcommand = args[0].toLowerCase();
            
            if (subcommand.equals("set") || subcommand.equals("get")) {
                String partial = args[1].toLowerCase();
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(partial))
                        .collect(Collectors.toList());
            }
        }

        
        if (args.length == 3) {
            String subcommand = args[0].toLowerCase();

            if (subcommand.equals("set")) {
                String partial = args[2].toLowerCase();
                return langManager.getAvailableLangs().stream()
                        .filter(lang -> lang.toLowerCase().startsWith(partial))
                        .collect(Collectors.toList());
            }
        }

        return new ArrayList<>();
    }
}
