package com.extendedclip.deluxemenus.command.subcommand;

import com.extendedclip.deluxemenus.DeluxeMenus;
import com.extendedclip.deluxemenus.dialog.DialogDefinition;
import com.extendedclip.deluxemenus.dialog.DialogManager;
import com.extendedclip.deluxemenus.dialog.DialogRegistry;
import com.extendedclip.deluxemenus.dialog.DialogService;
import com.extendedclip.deluxemenus.dialog.DialogSupport;
import com.extendedclip.deluxemenus.utils.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /dm dialog ...} - the dialog counterpart to {@code /dm open}.
 *
 * <p>Dialogs deliberately live under their own subcommand so a dialog id can never be confused with
 * a menu id, and so the existing {@code /dm open <menu> [player]} behaviour stays untouched.</p>
 *
 * <p>Permission model, mirrored from the menu commands:</p>
 * <ul>
 *     <li>{@code deluxemenus.dialog.open} gates the open operation.</li>
 *     <li>{@code deluxemenus.dialog.open.others} is required to target another player.</li>
 *     <li>{@code deluxemenus.dialog.open.bypass} skips the dialog's own {@code permission} and
 *         {@code open_requirements} for the viewer.</li>
 *     <li>{@code deluxemenus.dialog.list} and {@code deluxemenus.dialog.admin} gate inspection.</li>
 *     <li>{@code deluxemenus.placeholdersfor} and the matching {@code .exempt} node are shared with
 *         the menu commands for the {@code -p:<player>} argument.</li>
 * </ul>
 */
public class DialogCommand extends SubCommand {

    /**
     * Permission required to open a dialog.
     */
    public static final String OPEN_PERMISSION = "deluxemenus.dialog.open";

    /**
     * Permission required to open a dialog for another player.
     */
    public static final String OPEN_OTHERS_PERMISSION = "deluxemenus.dialog.open.others";

    /**
     * Permission that skips a dialog's own permission and open requirements for the viewer.
     */
    public static final String BYPASS_PERMISSION = "deluxemenus.dialog.open.bypass";

    /**
     * Permission required to list dialogs.
     */
    public static final String LIST_PERMISSION = "deluxemenus.dialog.list";

    /**
     * Permission required for diagnostic inspection of a dialog.
     */
    public static final String INFO_PERMISSION = "deluxemenus.dialog.admin";

    /**
     * Permission required to resolve placeholders against another player.
     */
    public static final String PLACEHOLDER_PERMISSION = "deluxemenus.placeholdersfor";

    /**
     * Permission that exempts a player from being used as a placeholder target.
     */
    public static final String PLACEHOLDER_EXEMPT_PERMISSION = "deluxemenus.placeholdersfor.exempt";

    private static final String PLACEHOLDER_ARGUMENT_PREFIX = "-p:";

    public DialogCommand(final @NotNull DeluxeMenus plugin) {
        super(plugin);
    }

    @Override
    public @NotNull String getName() {
        return "dialog";
    }

    @Override
    public void execute(final @NotNull CommandSender sender, final @NotNull List<String> arguments) {
        if (arguments.isEmpty()) {
            plugin.sms(sender, Messages.WRONG_USAGE_DIALOG_COMMAND);
            return;
        }

        final String action = arguments.get(0).toLowerCase(Locale.ROOT);
        final List<String> rest = arguments.subList(1, arguments.size());

        switch (action) {
            case "open" -> open(sender, rest);
            case "list" -> list(sender);
            case "info" -> info(sender, rest);
            default -> plugin.sms(sender, Messages.WRONG_USAGE_DIALOG_COMMAND);
        }
    }

    /**
     * Opens a dialog.
     *
     * <p>The argument list starts at the dialog id: {@code <dialog> [player] [-p:<player>]}.</p>
     *
     * @param sender the command sender
     * @param arguments the dialog id followed by the optional target and placeholder player
     */
    public void open(final @NotNull CommandSender sender, final @NotNull List<String> arguments) {
        if (!sender.hasPermission(OPEN_PERMISSION)) {
            plugin.sms(sender, Messages.NO_PERMISSION);
            return;
        }

        if (arguments.isEmpty()) {
            plugin.sms(sender, Messages.WRONG_USAGE_DIALOG_OPEN_COMMAND);
            return;
        }

        final DialogManager manager = plugin.getDialogManager();

        if (manager == null || !DialogSupport.isSupported()) {
            plugin.sms(sender, Messages.DIALOG_UNSUPPORTED);
            return;
        }

        if (manager.registry().size() == 0) {
            plugin.sms(sender, Messages.NO_DIALOGS_LOADED);
            return;
        }

        final String dialogId = arguments.get(0);
        String playerName = null;
        String placeholderName = null;

        for (int index = 1; index < arguments.size(); index++) {
            final String argument = arguments.get(index);

            if (argument.startsWith(PLACEHOLDER_ARGUMENT_PREFIX)) {
                placeholderName = argument.substring(PLACEHOLDER_ARGUMENT_PREFIX.length());
                continue;
            }

            if (playerName == null) {
                playerName = argument;
                continue;
            }

            plugin.sms(sender, Messages.WRONG_USAGE_DIALOG_OPEN_COMMAND);
            return;
        }

        final Player viewer;

        if (playerName == null) {
            if (!(sender instanceof Player player)) {
                plugin.sms(sender, Messages.MUST_SPECIFY_PLAYER_DIALOG);
                return;
            }

            viewer = player;
        } else {
            if (!sender.hasPermission(OPEN_OTHERS_PERMISSION)) {
                plugin.sms(sender, Messages.NO_PERMISSION);
                return;
            }

            viewer = Bukkit.getPlayerExact(playerName);

            if (viewer == null) {
                plugin.sms(sender, playerNotOnline(playerName));
                return;
            }
        }

        Player placeholderPlayer = null;

        if (placeholderName != null) {
            if (!sender.hasPermission(PLACEHOLDER_PERMISSION)) {
                plugin.sms(sender, Messages.NO_PERMISSION_PLAYER_ARGUMENT);
                return;
            }

            placeholderPlayer = Bukkit.getPlayerExact(placeholderName);

            if (placeholderPlayer == null) {
                plugin.sms(sender, playerNotOnline(placeholderName));
                return;
            }

            if (placeholderPlayer.hasPermission(PLACEHOLDER_EXEMPT_PERMISSION)) {
                plugin.sms(sender, Messages.PLAYER_IS_EXEMPT.message()
                        .replaceText(PLAYER_REPLACER_BUILDER.replacement(placeholderName).build()));
                return;
            }
        }

        final boolean bypass = sender.hasPermission(BYPASS_PERMISSION);
        final DialogService.OpenResult result = manager.service().open(viewer, dialogId, placeholderPlayer, bypass);

        switch (result) {
            case SUCCESS -> plugin.sms(sender, Messages.DIALOG_OPENED.message()
                    .replaceText(DIALOG_REPLACER_BUILDER.replacement(dialogId).build())
                    .replaceText(PLAYER_REPLACER_BUILDER.replacement(viewer.getName()).build()));
            case UNKNOWN_DIALOG -> plugin.sms(sender, invalidDialog(dialogId));
            case NO_PERMISSION -> plugin.sms(sender, Messages.DIALOG_NO_PERMISSION.message()
                    .replaceText(DIALOG_REPLACER_BUILDER.replacement(dialogId).build()));
            case REQUIREMENTS_NOT_MET -> plugin.sms(sender, Messages.DIALOG_REQUIREMENTS_NOT_MET.message()
                    .replaceText(DIALOG_REPLACER_BUILDER.replacement(dialogId).build()));
            case UNSUPPORTED -> plugin.sms(sender, Messages.DIALOG_UNSUPPORTED);
            case ERROR -> plugin.sms(sender, Messages.DIALOG_OPEN_ERROR.message()
                    .replaceText(DIALOG_REPLACER_BUILDER.replacement(dialogId).build()));
        }
    }

    private void list(final @NotNull CommandSender sender) {
        if (!sender.hasPermission(LIST_PERMISSION)) {
            plugin.sms(sender, Messages.NO_PERMISSION);
            return;
        }

        final DialogManager manager = plugin.getDialogManager();

        if (manager == null || !DialogSupport.isSupported()) {
            plugin.sms(sender, Messages.DIALOG_UNSUPPORTED);
            return;
        }

        final DialogRegistry registry = manager.registry();

        sender.sendMessage(Messages.DIALOG_LIST_HEADER.message());

        if (registry.size() == 0) {
            plugin.sms(sender, Messages.NO_DIALOGS_LOADED);
            return;
        }

        for (final String id : registry.getIds()) {
            sender.sendMessage(Component.text("> ", NamedTextColor.AQUA)
                    .append(Component.text(id, NamedTextColor.WHITE)));
        }

        sender.sendMessage(Component.text(registry.size() + (registry.size() == 1
                ? " dialog loaded."
                : " dialogs loaded."), NamedTextColor.YELLOW));
    }

    private void info(final @NotNull CommandSender sender, final @NotNull List<String> arguments) {
        if (!sender.hasPermission(INFO_PERMISSION)) {
            plugin.sms(sender, Messages.NO_PERMISSION);
            return;
        }

        if (arguments.isEmpty()) {
            plugin.sms(sender, Messages.WRONG_USAGE_DIALOG_COMMAND);
            return;
        }

        final DialogManager manager = plugin.getDialogManager();

        if (manager == null || !DialogSupport.isSupported()) {
            plugin.sms(sender, Messages.DIALOG_UNSUPPORTED);
            return;
        }

        final String dialogId = arguments.get(0);
        final Optional<DialogDefinition> optional = manager.registry().getById(dialogId);

        if (optional.isEmpty()) {
            plugin.sms(sender, invalidDialog(dialogId));
            return;
        }

        final DialogDefinition definition = optional.get();

        sender.sendMessage(Messages.DIALOG_INFO_HEADER.message());

        sendInfoLine(sender, "Id", definition.id());
        sendInfoLine(sender, "Type", definition.type().configName() + " - " + definition.type().description());
        sendInfoLine(sender, "Source file", definition.sourceFile());
        sendInfoLine(sender, "Version token", String.valueOf(definition.version()));
        sendInfoLine(sender, "Permission", definition.permission() == null ? "none" : definition.permission());
        sendInfoLine(sender, "Open command", definition.commands().isEmpty()
                ? "none (use /dm dialog open " + definition.id() + ")"
                : definition.commands().get(0)
                        + (definition.commands().size() > 1
                                ? " (aliases: " + String.join(", ", definition.commands().subList(1, definition.commands().size())) + ")"
                                : ""));
        sendInfoLine(sender, "Open requirements", definition.requirements() == null
                ? "none"
                : definition.requirements().getRequirements().size() + " checked against the viewer");
        sendInfoLine(sender, "Body elements", String.valueOf(definition.body().size()));
        sendInfoLine(sender, "Buttons", definition.buttons().size()
                + (definition.exitButton() == null ? "" : " + 1 exit"));
        sendInfoLine(sender, "Options", "columns=" + definition.columns()
                + ", can_close_with_escape=" + definition.canCloseWithEscape()
                + ", pause=" + definition.pause()
                + ", after_action=" + definition.afterAction().name().toLowerCase(Locale.ROOT));
        sendInfoLine(sender, "Refresh", definition.refreshes()
                ? "every " + definition.refreshIntervalSeconds() + "s, for at most "
                        + definition.refreshMaxSeconds() + "s without interaction"
                : "none");

        for (final DialogDefinition.InputSpec input : definition.inputs()) {
            sendInfoLine(sender, "Input", input.id() + " (" + input.type().configName()
                    + (input.required() ? ", required" : "") + ")");
        }

        for (final DialogDefinition.ButtonSpec button : definition.buttons()) {
            sendInfoLine(sender, "Button", button.id() + " (" + button.actions().size() + " action(s))");
        }
    }

    private static void sendInfoLine(final @NotNull CommandSender sender, final @NotNull String label,
                                     final @NotNull String value) {
        sender.sendMessage(Component.text("> ", NamedTextColor.AQUA)
                .append(Component.text(label + ": ", NamedTextColor.GRAY))
                .append(Component.text(value, NamedTextColor.WHITE)));
    }

    @Override
    public @Nullable List<String> onTabComplete(final @NotNull CommandSender sender, final @NotNull List<String> arguments) {
        final DialogManager manager = plugin.getDialogManager();

        if (manager == null || !DialogSupport.isSupported()) {
            return null;
        }

        if (arguments.isEmpty()) {
            return List.of(getName());
        }

        if (arguments.size() > 5) {
            return null;
        }

        if (arguments.size() == 1) {
            return matching(arguments.get(0), List.of(getName()));
        }

        if (!getName().equalsIgnoreCase(arguments.get(0))) {
            return null;
        }

        if (arguments.size() == 2) {
            final List<String> subActions = new ArrayList<>();

            if (sender.hasPermission(OPEN_PERMISSION)) {
                subActions.add("open");
            }

            if (sender.hasPermission(LIST_PERMISSION)) {
                subActions.add("list");
            }

            if (sender.hasPermission(INFO_PERMISSION)) {
                subActions.add("info");
            }

            return matching(arguments.get(1), subActions);
        }

        final String action = arguments.get(1).toLowerCase(Locale.ROOT);

        if (action.equals("list")) {
            return null;
        }

        if (arguments.size() == 3) {
            if (action.equals("info") && !sender.hasPermission(INFO_PERMISSION)) {
                return null;
            }

            if (action.equals("open") && !sender.hasPermission(OPEN_PERMISSION)) {
                return null;
            }

            return matching(arguments.get(2), openableDialogIds(sender));
        }

        if (!action.equals("open")) {
            return null;
        }

        final String dialogId = arguments.get(2);
        final String partial = arguments.get(arguments.size() - 1);

        if (arguments.size() == 4) {
            final List<String> suggestions = new ArrayList<>();

            suggestions.add(PLACEHOLDER_ARGUMENT_PREFIX);

            if (sender.hasPermission(OPEN_OTHERS_PERMISSION)) {
                final List<String> players = getPlayerNameCompletion(partial);

                if (players != null) {
                    suggestions.addAll(players);
                }
            }

            final List<String> result = matching(partial.isBlank() ? "" : partial, suggestions);

            // A dialog id that does not resolve yields no useful player completion.
            if (!manager.registry().contains(dialogId) && !partial.startsWith(PLACEHOLDER_ARGUMENT_PREFIX)) {
                return null;
            }

            return result;
        }

        if (!arguments.get(3).startsWith(PLACEHOLDER_ARGUMENT_PREFIX)) {
            return null;
        }

        return getPlayerNameCompletion(partial);
    }

    /**
     * The dialog ids this sender is allowed to open, used to keep restricted ids out of completion.
     *
     * @param sender the command sender
     * @return the ids the sender may open
     */
    private @NotNull List<String> openableDialogIds(final @NotNull CommandSender sender) {
        final DialogManager manager = plugin.getDialogManager();

        if (manager == null) {
            return List.of();
        }

        final List<String> ids = new ArrayList<>();
        final boolean bypass = sender.hasPermission(BYPASS_PERMISSION);

        for (final DialogDefinition definition : manager.registry().getAll()) {
            if (bypass || definition.permission() == null || definition.permission().isBlank()
                    || sender.hasPermission(definition.permission())) {
                ids.add(definition.id());
            }
        }

        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }

    private static @Nullable List<String> matching(final @Nullable String partial, final @NotNull List<String> options) {
        if (partial == null) {
            return null;
        }

        final String prefix = partial.toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>();

        for (final String option : options) {
            if (prefix.isEmpty() || option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(option);
            }
        }

        return matches;
    }

    private static @NotNull Component invalidDialog(final @NotNull String dialogId) {
        return Messages.INVALID_DIALOG.message().replaceText(dialogReplacer(dialogId));
    }

    private static @NotNull Component playerNotOnline(final @NotNull String playerName) {
        return Messages.PLAYER_IS_NOT_ONLINE.message().replaceText(playerReplacer(playerName));
    }

    /**
     * Builds the {@code <dialog>} replacement used by the dialog messages.
     *
     * <p>Exposed so the {@code open_command} handlers generated for dialogs can report failures
     * exactly the way {@code /dm dialog open} does.</p>
     *
     * @param dialogId the dialog id
     * @return the configured replacer
     */
    public static @NotNull TextReplacementConfig dialogReplacer(final @NotNull String dialogId) {
        return DIALOG_REPLACER_BUILDER.replacement(dialogId).build();
    }

    /**
     * Builds the {@code <player>} replacement used by the dialog messages.
     *
     * @param playerName the player name
     * @return the configured replacer
     */
    public static @NotNull TextReplacementConfig playerReplacer(final @NotNull String playerName) {
        return PLAYER_REPLACER_BUILDER.replacement(playerName).build();
    }
}
