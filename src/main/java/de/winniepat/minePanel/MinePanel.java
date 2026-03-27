package de.winniepat.minePanel;

import de.winniepat.minePanel.auth.*;
import de.winniepat.minePanel.config.WebPanelConfig;
import de.winniepat.minePanel.extensions.*;
import de.winniepat.minePanel.integrations.*;
import de.winniepat.minePanel.lifecycle.PluginLifecycleSupport;
import de.winniepat.minePanel.logs.*;
import de.winniepat.minePanel.persistence.*;
import de.winniepat.minePanel.util.ServerSchedulerBridge;
import de.winniepat.minePanel.web.*;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.time.Instant;

public final class MinePanel extends JavaPlugin {

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
    private ExtensionSettingsRepository extensionSettingsRepository;
    private ServerSchedulerBridge schedulerBridge;

    MiniMessage mm = MiniMessage.miniMessage();
    ComponentLogger componentLogger = this.getComponentLogger();

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
            WebAssetService webAssetService,
            ExtensionSettingsRepository extensionSettingsRepository
    ) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginLifecycleSupport.configureThirdPartyStartupLogging();
        this.schedulerBridge = new ServerSchedulerBridge(this);

        WebPanelConfig panelConfig = WebPanelConfig.fromConfig(getConfig());
        StartupContext startupContext = initializeStartupContext(panelConfig);

        PluginLifecycleSupport.announceMinePanelBanner(this, componentLogger, mm);
        PluginLifecycleSupport.announceBootstrapToken(startupContext.bootstrapService(), componentLogger, mm);

        PluginLifecycleSupport.registerPluginListeners(
                this,
                panelLogger,
                startupContext.knownPlayerRepository(),
                playerActivityRepository,
                joinLeaveEventRepository
        );
        PluginLifecycleSupport.synchronizeKnownPlayers(getServer(), startupContext.knownPlayerRepository(), playerActivityRepository);
        startWebPanel(panelConfig, startupContext);

        componentLogger.info("{}{}:{}", mm.deserialize("<aqua>MinePanel available at: </aqua>"), "http://" +  panelConfig.host(), panelConfig.port());
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
        this.extensionSettingsRepository = new ExtensionSettingsRepository(database);
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
                webAssetService,
                extensionSettingsRepository
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
                extensionCommandRegistry,
                schedulerBridge
        );
        ExtensionManager manager = new ExtensionManager(this, context);


        Path extensionDirectory = getDataFolder().toPath().resolve("extensions");
        manager.loadFromDirectory(extensionDirectory);
        manager.enableAll();
        return manager;
    }

    public ServerSchedulerBridge schedulerBridge() {
        return schedulerBridge;
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
                startupContext.webAssetService(),
                startupContext.extensionSettingsRepository()
        );
        this.webPanelServer.start();
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
            PluginLifecycleSupport.exportPanelLogsToServerLogsDirectory(getDataFolder().toPath(), logRepository, getLogger());
        }

        if (discordWebhookService != null) {
            discordWebhookService.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

}
