package de.winniepat.minePanel.logs;

import de.winniepat.minePanel.persistence.KnownPlayerRepository;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerSeenListener implements Listener {

    private final KnownPlayerRepository knownPlayerRepository;

    public PlayerSeenListener(KnownPlayerRepository knownPlayerRepository) {
        this.knownPlayerRepository = knownPlayerRepository;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        knownPlayerRepository.upsert(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }
}

