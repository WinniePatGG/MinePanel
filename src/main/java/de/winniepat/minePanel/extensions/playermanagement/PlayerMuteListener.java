package de.winniepat.minePanel.extensions.playermanagement;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.time.Instant;
import java.util.Optional;

public final class PlayerMuteListener implements Listener {

    private final PlayerMuteRepository muteRepository;

    public PlayerMuteListener(PlayerMuteRepository muteRepository) {
        this.muteRepository = muteRepository;
    }

    @EventHandler(ignoreCancelled = true)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        long now = Instant.now().toEpochMilli();
        Optional<PlayerMute> mute = muteRepository.findByUuid(event.getPlayer().getUniqueId());
        if (mute.isEmpty()) {
            return;
        }

        PlayerMute activeMute = mute.get();
        if (activeMute.isExpired(now)) {
            muteRepository.removeMute(activeMute.uuid());
            return;
        }

        event.setCancelled(true);
        if (activeMute.expiresAt() == null) {
            event.getPlayer().sendMessage(ChatColor.RED + "You are muted. Reason: " + activeMute.reason());
            return;
        }

        long secondsLeft = Math.max(1L, (activeMute.expiresAt() - now) / 1000L);
        event.getPlayer().sendMessage(ChatColor.RED + "You are muted for another " + secondsLeft + "s. Reason: " + activeMute.reason());
    }
}

