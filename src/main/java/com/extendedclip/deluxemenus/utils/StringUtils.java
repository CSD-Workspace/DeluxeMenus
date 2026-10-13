package com.extendedclip.deluxemenus.utils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StringUtils {

    private static final char SECTION_CHAR = '§';
    private static final String COLOR_CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";

    private final static Pattern HEX_PATTERN = Pattern
            .compile("&(#[a-f0-9]{6})", Pattern.CASE_INSENSITIVE);

    /**
     * Matches the angle bracket hex form {@code <#rrggbb>}, plus the three digit shorthand
     * {@code <#rgb>} that MiniMessage also accepts.
     * <p>
     * Only the hex form is matched: MiniMessage's named tags such as {@code <red>} or {@code <bold>}
     * are left alone, so this cannot swallow a MiniMessage tag.
     */
    private static final Pattern BRACKET_HEX_PATTERN = Pattern
            .compile("<#([a-f0-9]{6}|[a-f0-9]{3})>", Pattern.CASE_INSENSITIVE);

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character(SECTION_CHAR)
            .hexCharacter('#')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /**
     * Translates the ampersand color codes like '&7' to their section symbol counterparts like '§7'.
     * <br>
     * It also translates hex colors like '&#aaFF00' to their section symbol counterparts like '§x§a§a§F§F§0§0'.
     * <br>
     * Angle bracket hex colors are supported too, so '&lt;#ff5496&gt;' and '&lt;#f49&gt;' translate the same
     * way. This is the form MiniMessage uses, which makes it the one most people reach for; the
     * MiniMessage action types parse it natively and never pass through here, so this only adds the
     * syntax to legacy colored text.
     *
     * @param input The string in which to translate the color codes.
     * @return The string with the translated colors.
     */
    @NotNull
    public static String legacyColor(@NotNull String input) {
        input = translateBracketHex(input);

        final Matcher matcher = HEX_PATTERN.matcher(input);
        final StringBuilder builder = new StringBuilder();

        while (matcher.find()) {
            final StringBuilder replacement = new StringBuilder().append(SECTION_CHAR).append('x');
            for (final char character : matcher.group(1).substring(1).toCharArray()) {
                replacement.append(SECTION_CHAR).append(character);
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(builder);

        final char[] characters = builder.toString().toCharArray();
        for (int i = 0; i < characters.length - 1; i++) {
            if (characters[i] != '&' || COLOR_CODES.indexOf(characters[i + 1]) == -1) continue;
            characters[i] = SECTION_CHAR;
            characters[i + 1] = Character.toLowerCase(characters[i + 1]);
        }

        return new String(characters);
    }

    /**
     * Rewrites {@code <#rrggbb>} and {@code <#rgb>} into the section symbol hex form.
     *
     * @param input the string to rewrite
     * @return the rewritten string, unchanged when it contains no angle bracket hex color
     */
    private static @NotNull String translateBracketHex(final @NotNull String input) {
        final Matcher matcher = BRACKET_HEX_PATTERN.matcher(input);

        if (!matcher.find()) {
            return input;
        }

        final StringBuilder builder = new StringBuilder();

        do {
            final String digits = matcher.group(1);
            final String expanded = digits.length() == 3 ? expandShorthand(digits) : digits;
            matcher.appendReplacement(builder, Matcher.quoteReplacement(sectionHex(expanded)));
        } while (matcher.find());

        matcher.appendTail(builder);

        return builder.toString();
    }

    /**
     * Expands the three digit shorthand {@code abc} into {@code aabbcc}, the same way CSS and
     * MiniMessage do.
     *
     * @param digits the three hex digits
     * @return the six digit form
     */
    private static @NotNull String expandShorthand(final @NotNull String digits) {
        final StringBuilder expanded = new StringBuilder(digits.length() * 2);

        for (final char character : digits.toCharArray()) {
            expanded.append(character).append(character);
        }

        return expanded.toString();
    }

    /**
     * Builds the section symbol representation of a six digit hex color.
     *
     * @param digits the six hex digits, without the leading {@code #}
     * @return the section symbol form, for example {@code §x§f§f§5§4§9§6}
     */
    private static @NotNull String sectionHex(final @NotNull String digits) {
        final StringBuilder replacement = new StringBuilder().append(SECTION_CHAR).append('x');

        for (final char character : digits.toCharArray()) {
            replacement.append(SECTION_CHAR).append(character);
        }

        return replacement.toString();
    }

    /**
     * Parses a configured string into a component. Section symbols already present in the input,
     * such as those produced by PlaceholderAPI, are honoured alongside the '&' codes.
     */
    @NotNull
    public static Component color(@NotNull final String input) {
        return SERIALIZER.deserialize(legacyColor(input));
    }

    /**
     * Parses item display name / lore text.
     *
     * @param suppressDefaultItalics when {@code true}, italics are turned off for any part of the
     *                               text that does not set them, so item text is non-italic unless
     *                               the author writes {@code &o}. When {@code false} italics are
     *                               left untouched and the server's default applies. On 1.20.6
     *                               that renders unformatted text italic. See
     *                               {@code GeneralConfig#suppressDefaultItalics}.
     */
    @NotNull
    public static Component colorItemText(@NotNull final String input, final boolean suppressDefaultItalics) {
        final Component component = color(input);
        return suppressDefaultItalics
                ? component.applyFallbackStyle(TextDecoration.ITALIC.withState(false))
                : component;
    }

    /**
     * Serializes a component back into a legacy section symbol string, for comparison against
     * configured values.
     */
    @NotNull
    public static String legacy(@Nullable final Component component) {
        return component == null ? "" : SERIALIZER.serialize(component);
    }

    @NotNull
    public static String replacePlaceholdersAndArguments(@NotNull String input, final @Nullable Map<String, String> arguments,
                                                         final @Nullable Player player,
                                                         final boolean parsePlaceholdersInsideArguments,
                                                         final boolean parsePlaceholdersAfterArguments) {
        if (player == null) {
            return replaceArguments(input, arguments, null, parsePlaceholdersInsideArguments);
        }

        if (parsePlaceholdersAfterArguments) {
            return replacePlaceholders(replaceArguments(input, arguments, player, parsePlaceholdersInsideArguments), player);
        }

        return replaceArguments(replacePlaceholders(input, player), arguments, player, parsePlaceholdersInsideArguments);
    }

    @NotNull
    public static String replacePlaceholders(final @NotNull String input, final @NotNull Player player) {
        return PlaceholderAPI.setPlaceholders(player, input);
    }

    @NotNull
    public static String replaceArguments(@NotNull String input, final @Nullable Map<String, String> arguments,
                                          final @Nullable Player player, boolean parsePlaceholdersInsideArguments) {
        if (arguments == null || arguments.isEmpty()) {
            return input;
        }

        for (final Map.Entry<String, String> entry : arguments.entrySet()) {
            final String value = player != null && parsePlaceholdersInsideArguments
                    ? replacePlaceholders(entry.getValue(), player)
                    : entry.getValue();
            input = input.replace("{" + entry.getKey() + "}", value);
        }

        return input;
    }

    @Nullable
    public static Color parseRGBColor(@NotNull final String input) {
        final String[] parts = input.split(",");
        try {
            return Color.fromRGB(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
            );
        } catch (final Exception exception) {
            return null;
        }
    }
}
