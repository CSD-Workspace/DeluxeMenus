package com.extendedclip.deluxemenus.utils;

import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers colour code translation.
 *
 * <p>This is the single place both inventory menus and dialogs go through, so the assertions here
 * cover both at once. {@link StringUtils#color(String)} is checked as well as
 * {@link StringUtils#legacyColor(String)}: the string transform could be right while the serializer
 * still refuses the resulting form.</p>
 */
class StringUtilsColorTest {

    private static final String SECTION = "§";

    private static String hex(final String digits) {
        final StringBuilder builder = new StringBuilder().append(SECTION).append('x');

        for (final char character : digits.toCharArray()) {
            builder.append(SECTION).append(character);
        }

        return builder.toString();
    }

    @Test
    @DisplayName("angle bracket hex is translated to the section symbol form")
    void translatesAngleBracketHex() {
        assertEquals(hex("ff5496") + "Hello", StringUtils.legacyColor("<#ff5496>Hello"));
        assertEquals(hex("aaFF00") + "Hello", StringUtils.legacyColor("<#aaFF00>Hello"));
    }

    @Test
    @DisplayName("the three digit shorthand is expanded by doubling each digit")
    void expandsShorthandHex() {
        assertEquals(hex("ff4499") + "Hello", StringUtils.legacyColor("<#f49>Hello"));
        assertEquals(hex("ffaabb"), StringUtils.legacyColor("<#fab>"));
    }

    @Test
    @DisplayName("the existing ampersand forms keep working")
    void keepsExistingForms() {
        assertEquals(SECTION + "aGreen", StringUtils.legacyColor("&aGreen"));
        assertEquals(SECTION + "l" + SECTION + "aBold green", StringUtils.legacyColor("&l&aBold green"));
        assertEquals(hex("ff5496") + "Hex", StringUtils.legacyColor("&#ff5496Hex"));
    }

    @Test
    @DisplayName("both hex forms can appear in one string")
    void handlesMultipleHexColors() {
        assertEquals(
                hex("ff5496") + "a" + hex("00ff00") + "b" + hex("0000ff") + "c",
                StringUtils.legacyColor("<#ff5496>a<#00ff00>b<#0000ff>c"));
    }

    @Test
    @DisplayName("MiniMessage tags are left alone")
    void leavesMiniMessageTagsAlone() {
        // Only the hex form is rewritten, so a MiniMessage tag cannot be swallowed on its way to a
        // [minimessage] action.
        assertEquals("<red>Hello", StringUtils.legacyColor("<red>Hello"));
        assertEquals("<bold><gradient:red:blue>x</gradient></bold>",
                StringUtils.legacyColor("<bold><gradient:red:blue>x</gradient></bold>"));
    }

    @Test
    @DisplayName("malformed angle bracket colours are left as written")
    void leavesMalformedInputAlone() {
        assertEquals("<#ff5496", StringUtils.legacyColor("<#ff5496"), "an unclosed tag must not match");
        assertEquals("<#abcd>", StringUtils.legacyColor("<#abcd>"), "four digits is not a supported form");
        assertEquals("<#gggggg>", StringUtils.legacyColor("<#gggggg>"), "non hex characters must not match");
        assertEquals("<#>", StringUtils.legacyColor("<#>"));
        assertEquals("plain text", StringUtils.legacyColor("plain text"));
    }

    @Test
    @DisplayName("the serializer accepts the translated form and produces the right colour")
    void serializerProducesTheColour() {
        final TextColor expected = TextColor.fromHexString("#ff5496");

        assertEquals(expected, StringUtils.color("<#ff5496>Hello").color(),
                "the angle bracket form must end up as a real hex colour, not literal text");
        assertEquals(expected, StringUtils.color("&#ff5496Hello").color(),
                "the ampersand form must still produce the same colour");
    }

    @Test
    @DisplayName("the shorthand form renders as its expanded colour")
    void serializerExpandsShorthand() {
        assertEquals(TextColor.fromHexString("#ff4499"), StringUtils.color("<#f49>").color());
    }

    @Test
    @DisplayName("a MiniMessage tag is rendered as literal text, not a colour")
    void miniMessageTagIsNotAColour() {
        assertNull(StringUtils.color("<red>Hello").color(),
                "an untouched <red> tag is plain text as far as the legacy serializer is concerned");
    }

    @Test
    @DisplayName("colour codes are translated without disturbing surrounding text")
    void colourCodesSurviveAlongsideOtherText() {
        assertEquals("%player_name% " + hex("ff5496") + "is " + SECTION + "aonline",
                StringUtils.legacyColor("%player_name% <#ff5496>is &aonline"));
    }

    @Test
    @DisplayName("translating an already translated string changes nothing")
    void translationIsStable() {
        final String translated = StringUtils.legacyColor("<#ff5496>&aHello");

        assertEquals(translated, StringUtils.legacyColor(translated),
                "a second pass must not corrupt the section symbol output");
    }
}
