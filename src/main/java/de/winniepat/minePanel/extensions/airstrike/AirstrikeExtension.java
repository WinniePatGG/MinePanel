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
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

            TargetSnapshot target;
            try {
                target = context.schedulerBridge().callGlobal(
                        () -> resolveOnlineTarget(payload.uuid(), payload.username()),
                        2,
                        TimeUnit.SECONDS
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return webRegistry.json(response, 500, Map.of("error", "interrupted"));
            } catch (ExecutionException | TimeoutException exception) {
                return webRegistry.json(response, 500, Map.of("error", "lookup_failed"));
            }

            if (target == null) {
                return webRegistry.json(response, 404, Map.of("error", "player_not_online"));
            }

            try {
                launchAirstrike(target.uuid(), target.username(), user.username(), amount);
            } catch (IllegalStateException exception) {
                return webRegistry.json(response, 500, Map.of("error", "airstrike_schedule_failed"));
            }

            return webRegistry.json(response, 200, Map.of(
                    "ok", true,
                    "targetUuid", target.uuid().toString(),
                    "targetUsername", target.username(),
                    "tntCount", amount
            ));
        });
    }

    private void launchAirstrike(UUID targetUuid, String targetName, String actorName, int totalTnt) {
        context.panelLogger().log("AUDIT", actorName, "Triggered airstrike on " + targetName + " with " + totalTnt + " TNT");

        final de.winniepat.minePanel.util.ServerSchedulerBridge.CancellableTask[] repeatingTask = new de.winniepat.minePanel.util.ServerSchedulerBridge.CancellableTask[1];

        Runnable waveTask = new Runnable() {
            private int remainingTnt = totalTnt;
            private int remainingWaves = WAVE_COUNT;

            @Override
            public void run() {
                Player onlineTarget = context.plugin().getServer().getPlayer(targetUuid);
                if (onlineTarget == null || !onlineTarget.isOnline()) {
                    context.panelLogger().log("SYSTEM", "airstrike", "Airstrike cancelled because target went offline: " + targetName);
                    if (repeatingTask[0] != null) {
                        repeatingTask[0].cancel();
                    }
                    return;
                }

                int tntThisWave = (int) Math.ceil((double) remainingTnt / Math.max(1, remainingWaves));
                boolean dispatched = runOnPlayerScheduler(onlineTarget, () -> {
                    for (int index = 0; index < tntThisWave; index++) {
                        spawnStrikeTnt(onlineTarget);
                    }
                });

                if (!dispatched) {
                    context.panelLogger().log("SYSTEM", "airstrike", "Airstrike cancelled because player scheduler dispatch failed: " + targetName);
                    if (repeatingTask[0] != null) {
                        repeatingTask[0].cancel();
                    }
                    return;
                }

                remainingTnt = Math.max(0, remainingTnt - tntThisWave);

                remainingWaves--;

                if (remainingWaves <= 0 || remainingTnt <= 0) {
                    if (repeatingTask[0] != null) {
                        repeatingTask[0].cancel();
                    }
                }
            }
        };

        repeatingTask[0] = context.schedulerBridge().runRepeatingGlobal(waveTask, 0L, WAVE_INTERVAL_TICKS);
    }

    private boolean runOnPlayerScheduler(Player player, Runnable task) {
        if (!context.schedulerBridge().isFolia()) {
            task.run();
            return true;
        }

        try {
            Object entityScheduler = player.getClass().getMethod("getScheduler").invoke(player);
            Method runMethod = entityScheduler.getClass().getMethod("run", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, Runnable.class);
            runMethod.invoke(entityScheduler, context.plugin(), (java.util.function.Consumer<Object>) ignored -> task.run(), null);
            return true;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private void spawnStrikeTnt(Player target) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location targetLocation = target.getLocation();
        Vector velocity = target.getVelocity();
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

    private TargetSnapshot resolveOnlineTarget(String rawUuid, String rawUsername) {
        if (!isBlank(rawUuid)) {
            try {
                Player online = context.plugin().getServer().getPlayer(UUID.fromString(rawUuid.trim()));
                if (online != null) {
                    return new TargetSnapshot(online.getUniqueId(), online.getName());
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (!isBlank(rawUsername)) {
            Player online = context.plugin().getServer().getPlayerExact(rawUsername.trim());
            if (online != null) {
                return new TargetSnapshot(online.getUniqueId(), online.getName());
            }

            Optional<KnownPlayer> known = context.knownPlayerRepository().findByUsername(rawUsername.trim());
            if (known.isPresent()) {
                Player knownOnline = context.plugin().getServer().getPlayer(known.get().uuid());
                if (knownOnline != null) {
                    return new TargetSnapshot(knownOnline.getUniqueId(), knownOnline.getName());
                }
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

    private record TargetSnapshot(UUID uuid, String username) {
    }
}

