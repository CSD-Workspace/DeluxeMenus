package com.extendedclip.deluxemenus.dialog;

import com.extendedclip.deluxemenus.DeluxeMenus;
import com.extendedclip.deluxemenus.dialog.command.RegistrableDialogCommand;
import com.extendedclip.deluxemenus.utils.DebugLevel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Owns the dialog subsystem's lifecycle: where dialogs live on disk, how they are loaded and
 * reloaded, and when the registry is dropped.
 *
 * <p>The manager is created by {@link DeluxeMenus} only after the version gate has passed, so none
 * of the Paper dialog classes it reaches are loaded on an unsupported server.</p>
 */
public final class DialogManager {

    /**
     * A short summary of a load, suitable for the {@code /dm reload} output.
     *
     * @param loaded how many dialogs are now active
     * @param filesRead how many {@code .yml} files were read
     * @param filesFailed how many files were rejected
     * @param errors how many error diagnostics were produced
     * @param warnings how many warning diagnostics were produced
     */
    public record LoadSummary(int loaded, int filesRead, int filesFailed, int errors, int warnings) {

        /**
         * @return {@code true} when every file loaded cleanly
         */
        public boolean clean() {
            return filesFailed == 0 && errors == 0 && warnings == 0;
        }
    }

    private final DeluxeMenus plugin;
    private final DialogRegistry registry = new DialogRegistry();
    private final DialogSessions sessions = new DialogSessions();
    private final File directory;
    private final DialogLoader loader;
    private final DialogService service;
    private final List<RegistrableDialogCommand> commands = new ArrayList<>();
    private BukkitTask refreshTask;

    /**
     * Creates the manager.
     *
     * @param plugin the owning plugin
     */
    public DialogManager(final @NotNull DeluxeMenus plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), DialogLoader.DIALOG_DIRECTORY_NAME);
        this.loader = new DialogLoader(configuration -> plugin.getConfiguration() == null
                ? null
                : plugin.getConfiguration().loadRequirements(configuration));
        this.service = new DialogService(plugin, registry, sessions);
    }

    /**
     * The live registry.
     *
     * @return the registry
     */
    public @NotNull DialogRegistry registry() {
        return registry;
    }

    /**
     * The refresh session registry.
     *
     * @return the sessions
     */
    public @NotNull DialogSessions sessions() {
        return sessions;
    }

    /**
     * The open/show pipeline.
     *
     * @return the service
     */
    public @NotNull DialogService service() {
        return service;
    }

    /**
     * The directory dialog files are read from.
     *
     * @return the {@code gui_dialogs} directory
     */
    public @NotNull File directory() {
        return directory;
    }

    /**
     * Loads every dialog file, replacing the registry contents.
     *
     * <p>The registry is swapped in one step after parsing, so a reload never exposes a half loaded
     * state and never drops unrelated dialogs because one file is broken: valid files load, invalid
     * ones are reported and skipped, and files that no longer exist are simply absent from the new
     * registry.</p>
     *
     * @return a summary of the load
     */
    public @NotNull LoadSummary load() {
        // A fresh install gets the bundled examples so there is something to look at. An existing
        // directory is never touched, so deleting or renaming an example is permanent.
        if (!directory.isDirectory()) {
            installExampleDialogs();
        }

        // Drop the previous generation's open commands before the registry is swapped, so a command
        // never points at a dialog the new registry does not serve.
        unregisterCommands();

        final DialogLoader.LoadResult result = loader.loadAll(directory, true);

        DialogLoader.log(result.diagnostics(), plugin.getLogger(), Level.WARNING);

        registry.replaceAll(result.definitions());

        warnAboutMissingLinks();
        registerCommands();

        // A reload invalidates every session: the definitions behind them have been replaced, and
        // DialogService#refresh ends any session whose version no longer matches.
        sessions.clear();
        startRefreshTask();

        int errors = 0;
        int warnings = 0;

        for (final DialogDiagnostic diagnostic : result.diagnostics()) {
            if (diagnostic.isError()) {
                errors++;
            } else {
                warnings++;
            }
        }

        if (result.filesRead() == 0) {
            plugin.debug(DebugLevel.HIGH, Level.INFO,
                    "No dialog files were found in " + directory.getPath()
                            + ". Add *.yml files there to define native dialogs.");
        } else {
            plugin.debug(DebugLevel.HIGH, Level.INFO,
                    registry.size() + " dialog(s) loaded from " + result.filesRead() + " file(s).");
        }

        return new LoadSummary(registry.size(), result.filesRead(), result.filesFailed(), errors, warnings);
    }

    /**
     * Drops every dialog and any state derived from them.
     *
     * <p>Pending button callbacks hold their own definition reference and are rejected by the
     * registry version check once the registry is empty, so clearing is enough to make a stale
     * response a no-op.</p>
     */
    public void shutdown() {
        stopRefreshTask();
        unregisterCommands();
        sessions.clear();
        registry.clear();
    }

    /**
     * Starts the single task that drives every refreshing dialog.
     *
     * <p>One task for the whole server rather than one per player: the per-player scheduling is just
     * a timestamp comparison, so there is no reason to hold a task per session. It runs
     * synchronously because sending a dialog touches the player's connection.</p>
     */
    private void startRefreshTask() {
        stopRefreshTask();

        // Only run the timer when something can actually use it. Most servers never enable refresh,
        // and this keeps them from paying for a task that would scan an empty session map forever.
        if (registry.getAll().stream().noneMatch(DialogDefinition::refreshes)) {
            return;
        }

        // Every second is the finest granularity the configuration allows, so one pass per second
        // covers the shortest usable interval without wasting ticks.
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRefreshes, 20L, 20L);
    }

    /**
     * Stops the refresh task.
     */
    private void stopRefreshTask() {
        if (this.refreshTask == null) {
            return;
        }

        try {
            this.refreshTask.cancel();
        } catch (final IllegalStateException ignored) {
            // Already cancelled, which can happen when the server is shutting down.
        }

        this.refreshTask = null;
    }

    /**
     * Re-sends every dialog that is due for a refresh.
     */
    private void tickRefreshes() {
        if (!DialogSupport.isSupported()) {
            return;
        }

        for (final DialogSessions.Session session : sessions.due(System.currentTimeMillis())) {
            final Player player = Bukkit.getPlayer(session.playerId());

            if (player == null || !player.isOnline()) {
                sessions.end(session.playerId());
                continue;
            }

            // refresh() re-checks the session, so a definition replaced by a reload ends it here
            // rather than throwing a stale dialog back at the player.
            service.refresh(player, session);
        }
    }

    /**
     * Registers the {@code open_command} of every loaded dialog.
     *
     * <p>Collisions are decided here rather than at parse time because whether a name is free
     * depends on the running server: vanilla commands, other plugins and menu commands are all in
     * the same map. A name that is already taken is reported and skipped — the dialog itself still
     * loads and stays reachable through {@code /dm dialog open}.</p>
     */
    private void registerCommands() {
        final Map<String, String> claimed = new LinkedHashMap<>();

        for (final DialogDefinition definition : registry.getAll()) {
            if (definition.commands().isEmpty()) {
                continue;
            }

            final String name = definition.commands().get(0);
            final String owner = claimed.get(name);

            if (owner != null) {
                plugin.getLogger().warning("Dialog '" + definition.id() + "' (from "
                        + definition.sourceFile() + ") declares the command '" + name
                        + "', which dialog '" + owner + "' already claimed. The command is not "
                        + "registered; use /dm dialog open " + definition.id() + " instead.");
                continue;
            }

            if (RegistrableDialogCommand.isTaken(name)) {
                plugin.getLogger().warning("Dialog '" + definition.id() + "' (from "
                        + definition.sourceFile() + ") declares the command '" + name
                        + "', which is already registered by DeluxeMenus or another plugin. The "
                        + "command is not registered; use /dm dialog open " + definition.id()
                        + " instead, or rename it.");
                continue;
            }

            claimed.put(name, definition.id());

            for (final String alias : definition.commands().subList(1, definition.commands().size())) {
                if (claimed.containsKey(alias) || RegistrableDialogCommand.isTaken(alias)) {
                    plugin.getLogger().warning("Alias '" + alias + "' of dialog '" + definition.id()
                            + "' is already in use and will not answer; the rest of the command still works.");
                }
            }

            final RegistrableDialogCommand command =
                    new RegistrableDialogCommand(plugin, service, registry, definition);

            if (command.register()) {
                commands.add(command);
            }
        }

        if (!commands.isEmpty()) {
            plugin.debug(DebugLevel.HIGH, Level.INFO,
                    "Registered " + commands.size() + " dialog command(s).");
        }
    }

    /**
     * Removes every dialog command registered by the previous load.
     */
    private void unregisterCommands() {
        for (final RegistrableDialogCommand command : commands) {
            if (command.registered() && !command.unregistered()) {
                command.unregister();
            }
        }

        commands.clear();
    }

    /**
     * Copies the dialogs bundled in the jar into the dialog directory.
     *
     * <p>Only called when the directory does not exist yet, so this runs exactly once per
     * installation. Each file is written with {@code replace = false}, which means an example can
     * never overwrite a file the administrator already has.</p>
     */
    private void installExampleDialogs() {
        int installed = 0;

        for (final String fileName : DialogLoader.SHIPPED_EXAMPLE_FILES) {
            final String resource = DialogLoader.exampleResourcePath(fileName);

            if (plugin.getResource(resource) == null) {
                plugin.getLogger().warning("The bundled example dialog '" + resource
                        + "' is missing from the jar and was not installed.");
                continue;
            }

            try {
                plugin.saveResource(resource, false);
                installed++;
            } catch (final RuntimeException exception) {
                plugin.debug(DebugLevel.HIGHEST, Level.SEVERE,
                        "Failed to install the bundled example dialog '" + fileName + "'.");
                plugin.printStacktrace("Failed to install example dialog '" + fileName + "'.", exception);
            }
        }

        if (installed > 0) {
            plugin.debug(DebugLevel.HIGH, Level.INFO,
                    "Installed " + installed + " example dialog(s) into " + directory.getPath()
                            + ". Rename or delete them freely; they are written only once.");
        }
    }

    /**
     * Reports {@code open_dialog} actions that point at dialogs which are not loaded.
     *
     * <p>This can only be checked once every file has been read, because a dialog may legitimately
     * be defined in a different file.</p>
     */
    private void warnAboutMissingLinks() {
        for (final DialogDefinition definition : registry.getAll()) {
            for (final DialogDefinition.ActionSpec action : allActions(definition)) {
                if (action.kind() != DialogDefinition.ActionSpec.Kind.OPEN_DIALOG) {
                    continue;
                }

                if (!registry.contains(action.executable())) {
                    plugin.getLogger().warning("[warning] " + definition.sourceFile() + " (dialog '"
                            + definition.id() + "'): open_dialog action points at '" + action.executable()
                            + "', which is not loaded. The button will report an error when pressed.");
                }
            }
        }
    }

    private static @NotNull List<DialogDefinition.ActionSpec> allActions(final @NotNull DialogDefinition definition) {
        final List<DialogDefinition.ActionSpec> actions = new ArrayList<>();

        for (final DialogDefinition.ButtonSpec button : definition.buttons()) {
            actions.addAll(button.actions());
        }

        if (definition.exitButton() != null) {
            actions.addAll(definition.exitButton().actions());
        }

        return actions;
    }
}
