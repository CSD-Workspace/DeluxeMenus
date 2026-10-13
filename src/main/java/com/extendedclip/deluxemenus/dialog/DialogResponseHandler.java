package com.extendedclip.deluxemenus.dialog;

import com.extendedclip.deluxemenus.DeluxeMenus;
import com.extendedclip.deluxemenus.action.ActionType;
import com.extendedclip.deluxemenus.action.ClickAction;
import com.extendedclip.deluxemenus.action.ClickActionTask;
import com.extendedclip.deluxemenus.menu.MenuHolder;
import com.extendedclip.deluxemenus.utils.DebugLevel;
import com.extendedclip.deluxemenus.utils.Messages;
import io.papermc.paper.dialog.DialogResponseView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Turns a dialog button press into DeluxeMenus actions.
 *
 * <p>The handler is deliberately conservative because the response payload is ultimately
 * client-supplied data:</p>
 * <ul>
 *     <li>Paper hands out a distinct callback per button, so which button was pressed is known by
 *         construction. There is no index or button id from the client to trust.</li>
 *     <li>Every declared input is re-validated by {@link DialogInputValidator} against its
 *         configured type and constraints before any action runs.</li>
 *     <li>The definition's version token is checked against the live registry, so a response for a
 *         dialog that was reloaded or deleted in the meantime is rejected instead of silently
 *         running actions from a definition the server no longer serves.</li>
 *     <li>The response view is snapshotted before anything is scheduled, and actions always run on
 *         the main thread with a holder scoped to this single response.</li>
 * </ul>
 *
 * <p>Input values are exposed to actions through the ordinary DeluxeMenus argument syntax, so a
 * button action can use {@code {subject}} to interpolate what the player typed. They are never
 * registered globally and are dropped as soon as the response has been handled.</p>
 */
public final class DialogResponseHandler implements DialogRenderer.ButtonHandler {

    /**
     * Opens a dialog for a player, used when a submission has to be corrected or when a button links
     * to another dialog.
     */
    @FunctionalInterface
    public interface OpenHandler {

        /**
         * Opens a dialog for the given player.
         *
         * @param player the player
         * @param dialogId the dialog to show
         */
        void open(@NotNull Player player, @NotNull String dialogId);
    }

    /**
     * Re-renders and re-sends a dialog for a player.
     */
    @FunctionalInterface
    public interface RefreshHandler {

        /**
         * Refreshes a dialog the player is in.
         *
         * @param player the player
         * @param definition the definition that was rendered for them
         * @param placeholderPlayerId the player placeholders were resolved against, or {@code null}
         */
        void refresh(@NotNull Player player, @NotNull DialogDefinition definition,
                     @Nullable java.util.UUID placeholderPlayerId);
    }

    /**
     * An immutable copy of the values a player submitted.
     *
     * @param values the submitted values keyed by input id
     */
    private record ResponseSnapshot(@NotNull Map<String, Object> values) {

        /**
         * Reads every declared input out of the response.
         *
         * <p>This runs immediately on the callback thread so nothing depends on the lifetime of the
         * {@link DialogResponseView} afterwards.</p>
         *
         * @param definition the definition the response belongs to
         * @param response the raw response
         * @return the snapshot
         */
        static @NotNull ResponseSnapshot capture(final @NotNull DialogDefinition definition,
                                                 final @NotNull DialogResponseView response) {
            final Map<String, Object> values = new LinkedHashMap<>();

            for (final DialogDefinition.InputSpec spec : definition.inputs()) {
                switch (spec.type()) {
                    case TEXT, SINGLE_OPTION -> values.put(spec.id(), response.getText(spec.id()));
                    case BOOLEAN -> values.put(spec.id(), response.getBoolean(spec.id()));
                    case NUMBER_RANGE -> values.put(spec.id(), response.getFloat(spec.id()));
                }
            }

            return new ResponseSnapshot(values);
        }
    }

    private final DeluxeMenus plugin;
    private final DialogRegistry registry;
    private final DialogSessions sessions;
    private final OpenHandler openHandler;
    private final RefreshHandler refreshHandler;

    /**
     * Creates a response handler.
     *
     * @param plugin the owning plugin
     * @param registry the registry used for stale-response detection
     * @param sessions the refresh sessions, so a press can end the one it belongs to
     * @param openHandler opens a dialog when a submission needs correcting or a button links onward
     * @param refreshHandler re-renders the current dialog for a {@code [refresh]} action
     */
    public DialogResponseHandler(final @NotNull DeluxeMenus plugin, final @NotNull DialogRegistry registry,
                                 final @NotNull DialogSessions sessions, final @NotNull OpenHandler openHandler,
                                 final @NotNull RefreshHandler refreshHandler) {
        this.plugin = plugin;
        this.registry = registry;
        this.sessions = sessions;
        this.openHandler = openHandler;
        this.refreshHandler = refreshHandler;
    }

    @Override
    public void onButton(final @NotNull Player player, final @NotNull DialogDefinition definition,
                         final @NotNull DialogDefinition.ButtonSpec button,
                         final @NotNull DialogResponseView response) {
        // Read the payload now, while the view is still guaranteed to be valid.
        final ResponseSnapshot snapshot = ResponseSnapshot.capture(definition, response);

        // Paper does not guarantee which thread a dialog callback arrives on, so marshal the rest.
        if (Bukkit.isPrimaryThread()) {
            handle(player, definition, button, snapshot);
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> handle(player, definition, button, snapshot));
    }

    private void handle(final @NotNull Player player, final @NotNull DialogDefinition definition,
                        final @NotNull DialogDefinition.ButtonSpec button,
                        final @NotNull ResponseSnapshot snapshot) {
        if (!player.isOnline()) {
            return;
        }

        // A press is the only interaction a dialog reports, and it is the player's answer, so the
        // refresh loop stops here. The session is captured first because a [refresh] action has to
        // be able to re-establish it, including the player its placeholders were resolved against.
        final DialogSessions.Session previous = sessions.end(player.getUniqueId());

        if (!registry.isCurrent(definition.id(), definition.version())) {
            plugin.sms(player, Messages.DIALOG_RESPONSE_STALE.message()
                    .replaceText(replacer("<dialog>", definition.id())));
            plugin.debug(DebugLevel.HIGH, Level.INFO,
                    "Rejected a response for dialog '" + definition.id() + "' from " + player.getName()
                            + " because the dialog was reloaded or removed.");
            return;
        }

        if (!button.validateInputs()) {
            // A button that opts out of validation is treated as not submitting the form. Its
            // actions must not receive client supplied values at all, otherwise skipping validation
            // would just move the trust boundary.
            executeActions(player, definition, button, Map.of(), previous);
            return;
        }

        final DialogInputValidator.Result validation = DialogInputValidator.validate(definition, snapshot.values());

        if (!validation.isValid()) {
            player.sendMessage(Component.text("This form could not be submitted:", NamedTextColor.RED));

            for (final String problem : validation.problems()) {
                player.sendMessage(Component.text(" - " + problem, NamedTextColor.GRAY));
            }

            plugin.debug(DebugLevel.HIGH, Level.INFO,
                    "Rejected a submission for dialog '" + definition.id() + "' from " + player.getName()
                            + " because of " + validation.problems().size() + " validation problem(s).");

            // Hand back a freshly bound dialog so the player can correct the submission.
            openHandler.open(player, definition.id());
            return;
        }

        executeActions(player, definition, button, validation.typedArgs(), previous);
    }

    /**
     * Runs the button's action list in declaration order.
     *
     * <p>Actions are queued exactly the way menu item click actions are, so chance and delay behave
     * identically. Matching existing DeluxeMenus behaviour, one action failing does not abort the
     * rest of the list; each action reports its own problems.</p>
     *
     * <p>{@code open_dialog}, {@code refresh} and {@code close} are handled in-process and run
     * immediately, so {@code delay} and {@code chance} do not apply to them. {@code refresh} and
     * {@code close} act on the dialog the player is in rather than on an inventory.</p>
     *
     * @param player the player who pressed the button
     * @param definition the definition that was rendered
     * @param button the pressed button
     * @param typedArgs the validated input values, exposed to actions as {@code {key}}
     * @param previousSession the session that was live when the press arrived, or {@code null}
     */
    private void executeActions(final @NotNull Player player, final @NotNull DialogDefinition definition,
                                final @NotNull DialogDefinition.ButtonSpec button,
                                final @NotNull Map<String, String> typedArgs,
                                final @Nullable DialogSessions.Session previousSession) {
        final MenuHolder holder = new MenuHolder(plugin, player);

        if (!typedArgs.isEmpty()) {
            holder.setTypedArgs(typedArgs);
            holder.parsePlaceholdersInArguments(true);
            holder.parsePlaceholdersAfterArguments(true);
        }

        for (final DialogDefinition.ActionSpec action : button.actions()) {
            if (action.kind() == DialogDefinition.ActionSpec.Kind.OPEN_DIALOG) {
                // Routed through the normal open pipeline, so the linked dialog is still subject to
                // its own permission and requirement checks.
                openHandler.open(player, action.executable());
                continue;
            }

            if (action.type() == null) {
                continue;
            }

            if (action.type() == ActionType.REFRESH) {
                // A dialog has no menu to refresh, so this re-renders and re-sends the dialog the
                // player is looking at. Handled here rather than by ClickActionTask, which would
                // find no open menu and skip it.
                refreshHandler.refresh(player, definition,
                        previousSession == null ? null : previousSession.placeholderPlayerId());
                continue;
            }

            if (action.type() == ActionType.CLOSE) {
                // Closing a dialog is a client side operation; ClickActionTask would try to close a
                // menu inventory, which the player does not have open.
                sessions.end(player.getUniqueId());
                player.closeDialog();
                continue;
            }

            final ClickAction clickAction = new ClickAction(action.type(), action.executable());

            if (action.delay() != null) {
                clickAction.setDelay(action.delay());
            }

            if (action.chance() != null) {
                clickAction.setChance(action.chance());
            }

            if (!clickAction.checkChance(holder)) {
                continue;
            }

            final ClickActionTask task = new ClickActionTask(plugin, player.getUniqueId(), clickAction.getType(),
                    clickAction.getExecutable(), holder.getTypedArgs(), true, true);

            if (clickAction.hasDelay()) {
                task.runTaskLater(plugin, clickAction.getDelay(holder));
                continue;
            }

            task.runTask(plugin);
        }
    }

    private static @NotNull TextReplacementConfig replacer(final @NotNull String token, final @NotNull String value) {
        return TextReplacementConfig.builder().matchLiteral(token).replacement(value).build();
    }
}
