package com.extendedclip.deluxemenus.dialog;

import com.extendedclip.deluxemenus.action.ActionType;
import com.extendedclip.deluxemenus.requirement.RequirementList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * An immutable, fully validated runtime dialog definition.
 *
 * <p>Instances are produced by {@link DialogLoader} and never mutated afterwards. Anything that
 * depends on the viewing player (title placeholders, resolved components, submitted input values)
 * is computed per open in {@link DialogRenderer} and {@link DialogService}; a definition must never
 * hold player specific state.</p>
 *
 * <p>The nested {@code Spec} records describe the declarative parts of a dialog. They mirror the
 * YAML schema documented in {@code docs/dialogs/CONFIGURATION.md} on purpose, so the renderer is a
 * thin translation layer to the Paper API.</p>
 */
public final class DialogDefinition {

    /**
     * How long a refreshed dialog keeps refreshing without any interaction, in seconds.
     *
     * <p>Chosen as a bound on a refresh loop, not as a feature: Minecraft never reports that a dialog
     * was dismissed, so without a limit a dialog the viewer had already closed with escape would be
     * re-sent indefinitely.</p>
     */
    public static final int DEFAULT_REFRESH_MAX_SECONDS = 300;

    /**
     * What Paper should do once the dialog is dismissed.
     */
    public enum AfterAction {
        /** Return to the previous screen. */
        CLOSE,
        /** Leave the current screen as-is. */
        NONE,
        /** Show a "waiting for response" screen. */
        WAIT_FOR_RESPONSE;

        /**
         * Resolves a config value to an after-action.
         *
         * @param name the raw value
         * @return the matching action, or {@code null} when unknown
         */
        public static @Nullable AfterAction getByName(final @Nullable String name) {
            if (name == null) {
                return null;
            }
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
            } catch (final IllegalArgumentException exception) {
                return null;
            }
        }

        /**
         * The config values this enum accepts, for use in diagnostics.
         *
         * @return a comma separated list of names
         */
        public static @NotNull String listAll() {
            return "close, none, wait_for_response";
        }
    }

    /**
     * A single body element.
     *
     * @param content the raw, unexpanded text (legacy {@code &} codes are honoured)
     * @param width the rendered width, in Paper's 1..1024 range
     */
    public record BodySpec(@NotNull String content, int width) {
    }

    /**
     * One selectable entry of a {@link DialogInputType#SINGLE_OPTION} input.
     *
     * @param id the value reported back on submission
     * @param label the raw, unexpanded display label
     * @param initial whether this entry starts selected
     */
    public record InputOption(@NotNull String id, @Nullable String label, boolean initial) {
    }

    /**
     * A declared form input control.
     *
     * <p>Fields that do not apply to {@link #type()} keep their neutral default; the loader rejects
     * fields that are meaningless for the selected type instead of silently ignoring them.</p>
     *
     * @param id the response key, unique within the dialog
     * @param type the control to render
     * @param label the raw, unexpanded label
     * @param labelVisible whether Paper should draw the label
     * @param width the rendered width, in Paper's 1..1024 range
     * @param initial the raw initial value (parsed per type; {@code null} means unset)
     * @param maxLength maximum text length, at least 1 (text only)
     * @param multiline whether the text field accepts multiple lines (text only)
     * @param maxLines maximum number of lines (multiline text only)
     * @param height the rendered height of a multiline field
     * @param onTrue template value used when a checkbox is true
     * @param onFalse template value used when a checkbox is false
     * @param options the selectable entries (single option only)
     * @param rangeStart the inclusive lower bound (number range only)
     * @param rangeEnd the inclusive upper bound (number range only)
     * @param rangeStep the slider step, when set (number range only)
     * @param labelFormat the label format string or translation key (number range only)
     * @param required whether the input must hold a value on submission
     * @param minLength the minimum accepted text length when set (text only)
     */
    public record InputSpec(
            @NotNull String id,
            @NotNull DialogInputType type,
            @NotNull String label,
            boolean labelVisible,
            int width,
            @Nullable String initial,
            int maxLength,
            boolean multiline,
            @Nullable Integer maxLines,
            @Nullable Integer height,
            @NotNull String onTrue,
            @NotNull String onFalse,
            @NotNull List<InputOption> options,
            @Nullable Float rangeStart,
            @Nullable Float rangeEnd,
            @Nullable Float rangeStep,
            @Nullable String labelFormat,
            boolean required,
            @Nullable Integer minLength
    ) {
    }

    /**
     * A single declared action on a button.
     *
     * <p>{@link Kind#ACTION} actions are executed through the existing DeluxeMenus action pipeline.
     * {@link Kind#OPEN_DIALOG} is handled by the dialog subsystem itself and keeps the executable
     * field as the target dialog id.</p>
     */
    public record ActionSpec(
            @NotNull Kind kind,
            @Nullable ActionType type,
            @NotNull String executable,
            @Nullable String delay,
            @Nullable String chance
    ) {

        /**
         * Which executor handles this action.
         */
        public enum Kind {
            /** A regular DeluxeMenus action. */
            ACTION,
            /** Open another dialog. */
            OPEN_DIALOG
        }

        /**
         * Creates a regular DeluxeMenus action.
         *
         * @param type the action type
         * @param executable the action payload
         * @param delay the raw delay expression, or {@code null}
         * @param chance the raw chance expression, or {@code null}
         * @return the action spec
         */
        public static @NotNull ActionSpec action(final @NotNull ActionType type, final @NotNull String executable,
                                                 final @Nullable String delay, final @Nullable String chance) {
            return new ActionSpec(Kind.ACTION, type, executable, delay, chance);
        }

        /**
         * Creates an action that opens another dialog.
         *
         * @param dialogId the target dialog id
         * @return the action spec
         */
        public static @NotNull ActionSpec openDialog(final @NotNull String dialogId) {
            return new ActionSpec(Kind.OPEN_DIALOG, null, dialogId, null, null);
        }
    }

    /**
     * A button on a dialog.
     *
     * @param id the stable response key, unique within the dialog
     * @param label the raw, unexpanded label
     * @param tooltip the raw, unexpanded tooltip, or {@code null}
     * @param width the rendered width, in Paper's 1..1024 range
     * @param validateInputs whether the submitted form values are validated before the actions run.
     *                       When {@code false} the button is treated as not submitting the form: no
     *                       validation runs and no input value is exposed to its actions, which is
     *                       what a cancel or skip button needs.
     * @param actions the ordered actions executed when the button is pressed
     */
    public record ButtonSpec(
            @NotNull String id,
            @NotNull String label,
            @Nullable String tooltip,
            int width,
            boolean validateInputs,
            @NotNull List<ActionSpec> actions
    ) {

        /**
         * Whether any of this button's actions reads one of the given inputs.
         *
         * <p>Used to warn about a button that will be rejected by its own dialog's required fields
         * without ever using them, which is almost always a cancel button that should set
         * {@code validate_inputs: false}.</p>
         *
         * @param inputs the dialog's inputs
         * @return {@code true} when an action references one of the input ids as {@code {id}}
         */
        public boolean consumesInputs(final @NotNull List<InputSpec> inputs) {
            for (final ActionSpec action : actions) {
                final String executable = action.executable();

                for (final InputSpec input : inputs) {
                    if (executable.contains("{" + input.id() + "}")) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    private final String id;
    private final DialogType type;
    private final long version;
    private final String sourceFile;
    private final String title;
    private final String externalTitle;
    private final String permission;
    private final List<String> commands;
    private final boolean canCloseWithEscape;
    private final boolean pause;
    private final AfterAction afterAction;
    private final int columns;
    private final int refreshIntervalSeconds;
    private final int refreshMaxSeconds;
    private final List<BodySpec> body;
    private final List<InputSpec> inputs;
    private final List<ButtonSpec> buttons;
    private final ButtonSpec exitButton;
    private final RequirementList requirements;
    private final boolean requirementsDeclared;

    private DialogDefinition(final @NotNull Builder builder) {
        this.id = builder.id;
        this.type = builder.type;
        this.version = builder.version;
        this.sourceFile = builder.sourceFile;
        this.title = builder.title;
        this.externalTitle = builder.externalTitle;
        this.permission = builder.permission;
        this.commands = List.copyOf(builder.commands);
        this.canCloseWithEscape = builder.canCloseWithEscape;
        this.pause = builder.pause;
        this.afterAction = builder.afterAction;
        this.columns = builder.columns;
        this.refreshIntervalSeconds = builder.refreshIntervalSeconds;
        this.refreshMaxSeconds = builder.refreshMaxSeconds;
        this.body = List.copyOf(builder.body);
        this.inputs = List.copyOf(builder.inputs);
        this.buttons = List.copyOf(builder.buttons);
        this.exitButton = builder.exitButton;
        this.requirements = builder.requirements;
        this.requirementsDeclared = builder.requirementsDeclared;
    }

    /**
     * The stable dialog id used by commands and cross references.
     *
     * @return the id
     */
    public @NotNull String id() {
        return id;
    }

    /**
     * The dialog kind.
     *
     * @return the type
     */
    public @NotNull DialogType type() {
        return type;
    }

    /**
     * A monotonically increasing token that changes every time this dialog is (re)loaded.
     *
     * <p>Response callbacks capture the token they were created with and are rejected once the
     * registry no longer serves that exact version, which keeps a stale click from running actions
     * from a definition that has since been replaced or removed.</p>
     *
     * @return the version token
     */
    public long version() {
        return version;
    }

    /**
     * The dialog file this definition came from, relative to {@code gui_dialogs}.
     *
     * @return the source file name
     */
    public @NotNull String sourceFile() {
        return sourceFile;
    }

    /**
     * The raw dialog title, still containing placeholders.
     *
     * @return the title
     */
    public @NotNull String title() {
        return title;
    }

    /**
     * The raw external title, still containing placeholders, or {@code null}.
     *
     * @return the external title
     */
    public @Nullable String externalTitle() {
        return externalTitle;
    }

    /**
     * The optional permission a viewer must hold to open this dialog.
     *
     * @return the permission node, or {@code null}
     */
    public @Nullable String permission() {
        return permission;
    }

    /**
     * The command names declared by {@code open_command}, lower cased.
     *
     * <p>The first entry is the command itself and the rest are aliases, mirroring how menu
     * {@code open_command} values are read. Empty when the dialog declares no command, in which case
     * it can only be opened through {@code /dm dialog open}.</p>
     *
     * <p>Declaring a command is what opts in to having it registered; unlike menus there is no
     * separate {@code register_command} flag, because there is no other reason to name a command.</p>
     *
     * @return an immutable list of command names
     */
    public @NotNull List<String> commands() {
        return commands;
    }

    /**
     * Whether the viewer may dismiss the dialog with the escape key.
     *
     * @return {@code true} when escape closes the dialog
     */
    public boolean canCloseWithEscape() {
        return canCloseWithEscape;
    }

    /**
     * Whether the dialog pauses the game (single player only).
     *
     * @return {@code true} when the dialog pauses
     */
    public boolean pause() {
        return pause;
    }

    /**
     * What Paper should do once the dialog is dismissed.
     *
     * @return the after action
     */
    public @NotNull AfterAction afterAction() {
        return afterAction;
    }

    /**
     * The number of columns used to lay out buttons.
     *
     * @return the column count
     */
    public int columns() {
        return columns;
    }

    /**
     * How often the dialog is re-rendered and re-sent to the viewer, in seconds.
     *
     * <p>{@code 0} means the dialog is rendered once when it is opened and never refreshed.</p>
     *
     * <p>A dialog cannot be updated in place, so a refresh re-sends a freshly rendered dialog. That
     * resets any input the viewer had typed and re-sends the whole screen.</p>
     *
     * @return the refresh interval in seconds, or {@code 0}
     */
    public int refreshIntervalSeconds() {
        return refreshIntervalSeconds;
    }

    /**
     * How long a viewer's dialog keeps refreshing without any interaction, in seconds.
     *
     * <p>Minecraft never tells the server that a dialog was dismissed, so this is the only bound on
     * how long a refresh loop can run. {@code 0} means no bound, which configuration validation
     * never produces.</p>
     *
     * @return the maximum session lifetime in seconds
     */
    public int refreshMaxSeconds() {
        return refreshMaxSeconds;
    }

    /**
     * Whether this dialog refreshes on a timer.
     *
     * @return {@code true} when a refresh interval is configured
     */
    public boolean refreshes() {
        return refreshIntervalSeconds > 0;
    }

    /**
     * The ordered body elements.
     *
     * @return an immutable list of body elements
     */
    public @NotNull List<BodySpec> body() {
        return body;
    }

    /**
     * The ordered form inputs; empty for non-form dialogs.
     *
     * @return an immutable list of inputs
     */
    public @NotNull List<InputSpec> inputs() {
        return inputs;
    }

    /**
     * The ordered buttons.
     *
     * @return an immutable list of buttons
     */
    public @NotNull List<ButtonSpec> buttons() {
        return buttons;
    }

    /**
     * The optional exit button shown alongside the main buttons, or {@code null}.
     *
     * @return the exit button
     */
    public @Nullable ButtonSpec exitButton() {
        return exitButton;
    }

    /**
     * The resolved open requirements, or {@code null} when the dialog declares none.
     *
     * @return the requirement list
     */
    public @Nullable RequirementList requirements() {
        return requirements;
    }

    /**
     * Whether the dialog declared an {@code open_requirements} block.
     *
     * <p>Distinguishes "no requirements configured" from "requirements configured but unusable",
     * which matters when deciding whether opening should fail closed.</p>
     *
     * @return {@code true} when the file declared requirements
     */
    public boolean requirementsDeclared() {
        return requirementsDeclared;
    }

    /**
     * Looks up an input by its response key.
     *
     * @param inputId the input id
     * @return the input, or {@code null} when the dialog has no such input
     */
    public @Nullable InputSpec input(final @Nullable String inputId) {
        if (inputId == null) {
            return null;
        }
        for (final InputSpec spec : inputs) {
            if (spec.id().equals(inputId)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * Looks up a button by its response key.
     *
     * @param buttonId the button id
     * @return the button, or {@code null} when the dialog has no such button
     */
    public @Nullable ButtonSpec button(final @Nullable String buttonId) {
        if (buttonId == null) {
            return null;
        }
        for (final ButtonSpec spec : buttons) {
            if (spec.id().equals(buttonId)) {
                return spec;
            }
        }
        if (exitButton != null && exitButton.id().equals(buttonId)) {
            return exitButton;
        }
        return null;
    }

    /**
     * @return a short, log friendly description of this dialog
     */
    @Override
    public String toString() {
        return "DialogDefinition{id='" + id + "', type=" + type + ", source='" + sourceFile + "', v" + version + '}';
    }

    /**
     * @return a new builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * A builder for {@link DialogDefinition}. Only {@link DialogLoader} is expected to use it, but it
     * is public so tests can construct definitions directly.
     */
    public static final class Builder {

        private String id;
        private DialogType type;
        private long version;
        private String sourceFile = "<test>";
        private String title;
        private String externalTitle;
        private String permission;
        private List<String> commands = List.of();
        private boolean canCloseWithEscape = true;
        private boolean pause = false;
        private AfterAction afterAction = AfterAction.CLOSE;
        private int columns = 2;
        private int refreshIntervalSeconds = 0;
        private int refreshMaxSeconds = DEFAULT_REFRESH_MAX_SECONDS;
        private List<BodySpec> body = List.of();
        private List<InputSpec> inputs = List.of();
        private List<ButtonSpec> buttons = List.of();
        private ButtonSpec exitButton;
        private RequirementList requirements;
        private boolean requirementsDeclared;

        /**
         * @param id the dialog id
         * @return this builder
         */
        public @NotNull Builder id(final @NotNull String id) {
            this.id = id;
            return this;
        }

        /**
         * @param type the dialog kind
         * @return this builder
         */
        public @NotNull Builder type(final @NotNull DialogType type) {
            this.type = type;
            return this;
        }

        /**
         * @param version the version token
         * @return this builder
         */
        public @NotNull Builder version(final long version) {
            this.version = version;
            return this;
        }

        /**
         * @param sourceFile the source file name
         * @return this builder
         */
        public @NotNull Builder sourceFile(final @NotNull String sourceFile) {
            this.sourceFile = sourceFile;
            return this;
        }

        /**
         * @param title the raw title
         * @return this builder
         */
        public @NotNull Builder title(final @NotNull String title) {
            this.title = title;
            return this;
        }

        /**
         * @param externalTitle the raw external title, or {@code null}
         * @return this builder
         */
        public @NotNull Builder externalTitle(final @Nullable String externalTitle) {
            this.externalTitle = externalTitle;
            return this;
        }

        /**
         * @param permission the required permission, or {@code null}
         * @return this builder
         */
        public @NotNull Builder permission(final @Nullable String permission) {
            this.permission = permission;
            return this;
        }

        /**
         * @param commands the declared command names, first is the name and the rest are aliases
         * @return this builder
         */
        public @NotNull Builder commands(final @NotNull List<String> commands) {
            this.commands = commands;
            return this;
        }

        /**
         * @param canCloseWithEscape whether escape closes the dialog
         * @return this builder
         */
        public @NotNull Builder canCloseWithEscape(final boolean canCloseWithEscape) {
            this.canCloseWithEscape = canCloseWithEscape;
            return this;
        }

        /**
         * @param pause whether the dialog pauses a single player game
         * @return this builder
         */
        public @NotNull Builder pause(final boolean pause) {
            this.pause = pause;
            return this;
        }

        /**
         * @param afterAction what Paper does once the dialog is dismissed
         * @return this builder
         */
        public @NotNull Builder afterAction(final @NotNull AfterAction afterAction) {
            this.afterAction = afterAction;
            return this;
        }

        /**
         * @param columns the button column count
         * @return this builder
         */
        public @NotNull Builder columns(final int columns) {
            this.columns = columns;
            return this;
        }

        /**
         * @param refreshIntervalSeconds how often to re-render, in seconds; {@code 0} disables refresh
         * @return this builder
         */
        public @NotNull Builder refreshIntervalSeconds(final int refreshIntervalSeconds) {
            this.refreshIntervalSeconds = refreshIntervalSeconds;
            return this;
        }

        /**
         * @param refreshMaxSeconds how long a session may keep refreshing without interaction
         * @return this builder
         */
        public @NotNull Builder refreshMaxSeconds(final int refreshMaxSeconds) {
            this.refreshMaxSeconds = refreshMaxSeconds;
            return this;
        }

        /**
         * @param body the body elements
         * @return this builder
         */
        public @NotNull Builder body(final @NotNull List<BodySpec> body) {
            this.body = body;
            return this;
        }

        /**
         * @param inputs the form inputs
         * @return this builder
         */
        public @NotNull Builder inputs(final @NotNull List<InputSpec> inputs) {
            this.inputs = inputs;
            return this;
        }

        /**
         * @param buttons the buttons
         * @return this builder
         */
        public @NotNull Builder buttons(final @NotNull List<ButtonSpec> buttons) {
            this.buttons = buttons;
            return this;
        }

        /**
         * @param exitButton the exit button, or {@code null}
         * @return this builder
         */
        public @NotNull Builder exitButton(final @Nullable ButtonSpec exitButton) {
            this.exitButton = exitButton;
            return this;
        }

        /**
         * @param requirements the resolved requirements, or {@code null}
         * @return this builder
         */
        public @NotNull Builder requirements(final @Nullable RequirementList requirements) {
            this.requirements = requirements;
            return this;
        }

        /**
         * @param requirementsDeclared whether the file declared an {@code open_requirements} block
         * @return this builder
         */
        public @NotNull Builder requirementsDeclared(final boolean requirementsDeclared) {
            this.requirementsDeclared = requirementsDeclared;
            return this;
        }

        /**
         * @return the built definition
         */
        public @NotNull DialogDefinition build() {
            return new DialogDefinition(this);
        }
    }
}
