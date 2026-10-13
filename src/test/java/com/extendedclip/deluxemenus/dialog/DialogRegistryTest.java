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
 * Covers registry lookup, ordering, replacement and the stale-response token.
 */
class DialogRegistryTest {

    private static DialogDefinition definition(final String id, final long version) {
        return DialogDefinition.builder()
                .id(id)
                .type(DialogType.NOTICE)
                .version(version)
                .sourceFile(id + ".yml")
                .title(id)
                .build();
    }

    @Test
    @DisplayName("lookup is case insensitive but the original casing is preserved")
    void lookupIsCaseInsensitive() {
        final DialogRegistry registry = new DialogRegistry();
        registry.replaceAll(Map.of("confirm_warp", definition("confirm_warp", 1)));

        assertTrue(registry.getById("confirm_warp").isPresent());
        assertTrue(registry.getById("CONFIRM_WARP").isPresent());
        assertTrue(registry.getById("  Confirm_Warp  ").isPresent());
        assertEquals("confirm_warp", registry.getById("confirm_warp").orElseThrow().id());

        assertTrue(registry.contains("Confirm_Warp"));
        assertFalse(registry.contains("missing"));
        assertFalse(registry.contains(null));
    }

    @Test
    @DisplayName("ids are listed in a stable, case ordered sequence")
    void idsAreSorted() {
        final DialogRegistry registry = new DialogRegistry();
        final Map<String, DialogDefinition> definitions = new LinkedHashMap<>();
        definitions.put("zulu", definition("zulu", 1));
        definitions.put("alpha", definition("Alpha", 2));
        definitions.put("mike", definition("mike", 3));
        registry.replaceAll(definitions);

        assertEquals(List.of("Alpha", "mike", "zulu"), List.copyOf(registry.getIds()));
    }

    @Test
    @DisplayName("prefix matching filters ids case insensitively")
    void prefixMatching() {
        final DialogRegistry registry = new DialogRegistry();
        final Map<String, DialogDefinition> definitions = new LinkedHashMap<>();
        definitions.put("confirm_warp", definition("confirm_warp", 1));
        definitions.put("confirm_home", definition("confirm_home", 2));
        definitions.put("feedback", definition("feedback", 3));
        registry.replaceAll(definitions);

        assertEquals(List.of("confirm_home", "confirm_warp"), registry.getIdsMatching("confirm"));
        assertEquals(List.of("confirm_home", "confirm_warp"), registry.getIdsMatching("CONFIRM"));
        assertEquals(3, registry.getIdsMatching("").size());
        assertTrue(registry.getIdsMatching("nothing").isEmpty());
    }

    @Test
    @DisplayName("replaceAll swaps every entry in one step and unregisters removed files")
    void replaceAllIsAtomic() {
        final DialogRegistry registry = new DialogRegistry();
        registry.replaceAll(Map.of("one", definition("one", 1), "two", definition("two", 2)));
        assertEquals(2, registry.size());

        // A reload that only produced 'two' must drop 'one' entirely.
        registry.replaceAll(Map.of("two", definition("two", 3)));

        assertEquals(1, registry.size());
        assertFalse(registry.contains("one"), "a deleted file must not leave a stale entry behind");
        assertTrue(registry.contains("two"));
    }

    @Test
    @DisplayName("clear empties the registry")
    void clearEmpties() {
        final DialogRegistry registry = new DialogRegistry();
        registry.replaceAll(Map.of("one", definition("one", 1)));

        registry.clear();

        assertEquals(0, registry.size());
        assertTrue(registry.getAll().isEmpty());
        assertTrue(registry.getIds().isEmpty());
    }

    @Test
    @DisplayName("a version token identifies exactly one loaded generation")
    void versionTokenDetectsStaleDefinitions() {
        final DialogRegistry registry = new DialogRegistry();
        registry.replaceAll(Map.of("thing", definition("thing", 7)));

        assertTrue(registry.isCurrent("thing", 7), "the live generation is current");
        assertFalse(registry.isCurrent("thing", 6), "an older generation is stale");
        assertFalse(registry.isCurrent("thing", 8), "an unknown generation is stale");
        assertFalse(registry.isCurrent("other", 7), "an unknown id is stale");

        // Reloading produces a new generation, which invalidates callbacks from the previous one.
        registry.replaceAll(Map.of("thing", definition("thing", 9)));

        assertFalse(registry.isCurrent("thing", 7), "the previous generation must be rejected after a reload");
        assertTrue(registry.isCurrent("thing", 9));
    }

    @Test
    @DisplayName("normalize is documented and stable")
    void normalizeTrimsAndLowercases() {
        assertEquals("confirm_warp", DialogRegistry.normalize("  Confirm_Warp "));
        assertEquals("confirm_warp", DialogRegistry.normalize("CONFIRM_WARP"));
    }
}
