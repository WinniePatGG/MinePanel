package de.winniepat.minePanel.extensions.playerstats;

import de.winniepat.minePanel.extensions.ExtensionContext;
import de.winniepat.minePanel.extensions.ExtensionWebRegistry;
import de.winniepat.minePanel.extensions.MinePanelExtension;
import de.winniepat.minePanel.users.PanelPermission;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PlayerStatsExtension implements MinePanelExtension {

    private ExtensionContext context;

    @Override
    public String id() {
        return "player-stats";
    }

    @Override
    public String displayName() {
        return "Player Stats";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        webRegistry.get("/api/extensions/player-stats/player/:uuid", PanelPermission.VIEW_PLAYER_STATS, (request, response, user) -> {
            UUID playerUuid;
            try {
                playerUuid = UUID.fromString(request.params("uuid"));
            } catch (IllegalArgumentException exception) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_uuid"));
            }

            StatsSnapshot snapshot;
            try {
                snapshot = fetchStats(playerUuid);
            } catch (Exception exception) {
                return webRegistry.json(response, 500, Map.of(
                        "available", false,
                        "error", "player_stats_lookup_failed",
                        "details", exception.getClass().getSimpleName()
                ));
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("available", true);
            payload.put("kills", snapshot.kills());
            payload.put("deaths", snapshot.deaths());
            payload.put("economyAvailable", snapshot.economyAvailable());
            payload.put("balance", snapshot.balance());
            payload.put("balanceFormatted", snapshot.balanceFormatted());
            payload.put("economyProvider", snapshot.economyProvider());
            payload.put("generatedAt", System.currentTimeMillis());
            return webRegistry.json(response, 200, payload);
        });
    }

    private StatsSnapshot fetchStats(UUID playerUuid) throws ExecutionException, InterruptedException, TimeoutException {
        return context.plugin().getServer().getScheduler().callSyncMethod(context.plugin(), () -> {
            OfflinePlayer offlinePlayer = context.plugin().getServer().getOfflinePlayer(playerUuid);
            int kills = safeStatistic(offlinePlayer, Statistic.PLAYER_KILLS);
            int deaths = safeStatistic(offlinePlayer, Statistic.DEATHS);

            EconomyLookup economy = lookupEconomy(offlinePlayer);
            return new StatsSnapshot(kills, deaths, economy.available(), economy.balance(), economy.formatted(), economy.provider());
        }).get(2, TimeUnit.SECONDS);
    }

    private int safeStatistic(OfflinePlayer player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private EconomyLookup lookupEconomy(OfflinePlayer player) {
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration = context.plugin().getServer().getServicesManager().getRegistration(economyClass);
            if (registration == null) {
                return EconomyLookup.unavailable();
            }

            Method getProvider = registration.getClass().getMethod("getProvider");
            Object provider = getProvider.invoke(registration);
            if (provider == null) {
                return EconomyLookup.unavailable();
            }

            String providerName;
            try {
                Method getName = provider.getClass().getMethod("getName");
                providerName = String.valueOf(getName.invoke(provider));
            } catch (Exception ignored) {
                providerName = provider.getClass().getSimpleName();
            }

            Double balance = invokeBalance(provider, player);
            if (balance == null) {
                return new EconomyLookup(true, null, "-", providerName);
            }

            String formatted = formatBalance(provider, balance);
            return new EconomyLookup(true, balance, formatted, providerName);
        } catch (ClassNotFoundException ignored) {
            return EconomyLookup.unavailable();
        } catch (Exception ignored) {
            return EconomyLookup.unavailable();
        }
    }

    private Double invokeBalance(Object provider, OfflinePlayer player) {
        try {
            Method getBalanceOffline = provider.getClass().getMethod("getBalance", OfflinePlayer.class);
            Object value = getBalanceOffline.invoke(provider, player);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
        } catch (Exception ignored) {
            // Fallback below.
        }

        try {
            Method getBalanceString = provider.getClass().getMethod("getBalance", String.class);
            String playerName = player.getName();
            if (playerName == null || playerName.isBlank()) {
                return null;
            }
            Object value = getBalanceString.invoke(provider, playerName);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
        } catch (Exception ignored) {
            // No compatible method available.
        }

        return null;
    }

    private String formatBalance(Object provider, double value) {
        try {
            Method formatMethod = provider.getClass().getMethod("format", double.class);
            Object formatted = formatMethod.invoke(provider, value);
            if (formatted != null) {
                return String.valueOf(formatted);
            }
        } catch (Exception ignored) {
            // Fallback below.
        }

        return String.format(Locale.US, "%.2f", value);
    }

    private record StatsSnapshot(
            int kills,
            int deaths,
            boolean economyAvailable,
            Double balance,
            String balanceFormatted,
            String economyProvider
    ) {
    }

    private record EconomyLookup(boolean available, Double balance, String formatted, String provider) {
        private static EconomyLookup unavailable() {
            return new EconomyLookup(false, null, "-", "");
        }
    }
}

