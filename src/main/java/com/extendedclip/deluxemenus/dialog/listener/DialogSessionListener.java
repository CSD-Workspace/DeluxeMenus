package com.extendedclip.deluxemenus.dialog.listener;

import com.extendedclip.deluxemenus.DeluxeMenus;
import com.extendedclip.deluxemenus.dialog.DialogSessions;
import com.extendedclip.deluxemenus.listener.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Ends a player's dialog refresh session as soon as anything else takes over their screen.
 *
 * <p>Minecraft never tells the server that a dialog was dismissed, so a refresh loop would otherwise
 * keep re-sending a dialog the player had already left. What the server <em>can</em> observe is every
 * other way the player's screen can change, and each of those proves the dialog is gone:</p>
 *
 * <ul>
 *     <li>opening any inventory - a menu, another plugin's GUI or a chest - replaces the dialog
 *         screen, since a dialog is not a container and cannot share the screen with one;</li>
 *     <li>quitting, dying, respawning or changing world moves the player somewhere the dialog is
 *         no longer valid.</li>
 * </ul>
 *
 * <p>Together with the escape rule - a dialog with a timer never allows escape, which removes the one
 * dismissal route the server cannot see - this is what makes a timed refresh safe. The two are
 * derived from each other, so they cannot drift apart.</p>
 */
public class DialogSessionListener extends Listener {

    private final DialogSessions sessions;

    /**
     * Creates the listener.
     *
     * @param plugin the owning plugin
     * @param sessions the refresh sessions to invalidate
     */
    public DialogSessionListener(final @NotNull DeluxeMenus plugin, final @NotNull DialogSessions sessions) {
        super(plugin);
        this.sessions = sessions;
    }

    /**
     * A player who opens any inventory has left the dialog behind.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(final @NotNull InventoryOpenEvent event) {
        // HumanEntity rather than Player: the session map is keyed by UUID, so a non player holder
        // simply will not match anything.
        endSession(event.getPlayer().getUniqueId());
    }

    /**
     * A player who quits cannot be shown anything.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final @NotNull PlayerQuitEvent event) {
        endSession(event.getPlayer().getUniqueId());
    }

    /**
     * Death closes any screen the player had open.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final @NotNull PlayerDeathEvent event) {
        endSession(event.getEntity().getUniqueId());
    }

    /**
     * Respawning opens a new screen, so the dialog is gone.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(final @NotNull PlayerRespawnEvent event) {
        endSession(event.getPlayer().getUniqueId());
    }

    /**
     * Changing world closes the player's screen.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(final @NotNull PlayerChangedWorldEvent event) {
        endSession(event.getPlayer().getUniqueId());
    }

    private void endSession(final @NotNull UUID playerId) {
        if (sessions.get(playerId) != null) {
            sessions.end(playerId);
        }
    }
}
