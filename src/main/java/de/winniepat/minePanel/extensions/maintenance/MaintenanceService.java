package de.winniepat.minePanel.extensions.maintenance;

import de.winniepat.minePanel.extensions.ExtensionContext;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MaintenanceService {

    public static final String BYPASS_PERMISSION = "minepanel.maintenance.bypass";

    private final ExtensionContext context;

    private volatile boolean enabled;
    private volatile String reason = "§cServer maintenance in progress";
    private volatile String motd = "§cMaintenance mode is active";
    private volatile String changedBy = "SYSTEM";
    private volatile long changedAt = Instant.now().toEpochMilli();

    public MaintenanceService(ExtensionContext context) {
        this.context = context;
    }

    public MaintenanceSnapshot snapshot() {
        return new MaintenanceSnapshot(enabled, reason, motd, changedBy, changedAt, countAffectedOnlinePlayers());
    }

    public int enable(String actor, String nextReason, String nextMotd, boolean kickNonStaff) {
        enabled = true;
        reason = normalizeReason(nextReason);
        motd = normalizeMotd(nextMotd);
        changedBy = normalizeActor(actor);
        changedAt = Instant.now().toEpochMilli();

        if (!kickNonStaff) {
            return 0;
        }
        return kickNonStaffPlayers();
    }

    public void disable(String actor) {
        enabled = false;
        changedBy = normalizeActor(actor);
        changedAt = Instant.now().toEpochMilli();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String reason() {
        return reason;
    }

    public String changedBy() {
        return changedBy;
    }

    public String motd() {
        return motd;
    }

    public long changedAt() {
        return changedAt;
    }

    public boolean canBypass(Player player) {
        return player != null && (player.isOp() || player.hasPermission(BYPASS_PERMISSION));
    }

    public int kickNonStaffPlayers() {
        return runSync(this::kickNonStaffPlayersSync);
    }

    private int kickNonStaffPlayersSync() {
        int kicked = 0;
        for (Player online : context.plugin().getServer().getOnlinePlayers()) {
            if (canBypass(online)) {
                continue;
            }
            kicked++;
            online.kickPlayer(kickMessage());
        }
        return kicked;
    }

    public int countAffectedOnlinePlayers() {
        return runSync(this::countAffectedOnlinePlayersSync);
    }

    private int countAffectedOnlinePlayersSync() {
        int affected = 0;
        for (Player online : context.plugin().getServer().getOnlinePlayers()) {
            if (!canBypass(online)) {
                affected++;
            }
        }
        return affected;
    }

    public String kickMessage() {
        return "Maintenance mode is active. " + reason;
    }

    private String normalizeReason(String value) {
        if (value == null || value.isBlank()) {
            return "Server maintenance in progress";
        }
        String trimmed = value.trim();
        return trimmed.length() > 180 ? trimmed.substring(0, 180) : trimmed;
    }

    private String normalizeMotd(String value) {
        if (value == null || value.isBlank()) {
            return "Maintenance mode is active";
        }
        String trimmed = value.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    private String normalizeActor(String value) {
        if (value == null || value.isBlank()) {
            return "SYSTEM";
        }
        return value.trim();
    }

    private int runSync(SyncIntTask task) {
        try {
            if (context.plugin().getServer().isPrimaryThread()) {
                return task.run();
            }

            Future<Integer> future = context.plugin().getServer().getScheduler().callSyncMethod(context.plugin(), task::run);
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("maintenance_task_interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("maintenance_task_failed", exception);
        }
    }

    @FunctionalInterface
    private interface SyncIntTask {
        int run();
    }

    public record MaintenanceSnapshot(
            boolean enabled,
            String reason,
            String motd,
            String changedBy,
            long changedAt,
            int affectedOnlinePlayers
    ) {
    }
}

