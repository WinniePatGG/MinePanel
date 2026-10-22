package de.winniepat.minePanel.extensions.maintenance;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public final class MaintenanceJoinListener implements Listener {

    private final MaintenanceService maintenanceService;

    public MaintenanceJoinListener(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!maintenanceService.isEnabled()) {
            return;
        }

        if (maintenanceService.canBypass(event.getPlayer())) {
            return;
        }

        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, maintenanceService.kickMessage());
    }
}

