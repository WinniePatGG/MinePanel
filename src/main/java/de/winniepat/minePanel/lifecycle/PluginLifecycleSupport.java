package de.winniepat.minePanel.lifecycle;

import de.winniepat.minePanel.MinePanel;
import de.winniepat.minePanel.logs.*;
import de.winniepat.minePanel.persistence.*;
import de.winniepat.minePanel.web.BootstrapService;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;

public final class PluginLifecycleSupport {

    private static final DateTimeFormatter EXPORT_FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private PluginLifecycleSupport() {
    }

    @SuppressWarnings("all")
    public static void configureThirdPartyStartupLogging() {
        System.setProperty("org.eclipse.jetty.util.log.announce", "false");
        System.setProperty("org.eclipse.jetty.LEVEL", "WARN");

        Logger.getLogger("spark").setLevel(Level.WARNING);
        Logger.getLogger("org.eclipse.jetty").setLevel(Level.WARNING);
        Logger.getLogger("org.eclipse.jetty.util.log").setLevel(Level.WARNING);

        try {
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Class<?> configuratorClass = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Method setLevelMethod = configuratorClass.getMethod("setLevel", String.class, levelClass);
            Object warnLevel = levelClass.getField("WARN").get(null);

            setLevelMethod.invoke(null, "spark", warnLevel);
            setLevelMethod.invoke(null, "org.eclipse.jetty", warnLevel);
            setLevelMethod.invoke(null, "org.eclipse.jetty.util.log", warnLevel);
        } catch (ReflectiveOperationException ignored) {

        }
    }

    public static void announceMinePanelBanner(JavaPlugin plugin, ComponentLogger componentLogger, MiniMessage miniMessage) {
        plugin.getLogger().info("");
        componentLogger.info(miniMessage.deserialize("<gold> __  __  ____  </gold>"));
        componentLogger.info(miniMessage.deserialize("<gold>|  \\/  | |  _ \\ </gold>"));
        componentLogger.info("{}{}", miniMessage.deserialize("<gold>| |\\/| | | |_) |  MinePanel: </gold>"), miniMessage.deserialize("<green>" + plugin.getDescription().getVersion() + "</green>"));
        componentLogger.info("{}{}", miniMessage.deserialize("<gold>| |  | | |  __/   Running on: </gold>"), miniMessage.deserialize("<aqua>" + plugin.getServer().getName() + "</aqua>" + "(" + plugin.getServer().getMinecraftVersion() + ")"));
        componentLogger.info(miniMessage.deserialize("<gold>|_|  |_| |_|      </gold>"));
        plugin.getLogger().info("");
    }

    public static void announceBootstrapToken(BootstrapService bootstrapService, ComponentLogger componentLogger, MiniMessage miniMessage) {
        bootstrapService.getBootstrapToken().ifPresent(token -> {
            componentLogger.info("{}{}", miniMessage.deserialize("<dark_green>First launch setup token: </dark_green>"), token);
            componentLogger.info("");
        });
    }

    public static void registerPluginListeners(
            MinePanel plugin,
            PanelLogger panelLogger,
            KnownPlayerRepository knownPlayerRepository,
            PlayerActivityRepository playerActivityRepository,
            JoinLeaveEventRepository joinLeaveEventRepository
    ) {
        plugin.getServer().getPluginManager().registerEvents(new ChatCaptureListener(panelLogger), plugin);
        plugin.getServer().getPluginManager().registerEvents(new CommandCaptureListener(panelLogger), plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new PlayerActivityListener(plugin, knownPlayerRepository, playerActivityRepository, joinLeaveEventRepository, panelLogger),
                plugin
        );
    }

    public static void synchronizeKnownPlayers(Server server, KnownPlayerRepository knownPlayerRepository, PlayerActivityRepository playerActivityRepository) {
        for (OfflinePlayer offlinePlayer : server.getOfflinePlayers()) {
            if (offlinePlayer.getName() != null) {
                knownPlayerRepository.upsert(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                playerActivityRepository.ensureFromOffline(
                        offlinePlayer.getUniqueId(),
                        Math.max(0L, offlinePlayer.getFirstPlayed()),
                        Math.max(0L, offlinePlayer.getLastPlayed())
                );
            }
        }

        server.getOnlinePlayers().forEach(player -> {
            knownPlayerRepository.upsert(player.getUniqueId(), player.getName());
            long now = Instant.now().toEpochMilli();
            playerActivityRepository.onJoin(
                    player.getUniqueId(),
                    now,
                    player.getAddress() != null && player.getAddress().getAddress() != null
                            ? player.getAddress().getAddress().getHostAddress()
                            : ""
            );
        });
    }

    public static void exportPanelLogsToServerLogsDirectory(Path dataFolder, LogRepository logRepository, Logger logger) {
        try {
            Path logsDirectory = dataFolder.resolve("logs");
            Files.createDirectories(logsDirectory);

            String timestamp = EXPORT_FILE_NAME_FORMAT.format(Instant.now().atZone(ZoneOffset.UTC));
            Path exportFile = logsDirectory.resolve("minepanel-panel-" + timestamp + ".log");

            StringBuilder output = new StringBuilder();
            for (var entry : logRepository.allLogsAscending()) {
                output.append(Instant.ofEpochMilli(entry.createdAt()))
                        .append(" [")
                        .append(entry.kind())
                        .append("] [")
                        .append(entry.source())
                        .append("] ")
                        .append(entry.message())
                        .append(System.lineSeparator());
            }

            if (output.isEmpty()) {
                output.append(Instant.now()).append(" [SYSTEM] [PLUGIN] No panel logs available during shutdown export").append(System.lineSeparator());
            }

            Files.writeString(exportFile, output, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            logger.info("Exported panel logs to " + exportFile.toAbsolutePath());
        } catch (IOException | IllegalStateException exception) {
            logger.warning("Could not export panel logs on disable: " + exception.getMessage());
        }
    }
}

