package com.extendedclip.deluxemenus.dialog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers dialog refresh scheduling.
 *
 * <p>Every method takes an explicit "now", so the whole policy — including the hard lifetime that
 * exists because Minecraft never reports a dialog being closed — is testable without a server or a
 * clock.</p>
 */
class DialogSessionsTest {

    private static final long SECOND = 1000L;

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static DialogDefinition refreshing(final long version, final int interval, final int max) {
        return DialogDefinition.builder()
                .id("stats")
                .type(DialogType.NOTICE)
                .version(version)
                .sourceFile("stats.yml")
                .title("Stats")
                .refreshIntervalSeconds(interval)
                .refreshMaxSeconds(max)
                .build();
    }

    private static DialogDefinition notRefreshing(final long version) {
        return DialogDefinition.builder()
                .id("plain")
                .type(DialogType.NOTICE)
                .version(version)
                .sourceFile("plain.yml")
                .title("Plain")
                .build();
    }

    @Test
    @DisplayName("a dialog with no interval never creates a session")
    void noIntervalMeansNoSession() {
        final DialogSessions sessions = new DialogSessions();
        sessions.touch(PLAYER, notRefreshing(1), null, 0);

        assertEquals(0, sessions.size());
        assertNull(sessions.get(PLAYER));
    }

    @Test
    @DisplayName("a refreshing dialog creates a session due after its interval")
    void becomesDueAfterTheInterval() {
        final DialogSessions sessions = new DialogSessions();
        sessions.touch(PLAYER, refreshing(1, 2, 300), null, 0);

        assertEquals(1, sessions.size());
        assertNotNull(sessions.get(PLAYER));

        assertTrue(sessions.due(1 * SECOND).isEmpty(), "not due before the interval elapses");
        assertEquals(1, sessions.due(2 * SECOND).size(), "due once the interval elapses");
    }

    @Test
    @DisplayName("the placeholder player is remembered so a refresh resolves the same placeholders")
    void remembersThePlaceholderPlayer() {
        final DialogSessions sessions = new DialogSessions();
        sessions.touch(PLAYER, refreshing(1, 2, 300), OTHER_PLAYER, 0);

        final DialogSessions.Session session = sessions.get(PLAYER);

        assertNotNull(session);
        assertEquals(OTHER_PLAYER, session.placeholderPlayerId());
        assertEquals("stats", session.dialogId());
        assertEquals(1L, session.definitionVersion());
    }

    @Test
    @DisplayName("refreshing the same dialog keeps the original start time")
    void refreshDoesNotExtendTheLifetime() {
        final DialogSessions sessions = new DialogSessions();
        sessions.touch(PLAYER, refreshing(1, 2, 10), null, 0);

        final long startedAt = sessions.get(PLAYER).startedAt();

        // Simulate a refresh five seconds later.
        sessions.touch(PLAYER, refreshing(1, 2, 10), null, 5 * SECOND);

        final DialogSessions.Session session = sessions.get(PLAYER);

        assertEquals(startedAt, session.startedAt(), "a refresh must not restart the clock");
        assertEquals(5 * SECOND, session.lastRefreshAt(), "but it does reset the interval");
        assertTrue(sessions.due(6 * SECOND).isEmpty(), "so it is not due until the interval elapses again");
        assertEquals(1, sessions.due(7 * SECOND).size(), "and then it is due once more");
    }

    @Test
    @DisplayName("a session stops refreshing once its hard lifetime elapses")
    void expiresAfterTheMaximumLifetime() {
        final DialogSessions sessions = new DialogSessions();
        sessions.touch(PLAYER, refreshing(1, 2, 10), null, 0);

        assertFalse(sessions.due(9 * SECOND).isEmpty(), "still refreshing at 9s");

        // Minecraft never reports a dismissed dialog, so this bound is the only thing that stops a
        // refresh loop for a dialog the player already closed with escape.
        assertTrue(sessions.due(10 * SECOND).isEmpty(), "must stop refreshing at the lifetime");
        assertEquals(0, sessions.size(), "and the session must be dropped, not kept around expired");
        assertNull(sessions.get(PLAYER));
    }

    @Test
    @DisplayName("a definition version change starts a new session rather than continuing the old one")
    void versionChangeStartsANewSession() {
        final DialogSessions sessions = new DialogSessions();
        sessions.touch(PLAYER, refreshing(1, 2, 10), null, 0);
        sessions.touch(PLAYER, refreshing(2, 2, 10), null, 5 * SECOND);

        final DialogSessions.Session session = sessions.get(PLAYER);

        assertEquals(2L, session.definitionVersion());
        assertEquals(5 * SECOND, session.startedAt(),
                "a reloaded definition is a new dialog, so its lifetime starts over");
    }

    @Test
    @DisplayName("showing a different dialog replaces the session")
    void differentDialogReplacesTheSession() {
        final DialogSessions sessions = new DialogSessions();
        sessions.touch(PLAYER, refreshing(1, 2, 300), null, 0);

        final DialogDefinition other = DialogDefinition.builder()
                .id("other")
                .type(DialogType.NOTICE)
                .version(1)
                .sourceFile("other.yml")
                .title("Other")
                .refreshIntervalSeconds(5)
                .refreshMaxSeconds(300)
                .build();

        sessions.touch(PLAYER, other, null, SECOND);

        assertEquals(1, sessions.size(), "one session per player");
        assertEquals("other", sessions.get(PLAYER).dialogId());
    }

    @Test
    @DisplayName("a non-refreshing dialog clears any session the player had")
    void nonRefreshingDialogClearsTheSession() {
        final DialogSessions sessions = new DialogSessions();
        sessions.touch(PLAYER, refreshing(1, 2, 300), null, 0);

        sessions.touch(PLAYER, notRefreshing(1), null, SECOND);

        assertEquals(0, sessions.size());
        assertTrue(sessions.due(60 * SECOND).isEmpty());
    }

    @Test
    @DisplayName("ending, clearing and sizing behave as expected")
    void lifecycle() {
        final DialogSessions sessions = new DialogSessions();
        sessions.touch(PLAYER, refreshing(1, 2, 300), null, 0);
        sessions.touch(OTHER_PLAYER, refreshing(1, 2, 300), null, 0);

        assertEquals(2, sessions.size());

        assertNotNull(sessions.end(PLAYER));
        assertNull(sessions.end(PLAYER), "ending twice is not an error");
        assertEquals(1, sessions.size());
        assertNull(sessions.get(PLAYER), "an ended session is not due");
        assertTrue(sessions.due(60 * SECOND).stream().noneMatch(s -> s.playerId().equals(PLAYER)));

        sessions.clear();
        assertEquals(0, sessions.size());
    }

    @Test
    @DisplayName("several players are refreshed independently")
    void tracksPlayersIndependently() {
        final DialogSessions sessions = new DialogSessions();
        sessions.touch(PLAYER, refreshing(1, 2, 300), null, 0);
        sessions.touch(OTHER_PLAYER, refreshing(1, 5, 300), null, 0);

        final List<DialogSessions.Session> atTwo = sessions.due(2 * SECOND);
        assertEquals(1, atTwo.size());
        assertEquals(PLAYER, atTwo.get(0).playerId());

        final List<DialogSessions.Session> atFive = sessions.due(5 * SECOND);
        assertEquals(2, atFive.size(), "both are due by five seconds");
        assertEquals(2, sessions.size());
    }
}
