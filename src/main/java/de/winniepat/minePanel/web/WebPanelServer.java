package de.winniepat.minePanel.web;

import com.google.gson.*;
import de.winniepat.minePanel.MinePanel;
import de.winniepat.minePanel.auth.*;
import de.winniepat.minePanel.config.WebPanelConfig;
import de.winniepat.minePanel.extensions.*;
import de.winniepat.minePanel.integrations.*;
import de.winniepat.minePanel.logs.*;
import de.winniepat.minePanel.persistence.*;
import org.bukkit.BanEntry;
import de.winniepat.minePanel.users.*;
import org.bukkit.BanList;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import spark.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static spark.Spark.after;
import static spark.Spark.awaitInitialization;
import static spark.Spark.awaitStop;
import static spark.Spark.exception;
import static spark.Spark.get;
import static spark.Spark.halt;
import static spark.Spark.ipAddress;
import static spark.Spark.notFound;
import static spark.Spark.path;
import static spark.Spark.port;
import static spark.Spark.post;

public final class WebPanelServer {

    private static final String SESSION_COOKIE = "PANEL_SESSION";
    private static final List<String> PAPER_PLUGIN_LOADERS = List.of("paper", "spigot", "bukkit", "purpur", "folia");
    private static final long OVERVIEW_WINDOW_MILLIS = 60L * 60L * 1000L;
    private static final long OVERVIEW_SAMPLE_INTERVAL_TICKS = 20L * 5L;
    private final long serverStartedAtMillis = System.currentTimeMillis();

    private final MinePanel plugin;
    private final WebPanelConfig config;
    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final PasswordHasher passwordHasher;
    private final LogRepository logRepository;
    private final KnownPlayerRepository knownPlayerRepository;
    private final PlayerActivityRepository playerActivityRepository;
    private final DiscordWebhookService discordWebhookService;
    private final PanelLogger panelLogger;
    private final ServerLogService serverLogService;
    private final BootstrapService bootstrapService;
    private final ExtensionManager extensionManager;
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Deque<MetricSample> overviewSamples = new ArrayDeque<>();
    private BukkitTask overviewSamplerTask;

    public WebPanelServer(
            MinePanel plugin,
            WebPanelConfig config,
            UserRepository userRepository,
            SessionService sessionService,
            PasswordHasher passwordHasher,
            LogRepository logRepository,
            KnownPlayerRepository knownPlayerRepository,
            PlayerActivityRepository playerActivityRepository,
            DiscordWebhookService discordWebhookService,
            PanelLogger panelLogger,
            ServerLogService serverLogService,
            BootstrapService bootstrapService,
            ExtensionManager extensionManager
    ) {
        this.plugin = plugin;
        this.config = config;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.passwordHasher = passwordHasher;
        this.logRepository = logRepository;
        this.knownPlayerRepository = knownPlayerRepository;
        this.playerActivityRepository = playerActivityRepository;
        this.discordWebhookService = discordWebhookService;
        this.panelLogger = panelLogger;
        this.serverLogService = serverLogService;
        this.bootstrapService = bootstrapService;
        this.extensionManager = extensionManager;
    }

    public void start() {
        ipAddress(config.host());
        port(config.port());

        exception(Exception.class, (exception, request, response) -> {
            plugin.getLogger().warning("Web panel error: " + exception.getMessage());
            response.status(500);
            response.type("application/json");
            response.body(gson.toJson(Map.of("error", "internal_server_error")));
        });

        after((request, response) -> response.header("Cache-Control", "no-store"));

        notFound((request, response) -> {
            response.type("application/json");
            return gson.toJson(Map.of("error", "not_found"));
        });

        get("/", (request, response) -> {
            response.type("text/html");
            if (bootstrapService.needsBootstrap()) {
                return ResourceLoader.loadUtf8Text("/web/setup.html");
            }
            return ResourceLoader.loadUtf8Text("/web/login.html");
        });

        get("/setup", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/setup.html");
        });

        get("/dashboard", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-overview.html");
        });

        get("/console", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-console.html");
        });

        get("/dashboard/console", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-console.html");
        });

        get("/dashboard/users", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-users.html");
        });

        get("/dashboard/plugins", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-plugins.html");
        });

        get("/dashboard/overview", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-overview.html");
        });

        get("/dashboard/bans", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-bans.html");
        });


        get("/dashboard/resources", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-resources.html");
        });

        get("/dashboard/health", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-resources.html");
        });

        get("/dashboard/players", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-players.html");
        });

        get("/dashboard/discord-webhook", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-discord-webhook.html");
        });

        get("/dashboard/themes", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-themes.html");
        });

        get("/dashboard/extensions", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-extensions.html");
        });

        get("/dashboard/reports", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-reports.html");
        });

        get("/panel.css", (request, response) -> {
            response.type("text/css");
            return ResourceLoader.loadUtf8Text("/web/panel.css");
        });

        get("/theme.js", (request, response) -> {
            response.type("application/javascript");
            return ResourceLoader.loadUtf8Text("/web/theme.js");
        });

        get("/.well-known/appspecific/com.chrome.devtools.json", (request, response) -> {
            response.status(204);
            return "";
        });

        path("/api", () -> {
            post("/bootstrap", (request, response) -> handleBootstrap(request, response));
            post("/login", (request, response) -> handleLogin(request, response));
            post("/logout", (request, response) -> handleLogout(request, response));
            get("/me", (request, response) -> handleMe(request, response));
            get("/extensions/navigation", (request, response) -> handleExtensionNavigation(request, response));
            get("/extensions/status", (request, response) -> handleExtensionStatus(request, response));
            get("/users", (request, response) -> handleListUsers(request, response));
            post("/users", (request, response) -> handleCreateUser(request, response));
            post("/users/:id/role", (request, response) -> handleUpdateRole(request, response));
            get("/logs", (request, response) -> handleLogs(request, response));
            get("/logs/latest", (request, response) -> handleLatestLogId(request, response));
            get("/players", (request, response) -> handlePlayers(request, response));
            get("/players/profile/:uuid", (request, response) -> handlePlayerProfile(request, response));
            get("/plugins", (request, response) -> handlePlugins(request, response));
            get("/uptime", (request, response) -> handleUptime(request, response));
            get("/health", (request, response) -> handleHealth(request, response));
            get("/overview/metrics", (request, response) -> handleOverviewMetrics(request, response));
            get("/plugin-marketplace/search", (request, response) -> handlePluginMarketplaceSearch(request, response));
            get("/plugin-marketplace/versions", (request, response) -> handlePluginMarketplaceVersions(request, response));
            post("/plugin-marketplace/install", (request, response) -> handlePluginMarketplaceInstall(request, response));
            post("/players/:uuid/kick", (request, response) -> handleKickPlayer(request, response));
            post("/players/:uuid/temp-ban", (request, response) -> handleTempBanPlayer(request, response));
            post("/players/ban", (request, response) -> handleBanPlayer(request, response));
            post("/players/unban", (request, response) -> handleUnbanPlayer(request, response));
            post("/console/send", (request, response) -> handleSendConsole(request, response));
            get("/integrations/discord-webhook", (request, response) -> handleGetDiscordWebhook(request, response));
            post("/integrations/discord-webhook", (request, response) -> handleSaveDiscordWebhook(request, response));
        });

        extensionManager.registerWebRoutes(new ExtensionSparkRegistry());

        startOverviewSampler();
        awaitInitialization();
    }

    public void stop() {
        stopOverviewSampler();
        spark.Spark.stop();
        awaitStop();
    }

    private void startOverviewSampler() {
        if (overviewSamplerTask != null) {
            overviewSamplerTask.cancel();
        }

        // Capture one sample immediately, then continue in a fixed cadence.
        synchronized (overviewSamples) {
            captureOverviewSample();
        }
        overviewSamplerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            synchronized (overviewSamples) {
                captureOverviewSample();
            }
        }, OVERVIEW_SAMPLE_INTERVAL_TICKS, OVERVIEW_SAMPLE_INTERVAL_TICKS);
    }

    private void stopOverviewSampler() {
        if (overviewSamplerTask != null) {
            overviewSamplerTask.cancel();
            overviewSamplerTask = null;
        }
    }

    private void captureOverviewSample() {
        long now = System.currentTimeMillis();
        Runtime runtime = Runtime.getRuntime();
        long totalMemoryBytes = runtime.totalMemory();
        long usedMemoryBytes = totalMemoryBytes - runtime.freeMemory();
        long maxMemoryBytes = runtime.maxMemory();

        double memoryUsedMb = usedMemoryBytes / (1024.0 * 1024.0);
        double memoryPercent = maxMemoryBytes > 0 ? (usedMemoryBytes * 100.0 / maxMemoryBytes) : 0.0;
        double cpuPercent = readProcessCpuPercent();
        double tps = readPrimaryTps();

        overviewSamples.addLast(new MetricSample(now, tps, memoryPercent, memoryUsedMb, cpuPercent));

        long cutoff = now - OVERVIEW_WINDOW_MILLIS;
        while (!overviewSamples.isEmpty() && overviewSamples.peekFirst().timestampMillis() < cutoff) {
            overviewSamples.removeFirst();
        }
    }

    private double readPrimaryTps() {
        try {
            Object result = plugin.getServer().getClass().getMethod("getTPS").invoke(plugin.getServer());
            if (result instanceof double[] values && values.length > 0) {
                return values[0];
            }
        } catch (Exception ignored) {
            // On unsupported server implementations, TPS remains unavailable.
        }
        return -1.0;
    }

    private double readProcessCpuPercent() {
        java.lang.management.OperatingSystemMXBean mxBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (mxBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            double load = sunBean.getProcessCpuLoad();
            if (load >= 0) {
                return load * 100.0;
            }
        }
        return -1.0;
    }

    private String handleOverviewMetrics(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_DASHBOARD);

        List<Map<String, Object>> tpsHistory = new ArrayList<>();
        List<Map<String, Object>> memoryHistory = new ArrayList<>();
        List<Map<String, Object>> cpuHistory = new ArrayList<>();
        MetricSample latest;

        synchronized (overviewSamples) {
            if (overviewSamples.isEmpty()) {
                captureOverviewSample();
            }

            latest = overviewSamples.peekLast();
            for (MetricSample sample : overviewSamples) {
                tpsHistory.add(Map.of("timestamp", sample.timestampMillis(), "value", sample.tps()));
                memoryHistory.add(Map.of("timestamp", sample.timestampMillis(), "value", sample.memoryPercent()));
                cpuHistory.add(Map.of("timestamp", sample.timestampMillis(), "value", sample.cpuPercent()));
            }
        }

        Map<String, Object> current = new HashMap<>();
        double memoryMaxMb = Runtime.getRuntime().maxMemory() / (1024.0 * 1024.0);
        current.put("tps", latest == null ? -1.0 : latest.tps());
        current.put("memoryPercent", latest == null ? 0.0 : latest.memoryPercent());
        current.put("memoryUsedMb", latest == null ? 0.0 : latest.memoryUsedMb());
        current.put("memoryMaxMb", Math.max(0.0, memoryMaxMb));
        current.put("cpuPercent", latest == null ? -1.0 : latest.cpuPercent());

        Map<String, Object> history = new HashMap<>();
        history.put("tps", tpsHistory);
        history.put("memory", memoryHistory);
        history.put("cpu", cpuHistory);

        return json(response, 200, Map.of(
                "current", current,
                "history", history,
                "windowMinutes", 60
        ));
    }

    private String handleBootstrap(Request request, Response response) {
        if (!bootstrapService.needsBootstrap()) {
            return json(response, 400, Map.of("error", "bootstrap_not_required"));
        }

        BootstrapPayload payload = gson.fromJson(request.body(), BootstrapPayload.class);
        if (payload == null || isBlank(payload.token()) || isBlank(payload.username()) || isBlank(payload.password())) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        if (!bootstrapService.verifyToken(payload.token())) {
            return json(response, 403, Map.of("error", "invalid_bootstrap_token"));
        }

        if (!isValidUsername(payload.username()) || payload.password().length() < 10) {
            return json(response, 400, Map.of("error", "weak_credentials"));
        }

        PanelUser user = userRepository.createUser(payload.username().trim(), passwordHasher.hash(payload.password()), UserRole.OWNER);
        panelLogger.log("AUTH", "BOOTSTRAP", "Initial owner account created: " + user.username());

        String sessionToken = sessionService.createSession(user.id());
        setSessionCookie(response, sessionToken, config.sessionTtlMinutes() * 60);
        return json(response, 200, Map.of("ok", true, "user", toPublicUser(user)));
    }

    private String handleLogin(Request request, Response response) {
        if (bootstrapService.needsBootstrap()) {
            return json(response, 400, Map.of("error", "bootstrap_required"));
        }

        LoginPayload payload = gson.fromJson(request.body(), LoginPayload.class);
        if (payload == null || isBlank(payload.username()) || isBlank(payload.password())) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        Optional<PanelUserAuth> authUser = userRepository.findByUsername(payload.username().trim());
        if (authUser.isEmpty() || !passwordHasher.verify(payload.password(), authUser.get().passwordHash())) {
            panelLogger.log("AUTH", payload.username(), "Failed login attempt");
            return json(response, 401, Map.of("error", "invalid_credentials"));
        }

        PanelUser user = authUser.get().user();
        String sessionToken = sessionService.createSession(user.id());
        setSessionCookie(response, sessionToken, config.sessionTtlMinutes() * 60);
        panelLogger.log("AUTH", user.username(), "Login successful");

        return json(response, 200, Map.of("ok", true, "user", toPublicUser(user)));
    }

    private String handleLogout(Request request, Response response) {
        String token = request.cookie(SESSION_COOKIE);
        sessionService.deleteSession(token);
        response.removeCookie(SESSION_COOKIE);
        return json(response, 200, Map.of("ok", true));
    }

    private String handleMe(Request request, Response response) {
        PanelUser user = requireUser(request, PanelPermission.VIEW_DASHBOARD);
        return json(response, 200, Map.of("user", toPublicUser(user)));
    }

    private String handleListUsers(Request request, Response response) {
        requireUser(request, PanelPermission.MANAGE_USERS);
        List<Map<String, Object>> users = userRepository.findAllUsers().stream().map(this::toPublicUser).toList();
        return json(response, 200, Map.of("users", users));
    }

    private String handleCreateUser(Request request, Response response) {
        PanelUser actingUser = requireUser(request, PanelPermission.MANAGE_USERS);

        CreateUserPayload payload = gson.fromJson(request.body(), CreateUserPayload.class);
        if (payload == null || isBlank(payload.username()) || isBlank(payload.password()) || isBlank(payload.role())) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        if (!isValidUsername(payload.username()) || payload.password().length() < 10) {
            return json(response, 400, Map.of("error", "weak_credentials"));
        }

        UserRole role;
        try {
            role = UserRole.fromString(payload.role());
        } catch (IllegalArgumentException exception) {
            return json(response, 400, Map.of("error", "invalid_role"));
        }

        if (userRepository.findByUsername(payload.username().trim()).isPresent()) {
            return json(response, 409, Map.of("error", "username_exists"));
        }

        PanelUser created = userRepository.createUser(payload.username().trim(), passwordHasher.hash(payload.password()), role);
        panelLogger.log("AUDIT", actingUser.username(), "Created panel user " + created.username() + " with role " + role.name());

        return json(response, 201, Map.of("ok", true, "user", toPublicUser(created)));
    }

    private String handleUpdateRole(Request request, Response response) {
        PanelUser actingUser = requireUser(request, PanelPermission.MANAGE_USERS);

        String rawId = request.params("id");
        long userId;
        try {
            userId = Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            return json(response, 400, Map.of("error", "invalid_user_id"));
        }

        UpdateRolePayload payload = gson.fromJson(request.body(), UpdateRolePayload.class);
        if (payload == null || isBlank(payload.role())) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        UserRole role;
        try {
            role = UserRole.fromString(payload.role());
        } catch (IllegalArgumentException exception) {
            return json(response, 400, Map.of("error", "invalid_role"));
        }

        Optional<PanelUser> targetUser = userRepository.findById(userId);
        if (targetUser.isEmpty()) {
            return json(response, 404, Map.of("error", "user_not_found"));
        }

        if (targetUser.get().role() == UserRole.OWNER && actingUser.id() != targetUser.get().id()) {
            return json(response, 403, Map.of("error", "owner_role_locked"));
        }

        if (!userRepository.updateRole(userId, role)) {
            return json(response, 500, Map.of("error", "role_update_failed"));
        }

        panelLogger.log("AUDIT", actingUser.username(), "Changed role for " + targetUser.get().username() + " to " + role.name());
        return json(response, 200, Map.of("ok", true));
    }

    private String handleLogs(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_LOGS);

        int limit = parseInt(request.queryParams("limit"), 200, 1, 1000);
        int consoleLines = parseInt(request.queryParams("consoleLines"), 200, 1, 2000);
        String selectedConsoleFile = request.queryParams("consoleFile");
        if (selectedConsoleFile == null || selectedConsoleFile.isBlank()) {
            selectedConsoleFile = "latest.log";
        }

        List<PanelLogEntry> panelLogs = logRepository.recentLogs(limit);
        List<String> latestConsole = serverLogService.readLogLines(selectedConsoleFile, consoleLines);
        List<String> availableConsoleFiles = serverLogService.listLogFiles();

        if (!availableConsoleFiles.contains(selectedConsoleFile) && availableConsoleFiles.contains("latest.log")) {
            selectedConsoleFile = "latest.log";
            latestConsole = serverLogService.readLogLines(selectedConsoleFile, consoleLines);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("panelLogs", panelLogs);
        payload.put("latestConsole", latestConsole);
        payload.put("selectedConsoleFile", selectedConsoleFile);
        payload.put("availableConsoleFiles", availableConsoleFiles);
        return json(response, 200, payload);
    }

    private String handleLatestLogId(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_LOGS);
        return json(response, 200, Map.of("latestId", logRepository.latestLogId()));
    }

    private String handlePlayers(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_DASHBOARD);
        List<Map<String, Object>> players = snapshotPlayerRoster();
        long onlineCount = players.stream()
                .filter(player -> Boolean.TRUE.equals(player.get("online")))
                .count();

        return json(response, 200, Map.of(
                "count", onlineCount,
                "players", players.stream().filter(player -> Boolean.TRUE.equals(player.get("online"))).toList(),
                "allPlayers", players
        ));
    }

    private String handlePlayerProfile(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_DASHBOARD);

        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(request.params("uuid"));
        } catch (IllegalArgumentException exception) {
            return json(response, 400, Map.of("error", "invalid_uuid"));
        }

        Optional<KnownPlayer> knownPlayer = knownPlayerRepository.findByUuid(playerUuid);
        if (knownPlayer.isEmpty()) {
            return json(response, 404, Map.of("error", "player_not_found"));
        }

        Optional<PlayerActivity> activity = playerActivityRepository.findByUuid(playerUuid);
        boolean online = plugin.getServer().getPlayer(playerUuid) != null;
        String username = knownPlayer.get().username();

        long firstJoined = activity.map(PlayerActivity::firstJoined).orElse(0L);
        long lastSeen = activity.map(PlayerActivity::lastSeen).orElse(knownPlayer.get().lastSeenAt());
        long totalSessions = activity.map(PlayerActivity::totalSessions).orElse(0L);
        long totalPlaytimeSeconds = activity.map(PlayerActivity::totalPlaytimeSeconds).orElse(0L);
        long currentSessionStart = activity.map(PlayerActivity::currentSessionStart).orElse(0L);
        if (online && currentSessionStart > 0) {
            long now = Instant.now().toEpochMilli();
            totalPlaytimeSeconds += Math.max(0L, (now - currentSessionStart) / 1000L);
        }

        Map<String, Object> profile = new HashMap<>();
        profile.put("uuid", playerUuid.toString());
        profile.put("username", username);
        profile.put("online", online);
        profile.put("firstJoined", firstJoined);
        profile.put("lastSeen", lastSeen);
        profile.put("totalPlaytimeSeconds", totalPlaytimeSeconds);
        profile.put("totalSessions", totalSessions);
        profile.put("lastIp", activity.map(PlayerActivity::lastIp).orElse(""));
        profile.put("country", activity.map(PlayerActivity::lastCountry).orElse("Unknown"));

        return json(response, 200, Map.of("profile", profile));
    }

    private String handleBanPlayer(Request request, Response response) {
        PanelUser actingUser = requireUser(request, PanelPermission.MANAGE_PLAYERS);

        BanPayload payload = gson.fromJson(request.body(), BanPayload.class);
        String username = resolveTargetUsername(payload == null ? null : payload.username(), payload == null ? null : payload.uuid());
        if (username == null) {
            return json(response, 400, Map.of("error", "invalid_player"));
        }

        String reason = isBlank(payload == null ? null : payload.reason()) ? "Banned by panel moderator" : payload.reason().trim();
        PlayerActionResult result = banPlayer(username, null, reason, actingUser.username());
        if (!result.success()) {
            return json(response, 500, Map.of("error", result.error() == null ? "ban_failed" : result.error()));
        }

        panelLogger.log("AUDIT", actingUser.username(), "Banned player " + username + ": " + reason);
        return json(response, 200, Map.of("ok", true, "username", username));
    }

    private String handleUnbanPlayer(Request request, Response response) {
        PanelUser actingUser = requireUser(request, PanelPermission.MANAGE_PLAYERS);

        UnbanPayload payload = gson.fromJson(request.body(), UnbanPayload.class);
        String username = resolveTargetUsername(payload == null ? null : payload.username(), payload == null ? null : payload.uuid());
        if (username == null) {
            return json(response, 400, Map.of("error", "invalid_player"));
        }

        boolean removed = unbanPlayer(username);
        if (!removed) {
            return json(response, 404, Map.of("error", "player_not_banned"));
        }

        panelLogger.log("AUDIT", actingUser.username(), "Unbanned player " + username);
        return json(response, 200, Map.of("ok", true, "username", username));
    }

    private String handlePlugins(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_DASHBOARD);
        List<Map<String, Object>> plugins = snapshotInstalledPlugins();
        return json(response, 200, Map.of(
                "count", plugins.size(),
                "plugins", plugins
        ));
    }

    private String handlePluginMarketplaceSearch(Request request, Response response) {
        requireUser(request, PanelPermission.MANAGE_USERS);

        String source = normalizeMarketplaceSource(request.queryParams("source"));
        String query = request.queryParams("query");
        if (isBlank(query)) {
            return json(response, 400, Map.of("error", "query_required"));
        }

        List<Map<String, Object>> projects = switch (source) {
            case "modrinth" -> searchModrinthProjects(query);
            case "curseforge" -> searchCurseForgeProjects(query);
            case "hangar" -> searchHangarProjects(query);
            default -> List.of();
        };

        return json(response, 200, Map.of(
                "source", source,
                "results", projects
        ));
    }

    private String handlePluginMarketplaceVersions(Request request, Response response) {
        requireUser(request, PanelPermission.MANAGE_USERS);

        String source = normalizeMarketplaceSource(request.queryParams("source"));
        String projectId = request.queryParams("projectId");
        if (isBlank(projectId)) {
            return json(response, 400, Map.of("error", "project_id_required"));
        }

        List<Map<String, Object>> versions = switch (source) {
            case "modrinth" -> listModrinthVersions(projectId);
            case "curseforge" -> listCurseForgeFiles(projectId);
            case "hangar" -> listHangarVersions(projectId);
            default -> List.of();
        };

        return json(response, 200, Map.of(
                "source", source,
                "projectId", projectId,
                "versions", versions
        ));
    }

    private String handlePluginMarketplaceInstall(Request request, Response response) {
        PanelUser user = requireUser(request, PanelPermission.MANAGE_USERS);

        PluginInstallPayload payload = gson.fromJson(request.body(), PluginInstallPayload.class);
        if (payload == null || isBlank(payload.source()) || isBlank(payload.projectId()) || isBlank(payload.versionId())) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        String source = normalizeMarketplaceSource(payload.source());
        MarketplaceDownload download;
        try {
            download = switch (source) {
                case "modrinth" -> resolveModrinthDownload(payload.versionId());
                case "curseforge" -> resolveCurseForgeDownload(payload.projectId(), payload.versionId());
                case "hangar" -> resolveHangarDownload(payload.projectId(), payload.versionId());
                default -> null;
            };
        } catch (Exception exception) {
            return json(response, 500, Map.of("error", "marketplace_lookup_failed", "details", exception.getMessage()));
        }

        if (download == null || isBlank(download.downloadUrl()) || isBlank(download.fileName())) {
            return json(response, 404, Map.of("error", "download_not_found"));
        }

        try {
            Path pluginsDirectory = getPluginsDirectory();
            Files.createDirectories(pluginsDirectory);

            String safeFileName = sanitizePluginFileName(download.fileName());
            Path targetFile = pluginsDirectory.resolve(safeFileName).normalize();
            if (!targetFile.startsWith(pluginsDirectory)) {
                return json(response, 400, Map.of("error", "invalid_file_name"));
            }

            HttpRequest requestDownload = HttpRequest.newBuilder(URI.create(download.downloadUrl())).GET().build();
            HttpResponse<byte[]> downloadResponse = httpClient.send(requestDownload, HttpResponse.BodyHandlers.ofByteArray());
            if (downloadResponse.statusCode() >= 300) {
                return json(response, 502, Map.of("error", "download_failed", "status", downloadResponse.statusCode()));
            }

            Files.write(targetFile, downloadResponse.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            panelLogger.log("AUDIT", user.username(), "Installed plugin file " + safeFileName + " from " + source + " marketplace");

            return json(response, 200, Map.of(
                    "ok", true,
                    "fileName", safeFileName,
                    "path", targetFile.toAbsolutePath().toString(),
                    "requiresRestart", true
            ));
        } catch (Exception exception) {
            return json(response, 500, Map.of("error", "install_failed", "details", exception.getMessage()));
        }
    }

    private String handleUptime(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_DASHBOARD);
        long now = System.currentTimeMillis();
        return json(response, 200, Map.of(
                "startedAt", serverStartedAtMillis,
                "uptimeMillis", Math.max(0, now - serverStartedAtMillis)
        ));
    }

    private String handleHealth(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_DASHBOARD);

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double cpuSystemLoad = -1.0;
        double cpuProcessLoad = -1.0;
        int cpuCores = runtime.availableProcessors();
        java.lang.management.OperatingSystemMXBean mxBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (mxBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            cpuSystemLoad = sunBean.getCpuLoad();
            cpuProcessLoad = sunBean.getProcessCpuLoad();
        }

        Map<String, Object> health = new HashMap<>();
        health.put("uptimeMillis", Math.max(0, System.currentTimeMillis() - serverStartedAtMillis));
        health.put("serverName", plugin.getServer().getName());
        health.put("serverVersion", plugin.getServer().getVersion());
        health.put("bukkitVersion", plugin.getServer().getBukkitVersion());
        health.put("onlinePlayers", plugin.getServer().getOnlinePlayers().size());
        health.put("maxPlayers", plugin.getServer().getMaxPlayers());
        health.put("cpuSystemLoad", cpuSystemLoad);
        health.put("cpuProcessLoad", cpuProcessLoad);
        health.put("cpuCores", cpuCores);
        health.put("memoryUsed", usedMemory);
        health.put("memoryTotal", totalMemory);
        health.put("memoryMax", maxMemory);

        try {
            var fileStore = java.nio.file.Files.getFileStore(plugin.getDataFolder().toPath());
            long totalSpace = fileStore.getTotalSpace();
            long freeSpace = fileStore.getUsableSpace();
            health.put("diskTotal", totalSpace);
            health.put("diskUsed", totalSpace - freeSpace);
            health.put("diskFree", freeSpace);
        } catch (Exception ignored) {
            health.put("diskTotal", -1L);
            health.put("diskUsed", -1L);
            health.put("diskFree", -1L);
        }

        return json(response, 200, health);
    }

    private String handleGetDiscordWebhook(Request request, Response response) {
        requireUser(request, PanelPermission.MANAGE_USERS);
        return json(response, 200, toWebhookPayload(discordWebhookService.getConfig()));
    }

    private String handleSaveDiscordWebhook(Request request, Response response) {
        requireUser(request, PanelPermission.MANAGE_USERS);

        DiscordWebhookPayload payload = gson.fromJson(request.body(), DiscordWebhookPayload.class);
        if (payload == null) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        DiscordWebhookConfig previous = discordWebhookService.getConfig();
        String webhookUrl = normalizeWebhookUrl(payload.webhookUrl(), previous.webhookUrl());
        if (payload.enabled() != null && payload.enabled() && webhookUrl.isBlank()) {
            return json(response, 400, Map.of("error", "webhook_url_required"));
        }

        DiscordWebhookConfig updated = new DiscordWebhookConfig(
                payload.enabled() == null ? previous.enabled() : payload.enabled(),
                webhookUrl,
                payload.useEmbed() == null ? previous.useEmbed() : payload.useEmbed(),
                normalizeSimpleText(payload.botName(), previous.botName(), 80),
                normalizeSimpleText(payload.messageTemplate(), previous.messageTemplate(), 600),
                normalizeSimpleText(payload.embedTitleTemplate(), previous.embedTitleTemplate(), 120),
                payload.logChat() == null ? previous.logChat() : payload.logChat(),
                payload.logCommands() == null ? previous.logCommands() : payload.logCommands(),
                payload.logAuth() == null ? previous.logAuth() : payload.logAuth(),
                payload.logAudit() == null ? previous.logAudit() : payload.logAudit(),
                payload.logConsoleResponse() == null ? previous.logConsoleResponse() : payload.logConsoleResponse(),
                payload.logSystem() == null ? previous.logSystem() : payload.logSystem()
        );

        discordWebhookService.updateConfig(updated);
        panelLogger.log("AUDIT", "WEBHOOK", "Discord webhook configuration updated");
        return json(response, 200, Map.of("ok", true, "config", toWebhookPayload(updated)));
    }

    private String handleKickPlayer(Request request, Response response) {
        PanelUser actingUser = requireUser(request, PanelPermission.MANAGE_PLAYERS);

        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(request.params("uuid"));
        } catch (IllegalArgumentException exception) {
            return json(response, 400, Map.of("error", "invalid_uuid"));
        }

        KickPayload payload = gson.fromJson(request.body(), KickPayload.class);
        String reason = payload == null || isBlank(payload.reason()) ? "Kicked by panel moderator" : payload.reason().trim();

        PlayerActionResult result = kickPlayerByUuid(playerUuid, reason);
        if (!result.success()) {
            return json(response, 404, Map.of("error", result.error() == null ? "player_not_online" : result.error()));
        }

        panelLogger.log("AUDIT", actingUser.username(), "Kicked player " + result.username() + ": " + reason);
        return json(response, 200, Map.of("ok", true, "username", result.username()));
    }

    private String handleTempBanPlayer(Request request, Response response) {
        PanelUser actingUser = requireUser(request, PanelPermission.MANAGE_PLAYERS);

        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(request.params("uuid"));
        } catch (IllegalArgumentException exception) {
            return json(response, 400, Map.of("error", "invalid_uuid"));
        }

        TempBanPayload payload = gson.fromJson(request.body(), TempBanPayload.class);
        if (payload == null || payload.durationMinutes() == null) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        int durationMinutes = Math.max(1, Math.min(43_200, payload.durationMinutes()));
        String reason = isBlank(payload.reason()) ? "Temporarily banned by panel moderator" : payload.reason().trim();
        String resolvedUsername = resolveTargetUsername(payload.username(), playerUuid.toString());
        if (resolvedUsername == null) {
            return json(response, 400, Map.of("error", "invalid_player"));
        }

        TempBanResult result = tempBanPlayer(playerUuid, resolvedUsername, durationMinutes, reason, actingUser.username());
        if (!result.success()) {
            return json(response, 404, Map.of("error", result.error() == null ? "ban_failed" : result.error()));
        }

        panelLogger.log("AUDIT", actingUser.username(), "Temp-banned player " + result.username() + " for " + durationMinutes + " minute(s): " + reason);
        return json(response, 200, Map.of(
                "ok", true,
                "username", result.username(),
                "expiresAt", result.expiresAtMillis()
        ));
    }

    private String handleSendConsole(Request request, Response response) {
        PanelUser user = requireUser(request, PanelPermission.SEND_CONSOLE);

        ConsolePayload payload = gson.fromJson(request.body(), ConsolePayload.class);
        if (payload == null || isBlank(payload.message())) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        String rawMessage = payload.message().trim();
        if (rawMessage.isEmpty()) {
            return json(response, 400, Map.of("error", "empty_message"));
        }

        if (rawMessage.contains("\n") || rawMessage.contains("\r")) {
            return json(response, 400, Map.of("error", "invalid_message"));
        }

        String command = rawMessage.startsWith("/") ? rawMessage.substring(1).trim() : rawMessage;
        if (command.isEmpty()) {
            return json(response, 400, Map.of("error", "invalid_message"));
        }

        CommandDispatchResult dispatchResult = dispatchConsoleCommand(command);
        panelLogger.log("AUDIT", user.username(), "Sent console command: " + command);
        panelLogger.log("CONSOLE_RESPONSE", "SERVER", "Command '" + command + "' -> " + dispatchResult.response());

        return json(response, 200, Map.of(
                "ok", true,
                "dispatched", dispatchResult.dispatched(),
                "response", dispatchResult.response()
        ));
    }

    private List<Map<String, Object>> snapshotPlayerRoster() {
        List<OnlinePlayerSnapshot> onlinePlayers = snapshotOnlinePlayers();
        long now = Instant.now().toEpochMilli();
        Map<UUID, PlayerActivity> activityByUuid = playerActivityRepository.findAllByUuid();

        for (OnlinePlayerSnapshot onlinePlayer : onlinePlayers) {
            knownPlayerRepository.upsert(onlinePlayer.uuid(), onlinePlayer.username(), now);
        }

        Map<String, BanStatus> nameBans = snapshotNameBans();
        Map<UUID, Map<String, Object>> byUuid = new HashMap<>();

        for (KnownPlayer knownPlayer : knownPlayerRepository.findAll()) {
            String username = knownPlayer.username();
            BanStatus banStatus = nameBans.get(username.toLowerCase());
            PlayerActivity activity = activityByUuid.get(knownPlayer.uuid());

            long firstJoined = activity == null ? 0L : activity.firstJoined();
            long lastSeen = activity == null ? knownPlayer.lastSeenAt() : Math.max(knownPlayer.lastSeenAt(), activity.lastSeen());
            long totalPlaytimeSeconds = activity == null ? 0L : activity.totalPlaytimeSeconds();
            long totalSessions = activity == null ? 0L : activity.totalSessions();
            String lastIp = activity == null ? "" : activity.lastIp();
            String country = activity == null || isBlank(activity.lastCountry()) ? "Unknown" : activity.lastCountry();

            Map<String, Object> player = new HashMap<>();
            player.put("uuid", knownPlayer.uuid().toString());
            player.put("username", username);
            player.put("online", false);
            player.put("firstJoined", firstJoined);
            player.put("lastSeen", lastSeen);
            player.put("totalPlaytimeSeconds", totalPlaytimeSeconds);
            player.put("totalSessions", totalSessions);
            player.put("lastIp", lastIp);
            player.put("country", country);
            player.put("banned", banStatus != null);
            player.put("banExpiresAt", banStatus == null ? null : banStatus.expiresAtMillis());
            byUuid.put(knownPlayer.uuid(), player);
        }

        for (OnlinePlayerSnapshot onlinePlayer : onlinePlayers) {
            Map<String, Object> row = byUuid.computeIfAbsent(onlinePlayer.uuid(), ignored -> {
                Map<String, Object> created = new HashMap<>();
                created.put("uuid", onlinePlayer.uuid().toString());
                return created;
            });

            BanStatus banStatus = nameBans.get(onlinePlayer.username().toLowerCase());
            row.put("username", onlinePlayer.username());
            row.put("online", true);
            row.put("lastSeen", now);
            row.putIfAbsent("firstJoined", 0L);
            row.putIfAbsent("totalPlaytimeSeconds", 0L);
            row.putIfAbsent("totalSessions", 0L);
            row.putIfAbsent("lastIp", "");
            row.putIfAbsent("country", "Unknown");
            row.put("banned", banStatus != null);
            row.put("banExpiresAt", banStatus == null ? null : banStatus.expiresAtMillis());

            PlayerActivity activity = activityByUuid.get(onlinePlayer.uuid());
            if (activity != null) {
                row.put("firstJoined", activity.firstJoined());
                row.put("totalSessions", activity.totalSessions());
                long onlinePlaytime = activity.totalPlaytimeSeconds();
                if (activity.currentSessionStart() > 0) {
                    onlinePlaytime += Math.max(0L, (now - activity.currentSessionStart()) / 1000L);
                }
                row.put("totalPlaytimeSeconds", onlinePlaytime);
                row.put("lastIp", activity.lastIp());
                row.put("country", isBlank(activity.lastCountry()) ? "Unknown" : activity.lastCountry());
            }
        }

        List<Map<String, Object>> roster = new ArrayList<>(byUuid.values());
        roster.sort((left, right) -> {
            boolean leftOnline = Boolean.TRUE.equals(left.get("online"));
            boolean rightOnline = Boolean.TRUE.equals(right.get("online"));
            if (leftOnline != rightOnline) {
                return leftOnline ? -1 : 1;
            }

            String leftName = String.valueOf(left.getOrDefault("username", ""));
            String rightName = String.valueOf(right.getOrDefault("username", ""));
            return leftName.compareToIgnoreCase(rightName);
        });
        return roster;
    }

    private List<OnlinePlayerSnapshot> snapshotOnlinePlayers() {
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () ->
                    plugin.getServer().getOnlinePlayers().stream()
                            .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                            .map(player -> new OnlinePlayerSnapshot(player.getUniqueId(), player.getName()))
                            .toList()
            ).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading online players", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Could not read online players", exception);
        }
    }

    private Map<String, BanStatus> snapshotNameBans() {
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                Map<String, BanStatus> bans = new HashMap<>();
                for (BanEntry banEntry : plugin.getServer().getBanList(BanList.Type.NAME).getBanEntries()) {
                    String target = banEntry.getTarget();
                    if (target == null) {
                        continue;
                    }
                    Date expiration = banEntry.getExpiration();
                    bans.put(target.toLowerCase(), new BanStatus(expiration == null ? null : expiration.getTime()));
                }
                return bans;
            }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading bans", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Could not read bans", exception);
        }
    }

    private List<Map<String, Object>> snapshotInstalledPlugins() {
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () ->
                    java.util.Arrays.stream(plugin.getServer().getPluginManager().getPlugins())
                            .sorted(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER))
                            .map(current -> Map.<String, Object>of(
                                    "name", current.getName(),
                                    "version", current.getDescription().getVersion(),
                                    "enabled", current.isEnabled(),
                                    "main", current.getDescription().getMain(),
                                    "authors", current.getDescription().getAuthors()
                            ))
                            .toList()
            ).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading plugins", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Could not read installed plugins", exception);
        }
    }

    private CommandDispatchResult dispatchConsoleCommand(String command) {
        try {
            boolean dispatched = plugin.getServer().getScheduler().callSyncMethod(plugin,
                    () -> plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command)
            ).get(2, TimeUnit.SECONDS);
            if (dispatched) {
                return new CommandDispatchResult(true, "executed");
            }
            return new CommandDispatchResult(false, "not_executed_or_unknown_command");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CommandDispatchResult(false, "interrupted");
        } catch (ExecutionException | TimeoutException exception) {
            return new CommandDispatchResult(false, "dispatch_error: " + exception.getClass().getSimpleName());
        }
    }

    private PlayerActionResult kickPlayerByUuid(UUID playerUuid, String reason) {
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                Player target = plugin.getServer().getPlayer(playerUuid);
                if (target == null) {
                    return new PlayerActionResult(false, null, "player_not_online");
                }
                String username = target.getName();
                target.kickPlayer(reason);
                return new PlayerActionResult(true, username, null);
            }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new PlayerActionResult(false, null, "interrupted");
        } catch (ExecutionException | TimeoutException exception) {
            return new PlayerActionResult(false, null, "dispatch_error");
        }
    }

    private TempBanResult tempBanPlayer(UUID playerUuid, String username, int durationMinutes, String reason, String actingUsername) {
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                Player onlinePlayer = plugin.getServer().getPlayer(playerUuid);
                Date expiresAt = Date.from(Instant.now().plus(durationMinutes, ChronoUnit.MINUTES));
                plugin.getServer().getBanList(BanList.Type.NAME)
                        .addBan(username, reason, expiresAt, "WebPanel:" + actingUsername);

                if (onlinePlayer != null) {
                    onlinePlayer.kickPlayer("Temporarily banned for " + durationMinutes + " minute(s). Reason: " + reason);
                }

                return new TempBanResult(true, username, expiresAt.getTime(), null);
            }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new TempBanResult(false, null, 0L, "interrupted");
        } catch (ExecutionException | TimeoutException exception) {
            return new TempBanResult(false, null, 0L, "dispatch_error");
        }
    }

    private PlayerActionResult banPlayer(String username, UUID playerUuid, String reason, String actingUsername) {
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                plugin.getServer().getBanList(BanList.Type.NAME)
                        .addBan(username, reason, null, "WebPanel:" + actingUsername);

                Player onlineTarget = null;
                if (playerUuid != null) {
                    onlineTarget = plugin.getServer().getPlayer(playerUuid);
                }
                if (onlineTarget == null) {
                    onlineTarget = plugin.getServer().getPlayerExact(username);
                }
                if (onlineTarget != null) {
                    onlineTarget.kickPlayer("Banned. Reason: " + reason);
                }

                return new PlayerActionResult(true, username, null);
            }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new PlayerActionResult(false, null, "interrupted");
        } catch (ExecutionException | TimeoutException exception) {
            return new PlayerActionResult(false, null, "dispatch_error");
        }
    }

    private boolean unbanPlayer(String username) {
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                BanEntry banEntry = plugin.getServer().getBanList(BanList.Type.NAME).getBanEntry(username);
                if (banEntry == null) {
                    return false;
                }
                plugin.getServer().getBanList(BanList.Type.NAME).pardon(username);
                return true;
            }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            return false;
        }
    }

    private String resolveTargetUsername(String usernameRaw, String uuidRaw) {
        String sanitized = sanitizeUsername(usernameRaw);
        if (sanitized != null) {
            return sanitized;
        }

        if (uuidRaw == null || uuidRaw.isBlank()) {
            return null;
        }

        try {
            UUID uuid = UUID.fromString(uuidRaw);
            return knownPlayerRepository.findByUuid(uuid)
                    .map(KnownPlayer::username)
                    .orElse(null);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizeMarketplaceSource(String source) {
        if (source == null) {
            return "modrinth";
        }

        String normalized = source.trim().toLowerCase();
        return switch (normalized) {
            case "modrinth", "curseforge", "hangar" -> normalized;
            default -> "modrinth";
        };
    }

    private List<Map<String, Object>> searchModrinthProjects(String query) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String encodedFacets = URLEncoder.encode(
                "[[\"project_type:plugin\"],[\"categories:paper\",\"categories:spigot\",\"categories:bukkit\",\"categories:purpur\",\"categories:folia\"]]",
                StandardCharsets.UTF_8
        );
        JsonObject root = requestJson("https://api.modrinth.com/v2/search?query=" + encodedQuery + "&limit=20&facets=" + encodedFacets);

        List<Map<String, Object>> results = new ArrayList<>();
        JsonArray hits = root.getAsJsonArray("hits");
        if (hits == null) {
            return results;
        }

        for (JsonElement element : hits) {
            JsonObject hit = element.getAsJsonObject();
            JsonArray categories = hit.getAsJsonArray("categories");
            JsonArray displayCategories = hit.getAsJsonArray("display_categories");
            if (!containsAnyIgnoreCase(categories, PAPER_PLUGIN_LOADERS)
                    && !containsAnyIgnoreCase(displayCategories, PAPER_PLUGIN_LOADERS)) {
                continue;
            }

            Map<String, Object> row = new HashMap<>();
            row.put("projectId", stringValue(hit, "project_id"));
            row.put("name", stringValue(hit, "title"));
            row.put("description", stringValue(hit, "description"));
            row.put("author", stringValue(hit, "author"));
            row.put("iconUrl", stringValue(hit, "icon_url"));
            row.put("source", "modrinth");
            results.add(row);
        }
        return results;
    }

    private List<Map<String, Object>> listModrinthVersions(String projectId) {
        String encodedProject = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        JsonArray versions = requestJsonArray("https://api.modrinth.com/v2/project/" + encodedProject + "/version");

        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            JsonArray loaders = version.getAsJsonArray("loaders");
            if (!containsAnyIgnoreCase(loaders, PAPER_PLUGIN_LOADERS)) {
                continue;
            }

            JsonArray files = version.getAsJsonArray("files");
            if (files == null || files.isEmpty()) {
                continue;
            }

            JsonObject selectedFile = pickModrinthPrimaryFile(files);
            if (selectedFile == null) {
                continue;
            }

            Map<String, Object> row = new HashMap<>();
            row.put("versionId", stringValue(version, "id"));
            row.put("name", firstNonBlank(stringValue(version, "name"), stringValue(version, "version_number"), "Version"));
            row.put("gameVersions", listStringValues(version.getAsJsonArray("game_versions")));
            row.put("fileName", stringValue(selectedFile, "filename"));
            row.put("source", "modrinth");
            result.add(row);
        }
        return result;
    }

    private MarketplaceDownload resolveModrinthDownload(String versionId) {
        String encodedVersion = URLEncoder.encode(versionId, StandardCharsets.UTF_8);
        JsonObject version = requestJson("https://api.modrinth.com/v2/version/" + encodedVersion);
        JsonObject selectedFile = pickModrinthPrimaryFile(version.getAsJsonArray("files"));
        if (selectedFile == null) {
            return null;
        }

        return new MarketplaceDownload(
                stringValue(selectedFile, "url"),
                stringValue(selectedFile, "filename")
        );
    }

    private JsonObject pickModrinthPrimaryFile(JsonArray files) {
        if (files == null || files.isEmpty()) {
            return null;
        }

        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            String filename = stringValue(file, "filename");
            if (file.has("primary") && file.get("primary").getAsBoolean() && filename.endsWith(".jar")) {
                return file;
            }
        }
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (stringValue(file, "filename").endsWith(".jar")) {
                return file;
            }
        }
        return files.get(0).getAsJsonObject();
    }

    private boolean containsAnyIgnoreCase(JsonArray array, List<String> expectedValues) {
        if (array == null || expectedValues == null || expectedValues.isEmpty()) {
            return false;
        }

        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String value = element.getAsString();
            if (value == null) {
                continue;
            }
            String lowered = value.toLowerCase();
            for (String expected : expectedValues) {
                if (lowered.equals(expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Map<String, Object>> searchCurseForgeProjects(String query) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonObject root = requestJson("https://api.curse.tools/v1/cf/mods/search?gameId=432&classId=5&pageSize=20&searchFilter=" + encodedQuery);

        List<Map<String, Object>> results = new ArrayList<>();
        JsonArray data = root.getAsJsonArray("data");
        if (data == null) {
            return results;
        }

        for (JsonElement element : data) {
            JsonObject mod = element.getAsJsonObject();
            Map<String, Object> row = new HashMap<>();
            row.put("projectId", stringValue(mod, "id"));
            row.put("name", stringValue(mod, "name"));
            row.put("description", stringValue(mod, "summary"));
            row.put("author", firstAuthorName(mod));
            row.put("iconUrl", nestedStringValue(mod, "logo", "thumbnailUrl"));
            row.put("source", "curseforge");
            results.add(row);
        }
        return results;
    }

    private List<Map<String, Object>> listCurseForgeFiles(String projectId) {
        String encodedProject = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        JsonObject root = requestJson("https://api.curse.tools/v1/cf/mods/" + encodedProject + "/files?pageSize=25");
        JsonArray files = root.getAsJsonArray("data");

        List<Map<String, Object>> result = new ArrayList<>();
        if (files == null) {
            return result;
        }

        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (file.has("isAvailable") && !file.get("isAvailable").getAsBoolean()) {
                continue;
            }

            Map<String, Object> row = new HashMap<>();
            row.put("versionId", stringValue(file, "id"));
            row.put("name", firstNonBlank(stringValue(file, "displayName"), stringValue(file, "fileName"), "File"));
            row.put("gameVersions", listStringValues(file.getAsJsonArray("gameVersions")));
            row.put("fileName", stringValue(file, "fileName"));
            row.put("source", "curseforge");
            result.add(row);
        }
        return result;
    }

    private MarketplaceDownload resolveCurseForgeDownload(String projectId, String versionId) {
        String encodedProject = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        String encodedVersion = URLEncoder.encode(versionId, StandardCharsets.UTF_8);
        JsonObject file = requestJson("https://api.curse.tools/v1/cf/mods/" + encodedProject + "/files/" + encodedVersion);
        JsonObject data = file.getAsJsonObject("data");
        if (data == null) {
            data = file;
        }

        return new MarketplaceDownload(
                stringValue(data, "downloadUrl"),
                stringValue(data, "fileName")
        );
    }

    private List<Map<String, Object>> searchHangarProjects(String query) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonObject root = requestJson("https://hangar.papermc.io/api/v1/projects?query=" + encodedQuery + "&limit=20");

        List<Map<String, Object>> results = new ArrayList<>();
        JsonArray data = root.getAsJsonArray("result");
        if (data == null) {
            return results;
        }

        for (JsonElement element : data) {
            JsonObject project = element.getAsJsonObject();
            JsonObject namespace = project.getAsJsonObject("namespace");
            if (namespace == null) {
                continue;
            }

            String owner = stringValue(namespace, "owner");
            String slug = stringValue(namespace, "slug");
            if (isBlank(owner) || isBlank(slug)) {
                continue;
            }

            Map<String, Object> row = new HashMap<>();
            row.put("projectId", owner + "/" + slug);
            row.put("name", stringValue(project, "name"));
            row.put("description", stringValue(project, "description"));
            row.put("author", owner);
            row.put("iconUrl", stringValue(project, "avatarUrl"));
            row.put("source", "hangar");
            results.add(row);
        }
        return results;
    }

    private List<Map<String, Object>> listHangarVersions(String projectId) {
        String[] parts = splitHangarProject(projectId);
        if (parts == null) {
            return List.of();
        }

        String encodedOwner = URLEncoder.encode(parts[0], StandardCharsets.UTF_8);
        String encodedSlug = URLEncoder.encode(parts[1], StandardCharsets.UTF_8);
        JsonObject root = requestJson("https://hangar.papermc.io/api/v1/projects/" + encodedOwner + "/" + encodedSlug + "/versions?limit=30");
        JsonArray versions = root.getAsJsonArray("result");

        List<Map<String, Object>> result = new ArrayList<>();
        if (versions == null) {
            return result;
        }

        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            JsonObject downloads = version.getAsJsonObject("downloads");
            if (downloads == null || !downloads.has("PAPER")) {
                continue;
            }

            JsonObject paper = downloads.getAsJsonObject("PAPER");
            JsonObject fileInfo = paper.getAsJsonObject("fileInfo");
            JsonObject platformDependencies = version.getAsJsonObject("platformDependencies");
            JsonArray paperVersions = platformDependencies == null ? null : platformDependencies.getAsJsonArray("PAPER");

            Map<String, Object> row = new HashMap<>();
            row.put("versionId", stringValue(version, "name"));
            row.put("name", stringValue(version, "name"));
            row.put("gameVersions", listStringValues(paperVersions));
            row.put("fileName", fileInfo == null ? "" : stringValue(fileInfo, "name"));
            row.put("source", "hangar");
            result.add(row);
        }
        return result;
    }

    private MarketplaceDownload resolveHangarDownload(String projectId, String versionName) {
        String[] parts = splitHangarProject(projectId);
        if (parts == null) {
            return null;
        }

        String encodedOwner = URLEncoder.encode(parts[0], StandardCharsets.UTF_8);
        String encodedSlug = URLEncoder.encode(parts[1], StandardCharsets.UTF_8);
        String encodedVersion = URLEncoder.encode(versionName, StandardCharsets.UTF_8);
        JsonObject root = requestJson("https://hangar.papermc.io/api/v1/projects/" + encodedOwner + "/" + encodedSlug + "/versions/" + encodedVersion);
        JsonObject downloads = root.getAsJsonObject("downloads");
        if (downloads == null || !downloads.has("PAPER")) {
            return null;
        }

        JsonObject paper = downloads.getAsJsonObject("PAPER");
        JsonObject fileInfo = paper.getAsJsonObject("fileInfo");
        return new MarketplaceDownload(
                stringValue(paper, "downloadUrl"),
                fileInfo == null ? "" : stringValue(fileInfo, "name")
        );
    }

    private String[] splitHangarProject(String projectId) {
        if (isBlank(projectId)) {
            return null;
        }

        String[] parts = projectId.split("/");
        if (parts.length != 2 || isBlank(parts[0]) || isBlank(parts[1])) {
            return null;
        }
        return new String[]{parts[0], parts[1]};
    }

    private JsonObject requestJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Request failed with status " + response.statusCode());
            }
            return gson.fromJson(response.body(), JsonObject.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not query marketplace API", exception);
        }
    }

    private JsonArray requestJsonArray(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Request failed with status " + response.statusCode());
            }
            return gson.fromJson(response.body(), JsonArray.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not query marketplace API", exception);
        }
    }

    private Path getPluginsDirectory() {
        Path pluginDataFolder = plugin.getDataFolder().toPath();
        Path pluginsFolder = pluginDataFolder.getParent();
        if (pluginsFolder != null) {
            return pluginsFolder;
        }
        return pluginDataFolder.resolve("plugins");
    }

    private String sanitizePluginFileName(String fileName) {
        String raw = fileName == null ? "plugin.jar" : fileName;
        String cleaned = raw.replace("\\", "").replace("/", "").trim();
        if (cleaned.isEmpty()) {
            return "plugin.jar";
        }
        return cleaned;
    }

    private List<String> listStringValues(JsonArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (JsonElement element : array) {
            if (element != null && !element.isJsonNull()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private String firstAuthorName(JsonObject mod) {
        JsonArray authors = mod.getAsJsonArray("authors");
        if (authors == null || authors.isEmpty()) {
            return "";
        }
        JsonObject first = authors.get(0).getAsJsonObject();
        return stringValue(first, "name");
    }

    private String stringValue(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private String nestedStringValue(JsonObject object, String parentKey, String key) {
        if (object == null || !object.has(parentKey) || object.get(parentKey).isJsonNull()) {
            return "";
        }
        JsonObject parent = object.getAsJsonObject(parentKey);
        return stringValue(parent, key);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private Map<String, Object> toWebhookPayload(DiscordWebhookConfig config) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("enabled", config.enabled());
        payload.put("webhookUrl", config.webhookUrl());
        payload.put("useEmbed", config.useEmbed());
        payload.put("botName", config.botName());
        payload.put("messageTemplate", config.messageTemplate());
        payload.put("embedTitleTemplate", config.embedTitleTemplate());
        payload.put("logChat", config.logChat());
        payload.put("logCommands", config.logCommands());
        payload.put("logAuth", config.logAuth());
        payload.put("logAudit", config.logAudit());
        payload.put("logConsoleResponse", config.logConsoleResponse());
        payload.put("logSystem", config.logSystem());
        return payload;
    }

    private String normalizeWebhookUrl(String value, String fallback) {
        if (value == null) {
            return fallback == null ? "" : fallback;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        if (!(trimmed.startsWith("https://") || trimmed.startsWith("http://"))) {
            return fallback == null ? "" : fallback;
        }
        return trimmed;
    }

    private String normalizeSimpleText(String value, String fallback, int maxLength) {
        String raw = value == null ? fallback : value;
        if (raw == null || raw.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private String sanitizeUsername(String rawUsername) {
        if (rawUsername == null || rawUsername.isBlank()) {
            return null;
        }

        String trimmed = rawUsername.trim();
        if (!trimmed.matches("^[a-zA-Z0-9_]{3,16}$")) {
            return null;
        }
        return trimmed;
    }

    private PanelUser requireUser(Request request, PanelPermission permission) {
        String token = request.cookie(SESSION_COOKIE);
        if (isBlank(token)) {
            throw halt(401, gson.toJson(Map.of("error", "unauthorized")));
        }

        Optional<Long> userId = sessionService.resolveUserId(token);
        if (userId.isEmpty()) {
            throw halt(401, gson.toJson(Map.of("error", "invalid_session")));
        }

        Optional<PanelUser> user = userRepository.findById(userId.get());
        if (user.isEmpty()) {
            throw halt(401, gson.toJson(Map.of("error", "user_not_found")));
        }

        if (!user.get().role().hasPermission(permission)) {
            throw halt(403, gson.toJson(Map.of("error", "forbidden")));
        }

        return user.get();
    }

    private Map<String, Object> toPublicUser(PanelUser user) {
        return Map.of(
                "id", user.id(),
                "username", user.username(),
                "role", user.role().name(),
                "createdAt", user.createdAt()
        );
    }

    private void setSessionCookie(Response response, String token, int maxAgeSeconds) {
        response.raw().addHeader(
                "Set-Cookie",
                SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAgeSeconds
        );
    }

    private String json(Response response, int status, Map<String, Object> payload) {
        response.status(status);
        response.type("application/json");
        return gson.toJson(payload);
    }

    private int parseInt(String rawValue, int fallback, int min, int max) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(rawValue);
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean isValidUsername(String username) {
        return username != null && username.matches("^[a-zA-Z0-9_.-]{3,32}$");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String handleExtensionNavigation(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_DASHBOARD);

        List<Map<String, Object>> tabs = extensionManager.navigationTabs().stream()
                .map(tab -> Map.<String, Object>of(
                        "category", tab.category(),
                        "label", tab.label(),
                        "path", tab.path()
                ))
                .toList();

        return json(response, 200, Map.of("tabs", tabs));
    }

    private String handleExtensionStatus(Request request, Response response) {
        requireUser(request, PanelPermission.MANAGE_USERS);

        List<Map<String, Object>> installed = extensionManager.installedExtensions();
        List<Map<String, Object>> available = extensionManager.availableArtifacts();

        Map<String, Object> payload = new HashMap<>();
        payload.put("installed", installed);
        payload.put("available", available);
        payload.put("installedCount", installed.size());
        payload.put("availableCount", available.size());
        return json(response, 200, payload);
    }

    private final class ExtensionSparkRegistry implements ExtensionWebRegistry {

        @Override
        public void get(String path, PanelPermission permission, ExtensionRouteHandler handler) {
            spark.Spark.get(path, (request, response) -> invokeExtensionHandler(request, response, permission, handler));
        }

        @Override
        public void post(String path, PanelPermission permission, ExtensionRouteHandler handler) {
            spark.Spark.post(path, (request, response) -> invokeExtensionHandler(request, response, permission, handler));
        }

        @Override
        public String json(Response response, int status, Map<String, Object> payload) {
            return WebPanelServer.this.json(response, status, payload);
        }

        private Object invokeExtensionHandler(Request request, Response response, PanelPermission permission, ExtensionRouteHandler handler) throws Exception {
            PanelUser user = requireUser(request, permission);
            return handler.handle(request, response, user);
        }
    }

    private record LoginPayload(String username, String password) {
    }

    private record BootstrapPayload(String token, String username, String password) {
    }

    private record CreateUserPayload(String username, String password, String role) {
    }

    private record UpdateRolePayload(String role) {
    }

    private record ConsolePayload(String message) {
    }

    private record CommandDispatchResult(boolean dispatched, String response) {
    }

    private record KickPayload(String reason) {
    }

    private record TempBanPayload(String username, Integer durationMinutes, String reason) {
    }

    private record BanPayload(String username, String uuid, String reason) {
    }

    private record UnbanPayload(String username, String uuid) {
    }

    private record DiscordWebhookPayload(
            Boolean enabled,
            String webhookUrl,
            Boolean useEmbed,
            String botName,
            String messageTemplate,
            String embedTitleTemplate,
            Boolean logChat,
            Boolean logCommands,
            Boolean logAuth,
            Boolean logAudit,
            Boolean logConsoleResponse,
            Boolean logSystem
    ) {
    }

    private record PluginInstallPayload(String source, String projectId, String versionId) {
    }

    private record MarketplaceDownload(String downloadUrl, String fileName) {
    }

    private record PlayerActionResult(boolean success, String username, String error) {
    }

    private record TempBanResult(boolean success, String username, long expiresAtMillis, String error) {
    }

    private record OnlinePlayerSnapshot(UUID uuid, String username) {
    }

    private record BanStatus(Long expiresAtMillis) {
    }

    private record MetricSample(long timestampMillis, double tps, double memoryPercent, double memoryUsedMb, double cpuPercent) {
    }
}

