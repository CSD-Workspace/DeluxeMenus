package com.extendedclip.deluxemenus.dialog;

import com.extendedclip.deluxemenus.action.ActionType;
import com.extendedclip.deluxemenus.requirement.RequirementList;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers {@code plugins/DeluxeMenus/gui_dialogs/*.yml} files and turns them into validated
 * {@link DialogDefinition}s.
 *
 * <p>The loader is deliberately free of Paper dialog types and of the owning plugin, so the whole
 * parse and validate path can be unit tested without a running server. Requirement resolution is
 * injected through {@link RequirementLoader} because that is the one part which genuinely needs a
 * live plugin (Vault, item hooks, meta storage).</p>
 *
 * <p>Parsing never throws on bad input: every problem is recorded as a {@link DialogDiagnostic} and
 * the loader carries on, so one broken file cannot stop the others from loading.</p>
 */
public final class DialogLoader {

    /**
     * The directory, inside the plugin data folder, that holds dialog files.
     */
    public static final String DIALOG_DIRECTORY_NAME = "gui_dialogs";

    /**
     * The file extension dialog files must use.
     */
    public static final String FILE_EXTENSION = ".yml";

    /**
     * The example dialogs shipped inside the jar.
     *
     * <p>They are written into {@link #DIALOG_DIRECTORY_NAME} on a fresh install, the same way the
     * bundled example menus are written into {@code gui_menus}, so a new server has working
     * definitions to look at instead of an empty directory.</p>
     *
     * <p>In the jar these live under a {@value #DIALOG_DIRECTORY_NAME} folder, which is what makes
     * {@code JavaPlugin#saveResource} place them beside the user's own dialogs rather than in the
     * plugin data folder root.</p>
     */
    public static final List<String> SHIPPED_EXAMPLE_FILES = List.of(
            "warp_confirm.yml",
            "welcome_notice.yml",
            "server_selector.yml",
            "feedback_form.yml",
            "command_example.yml",
            "live_stats.yml"
    );

    /**
     * Builds the jar path of a shipped example dialog.
     *
     * @param fileName one of {@link #SHIPPED_EXAMPLE_FILES}
     * @return the resource path, for example {@code gui_dialogs/warp_confirm.yml}
     */
    public static @NotNull String exampleResourcePath(final @NotNull String fileName) {
        return DIALOG_DIRECTORY_NAME + "/" + fileName;
    }

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * The largest accepted value, in seconds, for either refresh option.
     *
     * <p>Guards against a typo turning into a refresh loop that outlives the server.</p>
     */
    public static final int MAX_REFRESH_INTERVAL_SECONDS = 86_400;

    /**
     * The command name pattern {@code open_command} entries must match.
     *
     * <p>Bukkit command names are matched lower case, so anything else is rejected rather than
     * silently lower cased, which would leave the administrator with a command that does not answer
     * to the name they wrote.</p>
     */
    private static final Pattern VALID_COMMAND = Pattern.compile("[a-z0-9_-]+");

    /**
     * Command names a dialog may never claim.
     *
     * <p>{@code /deluxemenus} and its aliases are registered through Paper's lifecycle command
     * registrar, which runs after {@code onEnable} finishes. They are therefore not yet present in
     * the command map while dialogs are being loaded, so they have to be reserved explicitly.</p>
     *
     * <p>Keep in sync with {@code DeluxeMenus#registerMainCommand()}.</p>
     */
    public static final List<String> RESERVED_COMMANDS = List.of("deluxemenus", "dm", "deluxemenu", "dmenu");

    /**
     * Extracts a {@code <delay=...>} suffix from an action string.
     *
     * <p>Identical to the pattern menu actions are parsed with. It is duplicated here rather than
     * imported from {@code DeluxeMenusConfig} so that dialog parsing pulls in no server bound
     * statics and stays unit testable without a running server.</p>
     */
    public static final Pattern DELAY_MATCHER = Pattern.compile("<delay=([^<>]+)>", Pattern.CASE_INSENSITIVE);

    /**
     * Extracts a {@code <chance=...>} suffix from an action string.
     *
     * @see #DELAY_MATCHER
     */
    public static final Pattern CHANCE_MATCHER = Pattern.compile("<chance=([^<>]+)>", Pattern.CASE_INSENSITIVE);

    private static final AtomicLong VERSION_COUNTER = new AtomicLong();

    private final RequirementLoader requirementLoader;

    /**
     * Creates a loader.
     *
     * @param requirementLoader resolves {@code open_requirements} blocks, or {@code null} to reject
     *                          every requirement block with a diagnostic
     */
    public DialogLoader(final @Nullable RequirementLoader requirementLoader) {
        this.requirementLoader = requirementLoader;
    }

    /**
     * A hook that turns an {@code open_requirements} block into a live requirement list.
     *
     * <p>The configuration passed in is an in-memory configuration laid out exactly like a menu's
     * {@code requirements} section under the top level key {@code open_requirements}, so the
     * existing menu requirement engine can be reused unchanged.</p>
     */
    @FunctionalInterface
    public interface RequirementLoader {

        /**
         * Resolves the requirements found at {@code open_requirements.requirements}.
         *
         * @param configuration the synthesized configuration
         * @return the resolved list, or {@code null} when nothing usable was declared
         */
        @Nullable RequirementList load(final @NotNull FileConfiguration configuration);
    }

    /**
     * The outcome of loading a directory of dialog files.
     *
     * @param definitions the dialogs that parsed and validated successfully, keyed by normalized id
     * @param diagnostics every problem found, in discovery order
     * @param filesRead how many {@code .yml} files were found
     * @param filesFailed how many files were rejected
     */
    public record LoadResult(
            @NotNull Map<String, DialogDefinition> definitions,
            @NotNull List<DialogDiagnostic> diagnostics,
            int filesRead,
            int filesFailed
    ) {

        /**
         * @return {@code true} when no file failed
         */
        public boolean allSucceeded() {
            return filesFailed == 0;
        }
    }

    /**
     * Loads every dialog file in a directory.
     *
     * <p>Files are processed in filename order so duplicate id reporting is deterministic and never
     * depends on filesystem iteration order.</p>
     *
     * @param directory the {@code gui_dialogs} directory
     * @param createIfMissing whether the directory should be created when absent
     * @return the load result; never {@code null}
     */
    public @NotNull LoadResult loadAll(final @NotNull File directory, final boolean createIfMissing) {
        final DialogDiagnostics diagnostics = new DialogDiagnostics();
        final Map<String, DialogDefinition> accepted = new LinkedHashMap<>();
        final Map<String, String> idOwners = new LinkedHashMap<>();

        if (!directory.isDirectory()) {
            if (createIfMissing && !directory.mkdirs() && !directory.isDirectory()) {
                diagnostics.error(directory.getName(), null, null,
                        "Could not create the dialog directory.",
                        "Check that the server can write to plugins/DeluxeMenus.");
            }

            if (!directory.isDirectory()) {
                return new LoadResult(accepted, diagnostics.all(), 0, 0);
            }
        }

        final File[] files = directory.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(FILE_EXTENSION));

        if (files == null || files.length == 0) {
            return new LoadResult(accepted, diagnostics.all(), 0, 0);
        }

        Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));

        int failed = 0;

        for (final File file : files) {
            final DialogDefinition definition = parseFile(file, diagnostics);

            if (definition == null) {
                failed++;
                continue;
            }

            final String key = DialogRegistry.normalize(definition.id());
            final String previousOwner = idOwners.get(key);

            if (previousOwner != null) {
                // Duplicate ids are rejected on the later file instead of silently overwriting, and
                // the definition from the earlier file is kept.
                diagnostics.error(file.getName(), definition.id(), "id",
                        "Duplicate dialog id '" + definition.id() + "', already defined in '" + previousOwner + "'.",
                        "Give each dialog a unique id; ids are compared case-insensitively.");
                failed++;
                continue;
            }

            idOwners.put(key, file.getName());
            accepted.put(key, definition);
        }

        return new LoadResult(accepted, diagnostics.all(), files.length, failed);
    }

    /**
     * Parses and validates a single dialog file.
     *
     * @param file the file to read
     * @param diagnostics the collector to report into
     * @return the parsed definition, or {@code null} when the file has errors
     */
    public @Nullable DialogDefinition parseFile(final @NotNull File file, final @NotNull DialogDiagnostics diagnostics) {
        final FileConfiguration configuration = new YamlConfiguration();

        try {
            configuration.load(file);
        } catch (final IOException exception) {
            diagnostics.error(file.getName(), null, null,
                    "Could not read the file: " + exception.getMessage(),
                    "Check file permissions and re-save the file as UTF-8.");
            return null;
        } catch (final InvalidConfigurationException exception) {
            diagnostics.error(file.getName(), null, null,
                    "The file is not valid YAML: " + exception.getMessage(),
                    "Check indentation and quoting; tabs are not valid YAML indentation.");
            return null;
        }

        return parse(configuration, file.getName(), diagnostics);
    }

    /**
     * Parses and validates an in-memory dialog definition.
     *
     * @param configuration the parsed YAML
     * @param sourceFile the file name used in diagnostics
     * @param diagnostics the collector to report into
     * @return the parsed definition, or {@code null} when the config has errors
     */
    public @Nullable DialogDefinition parse(final @NotNull FileConfiguration configuration,
                                            final @NotNull String sourceFile,
                                            final @NotNull DialogDiagnostics diagnostics) {
        final int errorsBefore = diagnostics.errors().size();

        final String id = readText(configuration, "id");

        if (id == null || id.isBlank()) {
            diagnostics.error(sourceFile, null, "id", "Missing required 'id'.",
                    "Add a stable id such as 'confirm_warp' at the top of the file.");
            return null;
        }

        if (!VALID_ID.matcher(id).matches()) {
            diagnostics.error(sourceFile, id, "id",
                    "Invalid dialog id '" + id + "'.",
                    "Ids may only contain letters, digits, '_' and '-'.");
            return null;
        }

        final String rawType = readText(configuration, "type");

        if (rawType == null || rawType.isBlank()) {
            diagnostics.error(sourceFile, id, "type", "Missing required 'type'.",
                    "Use one of: " + DialogType.listAll() + ".");
            return null;
        }

        final DialogType type = DialogType.getByName(rawType);

        if (type == null) {
            diagnostics.error(sourceFile, id, "type",
                    "Unknown dialog type '" + rawType + "'.",
                    "Use one of: " + DialogType.listAll() + ".");
            return null;
        }

        final String title = readText(configuration, "title");

        if (title == null || title.isBlank()) {
            diagnostics.error(sourceFile, id, "title", "Missing required 'title'.",
                    "Add a title such as 'title: \"&6Confirm\"'.");
            return null;
        }

        final String permission = readOptionalText(configuration, "permission", sourceFile, id, diagnostics);
        final String externalTitle = readOptionalText(configuration, "external_title", sourceFile, id, diagnostics);
        final List<String> commands = parseCommands(configuration, sourceFile, id, diagnostics);

        // Escape is not an option any more, because it is not a free choice: a refresh timer cannot
        // coexist with it (see DialogDefinition#canCloseWithEscape). The key is still recognised so
        // that a file written for an older build says what happened to it instead of failing silently.
        if (configuration.contains("options.can_close_with_escape")) {
            diagnostics.warning(sourceFile, id, "options.can_close_with_escape",
                    "'can_close_with_escape' is no longer an option and was ignored.",
                    "Escape follows 'refresh_interval' automatically: leave 'refresh_interval' out and "
                            + "escape closes the dialog, set it and escape is turned off because the "
                            + "timer would otherwise re-open a dialog the player had dismissed.");
        }

        final boolean pause = readBoolean(configuration, "options.pause", false, sourceFile, id, diagnostics);
        final int columns = readInt(configuration, "options.columns", 2, 1, 1024, sourceFile, id, diagnostics);

        // 0 disables refreshing. The upper bound keeps a typo from becoming a day-long refresh loop.
        // Setting this is what turns escape off for the dialog; DialogDefinition derives the two from
        // each other so they cannot drift apart.
        final int refreshIntervalSeconds = readInt(configuration, "options.refresh_interval", 0,
                0, MAX_REFRESH_INTERVAL_SECONDS, sourceFile, id, diagnostics);

        int refreshMaxSeconds = DialogDefinition.DEFAULT_REFRESH_MAX_SECONDS;

        if (refreshIntervalSeconds > 0 && configuration.contains("options.refresh_max_seconds")) {
            refreshMaxSeconds = readInt(configuration, "options.refresh_max_seconds",
                    DialogDefinition.DEFAULT_REFRESH_MAX_SECONDS, 1, MAX_REFRESH_INTERVAL_SECONDS,
                    sourceFile, id, diagnostics);
        }

        if (refreshIntervalSeconds > 0 && refreshMaxSeconds < refreshIntervalSeconds) {
            diagnostics.error(sourceFile, id, "options.refresh_max_seconds",
                    "refresh_max_seconds (" + refreshMaxSeconds + ") is shorter than refresh_interval ("
                            + refreshIntervalSeconds + "), so the dialog would never refresh.",
                    "Raise refresh_max_seconds, or lower refresh_interval.");
        }

        DialogDefinition.AfterAction afterAction = DialogDefinition.AfterAction.CLOSE;

        if (configuration.contains("options.after_action")) {
            final String rawAfterAction = readText(configuration, "options.after_action");
            final DialogDefinition.AfterAction parsed = DialogDefinition.AfterAction.getByName(rawAfterAction);

            if (parsed == null) {
                diagnostics.error(sourceFile, id, "options.after_action",
                        "Unknown after_action '" + rawAfterAction + "'.",
                        "Use one of: " + DialogDefinition.AfterAction.listAll() + ".");
            } else {
                afterAction = parsed;
            }
        }

        final List<DialogDefinition.BodySpec> body = parseBody(configuration, sourceFile, id, diagnostics);
        final List<DialogDefinition.InputSpec> inputs = parseInputs(configuration, sourceFile, id, type, diagnostics);
        // The exit button shares the button id namespace, so a collision with a grid button is caught.
        final Set<String> buttonIds = new LinkedHashSet<>();
        final List<DialogDefinition.ButtonSpec> buttons =
                parseButtons(configuration, sourceFile, id, buttonIds, diagnostics);
        final DialogDefinition.ButtonSpec exitButton =
                parseButton(configuration, "options.exit_button", sourceFile, id, buttonIds, diagnostics);

        validateButtonCount(type, buttons, exitButton, sourceFile, id, diagnostics);
        validateInputsAgainstType(type, inputs, configuration, sourceFile, id, diagnostics);
        warnAboutUnusedInputValidation(buttons, exitButton, inputs, sourceFile, id, diagnostics);
        warnAboutRefreshWithInputs(refreshIntervalSeconds, inputs, sourceFile, id, diagnostics);

        RequirementList requirements = null;
        final boolean requirementsDeclared = configuration.contains("open_requirements");

        if (requirementsDeclared) {
            requirements = parseRequirements(configuration, sourceFile, id, diagnostics);
        }

        if (diagnostics.errors().size() > errorsBefore) {
            return null;
        }

        return DialogDefinition.builder()
                .id(id)
                .type(type)
                .version(VERSION_COUNTER.incrementAndGet())
                .sourceFile(sourceFile)
                .title(title)
                .externalTitle(externalTitle)
                .permission(permission)
                .commands(commands)
                .pause(pause)
                .afterAction(afterAction)
                .columns(columns)
                .refreshIntervalSeconds(refreshIntervalSeconds)
                .refreshMaxSeconds(refreshMaxSeconds)
                .body(body)
                .inputs(inputs)
                .buttons(buttons)
                .exitButton(exitButton)
                .requirements(requirements)
                .requirementsDeclared(requirementsDeclared)
                .build();
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    /**
     * Parses {@code open_command}.
     *
     * <p>Accepts a single command name or a list where the first entry is the command and the rest
     * are aliases, matching how menus read {@code open_command}. Names are lower cased because that
     * is how Bukkit matches commands.</p>
     *
     * @param configuration the dialog configuration
     * @param sourceFile the source file name
     * @param id the dialog id
     * @param diagnostics the diagnostic collector
     * @return the declared command names, or an empty list when none are declared
     */
    private @NotNull List<String> parseCommands(final @NotNull FileConfiguration configuration,
                                                final @NotNull String sourceFile,
                                                final @NotNull String id,
                                                final @NotNull DialogDiagnostics diagnostics) {
        if (!configuration.contains("open_command")) {
            return List.of();
        }

        final Object raw = configuration.get("open_command");
        final List<String> declared = new ArrayList<>();

        if (raw instanceof String string) {
            declared.add(string);
        } else if (raw instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                final String command = scalarText(list.get(index));

                if (command == null) {
                    diagnostics.error(sourceFile, id, "open_command[" + index + "]",
                            "Each 'open_command' entry must be text.",
                            "For example open_command: [mydialog, md].");
                    continue;
                }

                declared.add(command);
            }
        } else {
            diagnostics.error(sourceFile, id, "open_command",
                    "'open_command' must be a command name or a list of command names.",
                    "For example open_command: mydialog, or a list whose first entry is the command "
                            + "and whose remaining entries are aliases.");
            return List.of();
        }

        final List<String> commands = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();

        for (int index = 0; index < declared.size(); index++) {
            final String path = declared.size() == 1 ? "open_command" : "open_command[" + index + "]";
            final String command = declared.get(index).trim().toLowerCase(Locale.ROOT);

            if (command.isEmpty()) {
                diagnostics.error(sourceFile, id, path, "Command name is empty.", null);
                continue;
            }

            if (!VALID_COMMAND.matcher(command).matches()) {
                diagnostics.error(sourceFile, id, path,
                        "Invalid command name '" + command + "'.",
                        "Command names may only contain lower case letters, digits, '_' and '-'.");
                continue;
            }

            if (RESERVED_COMMANDS.contains(command)) {
                diagnostics.error(sourceFile, id, path,
                        "The command '" + command + "' is reserved by DeluxeMenus.",
                        "Pick another name; '/" + command + "' is always the DeluxeMenus root command.");
                continue;
            }

            if (!seen.add(command)) {
                diagnostics.warning(sourceFile, id, path,
                        "Duplicate command '" + command + "' declared by this dialog; the extra entry is ignored.",
                        null);
                continue;
            }

            commands.add(command);
        }

        if (commands.isEmpty()) {
            diagnostics.error(sourceFile, id, "open_command",
                    "No usable command was declared.",
                    "Either remove the block or add a valid command name.");
        }

        return List.copyOf(commands);
    }

    // ------------------------------------------------------------------
    // Body
    // ------------------------------------------------------------------

    private @NotNull List<DialogDefinition.BodySpec> parseBody(final @NotNull FileConfiguration configuration,
                                                              final @NotNull String sourceFile,
                                                              final @NotNull String id,
                                                              final @NotNull DialogDiagnostics diagnostics) {
        final List<Map<String, Object>> entries =
                readEntryList(configuration, "body", sourceFile, id, diagnostics, "body");

        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        final List<DialogDefinition.BodySpec> body = new ArrayList<>();

        for (int index = 0; index < entries.size(); index++) {
            final Map<String, Object> entry = entries.get(index);
            final String path = "body[" + index + "]";
            final String rawType = mapText(entry, "type");

            if (rawType == null || rawType.isBlank()) {
                diagnostics.error(sourceFile, id, path + ".type", "Missing body element type.",
                        "The only supported body type is 'text'.");
                continue;
            }

            if (!rawType.trim().equalsIgnoreCase("text")) {
                diagnostics.error(sourceFile, id, path + ".type",
                        "Unsupported body type '" + rawType + "'.",
                        "The only supported body type is 'text'.");
                continue;
            }

            final String content = mapText(entry, "content");

            if (content == null || content.isEmpty()) {
                diagnostics.error(sourceFile, id, path + ".content", "Missing body 'content'.",
                        "Add the text to display, for example content: \"Hello\".");
                continue;
            }

            final Integer width = mapInt(entry, "width");

            if (width != null && (width < 1 || width > 1024)) {
                diagnostics.error(sourceFile, id, path + ".width",
                        "Body width " + width + " is outside Paper's allowed range.",
                        "Use a width between 1 and 1024.");
                continue;
            }

            body.add(new DialogDefinition.BodySpec(content, width == null ? 200 : width));
        }

        return body;
    }

    // ------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------

    private @NotNull List<DialogDefinition.InputSpec> parseInputs(final @NotNull FileConfiguration configuration,
                                                                 final @NotNull String sourceFile,
                                                                 final @NotNull String id,
                                                                 final @NotNull DialogType dialogType,
                                                                 final @NotNull DialogDiagnostics diagnostics) {
        final boolean declared = configuration.contains("inputs");
        final List<Map<String, Object>> entries =
                readEntryList(configuration, "inputs", sourceFile, id, diagnostics, "inputs");

        if (entries == null || entries.isEmpty()) {
            if (declared && dialogType != DialogType.FORM) {
                diagnostics.warning(sourceFile, id, "inputs",
                        "Dialog type '" + dialogType.configName() + "' does not use inputs; the block is ignored.",
                        "Inputs are only supported by type 'form'.");
            }
            return List.of();
        }

        if (!dialogType.supportsInputs()) {
            diagnostics.error(sourceFile, id, "inputs",
                    "Dialog type '" + dialogType.configName() + "' does not support inputs.",
                    "Use type 'form' if you need input controls.");
            return List.of();
        }

        final List<DialogDefinition.InputSpec> inputs = new ArrayList<>();
        final Set<String> seenIds = new LinkedHashSet<>();

        for (int index = 0; index < entries.size(); index++) {
            final DialogDefinition.InputSpec spec = parseInput(entries.get(index), "inputs[" + index + "]",
                    sourceFile, id, seenIds, diagnostics);

            if (spec != null) {
                inputs.add(spec);
            }
        }

        return inputs;
    }

    private @Nullable DialogDefinition.InputSpec parseInput(final @NotNull Map<String, Object> entry,
                                                            final @NotNull String path,
                                                            final @NotNull String sourceFile,
                                                            final @NotNull String id,
                                                            final @NotNull Set<String> seenIds,
                                                            final @NotNull DialogDiagnostics diagnostics) {
        final String inputId = mapText(entry, "id");

        if (inputId == null || inputId.isBlank()) {
            diagnostics.error(sourceFile, id, path + ".id", "Input is missing an 'id'.",
                    "Give the input a stable key such as 'subject'.");
            return null;
        }

        if (!VALID_ID.matcher(inputId).matches()) {
            diagnostics.error(sourceFile, id, path + ".id",
                    "Invalid input id '" + inputId + "'.",
                    "Input ids may only contain letters, digits, '_' and '-'.");
            return null;
        }

        if (!seenIds.add(DialogRegistry.normalize(inputId))) {
            diagnostics.error(sourceFile, id, path + ".id",
                    "Duplicate input id '" + inputId + "'.",
                    "Input ids must be unique within a dialog.");
            return null;
        }

        final String rawType = mapText(entry, "type");

        if (rawType == null || rawType.isBlank()) {
            diagnostics.error(sourceFile, id, path + ".type", "Input is missing a 'type'.",
                    "Use one of: " + DialogInputType.listAll() + ".");
            return null;
        }

        final DialogInputType inputType = DialogInputType.getByName(rawType);

        if (inputType == null) {
            diagnostics.error(sourceFile, id, path + ".type",
                    "Unknown input type '" + rawType + "'.",
                    "Use one of: " + DialogInputType.listAll() + ".");
            return null;
        }

        final String label = mapText(entry, "label");

        if (label == null || label.isEmpty()) {
            diagnostics.error(sourceFile, id, path + ".label", "Input is missing a 'label'.",
                    "Add the text shown next to the control.");
            return null;
        }

        final Integer widthValue = mapInt(entry, "width");

        if (widthValue != null && (widthValue < 1 || widthValue > 1024)) {
            diagnostics.error(sourceFile, id, path + ".width",
                    "Input width " + widthValue + " is outside Paper's allowed range.",
                    "Use a width between 1 and 1024.");
            return null;
        }

        final String initial = mapText(entry, "initial");
        final boolean labelVisible = mapBoolean(entry, "label_visible", true, sourceFile, id, path, diagnostics);
        final boolean required = mapBoolean(entry, "required", false, sourceFile, id, path, diagnostics);

        int maxLength = 32;
        boolean multiline = false;
        Integer maxLines = null;
        Integer height = null;
        String onTrue = "true";
        String onFalse = "false";
        List<DialogDefinition.InputOption> options = List.of();
        Float rangeStart = null;
        Float rangeEnd = null;
        Float rangeStep = null;
        String labelFormat = null;
        Integer minLength = null;

        switch (inputType) {
            case TEXT -> {
                if (entry.containsKey("max_length")) {
                    final Integer value = mapInt(entry, "max_length");

                    if (value == null || value < 1) {
                        diagnostics.error(sourceFile, id, path + ".max_length",
                                "max_length must be a whole number of at least 1.",
                                "For example max_length: 64.");
                        return null;
                    }

                    maxLength = value;
                }

                multiline = mapBoolean(entry, "multiline", false, sourceFile, id, path, diagnostics);

                if (entry.containsKey("max_lines")) {
                    final Integer value = mapInt(entry, "max_lines");

                    if (value == null || value < 1) {
                        diagnostics.error(sourceFile, id, path + ".max_lines",
                                "max_lines must be a whole number of at least 1.", null);
                        return null;
                    }

                    maxLines = value;
                }

                if (entry.containsKey("height")) {
                    final Integer value = mapInt(entry, "height");

                    if (value == null || value < 1 || value > 512) {
                        diagnostics.error(sourceFile, id, path + ".height",
                                "height must be between 1 and 512.", null);
                        return null;
                    }

                    height = value;
                }

                if (!multiline && (maxLines != null || height != null)) {
                    diagnostics.warning(sourceFile, id, path,
                            "max_lines/height are ignored unless multiline is true.",
                            "Set multiline: true to use them.");
                }

                if (entry.containsKey("min_length")) {
                    final Integer value = mapInt(entry, "min_length");

                    if (value == null || value < 0) {
                        diagnostics.error(sourceFile, id, path + ".min_length",
                                "min_length must be zero or greater.", null);
                        return null;
                    }

                    minLength = value;
                }
            }
            case BOOLEAN -> {
                if (initial != null && !initial.equalsIgnoreCase("true") && !initial.equalsIgnoreCase("false")) {
                    diagnostics.error(sourceFile, id, path + ".initial",
                            "Boolean inputs only accept 'true' or 'false' as initial value.",
                            "Use initial: false.");
                    return null;
                }

                final String readOnTrue = mapText(entry, "on_true");
                if (readOnTrue != null) {
                    onTrue = readOnTrue;
                }

                final String readOnFalse = mapText(entry, "on_false");
                if (readOnFalse != null) {
                    onFalse = readOnFalse;
                }
            }
            case SINGLE_OPTION -> {
                final List<Map<String, Object>> optionEntries =
                        readEntryList(entry, "options", path, sourceFile, id, diagnostics);

                if (optionEntries == null || optionEntries.isEmpty()) {
                    diagnostics.error(sourceFile, id, path + ".options",
                            "single_option inputs need at least one option.",
                            "Add an 'options' list with 'id' and 'label' entries.");
                    return null;
                }

                final List<DialogDefinition.InputOption> parsed = new ArrayList<>();
                final Set<String> optionIds = new LinkedHashSet<>();
                int initialCount = 0;

                for (int optionIndex = 0; optionIndex < optionEntries.size(); optionIndex++) {
                    final Map<String, Object> optionEntry = optionEntries.get(optionIndex);
                    final String optionPath = path + ".options[" + optionIndex + "]";

                    // 'value' is the value reported back on submission and is what the documented
                    // schema uses; 'id' is accepted as an alias because every other declared element
                    // in a dialog is keyed by id.
                    final String optionId = firstNonNull(mapText(optionEntry, "value"), mapText(optionEntry, "id"));

                    if (optionId == null || optionId.isBlank()) {
                        diagnostics.error(sourceFile, id, optionPath + ".value",
                                "Option is missing a 'value'.", "Add value: some_key.");
                        continue;
                    }

                    if (!optionIds.add(DialogRegistry.normalize(optionId))) {
                        diagnostics.error(sourceFile, id, optionPath + ".value",
                                "Duplicate option value '" + optionId + "'.", null);
                        continue;
                    }

                    final boolean optionInitial =
                            mapBoolean(optionEntry, "initial", false, sourceFile, id, optionPath, diagnostics);

                    if (optionInitial) {
                        initialCount++;
                    }

                    parsed.add(new DialogDefinition.InputOption(optionId, mapText(optionEntry, "label"), optionInitial));
                }

                if (parsed.isEmpty()) {
                    diagnostics.error(sourceFile, id, path + ".options", "No usable options were found.", null);
                    return null;
                }

                if (initialCount > 1) {
                    diagnostics.error(sourceFile, id, path + ".options",
                            "Only one single_option entry may be marked initial.", null);
                    return null;
                }

                options = List.copyOf(parsed);
            }
            case NUMBER_RANGE -> {
                final Float start = mapFloat(entry, "start");
                final Float end = mapFloat(entry, "end");

                if (start == null || end == null) {
                    diagnostics.error(sourceFile, id, path,
                            "number_range inputs need both 'start' and 'end'.",
                            "For example start: 0 and end: 10.");
                    return null;
                }

                if (start >= end) {
                    diagnostics.error(sourceFile, id, path,
                            "number_range 'start' must be smaller than 'end'.",
                            "For example start: 0 and end: 10.");
                    return null;
                }

                rangeStart = start;
                rangeEnd = end;

                final Float step = mapFloat(entry, "step");

                if (step != null && step <= 0) {
                    diagnostics.error(sourceFile, id, path + ".step",
                            "number_range 'step' must be greater than zero.", null);
                    return null;
                }

                rangeStep = step;

                if (initial != null) {
                    final Float parsedInitial = parseFloat(initial);

                    if (parsedInitial == null) {
                        diagnostics.error(sourceFile, id, path + ".initial",
                                "number_range 'initial' must be a number.", null);
                        return null;
                    }

                    if (parsedInitial < start || parsedInitial > end) {
                        diagnostics.error(sourceFile, id, path + ".initial",
                                "number_range 'initial' must be between 'start' and 'end'.", null);
                        return null;
                    }
                }

                final String readLabelFormat = mapText(entry, "label_format");
                if (readLabelFormat != null) {
                    labelFormat = readLabelFormat;
                }
            }
        }

        return new DialogDefinition.InputSpec(
                inputId,
                inputType,
                label,
                labelVisible,
                widthValue == null ? 200 : widthValue,
                initial,
                maxLength,
                multiline,
                maxLines,
                height,
                onTrue,
                onFalse,
                options,
                rangeStart,
                rangeEnd,
                rangeStep,
                labelFormat,
                required,
                minLength
        );
    }

    // ------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------

    private @NotNull List<DialogDefinition.ButtonSpec> parseButtons(final @NotNull FileConfiguration configuration,
                                                                   final @NotNull String sourceFile,
                                                                   final @NotNull String id,
                                                                   final @NotNull Set<String> seenIds,
                                                                   final @NotNull DialogDiagnostics diagnostics) {
        final List<Map<String, Object>> entries =
                readEntryList(configuration, "buttons", sourceFile, id, diagnostics, "buttons");

        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        final List<DialogDefinition.ButtonSpec> buttons = new ArrayList<>();

        for (int index = 0; index < entries.size(); index++) {
            final DialogDefinition.ButtonSpec button = parseButton(entries.get(index), "buttons[" + index + "]",
                    sourceFile, id, seenIds, diagnostics);

            if (button != null) {
                buttons.add(button);
            }
        }

        return buttons;
    }

    private @Nullable DialogDefinition.ButtonSpec parseButton(final @NotNull FileConfiguration configuration,
                                                             final @NotNull String path,
                                                             final @NotNull String sourceFile,
                                                             final @NotNull String id,
                                                             final @NotNull Set<String> seenIds,
                                                             final @NotNull DialogDiagnostics diagnostics) {
        if (!configuration.contains(path)) {
            return null;
        }

        final Map<String, Object> entry = asStringMap(configuration.get(path));

        if (entry == null) {
            diagnostics.error(sourceFile, id, path, "Expected a mapping of button options.", null);
            return null;
        }

        return parseButton(entry, path, sourceFile, id, seenIds, diagnostics);
    }

    private @Nullable DialogDefinition.ButtonSpec parseButton(final @NotNull Map<String, Object> entry,
                                                             final @NotNull String path,
                                                             final @NotNull String sourceFile,
                                                             final @NotNull String id,
                                                             final @NotNull Set<String> seenIds,
                                                             final @NotNull DialogDiagnostics diagnostics) {
        final String buttonId = mapText(entry, "id");

        if (buttonId == null || buttonId.isBlank()) {
            diagnostics.error(sourceFile, id, path + ".id", "Button is missing an 'id'.",
                    "Give the button a stable key such as 'confirm'.");
            return null;
        }

        if (!VALID_ID.matcher(buttonId).matches()) {
            diagnostics.error(sourceFile, id, path + ".id",
                    "Invalid button id '" + buttonId + "'.",
                    "Button ids may only contain letters, digits, '_' and '-'.");
            return null;
        }

        if (!seenIds.add(DialogRegistry.normalize(buttonId))) {
            diagnostics.error(sourceFile, id, path + ".id",
                    "Duplicate button id '" + buttonId + "'.",
                    "Button ids must be unique within a dialog.");
            return null;
        }

        final String label = mapText(entry, "label");

        if (label == null || label.isEmpty()) {
            diagnostics.error(sourceFile, id, path + ".label", "Button is missing a 'label'.",
                    "Add the text shown on the button.");
            return null;
        }

        final Integer widthValue = mapInt(entry, "width");

        if (widthValue != null && (widthValue < 1 || widthValue > 1024)) {
            diagnostics.error(sourceFile, id, path + ".width",
                    "Button width " + widthValue + " is outside Paper's allowed range.",
                    "Use a width between 1 and 1024.");
            return null;
        }

        final List<DialogDefinition.ActionSpec> actions = parseActions(entry, path, sourceFile, id, diagnostics);

        final boolean validateInputs =
                mapBoolean(entry, "validate_inputs", true, sourceFile, id, path, diagnostics);

        return new DialogDefinition.ButtonSpec(buttonId, label, mapText(entry, "tooltip"),
                widthValue == null ? 100 : widthValue, validateInputs, actions);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private @NotNull List<DialogDefinition.ActionSpec> parseActions(final @NotNull Map<String, Object> entry,
                                                                   final @NotNull String buttonPath,
                                                                   final @NotNull String sourceFile,
                                                                   final @NotNull String id,
                                                                   final @NotNull DialogDiagnostics diagnostics) {
        final Object raw = entry.get("actions");

        if (raw == null) {
            return List.of();
        }

        if (!(raw instanceof List<?> list)) {
            diagnostics.error(sourceFile, id, buttonPath + ".actions", "'actions' must be a list.", null);
            return List.of();
        }

        final List<DialogDefinition.ActionSpec> actions = new ArrayList<>();

        for (int index = 0; index < list.size(); index++) {
            final Object element = list.get(index);
            final String path = buttonPath + ".actions[" + index + "]";
            final DialogDefinition.ActionSpec action;

            if (element instanceof String string) {
                action = parseStringAction(string, path, sourceFile, id, diagnostics);
            } else {
                final Map<String, Object> map = asStringMap(element);

                if (map == null) {
                    diagnostics.error(sourceFile, id, path,
                            "Action must be either a string like '[player] spawn' or a mapping with a 'type'.",
                            null);
                    continue;
                }

                action = parseMapAction(map, path, sourceFile, id, diagnostics);
            }

            if (action == null) {
                continue;
            }

            actions.add(action);
        }

        return List.copyOf(actions);
    }

    private @Nullable DialogDefinition.ActionSpec parseStringAction(final @NotNull String raw,
                                                                   final @NotNull String path,
                                                                   final @NotNull String sourceFile,
                                                                   final @NotNull String id,
                                                                   final @NotNull DialogDiagnostics diagnostics) {
        final String trimmed = raw.trim();

        if (trimmed.isEmpty()) {
            diagnostics.error(sourceFile, id, path, "Action string is empty.", null);
            return null;
        }

        // Reuse the exact parsing the menu subsystem uses, including <delay=..> and <chance=..>.
        final MenuStyleAction parsed = MenuStyleAction.parse(trimmed);

        if (parsed == null) {
            diagnostics.error(sourceFile, id, path,
                    "No known action type found at the start of '" + trimmed + "'.",
                    "Start the action with a type identifier such as '[player]' or '[message]'.");
            return null;
        }

        return DialogDefinition.ActionSpec.action(parsed.type(), parsed.executable(), parsed.delay(), parsed.chance());
    }

    private @Nullable DialogDefinition.ActionSpec parseMapAction(final @NotNull Map<String, Object> entry,
                                                                final @NotNull String path,
                                                                final @NotNull String sourceFile,
                                                                final @NotNull String id,
                                                                final @NotNull DialogDiagnostics diagnostics) {
        final String rawType = mapText(entry, "type");

        if (rawType == null || rawType.isBlank()) {
            diagnostics.error(sourceFile, id, path + ".type", "Action is missing a 'type'.",
                    "Use a type such as 'player_command' or 'open_dialog'.");
            return null;
        }

        final String normalized = rawType.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');

        if (normalized.equals("open_dialog")) {
            final String dialogId = firstNonNull(mapText(entry, "dialog"), mapText(entry, "id"), mapText(entry, "value"));

            if (dialogId == null || dialogId.isBlank()) {
                diagnostics.error(sourceFile, id, path + ".dialog",
                        "open_dialog action is missing the target 'dialog'.",
                        "Add dialog: some_other_dialog.");
                return null;
            }

            if (!VALID_ID.matcher(dialogId).matches()) {
                diagnostics.error(sourceFile, id, path + ".dialog",
                        "Invalid target dialog id '" + dialogId + "'.", null);
                return null;
            }

            if (DialogRegistry.normalize(dialogId).equals(DialogRegistry.normalize(id))) {
                diagnostics.error(sourceFile, id, path + ".dialog",
                        "A dialog cannot open itself.",
                        "Point the action at a different dialog.");
                return null;
            }

            return DialogDefinition.ActionSpec.openDialog(dialogId);
        }

        final ActionTypeMapping mapping = ActionTypeMapping.byConfigName(normalized);

        if (mapping == null) {
            diagnostics.error(sourceFile, id, path + ".type",
                    "Unknown action type '" + rawType + "'.",
                    "Use a config name such as 'player_command', 'console_command', 'message', 'open_menu', "
                            + "'close', 'open_dialog', or a '[type] payload' string.");
            return null;
        }

        final String executable = mapping.readPayload(entry);

        if (executable == null) {
            diagnostics.error(sourceFile, id, path,
                    "Action '" + rawType + "' needs the '" + mapping.payloadKey() + "' key.",
                    "For example " + mapping.payloadKey() + ": \"some value\".");
            return null;
        }

        return DialogDefinition.ActionSpec.action(mapping.type(), executable,
                mapText(entry, "delay"), mapText(entry, "chance"));
    }

    /**
     * A single action parsed from a menu style {@code [type] payload} string, mirroring what
     * {@code DeluxeMenusConfig#getClickHandler} produces.
     *
     * @param type the resolved action type
     * @param executable the payload with the identifier, delay and chance stripped
     * @param delay the raw delay expression, or {@code null}
     * @param chance the raw chance expression, or {@code null}
     */
    private record MenuStyleAction(@NotNull ActionType type, @NotNull String executable,
                                   @Nullable String delay, @Nullable String chance) {

        /**
         * Parses a full menu style action string.
         *
         * @param command the raw string
         * @return the parsed action, or {@code null} when the type is unknown
         */
        static @Nullable MenuStyleAction parse(final @NotNull String command) {
            final ActionType type = ActionType.getByStart(command);

            if (type == null) {
                return null;
            }

            String executable = command.replaceFirst(Pattern.quote(type.getIdentifier()), "");

            if (executable.startsWith(" ")) {
                executable = executable.trim();
            }

            String delay = null;
            String chance = null;

            final Matcher delayMatcher = DELAY_MATCHER.matcher(executable);

            if (delayMatcher.find()) {
                delay = delayMatcher.group(1);
                executable = executable.replaceFirst(Pattern.quote(delayMatcher.group()), "");
            }

            final Matcher chanceMatcher = CHANCE_MATCHER.matcher(executable);

            if (chanceMatcher.find()) {
                chance = chanceMatcher.group(1);
                executable = executable.replaceFirst(Pattern.quote(chanceMatcher.group()), "");
            }

            // Stripping <delay=..> and <chance=..> leaves the surrounding spaces behind. Unlike the
            // menu parser, dialogs trim the result so a trailing space cannot leak into a message or
            // a dispatched command.
            return new MenuStyleAction(type, executable.trim(), delay, chance);
        }
    }

    /**
     * Maps dialog YAML action names onto the plugin's canonical {@link ActionType}s.
     */
    private enum ActionTypeMapping {

        MESSAGE("message", ActionType.MESSAGE, "message"),
        PLAYER_COMMAND("player_command", ActionType.PLAYER, "command"),
        CONSOLE_COMMAND("console_command", ActionType.CONSOLE, "command"),
        COMMAND_EVENT("command_event", ActionType.PLAYER_COMMAND_EVENT, "command"),
        BROADCAST("broadcast", ActionType.BROADCAST, "message"),
        ACTION_BAR("actionbar", ActionType.ACTION_BAR, "message"),
        MINI_MESSAGE("minimessage", ActionType.MINI_MESSAGE, "message"),
        MINI_BROADCAST("minibroadcast", ActionType.MINI_BROADCAST, "message"),
        CHAT("chat", ActionType.CHAT, "message"),
        LOG("log", ActionType.LOG, "message"),
        JSON("json", ActionType.JSON_MESSAGE, "message"),
        JSON_BROADCAST("jsonbroadcast", ActionType.JSON_BROADCAST, "message"),
        OPEN_MENU("open_menu", ActionType.OPEN_GUI_MENU, "menu"),
        OPEN_GUI_MENU("openguimenu", ActionType.OPEN_GUI_MENU, "menu"),
        CLOSE("close", ActionType.CLOSE, null),
        REFRESH("refresh", ActionType.REFRESH, null),
        SOUND("sound", ActionType.PLAY_SOUND, "sound"),
        RAW_SOUND("rawsound", ActionType.PLAY_RAW_SOUND, "sound"),
        BROADCAST_SOUND("broadcastsound", ActionType.BROADCAST_SOUND, "sound"),
        BROADCAST_RAW_SOUND("broadcastrawsound", ActionType.BROADCAST_RAW_SOUND, "sound"),
        BROADCAST_WORLD_SOUND("broadcastsoundworld", ActionType.BROADCAST_WORLD_SOUND, "sound"),
        BROADCAST_WORLD_RAW_SOUND("broadcastrawsoundworld", ActionType.BROADCAST_WORLD_RAW_SOUND, "sound"),
        CONNECT("connect", ActionType.CONNECT, "server"),
        TAKE_MONEY("takemoney", ActionType.TAKE_MONEY, "amount"),
        GIVE_MONEY("givemoney", ActionType.GIVE_MONEY, "amount"),
        TAKE_EXP("takeexp", ActionType.TAKE_EXP, "amount"),
        GIVE_EXP("giveexp", ActionType.GIVE_EXP, "amount"),
        GIVE_PERMISSION("givepermission", ActionType.GIVE_PERM, "permission"),
        TAKE_PERMISSION("takepermission", ActionType.TAKE_PERM, "permission"),
        META("meta", ActionType.META, "meta"),
        PLACEHOLDER("placeholder", ActionType.PLACEHOLDER, "placeholder"),
        EPHEMERAL_COOLDOWN("ephemeralcooldown", ActionType.SET_EPHEMERAL_COOLDOWN, "cooldown");

        private final String configName;
        private final ActionType type;
        private final String payloadKey;

        ActionTypeMapping(final @NotNull String configName, final @NotNull ActionType type,
                          final @Nullable String payloadKey) {
            this.configName = configName;
            this.type = type;
            this.payloadKey = payloadKey;
        }

        /**
         * @return the canonical DeluxeMenus action type
         */
        ActionType type() {
            return type;
        }

        /**
         * @return the YAML key holding the action payload, or {@code null} when the action takes none
         */
        @Nullable String payloadKey() {
            return payloadKey;
        }

        /**
         * Reads the payload for this action.
         *
         * @param entry the action mapping
         * @return the payload, the empty string for actions that take none, or {@code null} when a
         *         required payload is missing
         */
        @Nullable String readPayload(final @NotNull Map<String, Object> entry) {
            if (payloadKey == null) {
                return "";
            }

            return firstNonNull(mapText(entry, payloadKey), mapText(entry, "value"));
        }

        /**
         * Resolves a config action name.
         *
         * @param normalized the lower cased config name, with dashes and spaces already replaced
         * @return the mapping, or {@code null} when unknown
         */
        static @Nullable ActionTypeMapping byConfigName(final @NotNull String normalized) {
            final String canonical = switch (normalized) {
                case "player", "playercommand" -> "player_command";
                case "console", "consolecommand" -> "console_command";
                case "commandevent" -> "command_event";
                case "action_bar" -> "actionbar";
                case "mini_message" -> "minimessage";
                case "mini_broadcast" -> "minibroadcast";
                case "openmenu" -> "open_menu";
                case "sendtoserver", "send_to_server", "bungee", "bungeecord" -> "connect";
                case "play_sound" -> "sound";
                case "play_raw_sound" -> "rawsound";
                case "give_perm" -> "givepermission";
                case "take_perm" -> "takepermission";
                case "take_money" -> "takemoney";
                case "give_money" -> "givemoney";
                case "take_exp" -> "takeexp";
                case "give_exp" -> "giveexp";
                case "broadcast_json" -> "jsonbroadcast";
                case "set_ephemeral_cooldown" -> "ephemeralcooldown";
                case "message_broadcast" -> "broadcast";
                default -> normalized;
            };

            for (final ActionTypeMapping mapping : values()) {
                if (mapping.configName.equals(canonical)) {
                    return mapping;
                }
            }

            return null;
        }
    }

    // ------------------------------------------------------------------
    // Requirements
    // ------------------------------------------------------------------

    private @Nullable RequirementList parseRequirements(final @NotNull FileConfiguration configuration,
                                                        final @NotNull String sourceFile,
                                                        final @NotNull String id,
                                                        final @NotNull DialogDiagnostics diagnostics) {
        final List<Map<String, Object>> entries =
                readEntryList(configuration, "open_requirements", sourceFile, id, diagnostics, "open_requirements");

        if (entries == null || entries.isEmpty()) {
            diagnostics.error(sourceFile, id, "open_requirements",
                    "'open_requirements' must be a non-empty list of requirement mappings.",
                    "Either remove the block or add entries such as '- type: has permission'.");
            return null;
        }

        if (requirementLoader == null) {
            diagnostics.error(sourceFile, id, "open_requirements",
                    "Requirements are unavailable because the plugin configuration is not loaded.", null);
            return null;
        }

        // Reuse the menu requirement engine by reshaping the list into the section layout it expects.
        // A YamlConfiguration is used purely as an in-memory FileConfiguration; nothing is read from
        // or written to disk.
        final FileConfiguration memory = new YamlConfiguration();
        final ConfigurationSection requirements =
                memory.createSection("open_requirements").createSection("requirements");

        for (int index = 0; index < entries.size(); index++) {
            writeSection(requirements.createSection(String.valueOf(index)), entries.get(index));
        }

        final RequirementList requirementList = requirementLoader.load(memory);

        if (requirementList == null || requirementList.getRequirements().isEmpty()) {
            diagnostics.error(sourceFile, id, "open_requirements",
                    "No valid requirements could be parsed.",
                    "Check the 'type' values and their options; see the DeluxeMenus requirement documentation.");
            return null;
        }

        return requirementList;
    }

    private static void writeSection(final @NotNull ConfigurationSection target, final @NotNull Map<String, Object> source) {
        for (final Map.Entry<String, Object> entry : source.entrySet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();

            if (value instanceof ConfigurationSection section) {
                writeSection(target.createSection(key), new LinkedHashMap<>(section.getValues(false)));
                continue;
            }

            if (value instanceof Map<?, ?> nested) {
                final Map<String, Object> converted = asStringMap(nested);

                if (converted != null) {
                    writeSection(target.createSection(key), converted);
                }
                continue;
            }

            if (value instanceof List<?> list) {
                final boolean maps = !list.isEmpty() && list.stream().allMatch(element -> asStringMap(element) != null);

                if (maps) {
                    int index = 0;

                    for (final Object element : list) {
                        final Map<String, Object> converted = asStringMap(element);

                        if (converted != null) {
                            writeSection(target.createSection(key + "." + index), converted);
                        }

                        index++;
                    }
                } else {
                    target.set(key, list);
                }

                continue;
            }

            target.set(key, value);
        }
    }

    // ------------------------------------------------------------------
    // Cross field validation
    // ------------------------------------------------------------------

    private void validateButtonCount(final @NotNull DialogType type,
                                     final @NotNull List<DialogDefinition.ButtonSpec> buttons,
                                     final @Nullable DialogDefinition.ButtonSpec exitButton,
                                     final @NotNull String sourceFile,
                                     final @NotNull String id,
                                     final @NotNull DialogDiagnostics diagnostics) {
        if (type == DialogType.NOTICE || type == DialogType.CONFIRMATION) {
            if (exitButton != null) {
                diagnostics.warning(sourceFile, id, "options.exit_button",
                        "Dialog type '" + type.configName() + "' does not use an exit button; it is ignored.",
                        null);
            }

            final int count = buttons.size();

            if (count < type.minimumButtons() || count > type.maximumButtons()) {
                diagnostics.error(sourceFile, id, "buttons",
                        "Dialog type '" + type.configName() + "' needs exactly " + type.minimumButtons()
                                + (type.minimumButtons() == 1 ? " button" : " buttons") + " but " + count + " were given.",
                        describeButtonRequirement(type));
            }

            return;
        }

        if (buttons.isEmpty()) {
            diagnostics.error(sourceFile, id, "buttons",
                    "Dialog type '" + type.configName() + "' needs at least one button.",
                    "Add a 'buttons' list.");
            return;
        }

        final int count = buttons.size() + (exitButton != null ? 1 : 0);

        if (count > type.maximumButtons()) {
            diagnostics.error(sourceFile, id, "buttons",
                    "Dialog type '" + type.configName() + "' accepts at most " + type.maximumButtons()
                            + " buttons but " + count + " were given.",
                    null);
        }
    }

    private static @NotNull String describeButtonRequirement(final @NotNull DialogType type) {
        return switch (type) {
            case NOTICE -> "A notice dialog renders exactly one button.";
            case CONFIRMATION -> "A confirmation dialog renders exactly two buttons: the first is 'yes', the second is 'no'.";
            default -> "Add the required number of buttons.";
        };
    }

    private void validateInputsAgainstType(final @NotNull DialogType type,
                                           final @NotNull List<DialogDefinition.InputSpec> inputs,
                                           final @NotNull FileConfiguration configuration,
                                           final @NotNull String sourceFile,
                                           final @NotNull String id,
                                           final @NotNull DialogDiagnostics diagnostics) {
        if (!type.requiresInputs() || !inputs.isEmpty()) {
            return;
        }

        // When the block was declared, the per-input diagnostics already explain the failure.
        if (configuration.contains("inputs")) {
            return;
        }

        diagnostics.error(sourceFile, id, "inputs",
                "Dialog type 'form' needs at least one input.",
                "Add an 'inputs' list, or use type 'multi_action' for a button-only dialog.");
    }

    /**
     * Warns about buttons that will be rejected by their own dialog's required fields.
     *
     * <p>A form submits its values through whichever button is pressed, so a button that enables
     * validation but never reads an input is almost always a cancel or skip button. Without this
     * warning it fails at the worst possible moment: the player presses cancel, the required fields
     * are empty, and the dialog is rejected and re-opened with no obvious way out.</p>
     *
     * @param buttons the parsed buttons
     * @param exitButton the parsed exit button, or {@code null}
     * @param inputs the parsed inputs
     * @param sourceFile the source file name
     * @param id the dialog id
     * @param diagnostics the diagnostic collector
     */
    private void warnAboutUnusedInputValidation(final @NotNull List<DialogDefinition.ButtonSpec> buttons,
                                                final @Nullable DialogDefinition.ButtonSpec exitButton,
                                                final @NotNull List<DialogDefinition.InputSpec> inputs,
                                                final @NotNull String sourceFile,
                                                final @NotNull String id,
                                                final @NotNull DialogDiagnostics diagnostics) {
        if (inputs.stream().noneMatch(DialogDefinition.InputSpec::required)) {
            return;
        }

        final List<DialogDefinition.ButtonSpec> all = new ArrayList<>(buttons);

        if (exitButton != null) {
            all.add(exitButton);
        }

        for (final DialogDefinition.ButtonSpec button : all) {
            if (!button.validateInputs() || button.consumesInputs(inputs)) {
                continue;
            }

            diagnostics.warning(sourceFile, id, "buttons",
                    "Button '" + button.id() + "' enables input validation but none of its actions use "
                            + "an input, so pressing it fails the required fields and re-opens the dialog.",
                    "Set 'validate_inputs: false' on it if it is a cancel or skip button.");
        }
    }

    /**
     * Warns when a refreshing dialog also collects input.
     *
     * <p>Refreshing re-sends the dialog, which resets whatever the viewer had typed. That is fine for
     * a read-only display of live data and destructive for a form, so it is worth saying out loud
     * rather than letting an administrator discover it from a player report.</p>
     *
     * @param refreshIntervalSeconds the configured refresh interval
     * @param inputs the dialog's inputs
     * @param sourceFile the source file name
     * @param id the dialog id
     * @param diagnostics the diagnostic collector
     */
    private void warnAboutRefreshWithInputs(final int refreshIntervalSeconds,
                                            final @NotNull List<DialogDefinition.InputSpec> inputs,
                                            final @NotNull String sourceFile,
                                            final @NotNull String id,
                                            final @NotNull DialogDiagnostics diagnostics) {
        if (refreshIntervalSeconds <= 0 || inputs.isEmpty()) {
            return;
        }

        diagnostics.warning(sourceFile, id, "options.refresh_interval",
                "This dialog collects input and also refreshes every " + refreshIntervalSeconds
                        + "s. Refreshing re-sends the dialog, which clears anything the viewer has typed "
                        + "so far.",
                "Only refresh dialogs that display data. Remove refresh_interval for forms.");
    }

    // ------------------------------------------------------------------
    // Generic YAML helpers
    // ------------------------------------------------------------------

    private static @Nullable String readText(final @NotNull FileConfiguration configuration, final @NotNull String path) {
        if (!configuration.contains(path)) {
            return null;
        }

        return scalarText(configuration.get(path));
    }

    private static @Nullable String readOptionalText(final @NotNull FileConfiguration configuration,
                                                     final @NotNull String path,
                                                     final @NotNull String sourceFile,
                                                     final @NotNull String id,
                                                     final @NotNull DialogDiagnostics diagnostics) {
        if (!configuration.contains(path)) {
            return null;
        }

        final String value = scalarText(configuration.get(path));

        if (value == null) {
            diagnostics.warning(sourceFile, id, path, "Expected a text value; the entry is ignored.", null);
            return null;
        }

        return value;
    }

    private static boolean readBoolean(final @NotNull FileConfiguration configuration, final @NotNull String path,
                                       final boolean fallback, final @NotNull String sourceFile,
                                       final @NotNull String id, final @NotNull DialogDiagnostics diagnostics) {
        if (!configuration.contains(path)) {
            return fallback;
        }

        final Boolean value = coerceBoolean(configuration.get(path));

        if (value == null) {
            diagnostics.error(sourceFile, id, path, "Expected 'true' or 'false'.", null);
            return fallback;
        }

        return value;
    }

    private static int readInt(final @NotNull FileConfiguration configuration, final @NotNull String path,
                               final int fallback, final int minimum, final int maximum,
                               final @NotNull String sourceFile, final @NotNull String id,
                               final @NotNull DialogDiagnostics diagnostics) {
        if (!configuration.contains(path)) {
            return fallback;
        }

        final Object raw = configuration.get(path);

        if (!(raw instanceof Number number)) {
            diagnostics.error(sourceFile, id, path, "Expected a whole number.", null);
            return fallback;
        }

        final int value = number.intValue();

        if (value < minimum || value > maximum) {
            diagnostics.error(sourceFile, id, path,
                    "Value " + value + " is outside the allowed range " + minimum + ".." + maximum + ".", null);
            return fallback;
        }

        return value;
    }

    /**
     * Reads a list of mappings from a configuration key, reporting anything else.
     *
     * @param configuration the config to read from
     * @param path the key path
     * @param sourceFile the source file name
     * @param id the dialog id
     * @param diagnostics the diagnostic collector
     * @param label the human readable name of the block, used in messages
     * @return the list of mappings, or {@code null} when the key is absent or malformed
     */
    private static @Nullable List<Map<String, Object>> readEntryList(final @NotNull FileConfiguration configuration,
                                                                     final @NotNull String path,
                                                                     final @NotNull String sourceFile,
                                                                     final @NotNull String id,
                                                                     final @NotNull DialogDiagnostics diagnostics,
                                                                     final @NotNull String label) {
        if (!configuration.contains(path)) {
            return null;
        }

        return readEntries(configuration.get(path), path, sourceFile, id, diagnostics, label);
    }

    /**
     * Reads a nested list of mappings from a mapping entry.
     *
     * @param entry the parent mapping
     * @param key the key inside the mapping
     * @param path the key path used in diagnostics
     * @param sourceFile the source file name
     * @param id the dialog id
     * @param diagnostics the diagnostic collector
     * @return the list of mappings, or {@code null} when the key is absent or malformed
     */
    private static @Nullable List<Map<String, Object>> readEntryList(final @NotNull Map<String, Object> entry,
                                                                     final @NotNull String key,
                                                                     final @NotNull String path,
                                                                     final @NotNull String sourceFile,
                                                                     final @NotNull String id,
                                                                     final @NotNull DialogDiagnostics diagnostics) {
        if (!entry.containsKey(key)) {
            return null;
        }

        return readEntries(entry.get(key), path + "." + key, sourceFile, id, diagnostics, key);
    }

    private static @Nullable List<Map<String, Object>> readEntries(final @Nullable Object raw,
                                                                   final @NotNull String path,
                                                                   final @NotNull String sourceFile,
                                                                   final @NotNull String id,
                                                                   final @NotNull DialogDiagnostics diagnostics,
                                                                   final @NotNull String label) {
        if (!(raw instanceof List<?> list)) {
            diagnostics.error(sourceFile, id, path, "'" + label + "' must be a list.", null);
            return null;
        }

        final List<Map<String, Object>> entries = new ArrayList<>();

        for (int index = 0; index < list.size(); index++) {
            final Map<String, Object> map = asStringMap(list.get(index));

            if (map == null) {
                diagnostics.error(sourceFile, id, path + "[" + index + "]",
                        "Each '" + label + "' entry must be a mapping.", null);
                continue;
            }

            entries.add(map);
        }

        return entries;
    }

    /**
     * Normalizes a raw YAML value into a string-keyed map.
     *
     * <p>Bukkit returns raw {@link Map}s for list elements but wraps maps reached through a
     * {@link ConfigurationSection} in {@link MemorySection}s, so both shapes have to be accepted.</p>
     *
     * @param raw the raw value
     * @return the normalized map, or {@code null} when the value is not a mapping
     */
    private static @Nullable Map<String, Object> asStringMap(final @Nullable Object raw) {
        if (raw instanceof ConfigurationSection section) {
            return new LinkedHashMap<>(section.getValues(false));
        }

        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }

        final Map<String, Object> converted = new LinkedHashMap<>();

        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            converted.put(String.valueOf(entry.getKey()), entry.getValue());
        }

        return converted;
    }

    private static @Nullable String mapText(final @NotNull Map<String, Object> entry, final @NotNull String key) {
        return scalarText(entry.get(key));
    }

    private static boolean mapBoolean(final @NotNull Map<String, Object> entry, final @NotNull String key,
                                      final boolean fallback, final @NotNull String sourceFile,
                                      final @NotNull String id, final @NotNull String path,
                                      final @NotNull DialogDiagnostics diagnostics) {
        if (!entry.containsKey(key)) {
            return fallback;
        }

        final Boolean value = coerceBoolean(entry.get(key));

        if (value == null) {
            diagnostics.error(sourceFile, id, path + "." + key, "Expected 'true' or 'false'.", null);
            return fallback;
        }

        return value;
    }

    private static @Nullable Boolean coerceBoolean(final @Nullable Object raw) {
        if (raw instanceof Boolean bool) {
            return bool;
        }

        if (raw instanceof String string) {
            if (string.equalsIgnoreCase("true")) {
                return true;
            }
            if (string.equalsIgnoreCase("false")) {
                return false;
            }
        }

        return null;
    }

    private static @Nullable Integer mapInt(final @NotNull Map<String, Object> entry, final @NotNull String key) {
        final Object raw = entry.get(key);

        if (raw instanceof Number number) {
            return number.intValue();
        }

        if (raw instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (final NumberFormatException exception) {
                return null;
            }
        }

        return null;
    }

    private static @Nullable Float mapFloat(final @NotNull Map<String, Object> entry, final @NotNull String key) {
        final Object raw = entry.get(key);

        if (raw instanceof Number number) {
            return number.floatValue();
        }

        if (raw instanceof String string) {
            return parseFloat(string);
        }

        return null;
    }

    private static @Nullable Float parseFloat(final @NotNull String value) {
        try {
            return Float.parseFloat(value.trim());
        } catch (final NumberFormatException exception) {
            return null;
        }
    }

    private static @Nullable String scalarText(final @Nullable Object raw) {
        if (raw == null) {
            return null;
        }

        if (raw instanceof String string) {
            return string;
        }

        if (raw instanceof Number || raw instanceof Boolean || raw instanceof Character) {
            return String.valueOf(raw);
        }

        return null;
    }

    @SafeVarargs
    private static <T> @Nullable T firstNonNull(final @Nullable T... values) {
        for (final T value : values) {
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    /**
     * Logs diagnostics at a level chosen from their severity.
     *
     * @param diagnostics the diagnostics to log
     * @param logger the target logger
     * @param errorLevel the level used for errors; warnings always use {@link Level#WARNING}
     */
    public static void log(final @NotNull List<DialogDiagnostic> diagnostics, final @NotNull Logger logger,
                           final @NotNull Level errorLevel) {
        for (final DialogDiagnostic diagnostic : diagnostics) {
            logger.log(diagnostic.isError() ? errorLevel : Level.WARNING, diagnostic.format());
        }
    }
}
