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

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Instant;
import java.time.ZoneId;
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
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String OAUTH_MODE_LOGIN = "login";
    private static final String OAUTH_MODE_LINK = "link";
    private final long serverStartedAtMillis = System.currentTimeMillis();

    private final MinePanel plugin;
    private final WebPanelConfig config;
    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final PasswordHasher passwordHasher;
    private final LogRepository logRepository;
    private final KnownPlayerRepository knownPlayerRepository;
    private final PlayerActivityRepository playerActivityRepository;
    private final JoinLeaveEventRepository joinLeaveEventRepository;
    private final DiscordWebhookService discordWebhookService;
    private final PanelLogger panelLogger;
    private final ServerLogService serverLogService;
    private final BootstrapService bootstrapService;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final OAuthStateRepository oAuthStateRepository;
    private final ExtensionManager extensionManager;
    private final WebAssetService webAssetService;
    private final ExtensionSettingsRepository extensionSettingsRepository;
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Deque<MetricSample> overviewSamples = new ArrayDeque<>();
    private JsonArray cachedGitHubReleases = new JsonArray();
    private long githubReleaseCacheExpiresAtMillis = 0L;
    private String lastGitHubCatalogError = "";
    private final Set<String> restartRequiredExtensionIds = new HashSet<>();
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
            JoinLeaveEventRepository joinLeaveEventRepository,
            OAuthAccountRepository oAuthAccountRepository,
            OAuthStateRepository oAuthStateRepository,
            ExtensionManager extensionManager,
            WebAssetService webAssetService,
            ExtensionSettingsRepository extensionSettingsRepository
    ) {
        this.plugin = plugin;
        this.config = config;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.passwordHasher = passwordHasher;
        this.logRepository = logRepository;
        this.knownPlayerRepository = knownPlayerRepository;
        this.playerActivityRepository = playerActivityRepository;
        this.joinLeaveEventRepository = joinLeaveEventRepository;
        this.discordWebhookService = discordWebhookService;
        this.panelLogger = panelLogger;
        this.serverLogService = serverLogService;
        this.bootstrapService = bootstrapService;
        this.oAuthAccountRepository = oAuthAccountRepository;
        this.oAuthStateRepository = oAuthStateRepository;
        this.extensionManager = extensionManager;
        this.webAssetService = webAssetService;
        this.extensionSettingsRepository = extensionSettingsRepository;
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
                return webAssetService.readText("setup.html");
            }
            return webAssetService.readText("login.html");
        });

        get("/setup", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("setup.html");
        });

        get("/dashboard", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-overview.html");
        });

        get("/console", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-console.html");
        });

        get("/dashboard/console", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-console.html");
        });

        get("/dashboard/users", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-users.html");
        });

        get("/dashboard/plugins", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-plugins.html");
        });

        get("/dashboard/overview", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-overview.html");
        });

        get("/dashboard/bans", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-bans.html");
        });


        get("/dashboard/resources", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-resources.html");
        });

        get("/dashboard/health", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-resources.html");
        });

        get("/dashboard/players", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-players.html");
        });

        get("/dashboard/discord-webhook", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-discord-webhook.html");
        });

        get("/dashboard/themes", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-themes.html");
        });

        get("/dashboard/extensions", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-extensions.html");
        });

        get("/dashboard/extension-config", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-extension-config.html");
        });

        get("/dashboard/account", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-account.html");
        });

        get("/dashboard/reports", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-reports.html");
        });

        get("/dashboard/tickets", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-tickets.html");
        });

        get("/dashboard/world-backups", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-world-backups.html");
        });

        get("/dashboard/maintenance", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-maintenance.html");
        });

        get("/dashboard/whitelist", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-whitelist.html");
        });

        get("/dashboard/announcements", (request, response) -> {
            response.type("text/html");
            return webAssetService.readText("dashboard-announcements.html");
        });

        get("/panel.css", (request, response) -> {
            response.type("text/css");
            return webAssetService.readText("panel.css");
        });

        get("/theme.js", (request, response) -> {
            response.type("application/javascript");
            return webAssetService.readText("theme.js");
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
            get("/oauth/providers", (request, response) -> handleOAuthProviders(response));
            post("/oauth/:provider/start", (request, response) -> handleOAuthStart(request, response));
            get("/oauth/:provider/callback", (request, response) -> handleOAuthCallback(request, response));
            post("/oauth/:provider/unlink", (request, response) -> handleOAuthUnlink(request, response));
            get("/account/links", (request, response) -> handleAccountLinks(request, response));
            get("/extensions/navigation", (request, response) -> handleExtensionNavigation(request, response));
            get("/extensions/status", (request, response) -> handleExtensionStatus(request, response));
            get("/extensions/config", (request, response) -> handleExtensionConfigList(request, response));
            get("/extensions/config/:id", (request, response) -> handleExtensionConfigGet(request, response));
            post("/extensions/config/:id", (request, response) -> handleExtensionConfigSave(request, response));
            post("/extensions/install", (request, response) -> handleExtensionInstall(request, response));
            post("/extensions/reload", (request, response) -> handleExtensionReload(request, response));
            get("/web/live-version", (request, response) -> handleWebLiveVersion(response));
            get("/users", (request, response) -> handleListUsers(request, response));
            post("/users", (request, response) -> handleCreateUser(request, response));
            post("/users/:id/role", (request, response) -> handleUpdateRole(request, response));
            post("/users/:id/permissions", (request, response) -> handleUpdatePermissions(request, response));
            post("/users/:id/delete", (request, response) -> handleDeleteUser(request, response));
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

    private String handleWebLiveVersion(Response response) {
        response.type("application/json");
        return gson.toJson(Map.of("version", webAssetService.currentVersion()));
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
        long committedHeapBytes = runtime.totalMemory();
        long maxMemoryBytes = runtime.maxMemory();
        long nonHeapUsedBytes = readNonHeapUsedBytes();
        long nativeBufferBytes = readBufferPoolUsedBytes("direct") + readBufferPoolUsedBytes("mapped");

        long effectiveUsedMemoryBytes = committedHeapBytes + nonHeapUsedBytes + nativeBufferBytes;

        double memoryUsedMb = effectiveUsedMemoryBytes / (1024.0 * 1024.0);
        double memoryPercent = maxMemoryBytes > 0 ? (effectiveUsedMemoryBytes * 100.0 / maxMemoryBytes) : 0.0;
        memoryPercent = Math.max(0.0, Math.min(100.0, memoryPercent));
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

    private long readNonHeapUsedBytes() {
        try {
            MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
            if (memoryMXBean == null || memoryMXBean.getNonHeapMemoryUsage() == null) {
                return 0L;
            }
            return Math.max(0L, memoryMXBean.getNonHeapMemoryUsage().getUsed());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long readBufferPoolUsedBytes(String poolName) {
        try {
            for (BufferPoolMXBean bufferPool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if (bufferPool != null && poolName.equalsIgnoreCase(bufferPool.getName())) {
                    return Math.max(0L, bufferPool.getMemoryUsed());
                }
            }
        } catch (Exception ignored) {
            // Optional JVM metric; treat as unavailable.
        }
        return 0L;
    }

    private String handleOverviewMetrics(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_OVERVIEW);

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

        long heatmapWindowDays = 14L;
        long since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(heatmapWindowDays);
        JoinLeaveHeatmap heatmap = buildJoinLeaveHeatmaps(joinLeaveEventRepository.listEventsSince(since));

        return json(response, 200, Map.of(
                "current", current,
                "history", history,
                "windowMinutes", 60,
                "joinHeatmap", heatmap.joinCells(),
                "leaveHeatmap", heatmap.leaveCells(),
                "heatmapMaxJoin", heatmap.maxJoin(),
                "heatmapMaxLeave", heatmap.maxLeave(),
                "heatmapWindowDays", heatmapWindowDays
        ));
    }

    private JoinLeaveHeatmap buildJoinLeaveHeatmaps(List<JoinLeaveEventRepository.JoinLeaveEvent> events) {
        int[][] joins = new int[7][24];
        int[][] leaves = new int[7][24];

        for (JoinLeaveEventRepository.JoinLeaveEvent event : events) {
            var dateTime = Instant.ofEpochMilli(event.createdAt()).atZone(ZoneId.systemDefault());
            int day = dateTime.getDayOfWeek().getValue() - 1;
            int hour = dateTime.getHour();
            if (day < 0 || day > 6 || hour < 0 || hour > 23) {
                continue;
            }

            if ("JOIN".equalsIgnoreCase(event.eventType())) {
                joins[day][hour] += 1;
            } else if ("LEAVE".equalsIgnoreCase(event.eventType())) {
                leaves[day][hour] += 1;
            }
        }

        List<Map<String, Object>> joinCells = new ArrayList<>();
        List<Map<String, Object>> leaveCells = new ArrayList<>();
        int maxJoin = 0;
        int maxLeave = 0;

        for (int day = 0; day < 7; day++) {
            for (int hour = 0; hour < 24; hour++) {
                int joinCount = joins[day][hour];
                int leaveCount = leaves[day][hour];
                maxJoin = Math.max(maxJoin, joinCount);
                maxLeave = Math.max(maxLeave, leaveCount);

                joinCells.add(Map.of("day", day, "hour", hour, "count", joinCount));
                leaveCells.add(Map.of("day", day, "hour", hour, "count", leaveCount));
            }
        }

        return new JoinLeaveHeatmap(joinCells, leaveCells, maxJoin, maxLeave);
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
        PanelUser user = requireUser(request, PanelPermission.ACCESS_PANEL);
        return json(response, 200, Map.of("user", toPublicUser(user)));
    }

    private String handleOAuthProviders(Response response) {
        List<Map<String, Object>> providers = new ArrayList<>();
        providers.add(oauthProviderPayload("google", "Google"));
        providers.add(oauthProviderPayload("discord", "Discord"));
        return json(response, 200, Map.of("providers", providers));
    }

    private String handleOAuthStart(Request request, Response response) {
        if (bootstrapService.needsBootstrap()) {
            return json(response, 400, Map.of("error", "bootstrap_required"));
        }

        String provider = normalizeOAuthProvider(request.params("provider"));
        WebPanelConfig.OAuthProviderConfig providerConfig = config.oauthProvider(provider);
        if (!providerConfig.configured()) {
            return json(response, 400, Map.of("error", "oauth_provider_not_configured"));
        }

        OAuthStartPayload payload;
        try {
            String body = request.body();
            payload = (body == null || body.isBlank()) ? null : gson.fromJson(body, OAuthStartPayload.class);
        } catch (JsonSyntaxException exception) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }
        String mode = payload == null ? OAUTH_MODE_LOGIN : normalizeOAuthMode(payload.mode());
        if (!OAUTH_MODE_LOGIN.equals(mode) && !OAUTH_MODE_LINK.equals(mode)) {
            return json(response, 400, Map.of("error", "invalid_oauth_mode"));
        }

        Long userId = null;
        if (OAUTH_MODE_LINK.equals(mode)) {
            userId = requireUser(request, PanelPermission.ACCESS_PANEL).id();
        }

        String state = generateToken(24);
        long expiresAt = Instant.now().plus(config.oauthStateTtlMinutes(), ChronoUnit.MINUTES).toEpochMilli();
        oAuthStateRepository.createState(state, provider, mode, userId, expiresAt);

        String authUrl = buildOAuthAuthorizationUrl(provider, providerConfig, state);
        return json(response, 200, Map.of("ok", true, "provider", provider, "mode", mode, "authUrl", authUrl));
    }

    private Object handleOAuthCallback(Request request, Response response) {
        if (bootstrapService.needsBootstrap()) {
            response.redirect("/setup?oauth=bootstrap_required");
            return "";
        }

        String provider = normalizeOAuthProvider(request.params("provider"));
        WebPanelConfig.OAuthProviderConfig providerConfig = config.oauthProvider(provider);
        if (!providerConfig.configured()) {
            response.redirect("/?oauth=provider_not_configured");
            return "";
        }

        String callbackError = request.queryParams("error");
        if (!isBlank(callbackError)) {
            response.redirect("/?oauth=" + urlEncode("provider_error:" + callbackError));
            return "";
        }

        String code = request.queryParams("code");
        String state = request.queryParams("state");
        if (isBlank(code) || isBlank(state)) {
            response.redirect("/?oauth=invalid_callback");
            return "";
        }

        Optional<OAuthStateRepository.OAuthState> oauthState = oAuthStateRepository.consumeState(state, provider, OAUTH_MODE_LOGIN);
        String mode = OAUTH_MODE_LOGIN;
        if (oauthState.isEmpty()) {
            oauthState = oAuthStateRepository.consumeState(state, provider, OAUTH_MODE_LINK);
            mode = OAUTH_MODE_LINK;
        }
        if (oauthState.isEmpty()) {
            response.redirect("/?oauth=invalid_state");
            return "";
        }

        String modeFailureRedirect = OAUTH_MODE_LINK.equals(mode) ? "/dashboard/account" : "/";

        OAuthUserProfile profile;
        try {
            String accessToken = exchangeOAuthCodeForAccessToken(provider, providerConfig, code);
            profile = fetchOAuthUserProfile(provider, accessToken);
        } catch (Exception exception) {
            plugin.getLogger().warning("OAuth callback failed for provider=" + provider + ": " + exception.getMessage());
            response.redirect(modeFailureRedirect + "?oauth=exchange_failed");
            return "";
        }

        if (OAUTH_MODE_LINK.equals(mode)) {
            Long userId = oauthState.get().userId();
            if (userId == null) {
                response.redirect("/dashboard/account?oauth=invalid_state");
                return "";
            }

            Optional<Long> existingOwner = oAuthAccountRepository.findUserIdByProviderSubject(provider, profile.providerUserId());
            if (existingOwner.isPresent() && !Objects.equals(existingOwner.get(), userId)) {
                response.redirect("/dashboard/account?oauth=already_linked");
                return "";
            }

            oAuthAccountRepository.upsertLink(userId, provider, profile.providerUserId(), profile.displayName(), profile.email(), profile.avatarUrl());
            panelLogger.log("AUTH", "OAUTH", "Linked " + provider + " account for panel user id=" + userId);
            response.redirect("/dashboard/account?oauth=linked");
            return "";
        }

        Optional<Long> userId = oAuthAccountRepository.findUserIdByProviderSubject(provider, profile.providerUserId());
        if (userId.isEmpty()) {
            response.redirect("/?oauth=not_linked");
            return "";
        }

        Optional<PanelUser> user = userRepository.findById(userId.get());
        if (user.isEmpty()) {
            response.redirect("/?oauth=user_not_found");
            return "";
        }

        String sessionToken = sessionService.createSession(user.get().id());
        setSessionCookie(response, sessionToken, config.sessionTtlMinutes() * 60);
        panelLogger.log("AUTH", user.get().username(), "OAuth login successful via " + provider);
        response.redirect("/dashboard/overview");
        return "";
    }

    private String handleOAuthUnlink(Request request, Response response) {
        PanelUser user = requireUser(request, PanelPermission.ACCESS_PANEL);
        String provider = normalizeOAuthProvider(request.params("provider"));
        if (provider.isBlank()) {
            return json(response, 400, Map.of("error", "invalid_oauth_provider"));
        }

        boolean removed = oAuthAccountRepository.unlink(user.id(), provider);
        if (!removed) {
            return json(response, 404, Map.of("error", "oauth_link_not_found"));
        }

        panelLogger.log("AUDIT", user.username(), "Unlinked OAuth provider " + provider);
        return json(response, 200, Map.of("ok", true));
    }

    private String handleAccountLinks(Request request, Response response) {
        PanelUser user = requireUser(request, PanelPermission.ACCESS_PANEL);
        Map<String, OAuthAccountRepository.OAuthAccountLink> byProvider = new HashMap<>();
        for (OAuthAccountRepository.OAuthAccountLink link : oAuthAccountRepository.listByUserId(user.id())) {
            byProvider.put(normalizeOAuthProvider(link.provider()), link);
        }

        List<Map<String, Object>> providers = new ArrayList<>();
        providers.add(accountProviderPayload("google", "Google", byProvider.get("google")));
        providers.add(accountProviderPayload("discord", "Discord", byProvider.get("discord")));
        return json(response, 200, Map.of("providers", providers));
    }

    private String handleListUsers(Request request, Response response) {
        requireOwner(request);

        List<Map<String, Object>> users = userRepository.findAllUsers().stream().map(this::toPublicUser).toList();
        return json(response, 200, Map.of(
                "users", users,
                "permissions", permissionCatalogForCurrentRuntime(),
                "roleDefaults", roleDefaultsPayload()
        ));
    }

    private String handleCreateUser(Request request, Response response) {
        PanelUser actingUser = requireOwner(request);

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

        if (role == UserRole.OWNER) {
            return json(response, 403, Map.of("error", "owner_creation_blocked"));
        }

        if (userRepository.findByUsername(payload.username().trim()).isPresent()) {
            return json(response, 409, Map.of("error", "username_exists"));
        }

        Set<PanelPermission> requestedPermissions = parsePermissionNames(payload.permissions());
        PanelUser created = userRepository.createUser(payload.username().trim(), passwordHasher.hash(payload.password()), role, requestedPermissions);
        panelLogger.log("AUDIT", actingUser.username(), "Created panel user " + created.username() + " with role " + role.name());

        return json(response, 201, Map.of("ok", true, "user", toPublicUser(created)));
    }

    private String handleUpdateRole(Request request, Response response) {
        PanelUser actingUser = requireOwner(request);

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

        if (role == UserRole.OWNER) {
            return json(response, 403, Map.of("error", "owner_creation_blocked"));
        }

        if (targetUser.get().role() == UserRole.OWNER) {
            return json(response, 403, Map.of("error", "owner_role_locked"));
        }

        if (!userRepository.updateRole(userId, role)) {
            return json(response, 500, Map.of("error", "role_update_failed"));
        }

        panelLogger.log("AUDIT", actingUser.username(), "Changed role for " + targetUser.get().username() + " to " + role.name());
        return json(response, 200, Map.of("ok", true));
    }

    private String handleUpdatePermissions(Request request, Response response) {
        PanelUser actingUser = requireOwner(request);

        String rawId = request.params("id");
        long userId;
        try {
            userId = Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            return json(response, 400, Map.of("error", "invalid_user_id"));
        }

        Optional<PanelUser> targetUser = userRepository.findById(userId);
        if (targetUser.isEmpty()) {
            return json(response, 404, Map.of("error", "user_not_found"));
        }
        if (targetUser.get().role() == UserRole.OWNER) {
            return json(response, 403, Map.of("error", "owner_permissions_locked"));
        }

        UpdatePermissionsPayload payload = gson.fromJson(request.body(), UpdatePermissionsPayload.class);
        if (payload == null || payload.permissions() == null) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        Set<PanelPermission> permissions = parsePermissionNames(payload.permissions());
        if (!userRepository.updatePermissions(userId, permissions)) {
            return json(response, 500, Map.of("error", "permissions_update_failed"));
        }

        panelLogger.log("AUDIT", actingUser.username(), "Updated permissions for " + targetUser.get().username());
        return json(response, 200, Map.of("ok", true));
    }

    private String handleDeleteUser(Request request, Response response) {
        PanelUser actingUser = requireOwner(request);

        String rawId = request.params("id");
        long userId;
        try {
            userId = Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            return json(response, 400, Map.of("error", "invalid_user_id"));
        }

        Optional<PanelUser> targetUser = userRepository.findById(userId);
        if (targetUser.isEmpty()) {
            return json(response, 404, Map.of("error", "user_not_found"));
        }

        PanelUser target = targetUser.get();
        if (target.role() == UserRole.OWNER) {
            return json(response, 403, Map.of("error", "owner_deletion_blocked"));
        }

        if (target.id() == actingUser.id()) {
            return json(response, 403, Map.of("error", "self_deletion_blocked"));
        }

        if (!userRepository.deleteUser(target.id())) {
            return json(response, 500, Map.of("error", "user_delete_failed"));
        }

        sessionService.deleteSessionsByUserId(target.id());
        panelLogger.log("AUDIT", actingUser.username(), "Deleted panel user " + target.username() + " (id=" + target.id() + ")");
        return json(response, 200, Map.of("ok", true));
    }

    private String handleLogs(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_CONSOLE);

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
        requireUser(request, PanelPermission.VIEW_CONSOLE);
        return json(response, 200, Map.of("latestId", logRepository.latestLogId()));
    }

    private String handlePlayers(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_PLAYERS);
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
        requireUser(request, PanelPermission.VIEW_PLAYERS);

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
        requireUser(request, PanelPermission.VIEW_PLUGINS);
        List<Map<String, Object>> plugins = snapshotInstalledPlugins();
        return json(response, 200, Map.of(
                "count", plugins.size(),
                "plugins", plugins
        ));
    }

    private String handlePluginMarketplaceSearch(Request request, Response response) {
        requireUser(request, PanelPermission.MANAGE_PLUGINS);

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
        requireUser(request, PanelPermission.MANAGE_PLUGINS);

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
        PanelUser user = requireUser(request, PanelPermission.MANAGE_PLUGINS);

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
        requireUser(request, PanelPermission.VIEW_RESOURCES);
        long now = System.currentTimeMillis();
        return json(response, 200, Map.of(
                "startedAt", serverStartedAtMillis,
                "uptimeMillis", Math.max(0, now - serverStartedAtMillis)
        ));
    }

    private String handleHealth(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_RESOURCES);

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
        requireUser(request, PanelPermission.VIEW_DISCORD_WEBHOOK);
        return json(response, 200, toWebhookPayload(discordWebhookService.getConfig()));
    }

    private String handleSaveDiscordWebhook(Request request, Response response) {
        requireUser(request, PanelPermission.MANAGE_DISCORD_WEBHOOK);

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
                payload.logSecurity() == null ? previous.logSecurity() : payload.logSecurity(),
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
        payload.put("logSecurity", config.logSecurity());
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

    private Map<String, Object> oauthProviderPayload(String provider, String label) {
        WebPanelConfig.OAuthProviderConfig providerConfig = config.oauthProvider(provider);
        return Map.of(
                "provider", provider,
                "label", label,
                "enabled", providerConfig.enabled(),
                "configured", providerConfig.configured()
        );
    }

    private Map<String, Object> accountProviderPayload(String provider, String label, OAuthAccountRepository.OAuthAccountLink link) {
        WebPanelConfig.OAuthProviderConfig providerConfig = config.oauthProvider(provider);
        Map<String, Object> payload = new HashMap<>();
        payload.put("provider", provider);
        payload.put("label", label);
        payload.put("enabled", providerConfig.enabled());
        payload.put("configured", providerConfig.configured());

        if (link == null) {
            payload.put("linked", false);
            return payload;
        }

        payload.put("linked", true);
        payload.put("displayName", link.displayName());
        payload.put("email", link.email());
        payload.put("avatarUrl", link.avatarUrl());
        payload.put("linkedAt", link.linkedAt());
        payload.put("updatedAt", link.updatedAt());
        return payload;
    }

    private String normalizeOAuthProvider(String rawProvider) {
        if (rawProvider == null) {
            return "";
        }

        String normalized = rawProvider.trim().toLowerCase(Locale.ROOT);
        if ("google".equals(normalized) || "discord".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private String normalizeOAuthMode(String rawMode) {
        if (rawMode == null) {
            return OAUTH_MODE_LOGIN;
        }

        String normalized = rawMode.trim().toLowerCase(Locale.ROOT);
        if (OAUTH_MODE_LINK.equals(normalized)) {
            return OAUTH_MODE_LINK;
        }
        return OAUTH_MODE_LOGIN;
    }

    private String buildOAuthAuthorizationUrl(String provider, WebPanelConfig.OAuthProviderConfig providerConfig, String state) {
        String authorizationEndpoint;
        String scope;
        if ("google".equals(provider)) {
            authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth";
            scope = "openid profile email";
        } else if ("discord".equals(provider)) {
            authorizationEndpoint = "https://discord.com/api/oauth2/authorize";
            scope = "identify email";
        } else {
            throw new IllegalArgumentException("Unsupported OAuth provider: " + provider);
        }

        return authorizationEndpoint
                + "?client_id=" + urlEncode(providerConfig.clientId())
                + "&redirect_uri=" + urlEncode(providerConfig.redirectUri())
                + "&response_type=code"
                + "&scope=" + urlEncode(scope)
                + "&state=" + urlEncode(state)
                + "&prompt=" + urlEncode("select_account");
    }

    private String exchangeOAuthCodeForAccessToken(String provider, WebPanelConfig.OAuthProviderConfig providerConfig, String code)
            throws IOException, InterruptedException {
        String endpoint;
        if ("google".equals(provider)) {
            endpoint = "https://oauth2.googleapis.com/token";
        } else if ("discord".equals(provider)) {
            endpoint = "https://discord.com/api/oauth2/token";
        } else {
            throw new IllegalArgumentException("Unsupported OAuth provider: " + provider);
        }

        String body = "grant_type=authorization_code"
                + "&code=" + urlEncode(code)
                + "&client_id=" + urlEncode(providerConfig.clientId())
                + "&client_secret=" + urlEncode(providerConfig.clientSecret())
                + "&redirect_uri=" + urlEncode(providerConfig.redirectUri());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OAuth token exchange failed with status " + response.statusCode());
        }

        JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
        String accessToken = payload.has("access_token") ? payload.get("access_token").getAsString() : "";
        if (accessToken.isBlank()) {
            throw new IllegalStateException("OAuth provider did not return access_token");
        }

        return accessToken;
    }

    private OAuthUserProfile fetchOAuthUserProfile(String provider, String accessToken)
            throws IOException, InterruptedException {
        String endpoint;
        if ("google".equals(provider)) {
            endpoint = "https://www.googleapis.com/oauth2/v2/userinfo";
        } else if ("discord".equals(provider)) {
            endpoint = "https://discord.com/api/users/@me";
        } else {
            throw new IllegalArgumentException("Unsupported OAuth provider: " + provider);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OAuth userinfo failed with status " + response.statusCode());
        }

        JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
        if ("google".equals(provider)) {
            String providerUserId = asString(profile, "id");
            if (providerUserId.isBlank()) {
                throw new IllegalStateException("Google user info missing id");
            }
            return new OAuthUserProfile(
                    provider,
                    providerUserId,
                    asString(profile, "name"),
                    asString(profile, "email"),
                    asString(profile, "picture")
            );
        }

        String providerUserId = asString(profile, "id");
        if (providerUserId.isBlank()) {
            throw new IllegalStateException("Discord user info missing id");
        }

        String globalName = asString(profile, "global_name");
        String username = asString(profile, "username");
        String discriminator = asString(profile, "discriminator");
        String displayName = !globalName.isBlank() ? globalName : username;
        if (displayName.isBlank()) {
            displayName = providerUserId;
        }
        if (!username.isBlank() && !"0".equals(discriminator) && !discriminator.isBlank()) {
            displayName = username + "#" + discriminator;
        }

        String avatar = asString(profile, "avatar");
        String avatarUrl = "";
        if (!avatar.isBlank()) {
            avatarUrl = "https://cdn.discordapp.com/avatars/" + providerUserId + "/" + avatar + ".png?size=128";
        }

        return new OAuthUserProfile(provider, providerUserId, displayName, asString(profile, "email"), avatarUrl);
    }

    private String asString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String generateToken(int byteCount) {
        byte[] bytes = new byte[Math.max(16, byteCount)];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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

        if (!user.get().hasPermission(permission)) {
            throw halt(403, gson.toJson(Map.of("error", "forbidden")));
        }

        return user.get();
    }

    private PanelUser requireOwner(Request request) {
        PanelUser user = requireUser(request, PanelPermission.MANAGE_USERS);
        if (user.role() != UserRole.OWNER) {
            throw halt(403, gson.toJson(Map.of("error", "owner_required")));
        }
        return user;
    }

    private Map<String, Object> toPublicUser(PanelUser user) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", user.id());
        payload.put("username", user.username());
        payload.put("role", user.role().name());
        payload.put("isOwner", user.role() == UserRole.OWNER);
        payload.put("createdAt", user.createdAt());
        payload.put("permissions", user.permissions().stream().map(Enum::name).sorted().toList());
        return payload;
    }

    private List<Map<String, Object>> permissionCatalogForCurrentRuntime() {
        Set<String> installedExtensions = installedExtensionIds();
        List<Map<String, Object>> permissions = new ArrayList<>();

        for (PanelPermission permission : PanelPermission.values()) {
            if (!permission.assignable()) {
                continue;
            }

            String extensionId = permission.extensionId();
            if (extensionId != null && !installedExtensions.contains(normalizeExtensionKey(extensionId))) {
                continue;
            }

            permissions.add(Map.of(
                    "key", permission.name(),
                    "label", permission.label(),
                    "category", permission.category(),
                    "extensionId", extensionId == null ? "" : extensionId
            ));
        }

        permissions.sort(Comparator
                .comparing((Map<String, Object> item) -> String.valueOf(item.get("category")), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(item -> String.valueOf(item.get("label")), String.CASE_INSENSITIVE_ORDER));
        return permissions;
    }

    private Map<String, List<String>> roleDefaultsPayload() {
        Map<String, List<String>> payload = new HashMap<>();
        for (UserRole role : UserRole.values()) {
            List<String> defaults = role.defaultPermissions().stream()
                    .filter(PanelPermission::assignable)
                    .map(Enum::name)
                    .sorted()
                    .toList();
            payload.put(role.name(), defaults);
        }
        return payload;
    }

    private Set<String> installedExtensionIds() {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> installed : extensionManager.installedExtensions()) {
            String id = normalizeExtensionKey(String.valueOf(installed.getOrDefault("id", "")));
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Set<PanelPermission> parsePermissionNames(List<String> rawPermissions) {
        Set<PanelPermission> parsed = EnumSet.noneOf(PanelPermission.class);
        if (rawPermissions == null) {
            return parsed;
        }

        for (String raw : rawPermissions) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                parsed.add(PanelPermission.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Ignore unknown permission ids from stale clients.
            }
        }
        return parsed;
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
        PanelUser user = requireUser(request, PanelPermission.ACCESS_PANEL);

        List<Map<String, Object>> tabs = extensionManager.navigationTabs().stream()
                .filter(tab -> {
                    PanelPermission required = permissionForPath(tab.path());
                    return required == null || user.hasPermission(required);
                })
                .map(tab -> Map.<String, Object>of(
                        "category", tab.category(),
                        "label", tab.label(),
                        "path", tab.path()
                ))
                .toList();

        return json(response, 200, Map.of("tabs", tabs));
    }

    private PanelPermission permissionForPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        return switch (path.trim()) {
            case "/dashboard/overview" -> PanelPermission.VIEW_OVERVIEW;
            case "/console", "/dashboard/console" -> PanelPermission.VIEW_CONSOLE;
            case "/dashboard/resources" -> PanelPermission.VIEW_RESOURCES;
            case "/dashboard/players" -> PanelPermission.VIEW_PLAYERS;
            case "/dashboard/bans" -> PanelPermission.VIEW_BANS;
            case "/dashboard/plugins" -> PanelPermission.VIEW_PLUGINS;
            case "/dashboard/users" -> PanelPermission.VIEW_USERS;
            case "/dashboard/discord-webhook" -> PanelPermission.VIEW_DISCORD_WEBHOOK;
            case "/dashboard/themes" -> PanelPermission.VIEW_THEMES;
            case "/dashboard/extensions" -> PanelPermission.VIEW_EXTENSIONS;
            case "/dashboard/extension-config" -> PanelPermission.VIEW_EXTENSIONS;
            case "/dashboard/world-backups" -> PanelPermission.VIEW_BACKUPS;
            case "/dashboard/reports" -> PanelPermission.VIEW_REPORTS;
            case "/dashboard/tickets" -> PanelPermission.VIEW_TICKETS;
            case "/dashboard/maintenance" -> PanelPermission.VIEW_MAINTENANCE;
            case "/dashboard/whitelist" -> PanelPermission.VIEW_WHITELIST;
            case "/dashboard/announcements" -> PanelPermission.VIEW_ANNOUNCEMENTS;
            default -> null;
        };
    }

    private String handleExtensionStatus(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_EXTENSIONS);

        String channel = normalizeReleaseChannel(request.queryParams("channel"));
        List<Map<String, Object>> installed = extensionManager.installedExtensions();
        List<Map<String, Object>> localArtifacts = extensionManager.availableArtifacts();
        List<Map<String, Object>> availableExtensions = loadAvailableExtensionsFromGitHub(channel);

        Map<String, ExtensionVersionInfo> latestByExtensionId = new HashMap<>();
        for (Map<String, Object> extension : availableExtensions) {
            String extensionId = normalizeExtensionKey(String.valueOf(extension.getOrDefault("extensionId", "")));
            if (extensionId.isBlank()) {
                continue;
            }

            String latestVersionLabel = String.valueOf(extension.getOrDefault("version", ""));
            VersionToken latestVersion = parseVersionToken(latestVersionLabel);
            ExtensionVersionInfo existing = latestByExtensionId.get(extensionId);
            if (existing == null || compareVersionTokens(latestVersion, existing.versionToken()) > 0) {
                latestByExtensionId.put(extensionId, new ExtensionVersionInfo(latestVersionLabel, latestVersion));
            }
        }

        List<Map<String, Object>> installedWithStatus = new ArrayList<>();
        Set<String> installedIds = new HashSet<>();
        Set<String> outdatedInstalledIds = new HashSet<>();
        for (Map<String, Object> installedEntry : installed) {
            Map<String, Object> row = new HashMap<>(installedEntry);
            String source = String.valueOf(installedEntry.getOrDefault("source", ""));
            String extensionId = normalizeExtensionKey(String.valueOf(installedEntry.getOrDefault("id", "")));
            String sourceExtensionId = normalizeExtensionKey(parseExtensionId(source));
            String installedVersionLabel = extractVersionLabel(source);
            VersionToken installedVersion = parseVersionToken(installedVersionLabel);

            if (!extensionId.isBlank()) {
                installedIds.add(extensionId);
            }
            if (!sourceExtensionId.isBlank()) {
                installedIds.add(sourceExtensionId);
            }

            ExtensionVersionInfo latest = latestByExtensionId.get(extensionId);
            if (latest == null && !sourceExtensionId.isBlank()) {
                latest = latestByExtensionId.get(sourceExtensionId);
            }
            if (latest == null) {
                row.put("status", "unknown");
                row.put("outdated", false);
                row.put("statusText", "No release asset found");
            } else {
                int comparison = compareVersionTokens(installedVersion, latest.versionToken());
                if (comparison < 0) {
                    row.put("status", "outdated");
                    row.put("outdated", true);
                    row.put("statusText", "Outdated (latest: " + latest.versionLabel() + ")");
                    row.put("latestVersion", latest.versionLabel());
                    if (!extensionId.isBlank()) {
                        outdatedInstalledIds.add(extensionId);
                    }
                    if (!sourceExtensionId.isBlank()) {
                        outdatedInstalledIds.add(sourceExtensionId);
                    }
                } else if (comparison >= 0) {
                    row.put("status", "up_to_date");
                    row.put("outdated", false);
                    row.put("statusText", "Up to date");
                    row.put("latestVersion", latest.versionLabel());
                } else {
                    row.put("status", "unknown");
                    row.put("outdated", false);
                    row.put("statusText", "Version unknown (latest: " + latest.versionLabel() + ")");
                    row.put("latestVersion", latest.versionLabel());
                }
            }
            installedWithStatus.add(row);
        }

        List<Map<String, Object>> availableWithInstallState = new ArrayList<>();
        Set<String> restartRequiredSnapshot;
        synchronized (restartRequiredExtensionIds) {
            restartRequiredSnapshot = new HashSet<>(restartRequiredExtensionIds);
        }
        for (Map<String, Object> availableEntry : availableExtensions) {
            Map<String, Object> row = new HashMap<>(availableEntry);
            String extensionId = normalizeExtensionKey(String.valueOf(availableEntry.getOrDefault("extensionId", "")));
            if (!extensionId.isBlank() && restartRequiredSnapshot.contains(extensionId)) {
                row.put("installState", "restart_required");
            } else if (!extensionId.isBlank() && outdatedInstalledIds.contains(extensionId)) {
                row.put("installState", "outdated");
            } else if (!extensionId.isBlank() && installedIds.contains(extensionId)) {
                row.put("installState", "installed");
            } else {
                row.put("installState", "not_installed");
            }
            availableWithInstallState.add(row);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("installed", installedWithStatus);
        payload.put("localArtifacts", localArtifacts);
        payload.put("availableExtensions", availableWithInstallState);
        payload.put("channel", channel);
        payload.put("installedCount", installedWithStatus.size());
        payload.put("localArtifactCount", localArtifacts.size());
        payload.put("availableExtensionCount", availableWithInstallState.size());
        payload.put("restartRequiredCount", restartRequiredSnapshot.size());
        if (!lastGitHubCatalogError.isBlank()) {
            payload.put("availableExtensionsWarning", lastGitHubCatalogError);
        }
        return json(response, 200, payload);
    }

    private String handleExtensionConfigList(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_EXTENSIONS);

        List<Map<String, Object>> extensions = extensionManager.installedExtensions().stream()
                .filter(entry -> extensionHasConfig(normalizeExtensionKey(String.valueOf(entry.getOrDefault("id", ""))))
                )
                .map(entry -> {
                    String extensionId = normalizeExtensionKey(String.valueOf(entry.getOrDefault("id", "")));
                    String settingsJson = extensionSettingsRepository.findSettingsJson(extensionId).orElse("{}");
                    JsonObject settings = parseSettingsObject(settingsJson);

                    Map<String, Object> row = new HashMap<>(entry);
                    row.put("settings", settings == null ? new JsonObject() : settings);
                    row.put("settingsJson", settingsJson);
                    return row;
                })
                .toList();

        return json(response, 200, Map.of("extensions", extensions));
    }

    private String handleExtensionConfigGet(Request request, Response response) {
        requireUser(request, PanelPermission.VIEW_EXTENSIONS);

        String extensionId = normalizeExtensionKey(request.params("id"));
        if (extensionId.isBlank()) {
            return json(response, 400, Map.of("error", "invalid_extension_id"));
        }

        if (!extensionHasConfig(extensionId)) {
            return json(response, 404, Map.of("error", "extension_has_no_config"));
        }

        if (!isInstalledExtension(extensionId)) {
            return json(response, 404, Map.of("error", "extension_not_installed"));
        }

        String settingsJson = extensionSettingsRepository.findSettingsJson(extensionId).orElse("{}");
        JsonObject settings = parseSettingsObject(settingsJson);
        return json(response, 200, Map.of(
                "extensionId", extensionId,
                "settings", settings == null ? new JsonObject() : settings,
                "settingsJson", settingsJson
        ));
    }

    private String handleExtensionConfigSave(Request request, Response response) {
        PanelUser user = requireUser(request, PanelPermission.MANAGE_EXTENSIONS);

        String extensionId = normalizeExtensionKey(request.params("id"));
        if (extensionId.isBlank()) {
            return json(response, 400, Map.of("error", "invalid_extension_id"));
        }

        if (!extensionHasConfig(extensionId)) {
            return json(response, 404, Map.of("error", "extension_has_no_config"));
        }

        if (!isInstalledExtension(extensionId)) {
            return json(response, 404, Map.of("error", "extension_not_installed"));
        }

        JsonObject payload;
        try {
            payload = gson.fromJson(request.body(), JsonObject.class);
        } catch (JsonSyntaxException exception) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        if (payload == null || !payload.has("settings") || payload.get("settings") == null || !payload.get("settings").isJsonObject()) {
            return json(response, 400, Map.of("error", "invalid_settings_payload"));
        }

        JsonObject settings = payload.getAsJsonObject("settings");
        String settingsJson = gson.toJson(settings);
        extensionSettingsRepository.saveSettingsJson(extensionId, settingsJson, System.currentTimeMillis());
        boolean liveApplied = extensionManager.applySettings(extensionId, settingsJson);
        panelLogger.log("AUDIT", user.username(), "Updated extension settings for " + extensionId);

        return json(response, 200, Map.of(
                "ok", true,
                "extensionId", extensionId,
                "liveApplied", liveApplied,
                "settings", settings,
                "settingsJson", settingsJson
        ));
    }

    private boolean isInstalledExtension(String extensionId) {
        if (extensionId == null || extensionId.isBlank()) {
            return false;
        }
        return extensionManager.installedExtensions().stream()
                .map(entry -> normalizeExtensionKey(String.valueOf(entry.getOrDefault("id", ""))))
                .anyMatch(installedId -> installedId.equals(extensionId));
    }

    private boolean extensionHasConfig(String extensionId) {
        if (extensionId == null || extensionId.isBlank()) {
            return false;
        }

        return extensionManager.supportsSettings(extensionId);
    }

    private JsonObject parseSettingsObject(String settingsJson) {
        if (isBlank(settingsJson)) {
            return new JsonObject();
        }
        try {
            JsonElement element = gson.fromJson(settingsJson, JsonElement.class);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (Exception ignored) {
            // Ignore malformed persisted settings and return empty defaults.
        }
        return new JsonObject();
    }

    private String handleExtensionInstall(Request request, Response response) {
        PanelUser userSession = requireUser(request, PanelPermission.MANAGE_EXTENSIONS);

        JsonObject payload;
        try {
            payload = gson.fromJson(request.body(), JsonObject.class);
        } catch (JsonSyntaxException exception) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }
        if (payload == null) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        String channel = normalizeReleaseChannel(stringValue(payload, "channel"));
        String extensionId = normalizeExtensionKey(stringValue(payload, "extensionId"));
        String fileName = stringValue(payload, "fileName");
        if (extensionId.isBlank() || isBlank(fileName)) {
            return json(response, 400, Map.of("error", "invalid_payload"));
        }

        List<Map<String, Object>> available = loadAvailableExtensionsFromGitHub(channel);
        Map<String, Object> selected = null;
        for (Map<String, Object> row : available) {
            String rowId = normalizeExtensionKey(String.valueOf(row.getOrDefault("extensionId", "")));
            String rowFile = String.valueOf(row.getOrDefault("id", ""));
            if (extensionId.equals(rowId) && fileName.equals(rowFile)) {
                selected = row;
                break;
            }
        }

        if (selected == null) {
            return json(response, 404, Map.of("error", "extension_asset_not_found"));
        }

        String downloadUrl = String.valueOf(selected.getOrDefault("downloadUrl", ""));
        if (isBlank(downloadUrl)) {
            return json(response, 502, Map.of("error", "missing_download_url"));
        }

        if (!isValidExtensionJarFileName(fileName)) {
            return json(response, 400, Map.of("error", "invalid_file_name"));
        }

        try {
            Path extensionDirectory = plugin.getDataFolder().toPath().resolve("extensions");
            Files.createDirectories(extensionDirectory);

            Path destination = extensionDirectory.resolve(fileName).normalize();
            if (!destination.startsWith(extensionDirectory)) {
                return json(response, 400, Map.of("error", "invalid_file_name"));
            }

            ArtifactCleanupResult cleanupResult = removeExistingExtensionArtifacts(extensionDirectory, extensionId, fileName);
            downloadExtensionAsset(downloadUrl, destination);

            synchronized (restartRequiredExtensionIds) {
                restartRequiredExtensionIds.add(extensionId);
            }

            panelLogger.log("PANEL", "EXTENSIONS", "Downloaded extension " + fileName + " to " + destination.getFileName() + " by " + userSession.username());
            if (!cleanupResult.removedArtifacts().isEmpty()) {
                panelLogger.log("PANEL", "EXTENSIONS", "Removed old extension artifact(s) for " + extensionId + ": " + String.join(", ", cleanupResult.removedArtifacts()));
            }
            if (!cleanupResult.pendingCleanupArtifacts().isEmpty()) {
                panelLogger.log("PANEL", "EXTENSIONS", "Extension artifact(s) still in use for " + extensionId + ": " + String.join(", ", cleanupResult.pendingCleanupArtifacts()));
            }

            Map<String, Object> responsePayload = new HashMap<>();
            responsePayload.put("ok", true);
            responsePayload.put("fileName", fileName);
            responsePayload.put("extensionId", extensionId);
            responsePayload.put("requiresRestart", true);
            if (cleanupResult.pendingCleanupArtifacts().isEmpty()) {
                responsePayload.put("message", "Downloaded to plugins/MinePanel/extensions. Restart required.");
            } else {
                responsePayload.put("message", "Downloaded update, but old jar is currently in use. Restart required.");
            }
            responsePayload.put("removedArtifacts", cleanupResult.removedArtifacts());
            responsePayload.put("pendingCleanupArtifacts", cleanupResult.pendingCleanupArtifacts());
            return json(response, 200, responsePayload);
        } catch (IllegalStateException exception) {
            plugin.getLogger().warning("Could not replace extension asset " + fileName + ": " + exception.getMessage());
            return json(response, 409, Map.of("error", "extension_replace_failed", "details", exception.getMessage()));
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not install extension asset " + fileName + ": " + exception.getMessage());
            return json(response, 500, Map.of("error", "extension_install_failed"));
        }
    }

    private ArtifactCleanupResult removeExistingExtensionArtifacts(Path extensionDirectory, String extensionId, String selectedFileName) {
        List<String> removed = new ArrayList<>();
        List<String> pendingCleanup = new ArrayList<>();
        String normalizedSelected = selectedFileName == null ? "" : selectedFileName.trim().toLowerCase(Locale.ROOT);

        try (var files = Files.list(extensionDirectory)) {
            for (Path candidate : files.toList()) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }

                String candidateFileName = candidate.getFileName().toString();
                String loweredCandidate = candidateFileName.toLowerCase(Locale.ROOT);
                if (!loweredCandidate.endsWith(".jar") || loweredCandidate.equals(normalizedSelected)) {
                    continue;
                }

                String candidateExtensionId = normalizeExtensionKey(parseExtensionId(candidateFileName));
                if (candidateExtensionId.isBlank() || !candidateExtensionId.equals(extensionId)) {
                    continue;
                }

                try {
                    Files.deleteIfExists(candidate);
                    removed.add(candidateFileName);
                } catch (Exception exception) {
                    if (exception instanceof FileSystemException || exception instanceof AccessDeniedException) {
                        pendingCleanup.add(candidateFileName);
                        continue;
                    }
                    throw new IllegalStateException("Could not delete old extension artifact " + candidateFileName + ": " + exception.getMessage(), exception);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read extension directory", exception);
        }

        return new ArtifactCleanupResult(List.copyOf(removed), List.copyOf(pendingCleanup));
    }

    private record ArtifactCleanupResult(List<String> removedArtifacts, List<String> pendingCleanupArtifacts) {
    }

    private String handleExtensionReload(Request request, Response response) {
        PanelUser user = requireUser(request, PanelPermission.MANAGE_EXTENSIONS);

        ExtensionManager.ReloadResult reloadResult;
        try {
            reloadResult = plugin.getServer().getScheduler().callSyncMethod(plugin,
                    () -> extensionManager.reloadNewFromDirectory(new ExtensionSparkRegistry())
            ).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return json(response, 500, Map.of("error", "reload_interrupted"));
        } catch (ExecutionException | TimeoutException exception) {
            plugin.getLogger().warning("Could not reload extensions: " + exception.getMessage());
            return json(response, 500, Map.of("error", "extension_reload_failed"));
        }

        synchronized (restartRequiredExtensionIds) {
            for (String loadedId : reloadResult.loadedExtensionIds()) {
                restartRequiredExtensionIds.remove(normalizeExtensionKey(loadedId));
            }
        }

        int loadedCount = reloadResult.loadedExtensionIds().size();
        panelLogger.log("PANEL", "EXTENSIONS", "Reload requested by " + user.username() + ": loaded " + loadedCount + " extension(s)");

        String message = loadedCount == 0
                ? "No new extensions were loaded."
                : "Loaded " + loadedCount + " extension(s) successfully.";

        return json(response, 200, Map.of(
                "ok", true,
                "loadedCount", loadedCount,
                "loadedExtensionIds", reloadResult.loadedExtensionIds(),
                "warningCount", reloadResult.warnings().size(),
                "warnings", reloadResult.warnings(),
                "message", message
        ));
    }

    private void downloadExtensionAsset(String downloadUrl, Path destination) throws Exception {
        URI sourceUri = URI.create(downloadUrl);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(sourceUri)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "MinePanel")
                .header("X-GitHub-Api-Version", "2022-11-28");

        String token = resolveGitHubToken();
        if (!token.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpRequest request = requestBuilder.GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (isRedirectStatus(response.statusCode())) {
            Optional<String> location = response.headers().firstValue("location");
            if (location.isEmpty() || location.get().isBlank()) {
                throw new IllegalStateException("GitHub download redirect missing location");
            }

            URI redirectedUri = sourceUri.resolve(location.get().trim());
            HttpRequest redirectedRequest = HttpRequest.newBuilder(redirectedUri)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "MinePanel")
                    .GET()
                    .build();
            response = httpClient.send(redirectedRequest, HttpResponse.BodyHandlers.ofByteArray());
        }

        if (response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub download status " + response.statusCode());
        }

        Files.write(destination, response.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private boolean isRedirectStatus(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private boolean isValidExtensionJarFileName(String fileName) {
        if (isBlank(fileName)) {
            return false;
        }
        String trimmed = fileName.trim();
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        if (!lowered.endsWith(".jar") || !lowered.startsWith("minepanel-extension-")) {
            return false;
        }
        return trimmed.matches("^[a-zA-Z0-9._-]+$");
    }

    private String normalizeReleaseChannel(String rawChannel) {
        if (rawChannel == null || rawChannel.isBlank()) {
            return "release";
        }

        String normalized = rawChannel.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "release", "prerelease" -> normalized;
            default -> "release";
        };
    }

    private List<Map<String, Object>> loadAvailableExtensionsFromGitHub(String channel) {
        try {
            lastGitHubCatalogError = "";
            JsonArray releases = requestGitHubReleases();
            JsonObject release = selectLatestReleaseByChannel(releases, channel);
            if (release == null) {
                return List.of();
            }

            String releaseLabel = firstNonBlank(stringValue(release, "name"), stringValue(release, "tag_name"), "latest");
            String releaseUrl = stringValue(release, "html_url");
            boolean prerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();

            JsonArray assets = release.getAsJsonArray("assets");
            if (assets == null) {
                return List.of();
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonElement element : assets) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }

                JsonObject asset = element.getAsJsonObject();
                String fileName = stringValue(asset, "name");
                String downloadUrl = stringValue(asset, "browser_download_url");
                if (isBlank(fileName) || isBlank(downloadUrl)) {
                    continue;
                }

                String lowered = fileName.toLowerCase(Locale.ROOT);
                if (!lowered.endsWith(".jar") || !lowered.contains("minepanel-extension-")) {
                    continue;
                }

                String extensionId = parseExtensionId(fileName);
                if (isBlank(extensionId)) {
                    continue;
                }

                rows.add(Map.of(
                        "id", fileName,
                        "name", readableExtensionName(fileName),
                        "description", "Asset from " + releaseLabel,
                        "release", releaseLabel,
                        "prerelease", prerelease,
                        "projectUrl", releaseUrl,
                        "downloadUrl", downloadUrl,
                        "extensionId", extensionId,
                        "version", releaseLabel
                ));
            }

            rows.sort(Comparator.comparing(row -> String.valueOf(row.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER));
            return rows;
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "unknown_error" : exception.getMessage();
            lastGitHubCatalogError = "GitHub catalog unavailable: " + message;
            plugin.getLogger().warning("Could not load extension catalog from GitHub: " + message);
            return List.of();
        }
    }

    private JsonArray requestGitHubReleases() {
        long now = System.currentTimeMillis();
        if (now < githubReleaseCacheExpiresAtMillis && cachedGitHubReleases != null && !cachedGitHubReleases.isEmpty()) {
            return cachedGitHubReleases;
        }

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/WinniePatGG/MinePanel/releases?per_page=20"))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MinePanel")
                    .header("X-GitHub-Api-Version", "2022-11-28");

            String token = resolveGitHubToken();
            if (!token.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + token);
            }

            HttpRequest request = requestBuilder.GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                String errorMessage = "GitHub API status " + response.statusCode();
                try {
                    JsonObject body = gson.fromJson(response.body(), JsonObject.class);
                    if (body != null && body.has("message") && !body.get("message").isJsonNull()) {
                        errorMessage = body.get("message").getAsString();
                    }
                } catch (Exception ignored) {
                    // Keep fallback status text.
                }

                if (response.statusCode() == 403 && cachedGitHubReleases != null && !cachedGitHubReleases.isEmpty()) {
                    lastGitHubCatalogError = "GitHub rate limit hit, showing cached extension data";
                    githubReleaseCacheExpiresAtMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
                    return cachedGitHubReleases;
                }

                throw new IllegalStateException(errorMessage);
            }

            JsonElement parsed = gson.fromJson(response.body(), JsonElement.class);
            if (parsed == null || !parsed.isJsonArray()) {
                cachedGitHubReleases = new JsonArray();
                githubReleaseCacheExpiresAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(resolveGitHubCacheSeconds());
                return cachedGitHubReleases;
            }

            cachedGitHubReleases = parsed.getAsJsonArray();
            githubReleaseCacheExpiresAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(resolveGitHubCacheSeconds());
            return cachedGitHubReleases;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not query GitHub releases", exception);
        }
    }

    private String resolveGitHubToken() {
        String envToken = System.getenv("MINEPANEL_GITHUB_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            return envToken.trim();
        }

        String configToken = plugin.getConfig().getString("integrations.github.token", "");
        return configToken == null ? "" : configToken.trim();
    }

    private int resolveGitHubCacheSeconds() {
        int configured = plugin.getConfig().getInt("integrations.github.releaseCacheSeconds", 300);
        return Math.max(30, Math.min(3600, configured));
    }

    private JsonObject selectLatestReleaseByChannel(JsonArray releases, String channel) {
        if (releases == null || releases.isEmpty()) {
            return null;
        }

        boolean wantsPrerelease = "prerelease".equalsIgnoreCase(channel);
        JsonObject selected = null;
        Instant selectedPublishedAt = Instant.EPOCH;

        for (JsonElement element : releases) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }

            JsonObject release = element.getAsJsonObject();
            boolean draft = release.has("draft") && release.get("draft").getAsBoolean();
            if (draft) {
                continue;
            }

            boolean prerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();
            if (prerelease != wantsPrerelease) {
                continue;
            }

            Instant publishedAt;
            try {
                publishedAt = Instant.parse(stringValue(release, "published_at"));
            } catch (Exception ignored) {
                publishedAt = Instant.EPOCH;
            }

            if (selected == null || publishedAt.isAfter(selectedPublishedAt)) {
                selected = release;
                selectedPublishedAt = publishedAt;
            }
        }

        return selected;
    }

    private String parseExtensionId(String fileName) {
        if (isBlank(fileName)) {
            return "";
        }

        String lowered = fileName.trim().toLowerCase(Locale.ROOT);
        lowered = lowered.replace(".jar", "");
        lowered = lowered.replaceFirst("^minepanel-extension-", "");
        int versionSplit = lowered.lastIndexOf("-alpha-");
        if (versionSplit > 0) {
            lowered = lowered.substring(0, versionSplit);
        }
        return lowered;
    }

    private String normalizeExtensionKey(String rawValue) {
        if (rawValue == null) {
            return "";
        }

        return rawValue
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private String readableExtensionName(String fileName) {
        String id = parseExtensionId(fileName);
        if (id.isBlank()) {
            return fileName;
        }

        String[] parts = id.split("-");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.isEmpty() ? fileName : builder.toString();
    }

    private String extractVersionLabel(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "";
        }

        java.util.regex.Matcher alphaMatcher = java.util.regex.Pattern.compile("(?i)alpha-(\\d+)").matcher(rawValue);
        String lastAlpha = "";
        while (alphaMatcher.find()) {
            lastAlpha = "alpha-" + alphaMatcher.group(1);
        }
        if (!lastAlpha.isBlank()) {
            return lastAlpha;
        }

        java.util.regex.Matcher semverMatcher = java.util.regex.Pattern.compile("(?i)v?(\\d+(?:\\.\\d+)+)").matcher(rawValue);
        String lastSemver = "";
        while (semverMatcher.find()) {
            lastSemver = semverMatcher.group(1);
        }
        return lastSemver;
    }

    private VersionToken parseVersionToken(String rawValue) {
        String normalized = extractVersionLabel(rawValue);
        if (normalized.isBlank()) {
            return VersionToken.unknown();
        }

        java.util.regex.Matcher alphaMatcher = java.util.regex.Pattern.compile("(?i)alpha-(\\d+)").matcher(normalized);
        if (alphaMatcher.matches()) {
            try {
                return VersionToken.alpha(Integer.parseInt(alphaMatcher.group(1)));
            } catch (NumberFormatException ignored) {
                return VersionToken.unknown();
            }
        }

        java.util.regex.Matcher semverMatcher = java.util.regex.Pattern.compile("(?i)v?(\\d+(?:\\.\\d+)+)").matcher(normalized);
        if (semverMatcher.matches()) {
            String[] parts = semverMatcher.group(1).split("\\.");
            List<Integer> values = new ArrayList<>();
            for (String part : parts) {
                try {
                    values.add(Integer.parseInt(part));
                } catch (NumberFormatException ignored) {
                    return VersionToken.unknown();
                }
            }
            return VersionToken.semver(values);
        }

        return VersionToken.unknown();
    }

    private int compareVersionTokens(VersionToken installed, VersionToken latest) {
        if (installed == null || latest == null || installed.kind() == VersionKind.UNKNOWN || latest.kind() == VersionKind.UNKNOWN) {
            return Integer.MIN_VALUE;
        }

        if (installed.kind() != latest.kind()) {
            // Semantic versions are considered newer than alpha track.
            if (installed.kind() == VersionKind.SEMVER && latest.kind() == VersionKind.ALPHA) {
                return 1;
            }
            if (installed.kind() == VersionKind.ALPHA && latest.kind() == VersionKind.SEMVER) {
                return -1;
            }
            return Integer.MIN_VALUE;
        }

        if (installed.kind() == VersionKind.ALPHA) {
            return Integer.compare(installed.alphaBuild(), latest.alphaBuild());
        }

        int max = Math.max(installed.semverParts().size(), latest.semverParts().size());
        for (int i = 0; i < max; i++) {
            int left = i < installed.semverParts().size() ? installed.semverParts().get(i) : 0;
            int right = i < latest.semverParts().size() ? latest.semverParts().get(i) : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return 0;
    }

    private record ExtensionVersionInfo(String versionLabel, VersionToken versionToken) {
    }

    private enum VersionKind {
        UNKNOWN,
        ALPHA,
        SEMVER
    }

    private record VersionToken(VersionKind kind, int alphaBuild, List<Integer> semverParts) {
        private static VersionToken unknown() {
            return new VersionToken(VersionKind.UNKNOWN, -1, List.of());
        }

        private static VersionToken alpha(int build) {
            return new VersionToken(VersionKind.ALPHA, build, List.of());
        }

        private static VersionToken semver(List<Integer> parts) {
            return new VersionToken(VersionKind.SEMVER, -1, parts == null ? List.of() : List.copyOf(parts));
        }
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

    private record OAuthStartPayload(String mode) {
    }

    private record CreateUserPayload(String username, String password, String role, List<String> permissions) {
    }

    private record UpdateRolePayload(String role) {
    }

    private record UpdatePermissionsPayload(List<String> permissions) {
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
            Boolean logSecurity,
            Boolean logConsoleResponse,
            Boolean logSystem
    ) {
    }

    private record PluginInstallPayload(String source, String projectId, String versionId) {
    }

    private record MarketplaceDownload(String downloadUrl, String fileName) {
    }

    private record OAuthUserProfile(String provider, String providerUserId, String displayName, String email, String avatarUrl) {
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

    private record JoinLeaveHeatmap(
            List<Map<String, Object>> joinCells,
            List<Map<String, Object>> leaveCells,
            int maxJoin,
            int maxLeave
    ) {
    }
}
