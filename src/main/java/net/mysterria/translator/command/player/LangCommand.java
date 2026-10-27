package net.mysterria.translator.command.player;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import lombok.RequiredArgsConstructor;
import net.mysterria.translator.MysterriaTranslator;
import net.mysterria.translator.config.Messages;
import net.mysterria.translator.domain.language.model.source.Language;
import net.mysterria.translator.domain.preference.model.LanguagePreference;
import net.mysterria.translator.util.language.Languages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class LangCommand implements BasicCommand {

    private static final String ADMIN = "mtranslator.admin";
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("help", "toggle", "on", "off", "chat", "ui");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("set", "get", "list", "reload", "engines");
    private static final String AUTO = "auto";

    private final MysterriaTranslator plugin;

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        CommandSender sender = source.getSender();
        String subcommand = args.length == 0 ? "status" : args[0].toLowerCase();
        if (ADMIN_SUBCOMMANDS.contains(subcommand) && !sender.hasPermission(ADMIN)) {
            messages().send(sender, "no_permission");
            return;
        }
        switch (subcommand) {
            case "status" -> asPlayer(sender, this::showStatus);
            case "help" -> {
                messages().sendLines(sender, "help");
                if (sender.hasPermission(ADMIN)) {
                    messages().sendLines(sender, "help_admin");
                }
            }
            case "toggle", "on", "off" -> asPlayer(sender, player -> toggle(player, subcommand));
            case "chat" -> asPlayer(sender, player -> setChatLanguage(player, args));
            case "ui" -> asPlayer(sender, player -> setInterfaceLanguage(player, args));
            case "set" -> setForOther(sender, args);
            case "get" -> getForOther(sender, args);
            case "list" -> messages().send(sender, "list_languages", "{langs}", String.join(", ", plugin.getLanguages().available()));
            case "reload" -> reload(sender);
            case "engines" -> showEngines(sender);
            default -> messages().send(sender, "unknown_subcommand");
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            Stream<String> options = sender.hasPermission(ADMIN)
                    ? Stream.concat(PLAYER_SUBCOMMANDS.stream(), ADMIN_SUBCOMMANDS.stream())
                    : PLAYER_SUBCOMMANDS.stream();
            return startingWith(options, args.length == 0 ? "" : args[0]);
        }
        String subcommand = args[0].toLowerCase();
        String partial = args[args.length - 1];
        if (args.length == 2) {
            return switch (subcommand) {
                case "chat" -> startingWith(Stream.concat(Stream.of(AUTO), Language.translatableCodes().stream()), partial);
                case "ui" -> startingWith(plugin.getLanguages().available().stream(), partial);
                case "set", "get" -> sender.hasPermission(ADMIN)
                        ? startingWith(Bukkit.getOnlinePlayers().stream().map(Player::getName), partial)
                        : List.of();
                default -> List.of();
            };
        }
        if (args.length == 3 && subcommand.equals("set") && sender.hasPermission(ADMIN)) {
            return startingWith(plugin.getLanguages().available().stream(), partial);
        }
        return List.of();
    }

    private void showStatus(Player player) {
        LanguagePreference preference = plugin.getPreferences().find(player.getUniqueId());
        boolean pinned = preference != null && preference.getChatLanguage() != null;
        boolean enabled = plugin.getPreferences().translationsEnabled(player.getUniqueId());
        messages().sendLines(player, "status",
                "{ui}", plugin.getPreferences().language(player.getUniqueId()),
                "{chat}", Languages.displayName(plugin.getPreferences().chatTarget(player)),
                "{chat_mode}", messages().raw(pinned ? "chat_mode_pinned" : "chat_mode_auto"),
                "{state}", messages().raw(enabled ? "state_on" : "state_off"));
    }

    private void toggle(Player player, String subcommand) {
        boolean enabled = switch (subcommand) {
            case "on" -> true;
            case "off" -> false;
            default -> !plugin.getPreferences().translationsEnabled(player.getUniqueId());
        };
        plugin.getPreferences().update(player.getUniqueId(), preference -> preference.setTranslationsEnabled(enabled));
        messages().send(player, enabled ? "toggle_on" : "toggle_off");
    }

    private void setChatLanguage(Player player, String[] args) {
        if (args.length != 2) {
            messages().send(player, "chat_usage");
            return;
        }
        String code = args[1].toLowerCase();
        if (code.equals(AUTO)) {
            plugin.getPreferences().update(player.getUniqueId(), preference -> preference.setChatLanguage(null));
            messages().send(player, "chat_auto", "{lang}", Languages.displayName(plugin.getPreferences().chatTarget(player)));
            return;
        }
        if (Language.translatableCodes().stream().noneMatch(known -> known.equals(code) || known.startsWith(code + "_"))) {
            messages().send(player, "invalid_chat_lang", "{lang}", args[1]);
            return;
        }
        Locale locale = Languages.fromCode(code);
        plugin.getPreferences().update(player.getUniqueId(), preference -> preference.setChatLanguage(locale.toLanguageTag()));
        messages().send(player, "chat_set", "{lang}", Languages.displayName(locale));
    }

    private void setInterfaceLanguage(Player player, String[] args) {
        if (args.length != 2 || !plugin.getLanguages().isAvailable(args[1].toLowerCase())) {
            messages().send(player, "invalid_lang", "{lang}", args.length == 2 ? args[1] : "", "{langs}", String.join(", ", plugin.getLanguages().available()));
            return;
        }
        String language = args[1].toLowerCase();
        plugin.getPreferences().update(player.getUniqueId(), preference -> preference.setLanguage(language));
        messages().send(player, "ui_set", "{lang}", language);
    }

    private void setForOther(CommandSender sender, String[] args) {
        if (args.length != 3) {
            messages().send(sender, "set_usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        String language = args[2].toLowerCase();
        if (target == null) {
            messages().send(sender, "player_not_found");
        } else if (!plugin.getLanguages().isAvailable(language)) {
            messages().send(sender, "invalid_lang", "{lang}", args[2], "{langs}", String.join(", ", plugin.getLanguages().available()));
        } else {
            plugin.getPreferences().update(target.getUniqueId(), preference -> preference.setLanguage(language));
            messages().send(sender, "set_success", "{player}", target.getName(), "{lang}", language);
        }
    }

    private void getForOther(CommandSender sender, String[] args) {
        if (args.length != 2) {
            messages().send(sender, "get_usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages().send(sender, "player_not_found");
            return;
        }
        messages().send(sender, "get_success", "{player}", target.getName(),
                "{lang}", plugin.getPreferences().language(target.getUniqueId()),
                "{chat}", Languages.displayName(plugin.getPreferences().chatTarget(target)),
                "{state}", messages().raw(plugin.getPreferences().translationsEnabled(target.getUniqueId()) ? "state_on" : "state_off"));
    }

    private void reload(CommandSender sender) {
        List<String> errors = plugin.reload();
        if (errors.isEmpty()) {
            messages().send(sender, "reload_success", "{langs}", String.valueOf(plugin.getLanguages().available().size()),
                    "{entries}", String.valueOf(plugin.getLanguages().entryCount()));
            return;
        }
        messages().send(sender, "reload_failed");
        errors.forEach(error -> messages().send(sender, "reload_error", "{error}", error));
    }

    private void showEngines(CommandSender sender) {
        messages().sendLines(sender, "engines_header", "{pool}", plugin.getThreadPool().stats(),
                "{cache}", String.valueOf(plugin.getTranslations().cachedTranslations()));
        plugin.getEngines().status().forEach((engine, state) -> messages().sendLines(sender, "engines_line", "{engine}", engine, "{state}", state));
    }

    private void asPlayer(CommandSender sender, Consumer<Player> action) {
        if (sender instanceof Player player) {
            action.accept(player);
        } else {
            messages().send(sender, "only_players");
        }
    }

    private Messages messages() {
        return plugin.getMessages();
    }

    private static List<String> startingWith(Stream<String> options, String partial) {
        String lower = partial.toLowerCase();
        return options.filter(option -> option.toLowerCase().startsWith(lower)).toList();
    }
}
