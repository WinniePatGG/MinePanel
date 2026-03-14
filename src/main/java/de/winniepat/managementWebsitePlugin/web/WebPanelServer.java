package de.winniepat.managementWebsitePlugin.web;

import com.google.gson.Gson;
import de.winniepat.managementWebsitePlugin.ManagementWebsitePlugin;
import de.winniepat.managementWebsitePlugin.auth.PasswordHasher;
import de.winniepat.managementWebsitePlugin.auth.SessionService;
import de.winniepat.managementWebsitePlugin.config.WebPanelConfig;
import de.winniepat.managementWebsitePlugin.logs.PanelLogEntry;
import de.winniepat.managementWebsitePlugin.logs.PanelLogger;
import de.winniepat.managementWebsitePlugin.logs.ServerLogService;
import de.winniepat.managementWebsitePlugin.persistence.LogRepository;
import de.winniepat.managementWebsitePlugin.persistence.UserRepository;
import de.winniepat.managementWebsitePlugin.users.PanelPermission;
import de.winniepat.managementWebsitePlugin.users.PanelUser;
import de.winniepat.managementWebsitePlugin.users.PanelUserAuth;
import de.winniepat.managementWebsitePlugin.users.UserRole;
import org.bukkit.entity.Player;
import spark.Request;
import spark.Response;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final ManagementWebsitePlugin plugin;
    private final WebPanelConfig config;
    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final PasswordHasher passwordHasher;
    private final LogRepository logRepository;
    private final PanelLogger panelLogger;
    private final ServerLogService serverLogService;
    private final BootstrapService bootstrapService;
    private final Gson gson = new Gson();

    public WebPanelServer(
            ManagementWebsitePlugin plugin,
            WebPanelConfig config,
            UserRepository userRepository,
            SessionService sessionService,
            PasswordHasher passwordHasher,
            LogRepository logRepository,
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
            return ResourceLoader.loadUtf8Text("/web/dashboard.html");
        });

        get("/dashboard/console", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard.html");
        });

        get("/dashboard/users", (request, response) -> {
            response.type("text/html");
            return ResourceLoader.loadUtf8Text("/web/dashboard-users.html");
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
            post("/console/send", (request, response) -> handleSendConsole(request, response));
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
        List<Map<String, String>> players = snapshotOnlinePlayers();
        return json(response, 200, Map.of(
                "count", players.size(),
                "players", players
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

        boolean dispatched = dispatchConsoleCommand(command);
        panelLogger.log("AUDIT", user.username(), "Sent console command: " + command);
        return json(response, 200, Map.of("ok", true, "dispatched", dispatched));
    }

    private List<Map<String, String>> snapshotOnlinePlayers() {
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () ->
                    plugin.getServer().getOnlinePlayers().stream()
                            .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                            .map(player -> Map.of(
                                    "username", player.getName(),
                                    "uuid", player.getUniqueId().toString()
                            ))
                            .toList()
            ).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading online players", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Could not read online players", exception);
        }
    }

    private boolean dispatchConsoleCommand(String command) {
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin,
                    () -> plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command)
            ).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dispatching console command", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Could not dispatch console command", exception);
        }
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
}

