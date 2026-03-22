package de.winniepat.minePanel.extensions.announcements;

import de.winniepat.minePanel.extensions.ExtensionContext;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class AnnouncementService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExtensionContext context;
    private final AnnouncementRepository repository;

    private volatile boolean enabled;
    private volatile int intervalSeconds;
    private volatile long nextRunAt;
    private int rotationCursor;
    private BukkitTask task;

    public AnnouncementService(ExtensionContext context, AnnouncementRepository repository) {
        this.context = context;
        this.repository = repository;

        AnnouncementRepository.AnnouncementConfig config = repository.readConfig();
        this.enabled = config.enabled();
        this.intervalSeconds = Math.max(10, config.intervalSeconds());
        this.nextRunAt = System.currentTimeMillis() + (long) this.intervalSeconds * 1000L;
        this.rotationCursor = 0;
    }

    public void start() {
        stop();
        this.task = context.plugin().getServer().getScheduler().runTaskTimer(context.plugin(), this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public AnnouncementState state() {
        return new AnnouncementState(enabled, intervalSeconds, nextRunAt, repository.listMessages());
    }

    public void updateConfig(boolean enabled, int intervalSeconds) {
        this.enabled = enabled;
        this.intervalSeconds = Math.max(10, Math.min(86_400, intervalSeconds));
        this.nextRunAt = System.currentTimeMillis() + (long) this.intervalSeconds * 1000L;
        repository.saveConfig(this.enabled, this.intervalSeconds, System.currentTimeMillis());
    }

    public boolean sendNow() {
        Announcement next = nextAnnouncement();
        if (next == null) {
            return false;
        }

        broadcast(next.message());
        nextRunAt = System.currentTimeMillis() + (long) intervalSeconds * 1000L;
        return true;
    }

    public long addMessage(String message) {
        long id = repository.createMessage(message, System.currentTimeMillis());
        if (id > 0) {
            nextRunAt = System.currentTimeMillis() + (long) intervalSeconds * 1000L;
        }
        return id;
    }

    public boolean deleteMessage(long id) {
        boolean deleted = repository.deleteMessage(id);
        if (deleted) {
            rotationCursor = 0;
        }
        return deleted;
    }

    public boolean setMessageEnabled(long id, boolean enabled) {
        boolean updated = repository.setMessageEnabled(id, enabled, System.currentTimeMillis());
        if (updated) {
            rotationCursor = 0;
        }
        return updated;
    }

    private void tick() {
        if (!enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextRunAt) {
            return;
        }

        Announcement next = nextAnnouncement();
        if (next == null) {
            nextRunAt = now + (long) intervalSeconds * 1000L;
            return;
        }

        broadcast(next.message());
        nextRunAt = now + (long) intervalSeconds * 1000L;
    }

    private Announcement nextAnnouncement() {
        List<Announcement> enabledMessages = repository.listMessages().stream()
                .filter(Announcement::enabled)
                .toList();

        if (enabledMessages.isEmpty()) {
            rotationCursor = 0;
            return null;
        }

        if (rotationCursor >= enabledMessages.size()) {
            rotationCursor = 0;
        }

        Announcement selected = enabledMessages.get(rotationCursor);
        rotationCursor = (rotationCursor + 1) % enabledMessages.size();
        return selected;
    }

    private void broadcast(String message) {
        long nowMillis = System.currentTimeMillis();
        List<Player> onlinePlayers = List.copyOf(context.plugin().getServer().getOnlinePlayers());

        for (Player onlinePlayer : onlinePlayers) {
            String resolved = resolvePlaceholders(message, onlinePlayer, nowMillis, onlinePlayers.size());
            onlinePlayer.sendMessage(resolved);
        }

        context.panelLogger().log("SYSTEM", "ANNOUNCEMENTS", "Broadcasted announcement: " + message);
    }

    private String resolvePlaceholders(String template, Player player, long nowMillis, int onlineCount) {
        if (template == null || template.isBlank()) {
            return "";
        }

        ZoneId zoneId = ZoneId.systemDefault();
        var now = Instant.ofEpochMilli(nowMillis).atZone(zoneId);
        double tps = readPrimaryTps();

        String resolved = template;
        resolved = resolved.replace("%player%", player == null ? "Player" : player.getName());
        resolved = resolved.replace("%time%", TIME_FORMAT.format(now));
        resolved = resolved.replace("%date%", DATE_FORMAT.format(now));
        resolved = resolved.replace("%datetime%", DATETIME_FORMAT.format(now));
        resolved = resolved.replace("%online%", String.valueOf(onlineCount));
        resolved = resolved.replace("%max_players%", String.valueOf(context.plugin().getServer().getMaxPlayers()));
        resolved = resolved.replace("%world%", player == null || player.getWorld() == null ? "unknown" : player.getWorld().getName());
        resolved = resolved.replace("%server%", context.plugin().getServer().getName());
        resolved = resolved.replace("%tps%", tps < 0 ? "N/A" : String.format(Locale.US, "%.2f", tps));
        return resolved;
    }

    private double readPrimaryTps() {
        try {
            Object result = context.plugin().getServer().getClass().getMethod("getTPS").invoke(context.plugin().getServer());
            if (result instanceof double[] values && values.length > 0) {
                return values[0];
            }
        } catch (Exception ignored) {
            // Keep placeholder available on implementations without Paper TPS API.
        }
        return -1.0;
    }

    public record AnnouncementState(
            boolean enabled,
            int intervalSeconds,
            long nextRunAt,
            List<Announcement> messages
    ) {
    }
}

