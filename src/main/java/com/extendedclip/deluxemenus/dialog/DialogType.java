package com.extendedclip.deluxemenus.dialog;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The supported native dialog kinds, keyed by the {@code type} value used in dialog YAML.
 *
 * <p>Each constant documents the exact Paper API type it is rendered with. The renderer keeps this
 * mapping in one place so that config-level names never leak the underlying Java class names.</p>
 */
public enum DialogType {

    /**
     * Informational content with exactly one button.
     * <p>Rendered as {@code io.papermc.paper.registry.data.dialog.type.NoticeType}.</p>
     */
    NOTICE("notice", "A notice with a single button", 1, 1),

    /**
     * Two mutually exclusive choices (confirm / cancel).
     * <p>Rendered as {@code io.papermc.paper.registry.data.dialog.type.ConfirmationType}.</p>
     */
    CONFIRMATION("confirmation", "A confirmation with exactly two buttons (yes / no)", 2, 2),

    /**
     * Multiple independent choices, each with its own action list.
     * <p>Rendered as {@code io.papermc.paper.registry.data.dialog.type.MultiActionType}.</p>
     */
    MULTI_ACTION("multi_action", "A prompt with one or more independent action buttons", 1, 64),

    /**
     * A form with input controls and one or more submission buttons.
     * <p>Rendered as {@code io.papermc.paper.registry.data.dialog.type.MultiActionType} with the
     * dialog's inputs attached to the base, which is how Paper expresses server forms.</p>
     */
    FORM("form", "A form with input controls and submission buttons", 1, 64);

    private final String configName;
    private final String description;
    private final int minimumButtons;
    private final int maximumButtons;

    DialogType(final @NotNull String configName, final @NotNull String description,
               final int minimumButtons, final int maximumButtons) {
        this.configName = configName;
        this.description = description;
        this.minimumButtons = minimumButtons;
        this.maximumButtons = maximumButtons;
    }

    /**
     * The canonical value accepted in the {@code type} field of a dialog file.
     *
     * @return the config name
     */
    public @NotNull String configName() {
        return configName;
    }

    /**
     * A short description used by {@code /dm dialog info} and diagnostics.
     *
     * @return the description
     */
    public @NotNull String description() {
        return description;
    }

    /**
     * The smallest number of buttons this dialog kind accepts.
     *
     * @return the minimum button count
     */
    public int minimumButtons() {
        return minimumButtons;
    }

    /**
     * The largest number of buttons this dialog kind accepts.
     *
     * @return the maximum button count
     */
    public int maximumButtons() {
        return maximumButtons;
    }

    /**
     * Whether this dialog kind requires at least one input.
     *
     * @return {@code true} for {@link #FORM}
     */
    public boolean requiresInputs() {
        return this == FORM;
    }

    /**
     * Whether this dialog kind permits input controls at all.
     *
     * @return {@code true} for {@link #FORM}
     */
    public boolean supportsInputs() {
        return this == FORM;
    }

    /**
     * Resolves a config value to a dialog type.
     *
     * @param name the raw {@code type} value
     * @return the matching type, or {@code null} when the name is unknown
     */
    public static @Nullable DialogType getByName(final @Nullable String name) {
        if (name == null) {
            return null;
        }

        final String normalized = name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');

        for (final DialogType type : values()) {
            if (type.configName.equals(normalized)) {
                return type;
            }
        }

        // A couple of friendly aliases so common spellings do not fail validation.
        return switch (normalized) {
            case "confirm", "server_confirmation" -> CONFIRMATION;
            case "multiaction", "server_multi_action" -> MULTI_ACTION;
            // "server_form" and "server_notice" are the names Minecraft itself uses.
            case "server_form", "dialog_form" -> FORM;
            case "server_notice", "dialog_notice" -> NOTICE;
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
        for (final DialogType type : values()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(type.configName);
        }
        return builder.toString();
    }
}
