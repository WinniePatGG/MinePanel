package de.winniepat.minePanel.web;

import com.google.gson.Gson;
import de.winniepat.minePanel.MinePanel;
import de.winniepat.minePanel.auth.PasswordHasher;
import de.winniepat.minePanel.auth.SessionService;
import de.winniepat.minePanel.config.WebPanelConfig;
import de.winniepat.minePanel.integrations.DiscordWebhookConfig;
import de.winniepat.minePanel.integrations.DiscordWebhookService;
import de.winniepat.minePanel.logs.PanelLogEntry;
import de.winniepat.minePanel.logs.PanelLogger;
import de.winniepat.minePanel.logs.ServerLogService;
import de.winniepat.minePanel.persistence.KnownPlayer;
import de.winniepat.minePanel.persistence.KnownPlayerRepository;
import de.winniepat.minePanel.persistence.LogRepository;
import de.winniepat.minePanel.persistence.UserRepository;
import org.bukkit.BanEntry;
import de.winniepat.minePanel.users.PanelPermission;
import de.winniepat.minePanel.users.PanelUser;
import de.winniepat.minePanel.users.PanelUserAuth;
import de.winniepat.minePanel.users.UserRole;
import org.bukkit.BanList;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import spark.Request;
import spark.Response;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    private final MinePanel plugin;
    private final WebPanelConfig config;
    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final PasswordHasher passwordHasher;
    private final LogRepository logRepository;
    private final KnownPlayerRepository knownPlayerRepository;
    private final DiscordWebhookService discordWebhookService;
    private final PanelLogger panelLogger;
    private final ServerLogService serverLogService;
    private final BootstrapService bootstrapService;
    private final Gson gson = new Gson();

    public WebPanelServer(
            MinePanel plugin,
            WebPanelConfig config,
            UserRepository userRepository,
            SessionService sessionService,
            PasswordHasher passwordHasher,
            LogRepository logRepository,
            KnownPlayerRepository knownPlayerRepository,
            DiscordWebhookService discordWebhookService,
            PanelLogger panelLogger,
            ServerLogService serverLogService,
            BootstrapService bootstrapService
    ) {
        this.plugin = plugin;
        this.config = config;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.passwordHasher = passwordHasher;
        this.logRepository = logRepository;
        this.knownPlayerRepository = knownPlayerRepository;
        this.discordWebhookService = discordWebhookService;
        this.panelLogger = panelLogger;
        this.serverLogService = serverLogService;
        this.bootstrapService = bootstrapService;
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

        get("/dashboard/players", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-players.html");
        });

        get("/dashboard/discord-webhook", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-discord-webhook.html");
        });

        get("/panel.css", (request, response) -> {
            response.type("text/css");
            return ResourceLoader.loadUtf8Text("/web/panel.css");
        });

        // Chromium sometimes probes this path; return 204 to avoid noisy unmatched-route logs.
        get("/.well-known/appspecific/com.chrome.devtools.json", (request, response) -> {
            response.status(204);
            return "";
        });

        path("/api", () -> {
            post("/bootstrap", (request, response) -> handleBootstrap(request, response));
            post("/login", (request, response) -> handleLogin(request, response));
            post("/logout", (request, response) -> handleLogout(request, response));
            get("/me", (request, response) -> handleMe(request, response));
            get("/users", (request, response) -> handleListUsers(request, response));
            post("/users", (request, response) -> handleCreateUser(request, response));
            post("/users/:id/role", (request, response) -> handleUpdateRole(request, response));
            get("/logs", (request, response) -> handleLogs(request, response));
            get("/logs/latest", (request, response) -> handleLatestLogId(request, response));
            get("/players", (request, response) -> handlePlayers(request, response));
            get("/plugins", (request, response) -> handlePlugins(request, response));
            post("/players/:uuid/kick", (request, response) -> handleKickPlayer(request, response));
            post("/players/:uuid/temp-ban", (request, response) -> handleTempBanPlayer(request, response));
            post("/players/ban", (request, response) -> handleBanPlayer(request, response));
            post("/players/unban", (request, response) -> handleUnbanPlayer(request, response));
            post("/console/send", (request, response) -> handleSendConsole(request, response));
            get("/integrations/discord-webhook", (request, response) -> handleGetDiscordWebhook(request, response));
            post("/integrations/discord-webhook", (request, response) -> handleSaveDiscordWebhook(request, response));
        });

        awaitInitialization();
    }

    public void stop() {
        spark.Spark.stop();
        awaitStop();
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

        for (OnlinePlayerSnapshot onlinePlayer : onlinePlayers) {
            knownPlayerRepository.upsert(onlinePlayer.uuid(), onlinePlayer.username(), now);
        }

        Map<String, BanStatus> nameBans = snapshotNameBans();
        Map<UUID, Map<String, Object>> byUuid = new HashMap<>();

        for (KnownPlayer knownPlayer : knownPlayerRepository.findAll()) {
            String username = knownPlayer.username();
            BanStatus banStatus = nameBans.get(username.toLowerCase());

            Map<String, Object> player = new HashMap<>();
            player.put("uuid", knownPlayer.uuid().toString());
            player.put("username", username);
            player.put("online", false);
            player.put("lastSeenAt", knownPlayer.lastSeenAt());
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
            row.put("lastSeenAt", now);
            row.put("banned", banStatus != null);
            row.put("banExpiresAt", banStatus == null ? null : banStatus.expiresAtMillis());
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

    private record PlayerActionResult(boolean success, String username, String error) {
    }

    private record TempBanResult(boolean success, String username, long expiresAtMillis, String error) {
    }

    private record OnlinePlayerSnapshot(UUID uuid, String username) {
    }

    private record BanStatus(Long expiresAtMillis) {
    }
}

