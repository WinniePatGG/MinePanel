package de.winniepat.minePanel.logs;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

public final class CommandCaptureListener implements Listener {

    private final PanelLogger panelLogger;

    public CommandCaptureListener(PanelLogger panelLogger) {
        this.panelLogger = panelLogger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        panelLogger.log("COMMAND", event.getPlayer().getName(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        panelLogger.log("CONSOLE_COMMAND", "CONSOLE", event.getCommand());
    }
}

