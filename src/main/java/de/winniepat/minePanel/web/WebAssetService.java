package de.winniepat.minePanel.web;

import de.winniepat.minePanel.MinePanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class WebAssetService {

    private static final List<String> BUNDLED_WEB_FILES = List.of(
            "dashboard-account.html",
            "dashboard-announcements.html",
            "dashboard-bans.html",
            "dashboard-console.html",
            "dashboard-discord-webhook.html",
            "dashboard-extension-config.html",
            "dashboard-extensions.html",
            "dashboard-maintenance.html",
            "dashboard-overview.html",
            "dashboard-players.html",
            "dashboard-plugins.html",
            "dashboard-reports.html",
            "dashboard-resources.html",
            "dashboard-tickets.html",
            "dashboard-world-backups.html",
            "dashboard-whitelist.html",
            "dashboard-themes.html",
            "dashboard-users.html",
            "login.html",
            "panel.css",
            "setup.html",
            "theme.js"
    );

    private static final String LEGACY_EXTENSIONS_DOWNLOAD_MARKER = "href=\"${downloadUrl}\"";
    private static final String CURRENT_EXTENSIONS_INSTALL_MARKER = "data-install-extension";
    private static final long VERSION_CACHE_TTL_MILLIS = TimeUnit.SECONDS.toMillis(2);

    private final MinePanel plugin;
    private final Path webDirectory;
    private final Map<String, CachedTextAsset> cachedTextAssets = new ConcurrentHashMap<>();
    private volatile long cachedVersion = 0L;
    private volatile long cachedVersionExpiresAt = 0L;

    public WebAssetService(MinePanel plugin, Path webDirectory) {
        this.plugin = plugin;
        this.webDirectory = webDirectory;
    }

    public void ensureSeeded() {
        try {
            Files.createDirectories(webDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create web directory: " + webDirectory, exception);
        }

        for (String fileName : BUNDLED_WEB_FILES) {
            Path target = webDirectory.resolve(fileName).normalize();
            if (!target.startsWith(webDirectory)) {
                continue;
            }
            if (Files.exists(target)) {
                migrateLegacyAssets(fileName, target);
                continue;
            }
            plugin.saveResource("web/" + fileName, false);
        }
    }

    private void migrateLegacyAssets(String fileName, Path target) {
        if (!"dashboard-extensions.html".equals(fileName)) {
            return;
        }

        try {
            String current = Files.readString(target, StandardCharsets.UTF_8);
            if (current.contains(CURRENT_EXTENSIONS_INSTALL_MARKER)) {
                return;
            }
            if (!current.contains(LEGACY_EXTENSIONS_DOWNLOAD_MARKER)) {
                return;
            }

            String bundled = ResourceLoader.loadUtf8Text("/web/" + fileName);
            Files.writeString(target, bundled, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            plugin.getLogger().info("Updated legacy web template: " + fileName);
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not migrate legacy web template " + fileName + ": " + exception.getMessage());
        }
    }

    public String readText(String fileName) {
        Path target = resolveFile(fileName);
        try {
            long lastModified = Files.exists(target) ? Files.getLastModifiedTime(target).toMillis() : 0L;
            CachedTextAsset cached = cachedTextAssets.get(fileName);
            if (cached != null && cached.lastModifiedMillis() == lastModified) {
                return cached.content();
            }

            String content = Files.readString(target, StandardCharsets.UTF_8);
            cachedTextAssets.put(fileName, new CachedTextAsset(lastModified, content));
            return content;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read web asset: " + fileName, exception);
        }
    }

    public long currentVersion() {
        long now = System.currentTimeMillis();
        if (now < cachedVersionExpiresAt) {
            return cachedVersion;
        }

        long newest = 0L;
        for (String fileName : BUNDLED_WEB_FILES) {
            Path target = resolveFile(fileName);
            if (!Files.exists(target)) {
                continue;
            }
            try {
                newest = Math.max(newest, Files.getLastModifiedTime(target).toMillis());
            } catch (IOException ignored) {
                // Ignore broken files so the rest of the panel can continue serving assets.
            }
        }
        cachedVersion = newest;
        cachedVersionExpiresAt = now + VERSION_CACHE_TTL_MILLIS;
        return newest;
    }

    public long lastModifiedMillis(String fileName) {
        Path target = resolveFile(fileName);
        try {
            if (!Files.exists(target)) {
                return 0L;
            }
            return Files.getLastModifiedTime(target).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private Path resolveFile(String fileName) {
        Path target = webDirectory.resolve(fileName).normalize();
        if (!target.startsWith(webDirectory)) {
            throw new IllegalArgumentException("Invalid web asset path: " + fileName);
        }
        return target;
    }

    public Path webDirectory() {
        return webDirectory;
    }

    private record CachedTextAsset(long lastModifiedMillis, String content) {
    }
}

