package com.extendedclip.deluxemenus.dialog;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The input controls a form dialog may declare, keyed by the {@code type} value used in dialog YAML.
 *
 * <p>Every constant maps to a class in {@code io.papermc.paper.registry.data.dialog.input} that was
 * verified to exist on Paper 1.21.7 and newer.</p>
 */
public enum DialogInputType {

    /**
     * A free text field. Backed by {@code TextDialogInput}.
     */
    TEXT("text", "a single line text field"),

    /**
     * A checkbox. Backed by {@code BooleanDialogInput}.
     */
    BOOLEAN("boolean", "a checkbox returning true or false"),

    /**
     * A radio button group. Backed by {@code SingleOptionDialogInput}.
     */
    SINGLE_OPTION("single_option", "a list of options where exactly one can be selected"),

    /**
     * A numeric slider. Backed by {@code NumberRangeDialogInput}.
     */
    NUMBER_RANGE("number_range", "a numeric range slider returning a float");

    private final String configName;
    private final String description;

    DialogInputType(final @NotNull String configName, final @NotNull String description) {
        this.configName = configName;
        this.description = description;
    }

    /**
     * The canonical value accepted in the {@code type} field of an input.
     *
     * @return the config name
     */
    public @NotNull String configName() {
        return configName;
    }

    /**
     * A short description used by diagnostics.
     *
     * @return the description
     */
    public @NotNull String description() {
        return description;
    }

    /**
     * Resolves a config value to an input type.
     *
     * @param name the raw {@code type} value
     * @return the matching type, or {@code null} when the name is unknown
     */
    public static @Nullable DialogInputType getByName(final @Nullable String name) {
        if (name == null) {
            return null;
        }

        final String normalized = name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');

        for (final DialogInputType type : values()) {
            if (type.configName.equals(normalized)) {
                return type;
            }
        }

        return switch (normalized) {
            case "bool", "checkbox" -> BOOLEAN;
            case "string", "text_input", "input" -> TEXT;
            case "select", "singleoption", "radio", "dropdown" -> SINGLE_OPTION;
            case "number", "numberrange", "range", "slider", "float" -> NUMBER_RANGE;
            default -> null;
        };
    }

    /**
     * The config values this enum accepts, for use in diagnostics.
     *
     * @return a comma separated list of canonical names
     */
    public static @NotNull String listAll() {
        final StringBuilder builder = new StringBuilder();
        for (final DialogInputType type : values()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(type.configName);
        }
        return builder.toString();
    }
}
