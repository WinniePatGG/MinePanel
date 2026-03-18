package de.winniepat.minePanel;

import de.winniepat.minePanel.auth.*;
import de.winniepat.minePanel.config.WebPanelConfig;
import de.winniepat.minePanel.extensions.*;
import de.winniepat.minePanel.integrations.*;
import de.winniepat.minePanel.logs.*;
import de.winniepat.minePanel.persistence.*;
import de.winniepat.minePanel.web.*;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MinePanel extends JavaPlugin {

    private static final DateTimeFormatter EXPORT_FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private Database database;
    private WebPanelServer webPanelServer;
    private DiscordWebhookService discordWebhookService;
    private LogRepository logRepository;
    private PanelLogger panelLogger;
    private PlayerActivityRepository playerActivityRepository;
    private JoinLeaveEventRepository joinLeaveEventRepository;
    private ExtensionManager extensionManager;
    private ExtensionCommandRegistry extensionCommandRegistry;
    private WebAssetService webAssetService;

    private record StartupContext(
            UserRepository userRepository,
            KnownPlayerRepository knownPlayerRepository,
            SessionService sessionService,
            PasswordHasher passwordHasher,
            ServerLogService serverLogService,
            BootstrapService bootstrapService,
            JoinLeaveEventRepository joinLeaveEventRepository,
            OAuthAccountRepository oAuthAccountRepository,
            OAuthStateRepository oAuthStateRepository,
            ExtensionManager extensionManager,
            WebAssetService webAssetService
    ) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configureThirdPartyStartupLogging();

        WebPanelConfig panelConfig = WebPanelConfig.fromConfig(getConfig());
        StartupContext startupContext = initializeStartupContext(panelConfig);

        announceBootstrapToken(startupContext.bootstrapService());
        registerPluginListeners(startupContext.knownPlayerRepository());
        synchronizeKnownPlayers(startupContext.knownPlayerRepository());
        startWebPanel(panelConfig, startupContext);

        getLogger().info("MinePanel available at http://" + panelConfig.host() + ":" + panelConfig.port());
        panelLogger.log("SYSTEM", "PLUGIN", "MinePanel plugin started");
    }

    private StartupContext initializeStartupContext(WebPanelConfig panelConfig) {
        this.database = new Database(getDataFolder().toPath().resolve("panel.db"));
        this.database.initialize();

        UserRepository userRepository = new UserRepository(database);
        int normalizedOwners = userRepository.demoteExtraOwnersToAdmin();
        if (normalizedOwners > 0) {
            getLogger().warning("Detected multiple owner accounts. Demoted " + normalizedOwners + " extra owner account(s) to ADMIN.");
        }

        this.logRepository = new LogRepository(database);
        KnownPlayerRepository knownPlayerRepository = new KnownPlayerRepository(database);
        this.playerActivityRepository = new PlayerActivityRepository(database);
        this.joinLeaveEventRepository = new JoinLeaveEventRepository(database);

        DiscordWebhookRepository discordWebhookRepository = new DiscordWebhookRepository(database);
        this.discordWebhookService = new DiscordWebhookService(getLogger(), discordWebhookRepository);
        this.panelLogger = new PanelLogger(logRepository, discordWebhookService);
        this.logRepository.clearLogs();

        SessionService sessionService = new SessionService(database, panelConfig.sessionTtlMinutes());
        PasswordHasher passwordHasher = new PasswordHasher();
        ServerLogService serverLogService = new ServerLogService(getDataFolder().toPath());
        BootstrapService bootstrapService = new BootstrapService(userRepository, panelConfig.bootstrapTokenLength());
        OAuthAccountRepository oAuthAccountRepository = new OAuthAccountRepository(database);
        OAuthStateRepository oAuthStateRepository = new OAuthStateRepository(database);
        this.webAssetService = initializeWebAssets();

        this.extensionManager = initializeExtensions(knownPlayerRepository);

        return new StartupContext(
                userRepository,
                knownPlayerRepository,
                sessionService,
                passwordHasher,
                serverLogService,
                bootstrapService,
                joinLeaveEventRepository,
                oAuthAccountRepository,
                oAuthStateRepository,
                extensionManager,
                webAssetService
        );
    }

    private WebAssetService initializeWebAssets() {
        WebAssetService service = new WebAssetService(this, getDataFolder().toPath().resolve("web"));
        service.ensureSeeded();
        return service;
    }

    private ExtensionManager initializeExtensions(KnownPlayerRepository knownPlayerRepository) {
        this.extensionCommandRegistry = new BukkitExtensionCommandRegistry(this);
        ExtensionContext context = new ExtensionContext(
                this,
                database,
                panelLogger,
                knownPlayerRepository,
                playerActivityRepository,
                extensionCommandRegistry
        );
        ExtensionManager manager = new ExtensionManager(this, context);


        Path extensionDirectory = getDataFolder().toPath().resolve("extensions");
        manager.loadFromDirectory(extensionDirectory);
        manager.enableAll();
        return manager;
    }

    private void announceBootstrapToken(BootstrapService bootstrapService) {
        bootstrapService.getBootstrapToken().ifPresent(token -> {
            getLogger().warning("--------------------------------------------------------");
            getLogger().warning("First launch setup token: " + token);
            getLogger().warning("--------------------------------------------------------");
        });
    }

    private void registerPluginListeners(KnownPlayerRepository knownPlayerRepository) {
        getServer().getPluginManager().registerEvents(new ChatCaptureListener(panelLogger), this);
        getServer().getPluginManager().registerEvents(new CommandCaptureListener(panelLogger), this);
        getServer().getPluginManager().registerEvents(new PlayerActivityListener(this, knownPlayerRepository, playerActivityRepository, joinLeaveEventRepository, panelLogger), this);
    }

    private void synchronizeKnownPlayers(KnownPlayerRepository knownPlayerRepository) {
        for (OfflinePlayer offlinePlayer : getServer().getOfflinePlayers()) {
            if (offlinePlayer.getName() != null) {
                knownPlayerRepository.upsert(offlinePlayer.getUniqueId(), offlinePlayer.getName());
                playerActivityRepository.ensureFromOffline(
                        offlinePlayer.getUniqueId(),
                        Math.max(0L, offlinePlayer.getFirstPlayed()),
                        Math.max(0L, offlinePlayer.getLastPlayed())
                );
            }
        }

        getServer().getOnlinePlayers().forEach(player -> {
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

    private void startWebPanel(WebPanelConfig panelConfig, StartupContext startupContext) {
        this.webPanelServer = new WebPanelServer(
                this,
                panelConfig,
                startupContext.userRepository(),
                startupContext.sessionService(),
                startupContext.passwordHasher(),
                this.logRepository,
                startupContext.knownPlayerRepository(),
                playerActivityRepository,
                discordWebhookService,
                panelLogger,
                startupContext.serverLogService(),
                startupContext.bootstrapService(),
                startupContext.joinLeaveEventRepository(),
                startupContext.oAuthAccountRepository(),
                startupContext.oAuthStateRepository(),
                startupContext.extensionManager(),
                startupContext.webAssetService()
        );
        this.webPanelServer.start();
    }

    private void configureThirdPartyStartupLogging() {
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

    @Override
    public void onDisable() {
        if (webPanelServer != null) {
            webPanelServer.stop();
        }

        if (extensionManager != null) {
            extensionManager.disableAll();
        }

        if (playerActivityRepository != null) {
            long now = Instant.now().toEpochMilli();
            getServer().getOnlinePlayers().forEach(player ->
                    playerActivityRepository.onQuit(player.getUniqueId(), now)
            );
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
            Path logsDirectory = getDataFolder().toPath().resolve("logs");
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

            getLogger().info("Exported panel logs to " + exportFile.toAbsolutePath());
        } catch (IOException | IllegalStateException exception) {
            getLogger().warning("Could not export panel logs on disable: " + exception.getMessage());
        }
    }
}
