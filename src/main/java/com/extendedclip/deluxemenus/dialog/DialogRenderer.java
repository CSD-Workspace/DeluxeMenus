package com.extendedclip.deluxemenus.dialog;

import com.extendedclip.deluxemenus.utils.StringUtils;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The single place where a {@link DialogDefinition} is translated into Paper's native dialog API.
 *
 * <p>Everything player specific happens here: placeholders are resolved against the viewer for whom
 * the dialog is being built, and each button gets a callback bound to this exact definition and
 * button. Because Paper hands back a distinct callback per button there is no index to forge and no
 * button id to trust from the client.</p>
 *
 * <p>This class references Paper dialog types directly, so it must only be touched once
 * {@link DialogSupport#isSupported()} has been confirmed. Nothing loads it before then, because the
 * plugin refuses to enable on older servers.</p>
 */
public final class DialogRenderer {

    /**
     * How long a rendered dialog's button callbacks stay valid when the dialog is not refreshed.
     *
     * <p>Combined with a single use per callback this bounds how long a stale response could
     * possibly be replayed, while still giving a player plenty of time to read and answer.</p>
     */
    public static final Duration CALLBACK_LIFETIME = Duration.ofHours(1);

    /**
     * The shortest callback lifetime used for a refreshing dialog.
     */
    private static final Duration MIN_REFRESHED_CALLBACK_LIFETIME = Duration.ofSeconds(30);

    /**
     * How much longer than its refresh interval a refreshing dialog's callbacks stay valid.
     *
     * <p>Every refresh renders a new dialog, and each of those registers a callback per button with
     * Paper. Tying the lifetime to the interval means a superseded generation expires shortly after
     * the next one replaces it, so the number of live callbacks stays proportional to the number of
     * open dialogs rather than to the number of refreshes.</p>
     */
    private static final Duration REFRESH_LIFETIME_MARGIN = Duration.ofSeconds(10);

    private DialogRenderer() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Receives a validated button press from a rendered dialog.
     */
    @FunctionalInterface
    public interface ButtonHandler {

        /**
         * Called when a player presses a button.
         *
         * @param player the player who pressed the button
         * @param definition the definition the dialog was rendered from
         * @param button the button that was pressed
         * @param response the raw response payload
         */
        void onButton(@NotNull Player player, @NotNull DialogDefinition definition,
                      @NotNull DialogDefinition.ButtonSpec button, @NotNull DialogResponseView response);
    }

    /**
     * Builds a viewer specific native dialog.
     *
     * @param definition the parsed definition
     * @param viewer the player who will see the dialog
     * @param placeholderPlayer the player used to resolve placeholders, or {@code null} to use the viewer
     * @param handler the handler invoked when a button is pressed
     * @return the native dialog, ready for {@code Audience#showDialog}
     */
    public static @NotNull io.papermc.paper.dialog.Dialog render(final @NotNull DialogDefinition definition,
                                                                final @NotNull Player viewer,
                                                                final @Nullable Player placeholderPlayer,
                                                                final @NotNull ButtonHandler handler) {
        return render(definition, viewer, placeholderPlayer, handler, callbackLifetime(definition));
    }

    /**
     * Builds a viewer specific native dialog with an explicit callback lifetime.
     *
     * @param definition the parsed definition
     * @param viewer the player who will see the dialog
     * @param placeholderPlayer the player used to resolve placeholders, or {@code null} to use the viewer
     * @param handler the handler invoked when a button is pressed
     * @param callbackLifetime how long the buttons stay valid
     * @return the native dialog, ready for {@code Audience#showDialog}
     */
    public static @NotNull io.papermc.paper.dialog.Dialog render(final @NotNull DialogDefinition definition,
                                                                final @NotNull Player viewer,
                                                                final @Nullable Player placeholderPlayer,
                                                                final @NotNull ButtonHandler handler,
                                                                final @NotNull Duration callbackLifetime) {
        final Player textTarget = placeholderPlayer != null ? placeholderPlayer : viewer;

        final DialogBase base = DialogBase.builder(text(definition.title(), textTarget))
                .externalTitle(definition.externalTitle() == null
                        ? null
                        : text(definition.externalTitle(), textTarget))
                .canCloseWithEscape(definition.canCloseWithEscape())
                .pause(definition.pause())
                .afterAction(afterAction(definition.afterAction()))
                .body(body(definition, textTarget))
                .inputs(inputs(definition, textTarget))
                .build();

        final io.papermc.paper.registry.data.dialog.type.DialogType type =
                type(definition, textTarget, handler, callbackLifetime);

        return io.papermc.paper.dialog.Dialog.create(factory -> factory
                .empty()
                .base(base)
                .type(type));
    }

    /**
     * Picks the callback lifetime a definition's buttons should use.
     *
     * @param definition the dialog definition
     * @return the lifetime, shortened for refreshing dialogs
     */
    public static @NotNull Duration callbackLifetime(final @NotNull DialogDefinition definition) {
        if (!definition.refreshes()) {
            return CALLBACK_LIFETIME;
        }

        final Duration scaled = Duration.ofSeconds(definition.refreshIntervalSeconds())
                .plus(REFRESH_LIFETIME_MARGIN);

        return scaled.compareTo(MIN_REFRESHED_CALLBACK_LIFETIME) > 0
                ? scaled
                : MIN_REFRESHED_CALLBACK_LIFETIME;
    }

    private static @NotNull io.papermc.paper.registry.data.dialog.type.DialogType type(
            final @NotNull DialogDefinition definition,
            final @NotNull Player textTarget,
            final @NotNull ButtonHandler handler,
            final @NotNull Duration callbackLifetime) {

        final List<ActionButton> buttons = new ArrayList<>();

        for (final DialogDefinition.ButtonSpec spec : definition.buttons()) {
            buttons.add(button(definition, spec, textTarget, handler, callbackLifetime));
        }

        return switch (definition.type()) {
            case NOTICE -> io.papermc.paper.registry.data.dialog.type.DialogType.notice(buttons.get(0));
            case CONFIRMATION -> io.papermc.paper.registry.data.dialog.type.DialogType.confirmation(
                    buttons.get(0), buttons.get(1));
            case MULTI_ACTION, FORM -> io.papermc.paper.registry.data.dialog.type.DialogType.multiAction(
                    buttons,
                    definition.exitButton() == null
                            ? null
                            : button(definition, definition.exitButton(), textTarget, handler, callbackLifetime),
                    definition.columns());
        };
    }

    private static @NotNull ActionButton button(final @NotNull DialogDefinition definition,
                                                final @NotNull DialogDefinition.ButtonSpec spec,
                                                final @NotNull Player textTarget,
                                                final @NotNull ButtonHandler handler,
                                                final @NotNull Duration callbackLifetime) {
        return ActionButton.builder(text(spec.label(), textTarget))
                .tooltip(spec.tooltip() == null ? null : text(spec.tooltip(), textTarget))
                .width(spec.width())
                .action(DialogAction.customClick(
                        (response, audience) -> {
                            if (audience instanceof Player player) {
                                handler.onButton(player, definition, spec, response);
                            }
                        },
                        ClickCallback.Options.builder()
                                .uses(1)
                                .lifetime(callbackLifetime)
                                .build()))
                .build();
    }

    private static @NotNull List<DialogBody> body(final @NotNull DialogDefinition definition,
                                                  final @NotNull Player textTarget) {
        final List<DialogBody> body = new ArrayList<>();

        for (final DialogDefinition.BodySpec spec : definition.body()) {
            body.add(DialogBody.plainMessage(text(spec.content(), textTarget), spec.width()));
        }

        return body;
    }

    private static @NotNull List<DialogInput> inputs(final @NotNull DialogDefinition definition,
                                                     final @NotNull Player textTarget) {
        final List<DialogInput> inputs = new ArrayList<>();

        for (final DialogDefinition.InputSpec spec : definition.inputs()) {
            inputs.add(input(spec, textTarget));
        }

        return inputs;
    }

    private static @NotNull DialogInput input(final @NotNull DialogDefinition.InputSpec spec,
                                              final @NotNull Player textTarget) {
        final Component label = text(spec.label(), textTarget);

        return switch (spec.type()) {
            case TEXT -> {
                TextDialogInput.MultilineOptions multiline = null;

                if (spec.multiline()) {
                    multiline = TextDialogInput.MultilineOptions.create(spec.maxLines(), spec.height());
                }

                yield DialogInput.text(spec.id(), label)
                        .width(spec.width())
                        .labelVisible(spec.labelVisible())
                        .initial(spec.initial() == null ? "" : spec.initial())
                        .maxLength(spec.maxLength())
                        .multiline(multiline)
                        .build();
            }
            case BOOLEAN -> DialogInput.bool(spec.id(), label)
                    .initial(spec.initial() != null && Boolean.parseBoolean(spec.initial()))
                    .onTrue(spec.onTrue())
                    .onFalse(spec.onFalse())
                    .build();
            case SINGLE_OPTION -> {
                final List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();

                for (final DialogDefinition.InputOption option : spec.options()) {
                    entries.add(SingleOptionDialogInput.OptionEntry.create(
                            option.id(),
                            option.label() == null ? null : text(option.label(), textTarget),
                            option.initial()));
                }

                yield DialogInput.singleOption(spec.id(), label, entries)
                        .width(spec.width())
                        .labelVisible(spec.labelVisible())
                        .build();
            }
            case NUMBER_RANGE -> {
                final io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput.Builder builder =
                        DialogInput.numberRange(spec.id(), label,
                                spec.rangeStart() == null ? 0.0F : spec.rangeStart(),
                                spec.rangeEnd() == null ? 1.0F : spec.rangeEnd())
                                .width(spec.width());

                if (spec.labelFormat() != null) {
                    builder.labelFormat(spec.labelFormat());
                }

                if (spec.initial() != null) {
                    final Float initial = parseFloat(spec.initial());

                    if (initial != null) {
                        builder.initial(initial);
                    }
                }

                if (spec.rangeStep() != null) {
                    builder.step(spec.rangeStep());
                }

                yield builder.build();
            }
        };
    }

    private static @Nullable Float parseFloat(final @NotNull String value) {
        try {
            return Float.parseFloat(value.trim());
        } catch (final NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Turns a raw config string into a component, expanding PlaceholderAPI placeholders and legacy
     * colour codes exactly the way the rest of DeluxeMenus does.
     *
     * @param raw the raw string
     * @param target the player to resolve placeholders against
     * @return the resolved component
     */
    public static @NotNull Component text(final @NotNull String raw, final @NotNull Player target) {
        return StringUtils.color(StringUtils.replacePlaceholders(raw, target));
    }

    private static @NotNull DialogBase.DialogAfterAction afterAction(
            final @NotNull DialogDefinition.AfterAction afterAction) {
        return switch (afterAction) {
            case CLOSE -> DialogBase.DialogAfterAction.CLOSE;
            case NONE -> DialogBase.DialogAfterAction.NONE;
            case WAIT_FOR_RESPONSE -> DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE;
        };
    }
}
