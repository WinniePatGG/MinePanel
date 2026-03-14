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

import java.nio.file.Path;

public final class MinePanel extends JavaPlugin {

    private Database database;
    private WebPanelServer webPanelServer;
    private DiscordWebhookService discordWebhookService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        WebPanelConfig panelConfig = WebPanelConfig.fromConfig(getConfig());
        Path panelLogDirectory = getDataFolder().toPath().resolve(panelConfig.logDirectoryName());

        this.database = new Database(getDataFolder().toPath().resolve("panel.db"));
        this.database.initialize();

        UserRepository userRepository = new UserRepository(database);
        LogRepository logRepository = new LogRepository(database);
        KnownPlayerRepository knownPlayerRepository = new KnownPlayerRepository(database);
        DiscordWebhookRepository discordWebhookRepository = new DiscordWebhookRepository(database);
        this.discordWebhookService = new DiscordWebhookService(getLogger(), discordWebhookRepository);
        SessionService sessionService = new SessionService(database, panelConfig.sessionTtlMinutes());
        PasswordHasher passwordHasher = new PasswordHasher();
        PanelLogger panelLogger = new PanelLogger(getLogger(), logRepository, discordWebhookService, panelLogDirectory);
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
                logRepository,
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
        if (discordWebhookService != null) {
            discordWebhookService.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }
}
