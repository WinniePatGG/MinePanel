package de.winniepat.minePanel;

import de.winniepat.minePanel.auth.PasswordHasher;
import de.winniepat.minePanel.auth.SessionService;
import de.winniepat.minePanel.config.WebPanelConfig;
import de.winniepat.minePanel.integrations.DiscordWebhookRepository;
import de.winniepat.minePanel.integrations.DiscordWebhookService;
import de.winniepat.minePanel.logs.ChatCaptureListener;
import de.winniepat.minePanel.logs.CommandCaptureListener;
import de.winniepat.minePanel.logs.PanelLogger;
import de.winniepat.minePanel.logs.PlayerSeenListener;
import de.winniepat.minePanel.logs.ServerLogService;
import de.winniepat.minePanel.persistence.Database;
import de.winniepat.minePanel.persistence.KnownPlayerRepository;
import de.winniepat.minePanel.persistence.LogRepository;
import de.winniepat.minePanel.persistence.UserRepository;
import de.winniepat.minePanel.web.BootstrapService;
import de.winniepat.minePanel.web.WebPanelServer;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class MinePanel extends JavaPlugin {

    private static final DateTimeFormatter EXPORT_FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private Database database;
    private WebPanelServer webPanelServer;
    private DiscordWebhookService discordWebhookService;
    private LogRepository logRepository;
    private PanelLogger panelLogger;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        WebPanelConfig panelConfig = WebPanelConfig.fromConfig(getConfig());
        Path panelLogDirectory = getDataFolder().toPath().resolve(panelConfig.logDirectoryName());

        this.database = new Database(getDataFolder().toPath().resolve("panel.db"));
        this.database.initialize();

        UserRepository userRepository = new UserRepository(database);
        this.logRepository = new LogRepository(database);
        KnownPlayerRepository knownPlayerRepository = new KnownPlayerRepository(database);
        DiscordWebhookRepository discordWebhookRepository = new DiscordWebhookRepository(database);
        this.discordWebhookService = new DiscordWebhookService(getLogger(), discordWebhookRepository);
        SessionService sessionService = new SessionService(database, panelConfig.sessionTtlMinutes());
        PasswordHasher passwordHasher = new PasswordHasher();
        this.panelLogger = new PanelLogger(getLogger(), logRepository, discordWebhookService, panelLogDirectory);
        ServerLogService serverLogService = new ServerLogService(getDataFolder().toPath());
        BootstrapService bootstrapService = new BootstrapService(userRepository, panelConfig.bootstrapTokenLength());

        logRepository.clearLogs();
        panelLogger.clearLogFiles();

        bootstrapService.getBootstrapToken().ifPresent(token -> {
                getLogger().warning("--------------------------------------------------------");
                getLogger().warning("First launch setup token: " + token);
                getLogger().warning("--------------------------------------------------------");

                }
        );

        getServer().getPluginManager().registerEvents(new ChatCaptureListener(panelLogger), this);
        getServer().getPluginManager().registerEvents(new CommandCaptureListener(panelLogger), this);
        getServer().getPluginManager().registerEvents(new PlayerSeenListener(knownPlayerRepository), this);

        for (OfflinePlayer offlinePlayer : getServer().getOfflinePlayers()) {
            if (offlinePlayer.getName() != null) {
                knownPlayerRepository.upsert(offlinePlayer.getUniqueId(), offlinePlayer.getName());
            }
        }
        getServer().getOnlinePlayers().forEach(player ->
                knownPlayerRepository.upsert(player.getUniqueId(), player.getName())
        );

        this.webPanelServer = new WebPanelServer(
                this,
                panelConfig,
                userRepository,
                sessionService,
                passwordHasher,
                this.logRepository,
                knownPlayerRepository,
                discordWebhookService,
                panelLogger,
                serverLogService,
                bootstrapService
        );
        this.webPanelServer.start();

        getLogger().info("MinePanel available at http://" + panelConfig.host() + ":" + panelConfig.port());
        panelLogger.log("SYSTEM", "PLUGIN", "MinePanel plugin started");

    }

    @Override
    public void onDisable() {
        if (webPanelServer != null) {
            webPanelServer.stop();
        }

        if (panelLogger != null) {
            panelLogger.log("SYSTEM", "PLUGIN", "MinePanel plugin stopping");
        }

        if (logRepository != null) {
            exportPanelLogsToServerLogsDirectory();
        }

        if (discordWebhookService != null) {
            discordWebhookService.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    private void exportPanelLogsToServerLogsDirectory() {
        try {
            Path pluginDataFolder = getDataFolder().toPath();
            Path pluginsFolder = pluginDataFolder.getParent();
            Path serverRoot = pluginsFolder == null ? pluginDataFolder : pluginsFolder.getParent();
            if (serverRoot == null) {
                serverRoot = pluginDataFolder;
            }

            Path logsDirectory = serverRoot.resolve("logs");
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

            Files.writeString(
                    exportFile,
                    output,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );

            getLogger().info("Exported panel logs to " + exportFile.toAbsolutePath());
        } catch (IOException | IllegalStateException exception) {
            getLogger().warning("Could not export panel logs on disable: " + exception.getMessage());
        }
    }
}
