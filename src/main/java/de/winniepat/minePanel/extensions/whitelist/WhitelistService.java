package de.winniepat.minePanel.extensions.whitelist;

import de.winniepat.minePanel.extensions.ExtensionContext;
import org.bukkit.OfflinePlayer;

import java.util.*;
import java.util.concurrent.*;

public final class WhitelistService {

    private final ExtensionContext context;

    public WhitelistService(ExtensionContext context) {
        this.context = context;
    }

    public List<Map<String, Object>> listEntries() {
        return runSync(() -> {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (OfflinePlayer player : context.plugin().getServer().getWhitelistedPlayers()) {
                String name = player.getName();
                String username = (name == null || name.isBlank()) ? player.getUniqueId().toString() : name;
                entries.add(Map.of(
                        "uuid", player.getUniqueId().toString(),
                        "username", username,
                        "online", player.isOnline()
                ));
            }

            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.get("username")), String.CASE_INSENSITIVE_ORDER));
            return entries;
        });
    }

    public boolean isWhitelistEnabled() {
        return runSync(() -> context.plugin().getServer().hasWhitelist());
    }

    public boolean setWhitelistEnabled(boolean enabled) {
        return runSync(() -> {
            boolean before = context.plugin().getServer().hasWhitelist();
            if (before == enabled) {
                return false;
            }
            context.plugin().getServer().setWhitelist(enabled);
            return true;
        });
    }

    public ChangeResult addByUsername(String rawUsername) {
        String username = normalizeUsername(rawUsername);
        if (username.isBlank()) {
            return ChangeResult.invalid("invalid_username");
        }

        return runSync(() -> {
            OfflinePlayer target = resolvePlayerByUsername(username);
            if (target == null) {
                return ChangeResult.invalid("player_not_found");
            }

            if (target.isWhitelisted()) {
                return ChangeResult.ok(target, false);
            }

            target.setWhitelisted(true);
            return ChangeResult.ok(target, true);
        });
    }

    public ChangeResult removeByUsername(String rawUsername) {
        String username = normalizeUsername(rawUsername);
        if (username.isBlank()) {
            return ChangeResult.invalid("invalid_username");
        }

        return runSync(() -> {
            OfflinePlayer target = findWhitelistedByUsername(username);
            if (target == null) {
                return ChangeResult.invalid("not_whitelisted");
            }

            target.setWhitelisted(false);
            if (target.isOnline() && target.getPlayer() != null) {
                target.getPlayer().kickPlayer("You have been removed from the whitelist.");
            }
            return ChangeResult.ok(target, true);
        });
    }

    private OfflinePlayer resolvePlayerByUsername(String username) {
        OfflinePlayer online = context.plugin().getServer().getPlayerExact(username);
        if (online != null) {
            return online;
        }

        for (OfflinePlayer offline : context.plugin().getServer().getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(username)) {
                return offline;
            }
        }

        OfflinePlayer fallback = context.plugin().getServer().getOfflinePlayer(username);
        return (fallback.getName() == null && !fallback.hasPlayedBefore()) ? null : fallback;
    }

    private OfflinePlayer findWhitelistedByUsername(String username) {
        for (OfflinePlayer player : context.plugin().getServer().getWhitelistedPlayers()) {
            String name = player.getName();
            if (name != null && name.equalsIgnoreCase(username)) {
                return player;
            }
        }
        return null;
    }

    private String normalizeUsername(String value) {
        if (value == null) {
            return "";
        }
        String username = value.trim();
        if (username.length() < 3 || username.length() > 16) {
            return "";
        }
        if (!username.matches("^[A-Za-z0-9_]+$")) {
            return "";
        }
        return username;
    }

    private <T> T runSync(Callable<T> task) {
        try {
            if (context.plugin().getServer().isPrimaryThread()) {
                try {
                    return task.call();
                } catch (Exception exception) {
                    throw new IllegalStateException("whitelist_task_failed", exception);
                }
            }

            Future<T> future = context.plugin().getServer().getScheduler().callSyncMethod(context.plugin(), task);
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("whitelist_task_interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("whitelist_task_failed", exception);
        }
    }

    public record ChangeResult(boolean success, boolean changed, String error, UUID uuid, String username) {
        private static ChangeResult ok(OfflinePlayer player, boolean changed) {
            String username = player.getName() == null || player.getName().isBlank()
                    ? player.getUniqueId().toString()
                    : player.getName();
            return new ChangeResult(true, changed, "", player.getUniqueId(), username);
        }

        private static ChangeResult invalid(String error) {
            return new ChangeResult(false, false, error, null, "");
        }
    }
}

