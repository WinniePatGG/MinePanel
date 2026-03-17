package de.winniepat.minePanel.extensions.airstrike;

import com.google.gson.Gson;
import de.winniepat.minePanel.extensions.ExtensionContext;
import de.winniepat.minePanel.extensions.ExtensionWebRegistry;
import de.winniepat.minePanel.extensions.MinePanelExtension;
import de.winniepat.minePanel.persistence.KnownPlayer;
import de.winniepat.minePanel.users.PanelPermission;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class AirstrikeExtension implements MinePanelExtension {

    private static final int DEFAULT_TNT = 5000;
    private static final int MAX_TNT = 20000;
    private static final int WAVE_COUNT = 20;
    private static final long WAVE_INTERVAL_TICKS = 12L;
    private static final double DROP_HEIGHT = 52.0;
    private static final double HORIZONTAL_SPREAD = 5.0;

    private final Gson gson = new Gson();
    private ExtensionContext context;

    @Override
    public String id() {
        return "airstrike";
    }

    @Override
    public String displayName() {
        return "Airstrike";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        webRegistry.post("/api/extensions/airstrike/launch", PanelPermission.MANAGE_AIRSTRIKE, (request, response, user) -> {
            AirstrikePayload payload = gson.fromJson(request.body(), AirstrikePayload.class);
            if (payload == null || (isBlank(payload.uuid()) && isBlank(payload.username()))) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_payload"));
            }

            int amount = sanitizeAmount(payload.amount());

            Player target = resolveOnlineTarget(payload.uuid(), payload.username());
            if (target == null) {
                return webRegistry.json(response, 404, Map.of("error", "player_not_online"));
            }

            launchAirstrike(target, user.username(), amount);
            return webRegistry.json(response, 200, Map.of(
                    "ok", true,
                    "targetUuid", target.getUniqueId().toString(),
                    "targetUsername", target.getName(),
                    "tntCount", amount
            ));
        });
    }

    private void launchAirstrike(Player target, String actorName, int totalTnt) {
        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName();

        context.panelLogger().log("AUDIT", actorName, "Triggered airstrike on " + targetName + " with " + totalTnt + " TNT");

        new BukkitRunnable() {
            private int remainingTnt = totalTnt;
            private int remainingWaves = WAVE_COUNT;

            @Override
            public void run() {
                Player onlineTarget = context.plugin().getServer().getPlayer(targetUuid);
                if (onlineTarget == null || !onlineTarget.isOnline()) {
                    context.panelLogger().log("SYSTEM", "airstrike", "Airstrike cancelled because target went offline: " + targetName);
                    cancel();
                    return;
                }

                int tntThisWave = (int) Math.ceil((double) remainingTnt / Math.max(1, remainingWaves));
                for (int index = 0; index < tntThisWave && remainingTnt > 0; index++) {
                    spawnStrikeTnt(onlineTarget);
                    remainingTnt--;
                }

                remainingWaves--;

                if (remainingWaves <= 0 || remainingTnt <= 0) {
                    cancel();
                }
            }
        }.runTaskTimer(context.plugin(), 0L, WAVE_INTERVAL_TICKS);
    }

    private void spawnStrikeTnt(Player target) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location targetLocation = target.getLocation();
        Vector velocity = target.getVelocity();
        // Lead the strike slightly so waves keep up with sprinting players.
        double leadFactor = 6.0;
        double predictedX = targetLocation.getX() + (velocity.getX() * leadFactor);
        double predictedZ = targetLocation.getZ() + (velocity.getZ() * leadFactor);

        double angle = random.nextDouble(0.0, Math.PI * 2.0);
        double radius = random.nextDouble(0.0, HORIZONTAL_SPREAD);
        double offsetX = Math.cos(angle) * radius;
        double offsetZ = Math.sin(angle) * radius;

        Location strikeCenter = new Location(targetLocation.getWorld(), predictedX, targetLocation.getY(), predictedZ);
        Location spawnLocation = strikeCenter.add(offsetX, DROP_HEIGHT + random.nextDouble(0.0, 8.0), offsetZ);
        TNTPrimed tnt = targetLocation.getWorld().spawn(spawnLocation, TNTPrimed.class);
        tnt.setFuseTicks(65 + random.nextInt(20));

        Vector tntVelocity = new Vector(
                random.nextDouble(-0.08, 0.08),
                -1.85 - random.nextDouble(0.0, 0.45),
                random.nextDouble(-0.08, 0.08)
        );
        tnt.setVelocity(tntVelocity);
    }

    private Player resolveOnlineTarget(String rawUuid, String rawUsername) {
        if (!isBlank(rawUuid)) {
            try {
                Player online = context.plugin().getServer().getPlayer(UUID.fromString(rawUuid.trim()));
                if (online != null) {
                    return online;
                }
            } catch (IllegalArgumentException ignored) {
                // Try username fallback.
            }
        }

        if (!isBlank(rawUsername)) {
            Player online = context.plugin().getServer().getPlayerExact(rawUsername.trim());
            if (online != null) {
                return online;
            }

            Optional<KnownPlayer> known = context.knownPlayerRepository().findByUsername(rawUsername.trim());
            if (known.isPresent()) {
                return context.plugin().getServer().getPlayer(known.get().uuid());
            }
        }

        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int sanitizeAmount(Integer requestedAmount) {
        if (requestedAmount == null) {
            return DEFAULT_TNT;
        }
        return Math.max(1, Math.min(MAX_TNT, requestedAmount));
    }

    private record AirstrikePayload(String uuid, String username, Integer amount) {
    }
}

