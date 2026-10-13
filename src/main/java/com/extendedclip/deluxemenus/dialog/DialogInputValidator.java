package com.extendedclip.deluxemenus.dialog;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the values a player submitted through a form dialog against the schema it declared.
 *
 * <p>The response payload is client supplied, so nothing in it may be trusted: a value that is
 * missing, of the wrong type, not one of the configured options, or outside the configured range is
 * rejected before any action runs.</p>
 *
 * <p>The validator is free of any Paper or Bukkit types, which keeps this security relevant path
 * directly unit testable.</p>
 */
public final class DialogInputValidator {

    private DialogInputValidator() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * The outcome of validating a submission.
     *
     * @param typedArgs the validated values as strings, ready to be exposed to actions as
     *                  <code>{input_id}</code>
     * @param problems human readable problems; empty when the submission is valid
     */
    public record Result(@NotNull Map<String, String> typedArgs, @NotNull List<String> problems) {

        /**
         * @return {@code true} when the submission passed every check
         */
        public boolean isValid() {
            return problems.isEmpty();
        }
    }

    /**
     * Validates a submission.
     *
     * @param definition the dialog definition the submission belongs to
     * @param rawValues the raw submitted values keyed by input id; a value may be {@code null}
     * @return the validation result
     */
    public static @NotNull Result validate(final @NotNull DialogDefinition definition,
                                           final @NotNull Map<String, Object> rawValues) {
        final Map<String, String> typedArgs = new LinkedHashMap<>();
        final java.util.ArrayList<String> problems = new java.util.ArrayList<>();

        for (final DialogDefinition.InputSpec spec : definition.inputs()) {
            final Object raw = rawValues.get(spec.id());

            switch (spec.type()) {
                case TEXT -> validateText(spec, raw, typedArgs, problems);
                case BOOLEAN -> validateBoolean(spec, raw, typedArgs, problems);
                case SINGLE_OPTION -> validateSingleOption(spec, raw, typedArgs, problems);
                case NUMBER_RANGE -> validateNumberRange(spec, raw, typedArgs, problems);
            }
        }

        return new Result(Collections.unmodifiableMap(typedArgs), List.copyOf(problems));
    }

    private static void validateText(final @NotNull DialogDefinition.InputSpec spec, final Object raw,
                                     final @NotNull Map<String, String> typedArgs,
                                     final @NotNull List<String> problems) {
        if (!(raw instanceof String value)) {
            if (spec.required()) {
                problems.add("'" + spec.id() + "' is required.");
            }
            return;
        }

        if (spec.required() && value.isBlank()) {
            problems.add("'" + spec.id() + "' is required.");
            return;
        }

        if (value.length() > spec.maxLength()) {
            problems.add("'" + spec.id() + "' must be at most " + spec.maxLength() + " characters.");
            return;
        }

        if (spec.minLength() != null && value.length() < spec.minLength()) {
            problems.add("'" + spec.id() + "' must be at least " + spec.minLength() + " characters.");
            return;
        }

        typedArgs.put(spec.id(), value);
    }

    private static void validateBoolean(final @NotNull DialogDefinition.InputSpec spec, final Object raw,
                                        final @NotNull Map<String, String> typedArgs,
                                        final @NotNull List<String> problems) {
        if (!(raw instanceof Boolean value)) {
            if (spec.required()) {
                problems.add("'" + spec.id() + "' is required.");
            }
            return;
        }

        typedArgs.put(spec.id(), Boolean.toString(value));
    }

    private static void validateSingleOption(final @NotNull DialogDefinition.InputSpec spec, final Object raw,
                                             final @NotNull Map<String, String> typedArgs,
                                             final @NotNull List<String> problems) {
        if (!(raw instanceof String value) || value.isBlank()) {
            if (spec.required()) {
                problems.add("'" + spec.id() + "' is required.");
            }
            return;
        }

        for (final DialogDefinition.InputOption option : spec.options()) {
            if (option.id().equals(value)) {
                typedArgs.put(spec.id(), value);
                return;
            }
        }

        problems.add("'" + spec.id() + "' received a value that is not one of its options.");
    }

    private static void validateNumberRange(final @NotNull DialogDefinition.InputSpec spec, final Object raw,
                                            final @NotNull Map<String, String> typedArgs,
                                            final @NotNull List<String> problems) {
        if (!(raw instanceof Float value)) {
            if (spec.required()) {
                problems.add("'" + spec.id() + "' is required.");
            }
            return;
        }

        if (Float.isNaN(value) || Float.isInfinite(value)) {
            problems.add("'" + spec.id() + "' must be a finite number.");
            return;
        }

        if (spec.rangeStart() != null && value < spec.rangeStart()) {
            problems.add("'" + spec.id() + "' is below the minimum of " + spec.rangeStart() + ".");
            return;
        }

        if (spec.rangeEnd() != null && value > spec.rangeEnd()) {
            problems.add("'" + spec.id() + "' is above the maximum of " + spec.rangeEnd() + ".");
            return;
        }

        typedArgs.put(spec.id(), String.valueOf(value));
    }
}
