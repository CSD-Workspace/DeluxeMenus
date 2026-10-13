package com.extendedclip.deluxemenus.dialog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the compatibility gate.
 *
 * <p>The pure {@code isSupported(int)} overload is tested directly because reading the live server
 * version requires a running server, while the arithmetic is what actually decides whether the
 * plugin disables itself.</p>
 */
class DialogSupportTest {

    @Test
    @DisplayName("1.21.6 and older are rejected")
    void rejectsServersAtOrBelowTheFloor() {
        assertFalse(DialogSupport.isSupported(1216), "1.21.6 must be rejected");
        assertFalse(DialogSupport.isSupported(1215), "1.21.5 must be rejected");
        assertFalse(DialogSupport.isSupported(1200), "1.20 must be rejected");
        assertFalse(DialogSupport.isSupported(0), "an unknown version must fail closed");
    }

    @Test
    @DisplayName("1.21.7 is the minimum supported version")
    void acceptsTheFloor() {
        assertTrue(DialogSupport.isSupported(1217), "1.21.7 must be accepted");
    }

    @Test
    @DisplayName("newer versions are accepted")
    void acceptsNewerServers() {
        assertTrue(DialogSupport.isSupported(1218), "1.21.8 must be accepted");
        assertTrue(DialogSupport.isSupported(1219), "1.21.9 must be accepted");
        assertTrue(DialogSupport.isSupported(12111), "1.21.11 must be accepted");
        assertTrue(DialogSupport.isSupported(2600), "a future version must be accepted");
    }

    @Test
    @DisplayName("the floor constant and its display name agree")
    void floorMatchesItsName() {
        assertEquals(1217, DialogSupport.MINIMUM_VERSION);
        assertEquals("1.21.7", DialogSupport.MINIMUM_VERSION_NAME);
        assertTrue(DialogSupport.isSupported(DialogSupport.MINIMUM_VERSION));
        assertFalse(DialogSupport.isSupported(DialogSupport.MINIMUM_VERSION - 1));
    }

    @Test
    @DisplayName("the rejection message names both the requirement and the detected version")
    void rejectionMessageIsActionable() {
        final String message = DialogSupport.unsupportedMessage("1.21.6-R0.1-SNAPSHOT");

        assertTrue(message.contains("1.21.7"), "the message must state the required version");
        assertTrue(message.contains("1.21.6-R0.1-SNAPSHOT"), "the message must state the detected version");
        assertTrue(message.contains("disable"), "the message must say the plugin is disabling");
    }
}
