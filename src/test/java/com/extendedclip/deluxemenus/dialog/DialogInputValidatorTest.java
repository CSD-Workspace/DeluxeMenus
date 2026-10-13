package com.extendedclip.deluxemenus.dialog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers response validation.
 *
 * <p>The submitted payload is client supplied, so these checks are the boundary that keeps an
 * arbitrary value out of a console command. Each case asserts both that the value is rejected and
 * that it never reaches the action argument map.</p>
 */
class DialogInputValidatorTest {

    private static DialogDefinition form(final DialogDefinition.InputSpec... inputs) {
        return DialogDefinition.builder()
                .id("form")
                .type(DialogType.FORM)
                .version(1)
                .sourceFile("form.yml")
                .title("Form")
                .inputs(List.of(inputs))
                .build();
    }

    private static DialogDefinition.InputSpec text(final String id, final boolean required,
                                                   final int maxLength, final Integer minLength) {
        return new DialogDefinition.InputSpec(id, DialogInputType.TEXT, id, true, 200, null,
                maxLength, false, null, null, "true", "false", List.of(),
                null, null, null, null, required, minLength);
    }

    private static DialogDefinition.InputSpec option(final String id, final boolean required,
                                                     final List<DialogDefinition.InputOption> options) {
        return new DialogDefinition.InputSpec(id, DialogInputType.SINGLE_OPTION, id, true, 200, null,
                32, false, null, null, "true", "false", options,
                null, null, null, null, required, null);
    }

    private static DialogDefinition.InputSpec number(final String id, final boolean required,
                                                     final float start, final float end) {
        return new DialogDefinition.InputSpec(id, DialogInputType.NUMBER_RANGE, id, true, 200, null,
                32, false, null, null, "true", "false", List.of(),
                start, end, 1.0F, null, required, null);
    }

    private static DialogDefinition.InputSpec bool(final String id, final boolean required) {
        return new DialogDefinition.InputSpec(id, DialogInputType.BOOLEAN, id, true, 200, null,
                32, false, null, null, "true", "false", List.of(),
                null, null, null, null, required, null);
    }

    @Test
    @DisplayName("a well formed submission yields the typed argument map")
    void acceptsValidSubmission() {
        final DialogDefinition definition = form(
                text("subject", true, 64, 3),
                option("category", true, List.of(
                        new DialogDefinition.InputOption("bug", "Bug", false),
                        new DialogDefinition.InputOption("suggestion", "Suggestion", true))),
                bool("anonymous", false),
                number("rating", true, 1.0F, 5.0F));

        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("subject", "A bug");
        values.put("category", "bug");
        values.put("anonymous", Boolean.TRUE);
        values.put("rating", 3.5F);

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition, values);

        assertTrue(result.isValid(), () -> "unexpected problems: " + result.problems());
        assertEquals("A bug", result.typedArgs().get("subject"));
        assertEquals("bug", result.typedArgs().get("category"));
        assertEquals("true", result.typedArgs().get("anonymous"));
        assertEquals("3.5", result.typedArgs().get("rating"));
    }

    @Test
    @DisplayName("a missing required value is rejected and never becomes an argument")
    void rejectsMissingRequiredValue() {
        final DialogDefinition definition = form(text("subject", true, 64, null));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition, Map.of());

        assertFalse(result.isValid());
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().get(0).contains("subject"));
        assertFalse(result.typedArgs().containsKey("subject"));
    }

    @Test
    @DisplayName("a blank required text value is rejected")
    void rejectsBlankRequiredText() {
        final DialogDefinition definition = form(text("subject", true, 64, null));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition, Map.of("subject", "   "));

        assertFalse(result.isValid());
        assertFalse(result.typedArgs().containsKey("subject"));
    }

    @Test
    @DisplayName("an optional missing value is accepted and simply absent")
    void acceptsMissingOptionalValue() {
        final DialogDefinition definition = form(text("note", false, 64, null), bool("flag", false));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition, Map.of());

        assertTrue(result.isValid());
        assertTrue(result.typedArgs().isEmpty());
    }

    @Test
    @DisplayName("text longer than max_length is rejected even though the client enforced it")
    void rejectsOverlongText() {
        final DialogDefinition definition = form(text("subject", true, 5, null));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition,
                Map.of("subject", "far too long"));

        assertFalse(result.isValid());
        assertTrue(result.problems().get(0).contains("at most 5"));
        assertFalse(result.typedArgs().containsKey("subject"));
    }

    @Test
    @DisplayName("text shorter than min_length is rejected")
    void rejectsTooShortText() {
        final DialogDefinition definition = form(text("subject", true, 64, 5));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition, Map.of("subject", "ab"));

        assertFalse(result.isValid());
        assertTrue(result.problems().get(0).contains("at least 5"));
    }

    @Test
    @DisplayName("a value that is not a string is rejected for a text input")
    void rejectsWrongTypeForText() {
        final DialogDefinition definition = form(text("subject", true, 64, null));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition,
                Map.of("subject", 42));

        assertFalse(result.isValid());
        assertFalse(result.typedArgs().containsKey("subject"));
    }

    @Test
    @DisplayName("a single_option value that is not one of the options is rejected")
    void rejectsUnknownOption() {
        final DialogDefinition definition = form(option("category", true, List.of(
                new DialogDefinition.InputOption("bug", "Bug", false))));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition,
                Map.of("category", "forged"));

        assertFalse(result.isValid());
        assertFalse(result.typedArgs().containsKey("category"));
    }

    @Test
    @DisplayName("a number below the configured range is rejected")
    void rejectsNumberBelowRange() {
        final DialogDefinition definition = form(number("rating", true, 1.0F, 5.0F));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition, Map.of("rating", 0.0F));

        assertFalse(result.isValid());
        assertTrue(result.problems().get(0).contains("below the minimum"));
        assertFalse(result.typedArgs().containsKey("rating"));
    }

    @Test
    @DisplayName("a number above the configured range is rejected")
    void rejectsNumberAboveRange() {
        final DialogDefinition definition = form(number("rating", true, 1.0F, 5.0F));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition, Map.of("rating", 99.0F));

        assertFalse(result.isValid());
        assertTrue(result.problems().get(0).contains("above the maximum"));
    }

    @Test
    @DisplayName("a non finite number is rejected")
    void rejectsNonFiniteNumber() {
        final DialogDefinition definition = form(number("rating", true, 1.0F, 5.0F));

        assertFalse(DialogInputValidator.validate(definition, Map.of("rating", Float.NaN)).isValid());
        assertFalse(DialogInputValidator.validate(definition, Map.of("rating", Float.POSITIVE_INFINITY)).isValid());
    }

    @Test
    @DisplayName("a boolean arriving with the wrong type is rejected")
    void rejectsWrongTypeForBoolean() {
        final DialogDefinition definition = form(bool("anonymous", true));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition,
                Map.of("anonymous", "yes"));

        assertFalse(result.isValid());
        assertFalse(result.typedArgs().containsKey("anonymous"));
    }

    @Test
    @DisplayName("every problem is reported in one pass rather than stopping at the first")
    void reportsEveryProblem() {
        final DialogDefinition definition = form(
                text("subject", true, 64, null),
                number("rating", true, 1.0F, 5.0F));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition, Map.of("rating", -5.0F));

        assertEquals(2, result.problems().size(), () -> "expected both problems, got " + result.problems());
    }

    @Test
    @DisplayName("a definition with no inputs validates anything to an empty map")
    void noInputsAcceptsEverything() {
        final DialogDefinition definition = DialogDefinition.builder()
                .id("plain")
                .type(DialogType.NOTICE)
                .version(1)
                .sourceFile("plain.yml")
                .title("Plain")
                .build();

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition,
                Map.of("anything", "goes"));

        assertTrue(result.isValid());
        assertTrue(result.typedArgs().isEmpty());
    }

    @Test
    @DisplayName("the typed argument map is immutable")
    void typedArgsAreImmutable() {
        final DialogDefinition definition = form(text("subject", true, 64, null));

        final DialogInputValidator.Result result = DialogInputValidator.validate(definition,
                Map.of("subject", "value"));

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> result.typedArgs().put("injected", "value"));
    }
}
