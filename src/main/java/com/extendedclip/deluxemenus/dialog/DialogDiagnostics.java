package com.extendedclip.deluxemenus.dialog;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Collects {@link DialogDiagnostic}s produced while parsing and validating dialog files.
 *
 * <p>A collector is passed through the loader so that parsing can keep going after a problem and
 * report every issue in one pass instead of failing on the first one.</p>
 */
public final class DialogDiagnostics {

    private final List<DialogDiagnostic> diagnostics = new ArrayList<>();

    /**
     * Adds a diagnostic to this collector.
     *
     * @param diagnostic the diagnostic to add
     */
    public void add(final @NotNull DialogDiagnostic diagnostic) {
        this.diagnostics.add(diagnostic);
    }

    /**
     * Records an error.
     *
     * @param sourceFile the dialog file
     * @param dialogId the dialog id, or {@code null}
     * @param path the key path, or {@code null}
     * @param message what is wrong
     * @param hint how to fix it, or {@code null}
     */
    public void error(final @NotNull String sourceFile, final String dialogId, final String path,
                      final @NotNull String message, final String hint) {
        add(DialogDiagnostic.error(sourceFile, dialogId, path, message, hint));
    }

    /**
     * Records a warning.
     *
     * @param sourceFile the dialog file
     * @param dialogId the dialog id, or {@code null}
     * @param path the key path, or {@code null}
     * @param message what is wrong
     * @param hint how to fix it, or {@code null}
     */
    public void warning(final @NotNull String sourceFile, final String dialogId, final String path,
                        final @NotNull String message, final String hint) {
        add(DialogDiagnostic.warning(sourceFile, dialogId, path, message, hint));
    }

    /**
     * All collected diagnostics, in insertion order.
     *
     * @return an unmodifiable view of the diagnostics
     */
    public @NotNull List<DialogDiagnostic> all() {
        return Collections.unmodifiableList(diagnostics);
    }

    /**
     * Only the diagnostics that prevent loading.
     *
     * @return an unmodifiable list of error diagnostics
     */
    public @NotNull List<DialogDiagnostic> errors() {
        return diagnostics.stream().filter(DialogDiagnostic::isError).collect(Collectors.toUnmodifiableList());
    }

    /**
     * Whether any error was recorded.
     *
     * @return {@code true} when at least one error is present
     */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(DialogDiagnostic::isError);
    }

    /**
     * Whether nothing at all was recorded.
     *
     * @return {@code true} when this collector is empty
     */
    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }

    /**
     * How many diagnostics were recorded.
     *
     * @return the diagnostic count
     */
    public int size() {
        return diagnostics.size();
    }
}
