package de.winniepat.minePanel.extensions.playermanagement;

import de.winniepat.minePanel.logs.PanelLogger;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class PlayerMuteListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN_TEXT_SERIALIZER = PlainTextComponentSerializer.plainText();

    private final PlayerMuteRepository muteRepository;
    private final PanelLogger panelLogger;
    private volatile boolean badWordFilterEnabled;
    private volatile Set<String> badWords;
    private volatile int autoMuteMinutes;
    private volatile String autoMuteReason;
    private volatile boolean cancelBlockedMessage;

    public PlayerMuteListener(
            PlayerMuteRepository muteRepository,
            PanelLogger panelLogger,
            boolean badWordFilterEnabled,
            Set<String> badWords,
            int autoMuteMinutes,
            String autoMuteReason,
            boolean cancelBlockedMessage
    ) {
        this.muteRepository = muteRepository;
        this.panelLogger = panelLogger;
        updateFilterConfig(badWordFilterEnabled, badWords, autoMuteMinutes, autoMuteReason, cancelBlockedMessage);
    }

    public void updateFilterConfig(
            boolean badWordFilterEnabled,
            Set<String> badWords,
            int autoMuteMinutes,
            String autoMuteReason,
            boolean cancelBlockedMessage
    ) {
        this.badWordFilterEnabled = badWordFilterEnabled;
        this.badWords = badWords == null ? Set.of() : new HashSet<>(badWords);
        this.autoMuteMinutes = Math.max(0, autoMuteMinutes);
        this.autoMuteReason = (autoMuteReason == null || autoMuteReason.isBlank())
                ? "Inappropriate language"
                : autoMuteReason.trim();
        this.cancelBlockedMessage = cancelBlockedMessage;
    }

    @EventHandler(ignoreCancelled = true)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        processChatMessage(event.getPlayer(), event.getMessage(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        processChatMessage(event.getPlayer(), PLAIN_TEXT_SERIALIZER.serialize(event.message()), event::setCancelled);
    }

    private void processChatMessage(Player player, String message, CancelHandler cancelHandler) {
        long now = Instant.now().toEpochMilli();
        Optional<PlayerMute> mute = muteRepository.findByUuid(player.getUniqueId());
        if (mute.isEmpty()) {
            handleBadWordFilter(player, message, now, cancelHandler);
            return;
        }

        PlayerMute activeMute = mute.get();
        if (activeMute.isExpired(now)) {
            muteRepository.removeMute(activeMute.uuid());
            handleBadWordFilter(player, message, now, cancelHandler);
            return;
        }

        cancelHandler.cancel(true);
        if (activeMute.expiresAt() == null) {
            player.sendMessage(ChatColor.RED + "You are muted. Reason: " + activeMute.reason());
            return;
        }

        long secondsLeft = Math.max(1L, (activeMute.expiresAt() - now) / 1000L);
        player.sendMessage(ChatColor.RED + "You are muted for another " + secondsLeft + "s. Reason: " + activeMute.reason());
    }

    private void handleBadWordFilter(Player player, String message, long now, CancelHandler cancelHandler) {
        if (!badWordFilterEnabled || badWords.isEmpty()) {
            return;
        }

        if (player.hasPermission("minepanel.chatfilter.bypass") || player.isOp()) {
            return;
        }

        String matchedWord = findMatchedBadWord(message);
        if (matchedWord == null) {
            return;
        }

        Long expiresAt = autoMuteMinutes <= 0 ? null : now + autoMuteMinutes * 60_000L;
        muteRepository.upsertMute(
                player.getUniqueId(),
                player.getName(),
                autoMuteReason,
                "AUTO_MOD",
                now,
                expiresAt
        );

        if (cancelBlockedMessage) {
            cancelHandler.cancel(true);
        }

        panelLogger.log(
                "SECURITY",
                "CHAT_FILTER",
                "Auto-muted " + player.getName() + " for bad language (matched: " + matchedWord + ")"
        );

        if (expiresAt == null) {
            player.sendMessage(ChatColor.RED + "Your message contained blocked language. You have been muted. Reason: " + autoMuteReason);
            return;
        }

        player.sendMessage(ChatColor.RED + "Your message contained blocked language. You have been muted for " + autoMuteMinutes + " minute(s). Reason: " + autoMuteReason);
    }

    private String findMatchedBadWord(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        String lower = message.toLowerCase(Locale.ROOT);
        for (String badWord : badWords) {
            if (badWord == null || badWord.isBlank()) {
                continue;
            }

            String normalizedBadWord = badWord.toLowerCase(Locale.ROOT).trim();
            String pattern = "(^|[^a-z0-9])" + Pattern.quote(normalizedBadWord) + "([^a-z0-9]|$)";
            if (Pattern.compile(pattern).matcher(lower).find()) {
                return badWord;
            }
        }

        return null;
    }

    @FunctionalInterface
    private interface CancelHandler {
        void cancel(boolean cancelled);
    }
}

