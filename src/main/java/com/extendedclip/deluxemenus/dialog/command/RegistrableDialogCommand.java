package com.extendedclip.deluxemenus.dialog.command;

import com.extendedclip.deluxemenus.DeluxeMenus;
import com.extendedclip.deluxemenus.command.subcommand.DialogCommand;
import com.extendedclip.deluxemenus.dialog.DialogDefinition;
import com.extendedclip.deluxemenus.dialog.DialogLoader;
import com.extendedclip.deluxemenus.dialog.DialogRegistry;
import com.extendedclip.deluxemenus.dialog.DialogService;
import com.extendedclip.deluxemenus.utils.DebugLevel;
import com.extendedclip.deluxemenus.utils.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * A command declared by a dialog's {@code open_command}, registered into Bukkit's command map.
 *
 * <p>This mirrors {@code RegistrableMenuCommand} deliberately, including the namespace it registers
 * under and the way it removes every alias on unregister. The behaviour differs in two ways: a
 * dialog command accepts an optional target player, and it runs the same pipeline as
 * {@code /dm dialog open}, so permissions, the dialog's own {@code permission} and its
 * {@code open_requirements} apply identically however the dialog is opened.</p>
 *
 * <p>Only the dialog id is held, never the definition. That is what lets a reload drop every command
 * from the previous generation without leaving one bound to a definition the registry no longer
 * serves.</p>
 */
public class RegistrableDialogCommand extends Command {

    private static final String FALLBACK_PREFIX = "deluxemenus";

    private final DeluxeMenus plugin;
    private final DialogService service;
    private final DialogRegistry registry;
    private final String dialogId;

    private boolean registered = false;
    private boolean unregistered = false;

    /**
     * Creates a command for a dialog.
     *
     * <p>{@link DialogLoader} guarantees the list is non-empty and lower cased by the time a
     * definition exists.</p>
     *
     * @param plugin the owning plugin
     * @param service the open pipeline
     * @param registry the live registry, consulted on every execution
     * @param definition the dialog that declared the command
     */
    public RegistrableDialogCommand(final @NotNull DeluxeMenus plugin,
                                    final @NotNull DialogService service,
                                    final @NotNull DialogRegistry registry,
                                    final @NotNull DialogDefinition definition) {
        super(definition.commands().get(0));
        this.plugin = plugin;
        this.service = service;
        this.registry = registry;
        this.dialogId = definition.id();

        if (definition.commands().size() > 1) {
            this.setAliases(definition.commands().subList(1, definition.commands().size()));
        }
    }

    @Override
    public boolean execute(final @NotNull CommandSender sender, final @NotNull String commandLabel,
                           final @NotNull String @NotNull [] typedArgs) {
        if (this.unregistered) {
            throw new IllegalStateException("This command was unregistered!");
        }

        if (typedArgs.length > 1) {
            plugin.sms(sender, Messages.WRONG_USAGE_DIALOG_OPEN_COMMAND);
            return true;
        }

        if (!sender.hasPermission(DialogCommand.OPEN_PERMISSION)) {
            plugin.sms(sender, Messages.NO_PERMISSION);
            return true;
        }

        final Player viewer;

        if (typedArgs.length == 1) {
            if (!sender.hasPermission(DialogCommand.OPEN_OTHERS_PERMISSION)) {
                plugin.sms(sender, Messages.NO_PERMISSION);
                return true;
            }

            viewer = Bukkit.getPlayerExact(typedArgs[0]);

            if (viewer == null) {
                plugin.sms(sender, Messages.PLAYER_IS_NOT_ONLINE.message()
                        .replaceText(DialogCommand.playerReplacer(typedArgs[0])));
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                plugin.sms(sender, Messages.MUST_SPECIFY_PLAYER_DIALOG);
                return true;
            }

            viewer = player;
        }

        // Resolved fresh on every execution, so a reload is picked up without re-registering. The
        // command itself survives a reload only if the dialog still declares it, but a dialog that
        // survived with its command removed cannot report a stale id here.
        if (!registry.contains(dialogId)) {
            plugin.sms(sender, Messages.INVALID_DIALOG.message()
                    .replaceText(DialogCommand.dialogReplacer(dialogId)));
            return true;
        }

        final boolean bypass = sender.hasPermission(DialogCommand.BYPASS_PERMISSION);
        final DialogService.OpenResult result = service.open(viewer, dialogId, null, bypass);

        switch (result) {
            case SUCCESS -> plugin.sms(sender, Messages.DIALOG_OPENED.message()
                    .replaceText(DialogCommand.dialogReplacer(dialogId))
                    .replaceText(DialogCommand.playerReplacer(viewer.getName())));
            case UNKNOWN_DIALOG -> plugin.sms(sender, Messages.INVALID_DIALOG.message()
                    .replaceText(DialogCommand.dialogReplacer(dialogId)));
            case NO_PERMISSION -> plugin.sms(sender, Messages.DIALOG_NO_PERMISSION.message()
                    .replaceText(DialogCommand.dialogReplacer(dialogId)));
            case REQUIREMENTS_NOT_MET -> plugin.sms(sender, Messages.DIALOG_REQUIREMENTS_NOT_MET.message()
                    .replaceText(DialogCommand.dialogReplacer(dialogId)));
            case UNSUPPORTED -> plugin.sms(sender, Messages.DIALOG_UNSUPPORTED);
            case ERROR -> plugin.sms(sender, Messages.DIALOG_OPEN_ERROR.message()
                    .replaceText(DialogCommand.dialogReplacer(dialogId)));
        }

        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(final @NotNull CommandSender sender,
                                             final @NotNull String alias,
                                             final @NotNull String @NotNull [] args) {
        if (args.length != 1 || !sender.hasPermission(DialogCommand.OPEN_OTHERS_PERMISSION)) {
            return List.of();
        }

        final String partial = args[0].toLowerCase(Locale.ROOT);
        final List<String> names = new ArrayList<>();

        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (partial.isEmpty() || player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                names.add(player.getName());
            }
        }

        return names;
    }

    /**
     * Registers this command into Bukkit's command map.
     *
     * <p>Registration is not guaranteed: another plugin, a menu command or a vanilla command may
     * already own the name. The dialog still loads and stays reachable through
     * {@code /dm dialog open <id>}, so a conflict degrades instead of breaking.</p>
     *
     * @return {@code true} when the command was registered
     */
    public boolean register() {
        if (registered) {
            throw new IllegalStateException("This command was already registered!");
        }

        registered = true;

        try {
            if (Bukkit.getCommandMap().register(FALLBACK_PREFIX, this)) {
                plugin.debug(DebugLevel.LOW, Level.INFO,
                        "Registered command: " + this.getName() + " for dialog: " + dialogId);
                return true;
            }
        } catch (final RuntimeException exception) {
            plugin.printStacktrace("Failed to register command '" + this.getName() + "'.", exception);
        }

        plugin.getLogger().warning("Could not register command '" + this.getName() + "' for dialog '"
                + dialogId + "' because a command with that name already exists. Use /dm dialog open "
                + dialogId + " instead, or rename the command.");
        return false;
    }

    /**
     * Removes this command and every alias from the command map.
     */
    public void unregister() {
        if (!registered) {
            throw new IllegalStateException("This command was not registered!");
        }

        if (unregistered) {
            throw new IllegalStateException("This command was already unregistered!");
        }

        unregistered = true;

        try {
            final CommandMap commandMap = Bukkit.getCommandMap();
            final Map<String, Command> knownCommands = commandMap.getKnownCommands();

            // CommandMap#register() adds every alias to the map, so every one of them has to be
            // removed to avoid leaving dangling references behind.
            knownCommands.remove(this.getName());
            knownCommands.remove(FALLBACK_PREFIX + ":" + this.getName());

            for (final String alias : this.getAliases()) {
                knownCommands.remove(alias);
                knownCommands.remove(FALLBACK_PREFIX + ":" + alias);
            }

            if (this.unregister(commandMap)) {
                plugin.debug(DebugLevel.HIGH, Level.INFO,
                        "Successfully unregistered command: " + this.getName());
            } else {
                plugin.debug(DebugLevel.HIGHEST, Level.WARNING,
                        "Failed to unregister command: " + this.getName());
            }
        } catch (final Exception exception) {
            plugin.printStacktrace(
                    "Something went wrong while trying to unregister command: " + this.getName(),
                    exception
            );
        }
    }

    /**
     * Whether {@link #register()} has been called.
     *
     * @return {@code true} once registration was attempted
     */
    public boolean registered() {
        return registered;
    }

    /**
     * Whether {@link #unregister()} has been called.
     *
     * @return {@code true} once the command was removed
     */
    public boolean unregistered() {
        return unregistered;
    }

    /**
     * The dialog this command opens.
     *
     * @return the dialog id
     */
    public @NotNull String dialogId() {
        return dialogId;
    }

    /**
     * Checks whether a command name is already claimed, so a collision can be reported instead of
     * silently stomping whatever owns the name.
     *
     * @param name the lower case command name
     * @return {@code true} when DeluxeMenus reserves the name or something already registered it
     */
    public static boolean isTaken(final @NotNull String name) {
        if (DialogLoader.RESERVED_COMMANDS.contains(name)) {
            return true;
        }

        final Map<String, Command> knownCommands = Bukkit.getCommandMap().getKnownCommands();

        return knownCommands.containsKey(name) || knownCommands.containsKey(FALLBACK_PREFIX + ":" + name);
    }

    @Override
    public @NotNull String toString() {
        return "RegistrableDialogCommand{" + this.getName() + " -> " + dialogId + '}';
    }
}
