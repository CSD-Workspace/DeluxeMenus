package com.extendedclip.deluxemenus.dialog;

import com.extendedclip.deluxemenus.DeluxeMenus;
import com.extendedclip.deluxemenus.menu.MenuHolder;
import com.extendedclip.deluxemenus.utils.DebugLevel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The single controlled pipeline for opening a dialog.
 *
 * <p>Every entry point - the {@code /dm dialog open} command and a button that links to another
 * dialog - goes through {@link #open(Player, String, Player, boolean)} so that permissions,
 * requirements, the online check and the version gate are applied uniformly and cannot be bypassed
 * by configuring one dialog to link to another.</p>
 */
public final class DialogService {

    /**
     * The outcome of an attempt to open a dialog.
     */
    public enum OpenResult {
        /** The dialog was handed to the client. */
        SUCCESS,
        /** No dialog with that id is loaded. */
        UNKNOWN_DIALOG,
        /** The viewer does not hold the dialog's own {@code permission}. */
        NO_PERMISSION,
        /** The viewer does not satisfy the dialog's {@code open_requirements}. */
        REQUIREMENTS_NOT_MET,
        /** The server is older than {@link DialogSupport#MINIMUM_VERSION_NAME}. */
        UNSUPPORTED,
        /** The dialog could not be rendered; the reason has been logged. */
        ERROR
    }

    private final DeluxeMenus plugin;
    private final DialogRegistry registry;
    private final DialogSessions sessions;
    private final DialogResponseHandler responseHandler;

    /**
     * Creates the service.
     *
     * @param plugin the owning plugin
     * @param registry the live dialog registry
     * @param sessions the refresh session registry
     */
    public DialogService(final @NotNull DeluxeMenus plugin, final @NotNull DialogRegistry registry,
                         final @NotNull DialogSessions sessions) {
        this.plugin = plugin;
        this.registry = registry;
        this.sessions = sessions;
        this.responseHandler = new DialogResponseHandler(plugin, registry, sessions, this::reopen,
                this::refresh);
    }

    /**
     * The handler passed to the renderer for every dialog this service shows.
     *
     * @return the response handler
     */
    public @NotNull DialogResponseHandler responseHandler() {
        return responseHandler;
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
     * Opens a dialog for a player, applying every check.
     *
     * @param viewer the player who should see the dialog
     * @param dialogId the dialog id, matched case-insensitively
     * @param placeholderPlayer the player to resolve placeholders against, or {@code null}
     * @param bypassChecks whether the dialog's own {@code permission} and {@code open_requirements}
     *                     should be skipped; only callers that have already verified authority pass
     *                     {@code true}
     * @return the outcome
     */
    public @NotNull OpenResult open(final @NotNull Player viewer, final @NotNull String dialogId,
                                    final @Nullable Player placeholderPlayer, final boolean bypassChecks) {
        if (!DialogSupport.isSupported()) {
            return OpenResult.UNSUPPORTED;
        }

        final Optional<DialogDefinition> optional = registry.getById(dialogId);

        if (optional.isEmpty()) {
            return OpenResult.UNKNOWN_DIALOG;
        }

        final DialogDefinition definition = optional.get();

        if (!bypassChecks) {
            final String permission = definition.permission();

            if (permission != null && !permission.isBlank() && !viewer.hasPermission(permission)) {
                return OpenResult.NO_PERMISSION;
            }

            if (definition.requirements() != null) {
                // Requirements are evaluated against the viewer, exactly as they would be for a menu,
                // by giving them a holder scoped to this single check. Deny commands configured on
                // the requirements run as part of evaluation.
                final MenuHolder holder = new MenuHolder(plugin, viewer);

                if (!definition.requirements().evaluate(holder)) {
                    return OpenResult.REQUIREMENTS_NOT_MET;
                }
            }
        }

        return show(viewer, definition, placeholderPlayer);
    }

    /**
     * Renders and shows an already resolved definition.
     *
     * @param viewer the player who should see the dialog
     * @param definition the definition to show
     * @param placeholderPlayer the player to resolve placeholders against, or {@code null}
     * @return the outcome
     */
    public @NotNull OpenResult show(final @NotNull Player viewer, final @NotNull DialogDefinition definition,
                                    final @Nullable Player placeholderPlayer) {
        if (!viewer.isOnline()) {
            return OpenResult.ERROR;
        }

        if (!Bukkit.isPrimaryThread()) {
            // Showing a dialog touches the player connection, so it has to happen on the main thread.
            Bukkit.getScheduler().runTask(plugin, () -> show(viewer, definition, placeholderPlayer));
            return OpenResult.SUCCESS;
        }

        try {
            viewer.showDialog(DialogRenderer.render(definition, viewer, placeholderPlayer, responseHandler));
            // Only recorded once the dialog actually reached the client, so a failed render cannot
            // leave a session behind that refreshes nothing.
            sessions.touch(viewer.getUniqueId(), definition,
                    placeholderPlayer == null ? null : placeholderPlayer.getUniqueId(),
                    System.currentTimeMillis());
            return OpenResult.SUCCESS;
        } catch (final RuntimeException | LinkageError exception) {
            plugin.debug(DebugLevel.HIGHEST, Level.SEVERE,
                    "Failed to show dialog '" + definition.id() + "' from '" + definition.sourceFile()
                            + "' to " + viewer.getName() + ".");
            plugin.printStacktrace("Failed to show dialog '" + definition.id() + "'.", exception);
            return OpenResult.ERROR;
        }
    }

    /**
     * Re-renders and re-sends a dialog the plugin already knows the player is looking at, refreshing
     * its placeholders.
     *
     * <p>Used by a {@code [refresh]} action. The definition is re-resolved from the registry so a
     * reload that happened while the press was in flight cannot resurrect a replaced dialog.</p>
     *
     * @param player the viewer
     * @param definition the definition that was rendered for them
     * @param placeholderPlayerId the player placeholders were resolved against, or {@code null}
     */
    public void refresh(final @NotNull Player player, final @NotNull DialogDefinition definition,
                        final @Nullable UUID placeholderPlayerId) {
        reshow(player, definition, placeholderPlayerId == null ? null : Bukkit.getPlayer(placeholderPlayerId));
    }

    /**
     * Re-renders and re-sends the dialog a refresh session belongs to.
     *
     * <p>Used by the automatic refresh timer. Ends the session instead of refreshing it when the
     * definition has been replaced by a reload, or when the player is no longer online.</p>
     *
     * @param player the viewer
     * @param session the session to refresh
     */
    public void refresh(final @NotNull Player player, final @NotNull DialogSessions.Session session) {
        final Optional<DialogDefinition> optional = registry.getById(session.dialogId());

        if (optional.isEmpty() || optional.get().version() != session.definitionVersion()) {
            sessions.end(player.getUniqueId());
            return;
        }

        reshow(player, optional.get(),
                session.placeholderPlayerId() == null ? null : Bukkit.getPlayer(session.placeholderPlayerId()));
    }

    /**
     * Re-sends a dialog, re-resolving it from the registry first.
     *
     * @param player the viewer
     * @param definition the definition to re-send
     * @param placeholderPlayer the player to resolve placeholders against, or {@code null}
     */
    private void reshow(final @NotNull Player player, final @NotNull DialogDefinition definition,
                        final @Nullable Player placeholderPlayer) {
        if (!player.isOnline()) {
            sessions.end(player.getUniqueId());
            return;
        }

        final Optional<DialogDefinition> current = registry.getById(definition.id());

        if (current.isEmpty() || current.get().version() != definition.version()) {
            sessions.end(player.getUniqueId());
            return;
        }

        // Sending a dialog touches the player's connection, so it has to happen on the main thread.
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> show(player, current.get(), placeholderPlayer));
            return;
        }

        show(player, current.get(), placeholderPlayer);
    }

    private void reopen(final @NotNull Player player, final @NotNull String dialogId) {
        open(player, dialogId, null, true);
    }
}
