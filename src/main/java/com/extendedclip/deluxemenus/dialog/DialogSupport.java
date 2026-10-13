package com.extendedclip.deluxemenus.dialog;

import com.extendedclip.deluxemenus.utils.VersionHelper;
import org.jetbrains.annotations.NotNull;

/**
 * Runtime compatibility gate for the native Minecraft dialog subsystem.
 *
 * <p>Native dialogs rely on {@code io.papermc.paper.dialog.*}, which is only available on
 * Paper/Minecraft {@value #MINIMUM_VERSION_NAME} and newer. Instantiating or even statically
 * referencing those types on an older server results in a {@link NoClassDefFoundError}.</p>
 *
 * <p>This class is deliberately kept free of any Paper dialog imports so that the version check can
 * run before any dialog class has been loaded. {@code DeluxeMenus#onEnable()} consults it as its very
 * first action and disables the plugin with an explicit message when the server is too old.</p>
 */
public final class DialogSupport {

    /**
     * The lowest server version that exposes the native dialog API, encoded the same way as
     * {@link VersionHelper#CURRENT_VERSION} (for example {@code 1.21.7 -> 1217}).
     */
    public static final int MINIMUM_VERSION = 1217;

    /**
     * Human readable form of {@link #MINIMUM_VERSION}.
     */
    public static final String MINIMUM_VERSION_NAME = "1.21.7";

    private DialogSupport() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Whether the current server exposes the native dialog API required by DeluxeMenus.
     *
     * @return {@code true} when the server is {@value #MINIMUM_VERSION_NAME} or newer
     */
    public static boolean isSupported() {
        return isSupported(VersionHelper.CURRENT_VERSION);
    }

    /**
     * The pure form of the version gate.
     *
     * <p>Split out so the threshold logic can be tested without a running server, since reading
     * {@link VersionHelper#CURRENT_VERSION} requires one.</p>
     *
     * @param currentVersion the server version encoded the same way as
     *                       {@link VersionHelper#CURRENT_VERSION}
     * @return {@code true} when the version is at or above {@link #MINIMUM_VERSION}
     */
    public static boolean isSupported(final int currentVersion) {
        return currentVersion >= MINIMUM_VERSION;
    }

    /**
     * Builds the message logged when DeluxeMenus refuses to start on an unsupported server.
     *
     * <p>The detected version is passed in rather than read here, so this stays callable - and
     * testable - without a running server.</p>
     *
     * @param detectedVersion the server version string to include in the message
     * @return the compatibility message
     */
    public static @NotNull String unsupportedMessage(final @NotNull String detectedVersion) {
        return "DeluxeMenus requires Paper/Minecraft " + MINIMUM_VERSION_NAME + " or newer because native "
                + "dialogs are a core feature. This server reports version '" + detectedVersion
                + "'. DeluxeMenus will now disable. Please update your server software.";
    }
}
