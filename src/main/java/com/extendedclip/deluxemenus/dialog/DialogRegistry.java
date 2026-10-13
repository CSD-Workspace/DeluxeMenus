package com.extendedclip.deluxemenus.dialog;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The live set of loaded dialogs, keyed by id.
 *
 * <p>Lookups are case-insensitive to match the behaviour of the menu registry, while the original
 * casing is preserved for display. Reloads build a complete replacement map and hand it to
 * {@link #replaceAll(Map)} so the swap is atomic from the point of view of commands and callbacks:
 * either every entry from the new load is visible or none of it is.</p>
 *
 * <p>The registry is an instance owned by {@link DialogManager} rather than a static holder, so
 * tests can exercise it in isolation and shutdown can drop it cleanly.</p>
 */
public final class DialogRegistry {

    private final Map<String, DialogDefinition> dialogs = new LinkedHashMap<>();

    /**
     * Normalizes an id for lookup.
     *
     * @param id the raw id
     * @return the normalized key
     */
    public static @NotNull String normalize(final @NotNull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Atomically replaces the registry contents.
     *
     * @param definitions the new definitions, keyed by their normalized id
     */
    public void replaceAll(final @NotNull Map<String, DialogDefinition> definitions) {
        this.dialogs.clear();
        this.dialogs.putAll(definitions);
    }

    /**
     * Removes every dialog. Used on shutdown and when a reload finds no usable files.
     */
    public void clear() {
        this.dialogs.clear();
    }

    /**
     * Looks a dialog up by id.
     *
     * @param id the dialog id
     * @return the dialog, or an empty optional when it is not loaded
     */
    public @NotNull Optional<DialogDefinition> getById(final @Nullable String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.dialogs.get(normalize(id)));
    }

    /**
     * Whether a dialog with the given id is loaded.
     *
     * @param id the dialog id
     * @return {@code true} when the id is present
     */
    public boolean contains(final @Nullable String id) {
        return id != null && this.dialogs.containsKey(normalize(id));
    }

    /**
     * Every loaded dialog,
     *
     * @return an unmodifiable view of the loaded dialogs
     */
    public @NotNull Collection<DialogDefinition> getAll() {
        return Collections.unmodifiableCollection(this.dialogs.values());
    }

    /**
     * The loaded dialog ids in their original casing, sorted alphabetically.
     *
     * @return a sorted, immutable set of ids
     */
    public @NotNull Set<String> getIds() {
        final Set<String> ids = new TreeSet<>();
        for (final DialogDefinition definition : this.dialogs.values()) {
            ids.add(definition.id());
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * The loaded dialog ids whose ids start with the given prefix, sorted alphabetically.
     *
     * @param prefix the prefix to match, case-insensitively
     * @return a sorted, immutable list of ids
     */
    public @NotNull List<String> getIdsMatching(final @Nullable String prefix) {
        final String normalizedPrefix = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        final Set<String> matches = new TreeSet<>();
        for (final DialogDefinition definition : this.dialogs.values()) {
            if (normalizedPrefix.isEmpty() || definition.id().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)) {
                matches.add(definition.id());
            }
        }
        return List.copyOf(matches);
    }

    /**
     * How many dialogs are loaded.
     *
     * @return the dialog count
     */
    public int size() {
        return this.dialogs.size();
    }

    /**
     * Whether the registry still serves the exact definition version a callback captured.
     *
     * <p>Used to reject responses produced by a dialog that has since been reloaded or removed.</p>
     *
     * @param id the dialog id the callback was created for
     * @param version the version token the callback captured
     * @return {@code true} when the identical definition is still live
     */
    public boolean isCurrent(final @NotNull String id, final long version) {
        final DialogDefinition current = this.dialogs.get(normalize(id));
        return current != null && current.version() == version;
    }
}
