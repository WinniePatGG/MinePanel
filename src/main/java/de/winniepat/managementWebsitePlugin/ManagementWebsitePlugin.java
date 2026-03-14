package de.winniepat.managementWebsitePlugin;

import de.winniepat.managementWebsitePlugin.auth.PasswordHasher;
import de.winniepat.managementWebsitePlugin.auth.SessionService;
import de.winniepat.managementWebsitePlugin.config.WebPanelConfig;
import de.winniepat.managementWebsitePlugin.logs.ChatCaptureListener;
import de.winniepat.managementWebsitePlugin.logs.CommandCaptureListener;
import de.winniepat.managementWebsitePlugin.logs.PanelLogger;
import de.winniepat.managementWebsitePlugin.logs.ServerLogService;
import de.winniepat.managementWebsitePlugin.persistence.Database;
import de.winniepat.managementWebsitePlugin.persistence.LogRepository;
import de.winniepat.managementWebsitePlugin.persistence.UserRepository;
import de.winniepat.managementWebsitePlugin.web.BootstrapService;
import de.winniepat.managementWebsitePlugin.web.WebPanelServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class ManagementWebsitePlugin extends JavaPlugin {

    private Database database;
    private WebPanelServer webPanelServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        WebPanelConfig panelConfig = WebPanelConfig.fromConfig(getConfig());
        Path panelLogDirectory = getDataFolder().toPath().resolve(panelConfig.logDirectoryName());

        this.database = new Database(getDataFolder().toPath().resolve("panel.db"));
        this.database.initialize();

        UserRepository userRepository = new UserRepository(database);
        LogRepository logRepository = new LogRepository(database);
        SessionService sessionService = new SessionService(database, panelConfig.sessionTtlMinutes());
        PasswordHasher passwordHasher = new PasswordHasher();
        PanelLogger panelLogger = new PanelLogger(getLogger(), logRepository, panelLogDirectory);
        ServerLogService serverLogService = new ServerLogService(getDataFolder().toPath());
        BootstrapService bootstrapService = new BootstrapService(userRepository, panelConfig.bootstrapTokenLength());

        // Keep each runtime session clean by resetting panel log history on startup.
        logRepository.clearLogs();
        panelLogger.clearLogFiles();

        bootstrapService.getBootstrapToken().ifPresent(token ->
                getLogger().warning("First launch setup token: " + token + " (open /setup in the panel and use this token)")
        );

        getServer().getPluginManager().registerEvents(new ChatCaptureListener(panelLogger), this);
        getServer().getPluginManager().registerEvents(new CommandCaptureListener(panelLogger), this);

        this.webPanelServer = new WebPanelServer(
                this,
                panelConfig,
                userRepository,
                sessionService,
                passwordHasher,
                logRepository,
                panelLogger,
                serverLogService,
                bootstrapService
        );
        this.webPanelServer.start();

        getLogger().info("Management panel available at http://" + panelConfig.host() + ":" + panelConfig.port());
        panelLogger.log("SYSTEM", "PLUGIN", "Management website plugin started");

    }

    @Override
    public void onDisable() {
        if (webPanelServer != null) {
            webPanelServer.stop();
        }
        if (database != null) {
            database.close();
        }
    }
}
