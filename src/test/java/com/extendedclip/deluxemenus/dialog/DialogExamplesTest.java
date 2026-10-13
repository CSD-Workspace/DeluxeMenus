package com.extendedclip.deluxemenus.dialog;

import com.extendedclip.deluxemenus.requirement.Requirement;
import com.extendedclip.deluxemenus.requirement.RequirementList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the example dialogs that ship inside the jar and are installed on a fresh server.
 *
 * <p>This is the automated half of the "all examples validate" acceptance criterion: if a documented
 * example stops parsing - because a field was renamed or a constraint changed - the build fails.
 * Running against the real resources also means the test covers exactly what a server administrator
 * receives, not a copy.</p>
 */
class DialogExamplesTest {

    private static final Path EXAMPLES = Paths.get("src", "main", "resources", DialogLoader.DIALOG_DIRECTORY_NAME);

    private static DialogLoader.LoadResult result;

    @BeforeAll
    static void loadExamples() {
        assertTrue(Files.isDirectory(EXAMPLES),
                () -> "the example directory should exist at " + EXAMPLES.toAbsolutePath());

        final DialogLoader loader = new DialogLoader(configuration ->
                new RequirementList(List.of(new AlwaysPassRequirement())));

        result = loader.loadAll(EXAMPLES.toFile(), false);
    }

    @Test
    @DisplayName("every dialog listed for installation exists as a resource")
    void everyShippedNameHasAResource() {
        assertFalse(DialogLoader.SHIPPED_EXAMPLE_FILES.isEmpty(),
                "at least one example should be installed on a fresh server");

        for (final String fileName : DialogLoader.SHIPPED_EXAMPLE_FILES) {
            assertTrue(Files.isRegularFile(EXAMPLES.resolve(fileName)),
                    () -> "SHIPPED_EXAMPLE_FILES lists '" + fileName + "' but no such resource exists, "
                            + "so nothing would be installed for it");
        }
    }

    @Test
    @DisplayName("no example resource is left out of the installation list")
    void everyResourceIsListedForInstallation() throws IOException {
        final List<String> onDisk;

        try (var files = Files.list(EXAMPLES)) {
            onDisk = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(DialogLoader.FILE_EXTENSION))
                    .sorted()
                    .toList();
        }

        final List<String> declared = DialogLoader.SHIPPED_EXAMPLE_FILES.stream().sorted().toList();

        assertEquals(declared, onDisk,
                "every dialog shipped in the jar must be listed in SHIPPED_EXAMPLE_FILES, otherwise it "
                        + "would never reach an administrator's server");
    }

    @Test
    @DisplayName("the resource path helper points at the jar folder used by saveResource")
    void resourcePathMatchesTheJarLayout() {
        assertEquals("gui_dialogs/warp_confirm.yml",
                DialogLoader.exampleResourcePath("warp_confirm.yml"));
        assertTrue(DialogLoader.exampleResourcePath("x.yml")
                        .startsWith(DialogLoader.DIALOG_DIRECTORY_NAME + "/"),
                "examples must live under the dialog directory so saveResource writes them there");
    }

    @Test
    @DisplayName("the shipped example set is loaded in filename order")
    void examplesArePresent() {
        assertTrue(result.filesRead() >= 6, () -> "expected at least 6 example files, found " + result.filesRead());
        assertEquals(
                List.of("command_example", "feedback_form", "live_stats", "server_selector", "warp_confirm",
                        "welcome_notice"),
                List.copyOf(result.definitions().keySet()),
                "examples should load in filename order and cover every dialog type");
    }

    @Test
    @DisplayName("every example validates with no errors and no warnings")
    void everyExampleValidates() {
        final List<DialogDiagnostic> problems = result.diagnostics();

        assertTrue(problems.isEmpty(), () -> "examples produced diagnostics:\n"
                + problems.stream().map(DialogDiagnostic::format).reduce("", (a, b) -> a + "\n" + b));
        assertTrue(result.allSucceeded());
        assertEquals(0, result.filesFailed());
    }

    @Test
    @DisplayName("the confirmation example has exactly two buttons and both action forms")
    void confirmationExample() {
        final DialogDefinition definition = result.definitions().get("warp_confirm");

        assertNotNull(definition);
        assertEquals(DialogType.CONFIRMATION, definition.type());
        assertEquals(2, definition.buttons().size());
        assertTrue(definition.body().size() >= 1);
        assertTrue(definition.buttons().get(0).actions().size() >= 1);

        // The first button uses the mapping form, the second the string form; both must resolve.
        assertNotNull(definition.buttons().get(0).actions().get(0).type());
        assertNotNull(definition.buttons().get(1).actions().get(0).type());
    }

    @Test
    @DisplayName("the multi-action example uses an exit button and links to another dialog")
    void multiActionExample() {
        final DialogDefinition definition = result.definitions().get("server_selector");

        assertNotNull(definition);
        assertEquals(DialogType.MULTI_ACTION, definition.type());
        assertEquals(3, definition.columns());
        assertNotNull(definition.exitButton());
        assertEquals("close", definition.exitButton().id());
        assertEquals(3, definition.buttons().size());

        final DialogDefinition.ActionSpec link = definition.buttons().get(2).actions().get(0);
        assertEquals(DialogDefinition.ActionSpec.Kind.OPEN_DIALOG, link.kind());

        // The link target has to be part of the same example set, which is what the manager warns
        // about when a real server loads a broken reference.
        assertTrue(result.definitions().containsKey(DialogRegistry.normalize(link.executable())),
                () -> "open_dialog target '" + link.executable() + "' should be loaded");
    }

    @Test
    @DisplayName("the notice example has exactly one button")
    void noticeExample() {
        final DialogDefinition definition = result.definitions().get("welcome_notice");

        assertNotNull(definition);
        assertEquals(DialogType.NOTICE, definition.type());
        assertEquals(1, definition.buttons().size());
    }

    @Test
    @DisplayName("the command example declares its own open command with an alias")
    void commandExample() {
        final DialogDefinition definition = result.definitions().get("command_example");

        assertNotNull(definition);
        assertEquals(DialogType.MULTI_ACTION, definition.type());
        assertEquals(2, definition.buttons().size());

        assertEquals(List.of("dmexample", "dme"), definition.commands(),
                "the first entry is the command and the rest are aliases");

        // A command example must not squat on a reserved name, or it could never be registered.
        for (final String command : definition.commands()) {
            assertFalse(DialogLoader.RESERVED_COMMANDS.contains(command),
                    () -> "'" + command + "' is reserved by DeluxeMenus and could never register");
        }
    }

    @Test
    @DisplayName("only the command example declares an open command")
    void onlyTheCommandExampleDeclaresACommand() {
        for (final DialogDefinition definition : result.definitions().values()) {
            if (definition.id().equals("command_example")) {
                continue;
            }

            assertTrue(definition.commands().isEmpty(),
                    () -> "dialog '" + definition.id() + "' should not register a command; installing "
                            + "an example that claims a server command is a collision risk");
        }
    }

    @Test
    @DisplayName("the form example documents every supported input type")
    void formExample() {
        final DialogDefinition definition = result.definitions().get("feedback_form");

        assertNotNull(definition);
        assertEquals(DialogType.FORM, definition.type());
        assertEquals(4, definition.inputs().size());

        assertEquals(DialogInputType.TEXT, definition.input("subject").type());
        assertEquals(DialogInputType.SINGLE_OPTION, definition.input("category").type());
        assertEquals(DialogInputType.BOOLEAN, definition.input("anonymous").type());
        assertEquals(DialogInputType.NUMBER_RANGE, definition.input("rating").type());

        assertTrue(definition.input("subject").required());
        assertTrue(definition.input("category").required());
        assertFalse(definition.input("anonymous").required());
        assertTrue(definition.input("rating").required());

        // The form documents open_requirements, which must have resolved.
        assertTrue(definition.requirementsDeclared());
        assertNotNull(definition.requirements());
    }

    @Test
    @DisplayName("the stats example keeps escape working and refreshes on demand instead")
    void refreshExampleIsOnDemand() {
        final DialogDefinition definition = result.definitions().get("live_stats");

        assertNotNull(definition);

        // A timer cannot coexist with escape: Minecraft never reports escape, so a timer would keep
        // re-opening a dialog the player had closed. The shipped example therefore demonstrates the
        // combination that always works - escape closes the dialog, and a button updates it.
        assertTrue(definition.canCloseWithEscape(),
                "the example must leave escape working, because that is what a dialog can always support");
        assertFalse(definition.refreshes(),
                "the example must not enable a timer, which would make escape impossible");

        assertTrue(definition.buttons().stream()
                        .flatMap(button -> button.actions().stream())
                        .anyMatch(action -> action.type() == com.extendedclip.deluxemenus.action.ActionType.REFRESH),
                "the example exists to demonstrate [refresh], so it must actually use it");

        // A dialog that is updated by a button still has to offer a way out, since the update path
        // must not be the only control.
        assertFalse(definition.buttons().isEmpty());
    }

    @Test
    @DisplayName("the form example's skip button opts out of input validation")
    void formExampleSkipButtonCanAlwaysClose() {
        final DialogDefinition definition = result.definitions().get("feedback_form");

        assertNotNull(definition);

        // Two of the four inputs are required, so a skip button that validated would be rejected and
        // re-open the form, leaving no way out through it.
        assertTrue(definition.input("subject").required());
        assertTrue(definition.input("rating").required());

        assertTrue(definition.button("submit").validateInputs(), "submit submits the form");
        assertFalse(definition.button("skip").validateInputs(),
                "skip must not be gated by the required fields it never uses");
    }

    @Test
    @DisplayName("the form example's action placeholders match its declared input ids")
    void formExamplePlaceholdersAreConsistent() {
        final DialogDefinition definition = result.definitions().get("feedback_form");

        assertNotNull(definition);

        final String command = definition.buttons().get(0).actions().get(0).executable();
        final String message = definition.buttons().get(0).actions().get(1).executable();

        // A documented {placeholder} must always correspond to a declared input id, otherwise the
        // example would silently interpolate nothing at runtime.
        assertInterpolatesDeclaredInput(definition, command, "rating");
        assertInterpolatesDeclaredInput(definition, command, "category");
        assertInterpolatesDeclaredInput(definition, message, "subject");
        assertInterpolatesDeclaredInput(definition, message, "anonymous");
    }

    private static void assertInterpolatesDeclaredInput(final DialogDefinition definition,
                                                        final String text, final String inputId) {
        assertTrue(text.contains("{" + inputId + "}"),
                () -> "the example should interpolate '{" + inputId + "}'");
        assertNotNull(definition.input(inputId),
                () -> "'{" + inputId + "}' is used but no input with that id is declared");
    }

    /**
     * A requirement that always passes, so the example's {@code open_requirements} block resolves
     * without needing a live plugin.
     */
    private static final class AlwaysPassRequirement extends Requirement {

        @Override
        public boolean evaluate(final com.extendedclip.deluxemenus.menu.MenuHolder holder) {
            return true;
        }
    }
}
