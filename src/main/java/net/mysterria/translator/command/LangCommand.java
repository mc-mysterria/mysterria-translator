package net.mysterria.translator.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.manager.LangManager;
import net.mysterria.translator.util.MessageSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class LangCommand implements CommandExecutor, TabCompleter {

    private final LangManager langManager;
    private final MysterriaTranslator plugin;

    public LangCommand(LangManager langManager, MysterriaTranslator plugin) {
        this.langManager = langManager;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mtranslator.admin") && args.length == 0) {
            sender.sendMessage(Component.empty());
            sender.sendMessage(Component.text("• ").color(NamedTextColor.AQUA)
                    .append(Component.text("Running ").color(NamedTextColor.WHITE))
                    .append(Component.text("MysterriaTranslator ").color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                    .append(Component.text("v. " + plugin.getDescription().getVersion()).color(NamedTextColor.AQUA))
                    .append(Component.text(" by ").color(NamedTextColor.WHITE))
                    .append(Component.text("Mysterria").color(NamedTextColor.AQUA)));
            sender.sendMessage(Component.text("   ").color(NamedTextColor.AQUA)
                    .append(Component.text("https://www.spigotmc.org/resources/125318/").color(NamedTextColor.GRAY)));
            sender.sendMessage(Component.empty());
            return true;
        }

        if (!sender.hasPermission("mtranslator.admin")) {
            sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "no_permission"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            Object helpObj = plugin.getMessagesConfig().get("help");
            if (helpObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> helpList = (List<String>) helpObj;
                for (String line : helpList) {
                    sender.sendMessage(Component.text(line.replace("&", "")).color(NamedTextColor.WHITE));
                }
            } else if (helpObj instanceof String) {
                sender.sendMessage(Component.text(((String) helpObj).replace("&", "")).color(NamedTextColor.WHITE));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            langManager.loadAll();
            langManager.clearCache();
            plugin.reloadTranslationManager();
            plugin.log("Reloaded " + langManager.getAvailableLangs().size() + " languages! " + langManager.getAvailableLangs());
            plugin.log("Reloaded " + langManager.getTotalTranslationsCount() + " total translations!");
            sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "reload_success"));
            return true;
        }

        if (args[0].equalsIgnoreCase("set")) {
            if (args.length == 3) {
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "player_not_found"));
                    return true;
                }
                String lang = args[2];
                List<String> availableLangs = langManager.getAvailableLangs();
                if (!availableLangs.contains(lang)) {
                    sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "invalid_lang", "{lang}", lang, "{langs}", String.join(", ", availableLangs)));
                    return true;
                }
                langManager.setPlayerLang(target.getUniqueId(), lang);
                langManager.savePlayerLang(target.getUniqueId());
                sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "set_success", "{player}", target.getName(), "{lang}", lang));
            } else {
                sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "set_usage"));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("get")) {
            if (args.length == 2) {
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "player_not_found"));
                    return true;
                }
                String lang = langManager.getPlayerLang(target.getUniqueId());
                sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "get_success", "{player}", target.getName(), "{lang}", lang));
            } else {
                sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "get_usage"));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            List<String> langs = langManager.getAvailableLangs();
            sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "list_languages", "{langs}", String.join(", ", langs)));
            return true;
        }

        sender.sendMessage(MessageSerializer.getMessage(plugin.getMessagesConfig(), "unknown_subcommand"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] strings) {
        return List.of("help", "reload", "set", "get", "list", "");
    }
}
