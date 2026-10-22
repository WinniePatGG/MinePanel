package de.winniepat.minePanel.extensions.maintenance;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

public final class MaintenanceMotdListener implements Listener {

    private final MaintenanceService maintenanceService;

    public MaintenanceMotdListener(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerListPing(ServerListPingEvent event) {
        if (!maintenanceService.isEnabled()) {
            return;
        }

        event.setMotd(maintenanceService.motd());
    }
}

