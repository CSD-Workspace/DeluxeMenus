package com.extendedclip.deluxemenus.command.subcommand;

import com.extendedclip.deluxemenus.DeluxeMenus;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /dm open-dialog <dialog> [player]} - the convenience alias for {@code /dm dialog open}.
 *
 * <p>It shares its implementation with {@link DialogCommand} so both spellings enforce identical
 * permissions, target-player rules and tab completion. The separate subcommand exists only so the
 * alias shows up as its own entry in the root dispatcher.</p>
 */
public class OpenDialogCommand extends SubCommand {

    private final DialogCommand delegate;

    /**
     * Creates the alias command.
     *
     * @param plugin the owning plugin
     * @param delegate the dialog command that performs the actual open
     */
    public OpenDialogCommand(final @NotNull DeluxeMenus plugin, final @NotNull DialogCommand delegate) {
        super(plugin);
        this.delegate = delegate;
    }

    @Override
    public @NotNull String getName() {
        return "opendialog";
    }

    @Override
    public void execute(final @NotNull CommandSender sender, final @NotNull List<String> arguments) {
        delegate.open(sender, arguments);
    }

    @Override
    public @Nullable List<String> onTabComplete(final @NotNull CommandSender sender, final @NotNull List<String> arguments) {
        if (arguments.isEmpty()) {
            return List.of(getName());
        }

        if (arguments.size() == 1) {
            final String partial = arguments.get(0).toLowerCase(Locale.ROOT);

            if (partial.isEmpty() || getName().startsWith(partial)) {
                return List.of(getName());
            }

            return null;
        }

        if (!getName().equalsIgnoreCase(arguments.get(0))) {
            return null;
        }

        // Delegate the remaining arguments to the dialog command's completion logic.
        final List<String> forwarded = new ArrayList<>();
        forwarded.add("dialog");
        forwarded.add("open");
        forwarded.addAll(arguments.subList(1, arguments.size()));

        return delegate.onTabComplete(sender, forwarded);
    }
}
