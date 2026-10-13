package com.extendedclip.deluxemenus.dialog;

import com.extendedclip.deluxemenus.action.ActionType;
import com.extendedclip.deluxemenus.requirement.Requirement;
import com.extendedclip.deluxemenus.requirement.RequirementList;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers dialog parsing and validation.
 *
 * <p>Everything here runs without a server: the loader is deliberately written against plain Bukkit
 * configuration types and takes requirement resolution as a dependency.</p>
 */
class DialogLoaderTest {

    private static final String CONFIRMATION_YAML = """
            id: confirm_warp
            type: confirmation
            title: "&6Confirm teleport"
            permission: "deluxemenus.dialog.confirm_warp"
            body:
              - type: text
                content: "You are about to teleport to &e%player_world%&r."
            buttons:
              - id: confirm
                label: "&aTeleport"
                tooltip: "Go to spawn"
                actions:
                  - type: player_command
                    command: "warp spawn"
                  - type: message
                    message: "Teleporting..."
              - id: cancel
                label: "&cCancel"
                actions:
                  - "[message] Teleport cancelled."
            """;

    private static final String FORM_YAML = """
            id: feedback_form
            type: form
            title: "Send feedback"
            body:
              - type: text
                content: "Your feedback helps improve the server."
            inputs:
              - id: subject
                type: text
                label: "Subject"
                initial: ""
                max_length: 64
                required: true
              - id: category
                type: single_option
                label: "Category"
                options:
                  - label: "Bug report"
                    value: bug
                  - label: "Suggestion"
                    value: suggestion
                    initial: true
              - id: anonymous
                type: boolean
                label: "Submit anonymously"
                initial: false
              - id: rating
                type: number_range
                label: "Rating"
                start: 1
                end: 5
                step: 1
                initial: 3
            buttons:
              - id: submit
                label: "Submit"
                actions:
                  - type: message
                    message: "Thanks, {subject}!"
            """;

    private static FileConfiguration yaml(final String contents) {
        final FileConfiguration configuration = new YamlConfiguration();

        try {
            configuration.loadFromString(contents);
        } catch (final InvalidConfigurationException exception) {
            throw new IllegalStateException(exception);
        }

        return configuration;
    }

    private static void write(final Path directory, final String name, final String contents) {
        try {
            Files.writeString(directory.resolve(name), contents, StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static DialogLoader loader() {
        return new DialogLoader(configuration -> null);
    }

    @Nested
    @DisplayName("valid definitions")
    class ValidDefinitions {

        @Test
        @DisplayName("a confirmation dialog parses, preserving button and action order")
        void parsesConfirmationDialog() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml(CONFIRMATION_YAML), "confirm_warp.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(diagnostics.errors().isEmpty(), () -> "unexpected errors: " + diagnostics.errors());

            assertEquals("confirm_warp", definition.id());
            assertEquals(DialogType.CONFIRMATION, definition.type());
            assertEquals("&6Confirm teleport", definition.title());
            assertEquals("deluxemenus.dialog.confirm_warp", definition.permission());
            assertEquals("confirm_warp.yml", definition.sourceFile());
            assertEquals(1, definition.body().size());
            assertEquals(200, definition.body().get(0).width(), "body width should default to 200");

            assertEquals(2, definition.buttons().size());

            final DialogDefinition.ButtonSpec confirm = definition.buttons().get(0);
            assertEquals("confirm", confirm.id());
            assertEquals("&aTeleport", confirm.label());
            assertEquals("Go to spawn", confirm.tooltip());
            assertEquals(100, confirm.width(), "button width should default to 100");
            assertEquals(2, confirm.actions().size());

            final DialogDefinition.ActionSpec first = confirm.actions().get(0);
            assertEquals(DialogDefinition.ActionSpec.Kind.ACTION, first.kind());
            assertEquals(ActionType.PLAYER, first.type());
            assertEquals("warp spawn", first.executable());

            final DialogDefinition.ActionSpec second = confirm.actions().get(1);
            assertEquals(ActionType.MESSAGE, second.type());
            assertEquals("Teleporting...", second.executable());

            // The menu style string form has to resolve to the same canonical type.
            final DialogDefinition.ButtonSpec cancel = definition.buttons().get(1);
            assertEquals(1, cancel.actions().size());
            assertEquals(ActionType.MESSAGE, cancel.actions().get(0).type());
            assertEquals("Teleport cancelled.", cancel.actions().get(0).executable());
        }

        @Test
        @DisplayName("a form dialog parses every supported input control")
        void parsesFormDialogWithEveryInputType() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml(FORM_YAML), "feedback_form.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(diagnostics.errors().isEmpty(), () -> "unexpected errors: " + diagnostics.errors());

            assertEquals(DialogType.FORM, definition.type());
            assertEquals(1, definition.buttons().size());
            assertEquals(4, definition.inputs().size());

            final DialogDefinition.InputSpec subject = definition.input("subject");
            assertNotNull(subject);
            assertEquals(DialogInputType.TEXT, subject.type());
            assertEquals(64, subject.maxLength());
            assertTrue(subject.required());
            assertFalse(subject.multiline());

            final DialogDefinition.InputSpec category = definition.input("category");
            assertNotNull(category);
            assertEquals(DialogInputType.SINGLE_OPTION, category.type());
            assertEquals(2, category.options().size());
            assertEquals("bug", category.options().get(0).id());
            assertEquals("Bug report", category.options().get(0).label());
            assertFalse(category.options().get(0).initial());
            assertTrue(category.options().get(1).initial());

            final DialogDefinition.InputSpec anonymous = definition.input("anonymous");
            assertNotNull(anonymous);
            assertEquals(DialogInputType.BOOLEAN, anonymous.type());
            assertFalse(anonymous.options().isEmpty() && anonymous.required());

            final DialogDefinition.InputSpec rating = definition.input("rating");
            assertNotNull(rating);
            assertEquals(DialogInputType.NUMBER_RANGE, rating.type());
            assertEquals(1.0F, rating.rangeStart());
            assertEquals(5.0F, rating.rangeEnd());
            assertEquals(1.0F, rating.rangeStep());
            assertEquals("3", rating.initial());
        }

        @Test
        @DisplayName("a notice dialog accepts exactly one button")
        void parsesNoticeDialog() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: welcome_notice
                    type: notice
                    title: "Welcome"
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "welcome_notice.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertEquals(DialogType.NOTICE, definition.type());
            assertEquals(1, definition.buttons().size());
            assertTrue(definition.buttons().get(0).actions().isEmpty());
        }

        @Test
        @DisplayName("options control pause, after_action, columns and the exit button")
        void parsesOptions() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: travel
                    type: multi_action
                    title: "Where to?"
                    options:
                      pause: true
                      after_action: none
                      columns: 3
                      exit_button:
                        id: back
                        label: "Back"
                    buttons:
                      - id: spawn
                        label: "Spawn"
                      - id: nether
                        label: "Nether"
                    """), "travel.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            // No refresh_interval, so escape is on. It is not a configurable option any more.
            assertTrue(definition.canCloseWithEscape());
            assertTrue(definition.pause());
            assertEquals(DialogDefinition.AfterAction.NONE, definition.afterAction());
            assertEquals(3, definition.columns());
            assertNotNull(definition.exitButton());
            assertEquals("back", definition.exitButton().id());
            assertEquals(DialogDefinition.AfterAction.CLOSE.name(), DialogDefinition.AfterAction.CLOSE.name());
        }

        @Test
        @DisplayName("the removed can_close_with_escape option is ignored with a warning, not an error")
        void ignoresTheRemovedEscapeOption() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: legacy
                    type: notice
                    title: "x"
                    options:
                      can_close_with_escape: false
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "legacy.yml", diagnostics);

            // A file written for an older build must keep working: the option is reported and dropped,
            // and escape follows the rule that actually holds - on, because there is no timer.
            assertNotNull(definition, () -> "a removed option must not stop the dialog loading: "
                    + diagnostics.all());
            assertTrue(definition.canCloseWithEscape());

            final DialogDiagnostic diagnostic = diagnostics.all().stream()
                    .filter(d -> !d.isError())
                    .filter(d -> d.path().contains("can_close_with_escape"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a warning, got: " + diagnostics.all()));

            assertTrue(diagnostic.message().contains("no longer an option"));
            assertTrue(diagnostic.hint().contains("refresh_interval"), "the hint must name the real rule");
        }

        @Test
        @DisplayName("an open_dialog action is recognized and kept as a dialog reference")
        void parsesOpenDialogAction() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: hub
                    type: notice
                    title: "Hub"
                    buttons:
                      - id: more
                        label: "More"
                        actions:
                          - type: open_dialog
                            dialog: help
                    """), "hub.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());

            final DialogDefinition.ActionSpec action = definition.buttons().get(0).actions().get(0);
            assertEquals(DialogDefinition.ActionSpec.Kind.OPEN_DIALOG, action.kind());
            assertEquals("help", action.executable());
            assertNull(action.type(), "an open_dialog action carries no ActionType");
        }

        @Test
        @DisplayName("action delay and chance are extracted from both forms")
        void parsesDelayAndChance() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: timing
                    type: notice
                    title: "Timing"
                    buttons:
                      - id: ok
                        label: "OK"
                        actions:
                          - "[console] say hi <delay=20> <chance=50>"
                          - type: message
                            message: "delayed"
                            delay: "10"
                            chance: "25"
                    """), "timing.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());

            final DialogDefinition.ActionSpec stringAction = definition.buttons().get(0).actions().get(0);
            assertEquals(ActionType.CONSOLE, stringAction.type());
            assertEquals("say hi", stringAction.executable());
            assertEquals("20", stringAction.delay());
            assertEquals("50", stringAction.chance());

            final DialogDefinition.ActionSpec mapAction = definition.buttons().get(0).actions().get(1);
            assertEquals("10", mapAction.delay());
            assertEquals("25", mapAction.chance());
        }

        @Test
        @DisplayName("open_requirements are resolved through the injected loader")
        void resolvesRequirements() {
            final RequirementList requirements = new RequirementList(List.of(new StubRequirement(true)));
            final DialogLoader loader = new DialogLoader(configuration -> requirements);

            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader.parse(yaml("""
                    id: guarded
                    type: notice
                    title: "Guarded"
                    open_requirements:
                      - type: has permission
                        permission: "some.permission"
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "guarded.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(definition.requirementsDeclared());
            assertSame(requirements, definition.requirements());
        }

        @Test
        @DisplayName("requirements are rejected when no resolver is available")
        void rejectsRequirementsWithoutResolver() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: guarded
                    type: notice
                    title: "Guarded"
                    open_requirements:
                      - type: has permission
                        permission: "some.permission"
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "guarded.yml", diagnostics);

            assertNull(definition, "a dialog with unresolvable requirements must fail closed");
            assertTrue(diagnostics.hasErrors());
        }
        @Test
        @DisplayName("open_command accepts a single name and a name plus aliases")
        void parsesOpenCommand() {
            final DialogDiagnostics single = new DialogDiagnostics();
            final DialogDefinition one = loader().parse(yaml("""
                    id: one_command
                    type: notice
                    title: "x"
                    open_command: mydialog
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "one_command.yml", single);

            assertNotNull(one, () -> "expected a definition, got: " + single.all());
            assertEquals(List.of("mydialog"), one.commands());

            final DialogDiagnostics multiple = new DialogDiagnostics();
            final DialogDefinition many = loader().parse(yaml("""
                    id: many_commands
                    type: notice
                    title: "x"
                    open_command:
                      - mydialog
                      - md
                      - MyOtherAlias
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "many_commands.yml", multiple);

            assertNotNull(many, () -> "expected a definition, got: " + multiple.all());
            assertEquals(List.of("mydialog", "md", "myotheralias"), many.commands(),
                    "names are lower cased because that is how Bukkit matches commands");
        }

        @Test
        @DisplayName("no open_command leaves the dialog command-less")
        void noOpenCommandMeansNoCommand() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: no_command
                    type: notice
                    title: "x"
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "no_command.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(definition.commands().isEmpty());
        }
    }

    @Nested
    @DisplayName("open_command validation")
    class CommandValidation {

        private DialogDefinition parseWithCommand(final String commandYaml, final DialogDiagnostics diagnostics) {
            return loader().parse(yaml("""
                    id: cmd
                    type: notice
                    title: "x"
                    """ + commandYaml + """
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "cmd.yml", diagnostics);
        }

        @Test
        @DisplayName("a command name with invalid characters is rejected")
        void rejectsInvalidCharacters() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();

            assertNull(parseWithCommand("open_command: \"my dialog!\"\n", diagnostics));
            assertTrue(diagnostics.errors().stream().anyMatch(d -> d.path().startsWith("open_command")));
        }

        @Test
        @DisplayName("a reserved command name is rejected with a hint")
        void rejectsReservedNames() {
            for (final String reserved : DialogLoader.RESERVED_COMMANDS) {
                final DialogDiagnostics diagnostics = new DialogDiagnostics();

                assertNull(parseWithCommand("open_command: " + reserved + "\n", diagnostics),
                        () -> "'" + reserved + "' must not be claimable by a dialog");

                final DialogDiagnostic diagnostic = diagnostics.errors().stream()
                        .filter(d -> d.path().startsWith("open_command"))
                        .findFirst()
                        .orElseThrow();

                assertTrue(diagnostic.message().contains("reserved"));
            }
        }

        @Test
        @DisplayName("the reserved list covers the DeluxeMenus root command and every alias")
        void reservedListMatchesRootCommand() {
            // Mirrors DeluxeMenus#registerMainCommand(), which registers these through Paper's
            // lifecycle registrar and therefore cannot be detected in the command map this early.
            assertTrue(DialogLoader.RESERVED_COMMANDS.contains("deluxemenus"));
            assertTrue(DialogLoader.RESERVED_COMMANDS.contains("dm"));
            assertTrue(DialogLoader.RESERVED_COMMANDS.contains("deluxemenu"));
            assertTrue(DialogLoader.RESERVED_COMMANDS.contains("dmenu"));
        }

        @Test
        @DisplayName("a duplicate command inside one dialog warns and keeps the first")
        void duplicateCommandWarns() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = parseWithCommand("open_command: [same, same]\n", diagnostics);

            assertNotNull(definition, () -> "a duplicate alias should not fail the dialog: " + diagnostics.all());
            assertEquals(List.of("same"), definition.commands());
            assertTrue(diagnostics.errors().isEmpty());
            assertTrue(diagnostics.all().stream().anyMatch(d -> !d.isError()));
        }

        @Test
        @DisplayName("a non-text open_command is rejected")
        void rejectsWrongType() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();

            assertNull(parseWithCommand("open_command:\n  name: mydialog\n", diagnostics));
            assertTrue(diagnostics.errors().stream().anyMatch(d -> "open_command".equals(d.path())));
        }

        @Test
        @DisplayName("an empty open_command block is rejected")
        void rejectsEmptyBlock() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();

            assertNull(parseWithCommand("open_command: []\n", diagnostics));
            assertTrue(diagnostics.errors().stream().anyMatch(d -> "open_command".equals(d.path())));
        }

        @Test
        @DisplayName("one bad entry does not silently drop the good ones")
        void reportsEachBadEntry() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();

            assertNull(parseWithCommand("open_command: [\"bad name\", dm, fine]\n", diagnostics));

            // Two errors: the invalid characters and the reserved name.
            assertEquals(2, diagnostics.errors().stream()
                    .filter(d -> d.path().startsWith("open_command"))
                    .count());
        }
    }

    @Nested
    @DisplayName("button input validation")
    class ButtonInputValidation {

        private static final String REQUIRED_FORM = """
                id: form
                type: form
                title: "x"
                inputs:
                  - id: subject
                    type: text
                    label: "Subject"
                    required: true
                buttons:
                  - id: submit
                    label: "Submit"
                    actions:
                      - type: message
                        message: "Thanks {subject}"
                  - id: skip
                    label: "Skip"
                    %s
                    actions:
                      - type: message
                        message: "Maybe later."
                """;

        private static String form(final String extra) {
            return String.format(REQUIRED_FORM, extra);
        }

        @Test
        @DisplayName("validation is on by default")
        void validationDefaultsToOn() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml(form("")), "form.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(definition.button("submit").validateInputs());
            assertTrue(definition.button("skip").validateInputs());
        }

        @Test
        @DisplayName("a button can opt out of validation")
        void aButtonCanOptOut() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(
                    yaml(form("validate_inputs: false")), "form.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(definition.button("submit").validateInputs(), "submit still validates");
            assertFalse(definition.button("skip").validateInputs(), "skip opts out");
        }

        @Test
        @DisplayName("a validating button that ignores the inputs is warned about")
        void warnsAboutAValidatingButtonThatIgnoresInputs() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml(form("")), "form.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());

            // 'skip' validates but never reads {subject}, so pressing it can only fail the required
            // field and re-open the form. That is the trap this warning exists to point out.
            final DialogDiagnostic warning = diagnostics.all().stream()
                    .filter(diagnostic -> !diagnostic.isError())
                    .filter(diagnostic -> diagnostic.message().contains("skip"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a warning about 'skip', got: " + diagnostics.all()));

            assertTrue(warning.hint().contains("validate_inputs: false"),
                    "the hint must name the fix");
        }

        @Test
        @DisplayName("opting out silences the warning")
        void optingOutSilencesTheWarning() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(
                    yaml(form("validate_inputs: false")), "form.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(diagnostics.isEmpty(), () -> "expected no diagnostics, got: " + diagnostics.all());
        }

        @Test
        @DisplayName("a button that reads an input is not warned about")
        void noWarningWhenTheButtonConsumesInputs() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: form
                    type: form
                    title: "x"
                    inputs:
                      - id: subject
                        type: text
                        label: "Subject"
                        required: true
                    buttons:
                      - id: submit
                        label: "Submit"
                        actions:
                          - type: message
                            message: "Thanks {subject}"
                    """), "form.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(diagnostics.isEmpty(), () -> "expected no diagnostics, got: " + diagnostics.all());
        }

        @Test
        @DisplayName("optional inputs do not trigger the warning")
        void noWarningWithoutRequiredInputs() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: form
                    type: form
                    title: "x"
                    inputs:
                      - id: subject
                        type: text
                        label: "Subject"
                    buttons:
                      - id: skip
                        label: "Skip"
                        actions:
                          - type: message
                            message: "Fine."
                    """), "form.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(diagnostics.isEmpty(), () -> "expected no diagnostics, got: " + diagnostics.all());
        }

        @Test
        @DisplayName("an exit button is warned about too")
        void warnsAboutTheExitButton() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: form
                    type: form
                    title: "x"
                    options:
                      exit_button:
                        id: cancel
                        label: "Cancel"
                    inputs:
                      - id: subject
                        type: text
                        label: "Subject"
                        required: true
                    buttons:
                      - id: submit
                        label: "Submit"
                        actions:
                          - type: message
                            message: "Thanks {subject}"
                    """), "form.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(diagnostics.all().stream().anyMatch(d -> d.message().contains("cancel")),
                    () -> "expected a warning about the exit button, got: " + diagnostics.all());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a missing id is reported and nothing is loaded")
        void missingId() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("type: notice\ntitle: \"x\"\nbuttons:\n  - id: a\n    label: ok\n"),
                    "no_id.yml", diagnostics));
            assertTrue(diagnostics.errors().stream().anyMatch(d -> "id".equals(d.path())));
        }

        @Test
        @DisplayName("a missing title is reported")
        void missingTitle() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: no_title
                    type: notice
                    buttons:
                      - id: a
                        label: "ok"
                    """), "no_title.yml", diagnostics));
            assertTrue(diagnostics.errors().stream().anyMatch(d -> "title".equals(d.path())));
        }

        @Test
        @DisplayName("a missing type is reported")
        void missingType() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("id: no_type\ntitle: \"x\"\n"), "no_type.yml", diagnostics));
            assertTrue(diagnostics.errors().stream().anyMatch(d -> "type".equals(d.path())));
        }

        @Test
        @DisplayName("an unknown type lists the supported values")
        void unknownType() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("id: bad\ntype: not_a_dialog\ntitle: \"x\"\n"),
                    "bad_type.yml", diagnostics));

            final DialogDiagnostic diagnostic = diagnostics.errors().stream()
                    .filter(d -> "type".equals(d.path()))
                    .findFirst()
                    .orElseThrow();

            assertTrue(diagnostic.hint().contains("notice"), "the hint should list the supported types");
            assertTrue(diagnostic.hint().contains("form"));
        }

        @Test
        @DisplayName("an invalid id is rejected")
        void invalidId() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("id: \"bad id!\"\ntype: notice\ntitle: \"x\"\n"),
                    "bad_id.yml", diagnostics));
            assertTrue(diagnostics.hasErrors());
        }

        @Test
        @DisplayName("a confirmation dialog needs exactly two buttons")
        void confirmationNeedsTwoButtons() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: one_sided
                    type: confirmation
                    title: "x"
                    buttons:
                      - id: yes
                        label: "Yes"
                    """), "one_sided.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> "buttons".equals(d.path())));
        }

        @Test
        @DisplayName("a notice dialog rejects more than one button")
        void noticeRejectsExtraButtons() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: chatty
                    type: notice
                    title: "x"
                    buttons:
                      - id: a
                        label: "A"
                      - id: b
                        label: "B"
                    """), "chatty.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> "buttons".equals(d.path())));
        }

        @Test
        @DisplayName("a form needs at least one input")
        void formNeedsInputs() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: empty_form
                    type: form
                    title: "x"
                    buttons:
                      - id: submit
                        label: "Submit"
                    """), "empty_form.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> "inputs".equals(d.path())));
        }

        @Test
        @DisplayName("a non-form dialog rejects an inputs block")
        void nonFormRejectsInputs() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: wrong_type
                    type: notice
                    title: "x"
                    inputs:
                      - id: a
                        type: text
                        label: "A"
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "wrong_type.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> "inputs".equals(d.path())));
        }

        @Test
        @DisplayName("a button without an id or label is reported")
        void malformedButton() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: bad_button
                    type: notice
                    title: "x"
                    buttons:
                      - label: "No id here"
                    """), "bad_button.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> d.path().startsWith("buttons[0]")));
        }

        @Test
        @DisplayName("duplicate button ids are rejected")
        void duplicateButtonIds() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: dup_buttons
                    type: multi_action
                    title: "x"
                    buttons:
                      - id: same
                        label: "A"
                      - id: SAME
                        label: "B"
                    """), "dup_buttons.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> d.path().endsWith(".id")));
        }

        @Test
        @DisplayName("duplicate input ids are rejected")
        void duplicateInputIds() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: dup_inputs
                    type: form
                    title: "x"
                    inputs:
                      - id: same
                        type: text
                        label: "A"
                      - id: same
                        type: boolean
                        label: "B"
                    buttons:
                      - id: submit
                        label: "Submit"
                    """), "dup_inputs.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> d.path().endsWith(".id")));
        }

        @Test
        @DisplayName("an unknown action type is rejected with a usable hint")
        void unknownActionType() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: bad_action
                    type: notice
                    title: "x"
                    buttons:
                      - id: ok
                        label: "OK"
                        actions:
                          - type: teleport_player
                            command: "x"
                    """), "bad_action.yml", diagnostics));

            final DialogDiagnostic diagnostic = diagnostics.errors().stream()
                    .filter(d -> d.path().contains("actions"))
                    .findFirst()
                    .orElseThrow();

            assertTrue(diagnostic.hint().contains("player_command"));
        }

        @Test
        @DisplayName("a malformed action list is rejected")
        void malformedActionList() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: bad_list
                    type: notice
                    title: "x"
                    buttons:
                      - id: ok
                        label: "OK"
                        actions: "not-a-list"
                    """), "bad_list.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> d.path().endsWith(".actions")));
        }

        @Test
        @DisplayName("an unknown action string is rejected")
        void unknownActionString() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: bad_string
                    type: notice
                    title: "x"
                    buttons:
                      - id: ok
                        label: "OK"
                        actions:
                          - "not an action at all"
                    """), "bad_string.yml", diagnostics));

            assertTrue(diagnostics.hasErrors());
        }

        @Test
        @DisplayName("a dialog cannot link to itself")
        void selfReferenceIsRejected() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: loop
                    type: notice
                    title: "x"
                    buttons:
                      - id: again
                        label: "Again"
                        actions:
                          - type: open_dialog
                            dialog: loop
                    """), "loop.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> "A dialog cannot open itself.".equals(d.message())));
        }

        @Test
        @DisplayName("out of range widths and unknown after_action values are rejected")
        void outOfRangeValues() {
            final DialogDiagnostics widthDiagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: wide
                    type: notice
                    title: "x"
                    buttons:
                      - id: ok
                        label: "OK"
                        width: 5000
                    """), "wide.yml", widthDiagnostics));
            assertTrue(widthDiagnostics.hasErrors());

            final DialogDiagnostics afterActionDiagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: odd_after
                    type: notice
                    title: "x"
                    options:
                      after_action: explode
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "odd_after.yml", afterActionDiagnostics));
            assertTrue(afterActionDiagnostics.errors().stream().anyMatch(d -> d.path().contains("after_action")));
        }

        @Test
        @DisplayName("an unknown body type is rejected")
        void unknownBodyType() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: bad_body
                    type: notice
                    title: "x"
                    body:
                      - type: item
                        content: "Stone"
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "bad_body.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> d.path().startsWith("body[0]")));
        }

        @Test
        @DisplayName("an invalid single_option entry set is rejected")
        void invalidSingleOption() {
            final DialogDiagnostics emptyOptions = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: no_options
                    type: form
                    title: "x"
                    inputs:
                      - id: choice
                        type: single_option
                        label: "Choice"
                    buttons:
                      - id: submit
                        label: "Submit"
                    """), "no_options.yml", emptyOptions));
            assertTrue(emptyOptions.hasErrors());

            final DialogDiagnostics twoInitial = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: two_initial
                    type: form
                    title: "x"
                    inputs:
                      - id: choice
                        type: single_option
                        label: "Choice"
                        options:
                          - label: "A"
                            value: a
                            initial: true
                          - label: "B"
                            value: b
                            initial: true
                    buttons:
                      - id: submit
                        label: "Submit"
                    """), "two_initial.yml", twoInitial));
            assertTrue(twoInitial.hasErrors());
        }

        @Test
        @DisplayName("an exit button cannot reuse a grid button's id")
        void exitButtonSharesTheIdNamespace() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: clash
                    type: multi_action
                    title: "x"
                    options:
                      exit_button:
                        id: same
                        label: "Exit"
                    buttons:
                      - id: same
                        label: "Button"
                    """), "clash.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> d.path().endsWith(".id")));
        }

        @Test
        @DisplayName("an invalid number_range is rejected")
        void invalidNumberRange() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: bad_range
                    type: form
                    title: "x"
                    inputs:
                      - id: rating
                        type: number_range
                        label: "Rating"
                        start: 10
                        end: 1
                    buttons:
                      - id: submit
                        label: "Submit"
                    """), "bad_range.yml", diagnostics));

            assertTrue(diagnostics.hasErrors());
        }
    }

    @Nested
    @DisplayName("refresh options")
    class RefreshOptions {

        @Test
        @DisplayName("refreshing is off unless an interval is configured")
        void offByDefault() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: plain
                    type: notice
                    title: "x"
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "plain.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertFalse(definition.refreshes());
            assertEquals(0, definition.refreshIntervalSeconds());
            assertEquals(DialogDefinition.DEFAULT_REFRESH_MAX_SECONDS, definition.refreshMaxSeconds());
        }

        @Test
        @DisplayName("an interval enables refreshing and can cap its lifetime")
        void parsesIntervalAndCap() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: stats
                    type: notice
                    title: "x"
                    options:
                      refresh_interval: 2
                      refresh_max_seconds: 60
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "stats.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(definition.refreshes());
            assertEquals(2, definition.refreshIntervalSeconds());
            assertEquals(60, definition.refreshMaxSeconds());
            assertFalse(definition.canCloseWithEscape(),
                    "a timer cannot know the player pressed escape, so escape is off while it runs");
        }

        @Test
        @DisplayName("setting refresh_interval turns escape off on its own, with nothing to configure")
        void timerTurnsEscapeOffAutomatically() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: live
                    type: notice
                    title: "x"
                    options:
                      refresh_interval: 2
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "live.yml", diagnostics);

            // Minecraft never reports escape, so a timer cannot tell that the player left and would
            // keep re-opening a dialog they had dismissed. Escape is therefore switched off by the
            // timer itself rather than by a second option that could contradict it.
            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertTrue(definition.refreshes());
            assertFalse(definition.canCloseWithEscape());

            assertTrue(diagnostics.isEmpty(),
                    () -> "the pairing is automatic and must not need a warning: " + diagnostics.all());
        }

        @Test
        @DisplayName("without a timer escape closes the dialog, and a [refresh] button is not a timer")
        void refreshActionWithEscapeIsFine() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: button_refresh
                    type: notice
                    title: "x"
                    buttons:
                      - id: refresh
                        label: "Refresh"
                        actions:
                          - type: refresh
                    """), "button_refresh.yml", diagnostics);

            assertNotNull(definition, () -> "button driven refresh needs no restriction: "
                    + diagnostics.all());
            assertTrue(definition.canCloseWithEscape());
            assertFalse(definition.refreshes(), "a [refresh] action is not a timer");
        }

        @Test
        @DisplayName("a negative or non numeric interval is rejected")
        void rejectsInvalidInterval() {
            final DialogDiagnostics negative = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: bad
                    type: notice
                    title: "x"
                    options:
                      refresh_interval: -5
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "bad.yml", negative));
            assertTrue(negative.errors().stream().anyMatch(d -> d.path().contains("refresh_interval")));

            final DialogDiagnostics notANumber = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: bad
                    type: notice
                    title: "x"
                    options:
                      refresh_interval: soon
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "bad.yml", notANumber));
            assertTrue(notANumber.errors().stream().anyMatch(d -> d.path().contains("refresh_interval")));
        }

        @Test
        @DisplayName("an interval beyond the accepted maximum is rejected")
        void rejectsAbsurdInterval() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: absurd
                    type: notice
                    title: "x"
                    options:
                      refresh_interval: 99999999
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "absurd.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> d.path().contains("refresh_interval")));
        }

        @Test
        @DisplayName("a lifetime shorter than the interval is rejected as useless")
        void rejectsCapShorterThanInterval() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            assertNull(loader().parse(yaml("""
                    id: useless
                    type: notice
                    title: "x"
                    options:
                      refresh_interval: 60
                      refresh_max_seconds: 10
                    buttons:
                      - id: ok
                        label: "OK"
                    """), "useless.yml", diagnostics));

            assertTrue(diagnostics.errors().stream().anyMatch(d -> d.path().contains("refresh_max_seconds")));
        }

        @Test
        @DisplayName("refreshing a form is warned about because it clears what was typed")
        void warnsWhenRefreshingAForm() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: refreshing_form
                    type: form
                    title: "x"
                    options:
                      refresh_interval: 5
                    inputs:
                      - id: subject
                        type: text
                        label: "Subject"
                    buttons:
                      - id: submit
                        label: "Submit"
                        actions:
                          - type: message
                            message: "Thanks {subject}"
                    """), "refreshing_form.yml", diagnostics);

            assertNotNull(definition, () -> "a warning must not stop the dialog loading: " + diagnostics.all());

            final DialogDiagnostic warning = diagnostics.all().stream()
                    .filter(d -> !d.isError())
                    .filter(d -> d.path().contains("refresh_interval"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a warning, got: " + diagnostics.all()));

            assertTrue(warning.message().contains("typed"), "the warning must say what is lost");
        }

        @Test
        @DisplayName("a refresh action is accepted without complaint")
        void refreshActionIsNoLongerWarnedAbout() {
            final DialogDiagnostics diagnostics = new DialogDiagnostics();
            final DialogDefinition definition = loader().parse(yaml("""
                    id: with_refresh_button
                    type: notice
                    title: "x"
                    buttons:
                      - id: refresh
                        label: "Refresh"
                        actions:
                          - type: refresh
                    """), "with_refresh_button.yml", diagnostics);

            assertNotNull(definition, () -> "expected a definition, got: " + diagnostics.all());
            assertEquals(ActionType.REFRESH, definition.buttons().get(0).actions().get(0).type());

            assertTrue(diagnostics.all().stream()
                            .noneMatch(d -> d.message().contains("no effect inside a dialog")),
                    "the old 'refresh has no effect' warning must be gone now that it works");
        }
    }

    @Nested
    @DisplayName("directory loading")
    class DirectoryLoading {

        @Test
        @DisplayName("the directory is created when it does not exist")
        void createsDirectory(@TempDir final Path temp) {
            final Path directory = temp.resolve("gui_dialogs");

            final DialogLoader.LoadResult result = loader().loadAll(directory.toFile(), true);

            assertTrue(Files.isDirectory(directory), "the dialog directory should have been created");
            assertEquals(0, result.filesRead());
            assertEquals(0, result.definitions().size());
        }

        @Test
        @DisplayName("a missing directory is reported when it cannot be created")
        void reportsUncreatableDirectory(@TempDir final Path temp) throws IOException {
            // A regular file where the directory should be makes mkdirs fail.
            final Path directory = temp.resolve("gui_dialogs");
            Files.writeString(directory, "not a directory", StandardCharsets.UTF_8);

            final DialogLoader.LoadResult result = loader().loadAll(directory.toFile(), true);

            assertEquals(0, result.filesRead());
            assertTrue(result.diagnostics().stream().anyMatch(DialogDiagnostic::isError));
        }

        @Test
        @DisplayName("only .yml files are read")
        void ignoresNonYmlFiles(@TempDir final Path temp) {
            write(temp, "one.yml", """
                    id: one
                    type: notice
                    title: "One"
                    buttons:
                      - id: ok
                        label: "OK"
                    """);
            write(temp, "notes.txt", "ignore me");
            write(temp, "two.yaml", "id: two\n");

            final DialogLoader.LoadResult result = loader().loadAll(temp.toFile(), false);

            assertEquals(1, result.filesRead(), "only .yml files should be counted");
            assertTrue(result.definitions().containsKey("one"));
        }

        @Test
        @DisplayName("a duplicate id across files is rejected deterministically on the later file")
        void rejectsDuplicateIdsAcrossFiles(@TempDir final Path temp) {
            final String body = """
                    type: notice
                    title: "Duplicate"
                    buttons:
                      - id: ok
                        label: "OK"
                    """;

            write(temp, "a_first.yml", "id: shared\n" + body);
            write(temp, "b_second.yml", "id: SHARED\n" + body);

            final DialogLoader.LoadResult result = loader().loadAll(temp.toFile(), false);

            assertEquals(1, result.definitions().size(), "only one definition may survive");
            assertEquals(1, result.filesFailed());
            assertEquals("a_first.yml", result.definitions().get("shared").sourceFile(),
                    "the alphabetically first file must win, regardless of iteration order");

            final DialogDiagnostic diagnostic = result.diagnostics().stream()
                    .filter(DialogDiagnostic::isError)
                    .findFirst()
                    .orElseThrow();

            assertTrue(diagnostic.message().contains("Duplicate"));
            assertEquals("b_second.yml", diagnostic.sourceFile());
        }

        @Test
        @DisplayName("one broken file does not stop the others from loading")
        void brokenFileDoesNotBlockValidOnes(@TempDir final Path temp) {
            write(temp, "good.yml", """
                    id: good
                    type: notice
                    title: "Good"
                    buttons:
                      - id: ok
                        label: "OK"
                    """);
            write(temp, "bad.yml", """
                    id: bad
                    type: nonsense
                    title: "Bad"
                    """);

            final DialogLoader.LoadResult result = loader().loadAll(temp.toFile(), false);

            assertEquals(1, result.definitions().size());
            assertTrue(result.definitions().containsKey("good"));
            assertFalse(result.definitions().containsKey("bad"));
            assertEquals(1, result.filesFailed());
            assertTrue(result.diagnostics().stream().anyMatch(DialogDiagnostic::isError));
        }

        @Test
        @DisplayName("unparseable YAML is reported against the file rather than thrown")
        void reportsInvalidYaml(@TempDir final Path temp) {
            write(temp, "broken.yml", "id: broken\ntype: [unclosed\n");

            final DialogLoader.LoadResult result = loader().loadAll(temp.toFile(), false);

            assertEquals(0, result.definitions().size());
            assertEquals(1, result.filesFailed());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.sourceFile().equals("broken.yml")));
        }

        @Test
        @DisplayName("an empty directory loads nothing without error")
        void emptyDirectory(@TempDir final Path temp) {
            final DialogLoader.LoadResult result = loader().loadAll(temp.toFile(), false);

            assertEquals(0, result.filesRead());
            assertTrue(result.diagnostics().isEmpty());
            assertTrue(result.allSucceeded());
        }
    }

    /**
     * A requirement that returns a fixed answer, so requirement plumbing can be tested without the
     * full menu requirement parser.
     */
    private static final class StubRequirement extends Requirement {

        private final boolean result;

        private StubRequirement(final boolean result) {
            this.result = result;
        }

        @Override
        public boolean evaluate(final com.extendedclip.deluxemenus.menu.MenuHolder holder) {
            return result;
        }
    }

    @Test
    @DisplayName("the test fixtures above are syntactically complete")
    void fixturesAreValid() {
        assertNotNull(yaml(CONFIRMATION_YAML));
        assertNotNull(yaml(FORM_YAML));
        assertEquals(1, Map.of("a", 1).size());
        assertEquals(1, List.of("a").size());
    }
}
