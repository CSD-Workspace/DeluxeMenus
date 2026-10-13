package com.extendedclip.deluxemenus.dialog;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks which player is currently being shown which dialog, and when it is due to be re-rendered.
 *
 * <p>A dialog cannot be updated in place — the native API only offers "show this dialog" — so
 * refreshing means re-sending a freshly rendered dialog. To do that on a timer the plugin has to
 * remember who is looking at what.</p>
 *
 * <p>Deliberately free of Bukkit types and of any clock of its own: every method takes the current
 * time, which keeps the whole scheduling policy unit testable.</p>
 *
 * <h2>Why sessions expire</h2>
 * <p>Minecraft does not tell the server that a dialog was dismissed. The only serverbound packet a
 * dialog produces is a button press, so a player pressing escape is invisible. A session therefore
 * carries a hard lifetime and stops refreshing once it elapses; without that, a dialog the player
 * had already closed would be re-sent forever.</p>
 */
public final class DialogSessions {

    /**
     * One player's live view of one dialog.
     *
     * @param playerId the viewer
     * @param dialogId the dialog being shown
     * @param definitionVersion the version token of the definition that was rendered
     * @param placeholderPlayerId the player used to resolve placeholders, or {@code null}
     * @param intervalSeconds how often to re-render
     * @param maxSeconds how long the session may keep refreshing without any interaction
     * @param startedAt when the session began, in milliseconds
     * @param lastRefreshAt when the dialog was last sent, in milliseconds
     */
    public record Session(
            @NotNull UUID playerId,
            @NotNull String dialogId,
            long definitionVersion,
            @Nullable UUID placeholderPlayerId,
            int intervalSeconds,
            int maxSeconds,
            long startedAt,
            long lastRefreshAt
    ) {

        /**
         * Whether the session has outlived its hard lifetime.
         *
         * @param now the current time in milliseconds
         * @return {@code true} when refreshing must stop
         */
        public boolean isExpired(final long now) {
            return maxSeconds > 0 && now - startedAt >= maxSeconds * 1000L;
        }

        /**
         * Whether enough time has passed for another refresh.
         *
         * @param now the current time in milliseconds
         * @return {@code true} when the dialog should be re-sent
         */
        public boolean isDue(final long now) {
            return intervalSeconds > 0 && now - lastRefreshAt >= intervalSeconds * 1000L;
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    /**
     * Records that a dialog was just shown, creating or refreshing the player's session.
     *
     * <p>Re-showing the same dialog keeps its original start time, so a refresh can never extend a
     * session's lifetime indefinitely. A dialog with no refresh interval clears any session, since
     * there is nothing to schedule.</p>
     *
     * @param playerId the viewer
     * @param definition the dialog that was shown
     * @param placeholderPlayerId the player used for placeholders, or {@code null}
     * @param now the current time in milliseconds
     */
    public void touch(final @NotNull UUID playerId, final @NotNull DialogDefinition definition,
                      final @Nullable UUID placeholderPlayerId, final long now) {
        if (definition.refreshIntervalSeconds() <= 0) {
            sessions.remove(playerId);
            return;
        }

        final Session existing = sessions.get(playerId);

        final long startedAt = existing != null
                && existing.dialogId().equals(definition.id())
                && existing.definitionVersion() == definition.version()
                ? existing.startedAt()
                : now;

        sessions.put(playerId, new Session(
                playerId,
                definition.id(),
                definition.version(),
                placeholderPlayerId,
                definition.refreshIntervalSeconds(),
                definition.refreshMaxSeconds(),
                startedAt,
                now
        ));
    }

    /**
     * The player's current session.
     *
     * @param playerId the viewer
     * @return the session, or {@code null} when the player is not in a refreshed dialog
     */
    public @Nullable Session get(final @NotNull UUID playerId) {
        return sessions.get(playerId);
    }

    /**
     * Forgets a player's session.
     *
     * @param playerId the viewer
     * @return the session that was removed, or {@code null}
     */
    public @Nullable Session end(final @NotNull UUID playerId) {
        return sessions.remove(playerId);
    }

    /**
     * Forgets every session.
     */
    public void clear() {
        sessions.clear();
    }

    /**
     * How many sessions are live.
     *
     * @return the session count
     */
    public int size() {
        return sessions.size();
    }

    /**
     * The sessions that are due for a refresh, dropping any that have expired.
     *
     * <p>Expiry is handled here so a stale session can never be returned and never lingers.</p>
     *
     * @param now the current time in milliseconds
     * @return the sessions to re-render, in no particular order
     */
    public @NotNull List<Session> due(final long now) {
        final List<Session> due = new ArrayList<>();
        final List<UUID> expired = new ArrayList<>();

        for (final Session session : sessions.values()) {
            if (session.isExpired(now)) {
                expired.add(session.playerId());
                continue;
            }

            if (session.isDue(now)) {
                due.add(session);
            }
        }

        for (final UUID playerId : expired) {
            sessions.remove(playerId);
        }

        return due;
    }
}
