package com.extendedclip.deluxemenus.dialog;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A single structured configuration problem found while loading a dialog file.
 *
 * <p>Diagnostics carry enough context for an administrator to fix the file without guessing: the
 * source file, the dialog id when known, the exact key path inside the file, and a short hint.</p>
 *
 * @param severity the severity of the problem
 * @param sourceFile the dialog file the problem was found in, relative to {@code gui_dialogs}
 * @param dialogId the dialog id, or {@code null} when the id itself could not be read
 * @param path the key path inside the file, or {@code null} when the whole file is affected
 * @param message what is wrong
 * @param hint how to fix it
 */
public record DialogDiagnostic(
        @NotNull Severity severity,
        @NotNull String sourceFile,
        @Nullable String dialogId,
        @Nullable String path,
        @NotNull String message,
        @Nullable String hint
) {

    /**
     * How serious a diagnostic is. Errors prevent the dialog from loading, warnings do not.
     */
    public enum Severity {
        WARNING,
        ERROR
    }

    /**
     * Creates an error diagnostic.
     *
     * @param sourceFile the dialog file
     * @param dialogId the dialog id, or {@code null}
     * @param path the key path, or {@code null}
     * @param message what is wrong
     * @param hint how to fix it, or {@code null}
     * @return the diagnostic
     */
    public static @NotNull DialogDiagnostic error(final @NotNull String sourceFile, final @Nullable String dialogId,
                                                  final @Nullable String path, final @NotNull String message,
                                                  final @Nullable String hint) {
        return new DialogDiagnostic(Severity.ERROR, sourceFile, dialogId, path, message, hint);
    }

    /**
     * Creates a warning diagnostic.
     *
     * @param sourceFile the dialog file
     * @param dialogId the dialog id, or {@code null}
     * @param path the key path, or {@code null}
     * @param message what is wrong
     * @param hint how to fix it, or {@code null}
     * @return the diagnostic
     */
    public static @NotNull DialogDiagnostic warning(final @NotNull String sourceFile, final @Nullable String dialogId,
                                                    final @Nullable String path, final @NotNull String message,
                                                    final @Nullable String hint) {
        return new DialogDiagnostic(Severity.WARNING, sourceFile, dialogId, path, message, hint);
    }

    /**
     * Whether this diagnostic prevents a dialog from loading.
     *
     * @return {@code true} when the severity is {@link Severity#ERROR}
     */
    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /**
     * Formats this diagnostic as a single log line.
     *
     * @return the formatted line
     */
    public @NotNull String format() {
        final StringBuilder builder = new StringBuilder();
        builder.append('[').append(severity.name().toLowerCase(Locale.ROOT)).append("] ");
        builder.append(sourceFile);
        if (dialogId != null && !dialogId.isBlank()) {
            builder.append(" (dialog '").append(dialogId).append("')");
        }
        if (path != null && !path.isBlank()) {
            builder.append(" at '").append(path).append('\'');
        }
        builder.append(": ").append(message);
        if (hint != null && !hint.isBlank()) {
            builder.append(" Hint: ").append(hint);
        }
        return builder.toString();
    }
}
