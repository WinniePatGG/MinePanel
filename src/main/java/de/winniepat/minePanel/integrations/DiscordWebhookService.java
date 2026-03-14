package de.winniepat.minePanel.integrations;

import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class DiscordWebhookService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final Logger logger;
    private final DiscordWebhookRepository repository;
    private final AtomicReference<DiscordWebhookConfig> configReference;
    private final ExecutorService senderExecutor;
    private final HttpClient httpClient;
    private final Gson gson;

    public DiscordWebhookService(Logger logger, DiscordWebhookRepository repository) {
        this.logger = logger;
        this.repository = repository;
        this.configReference = new AtomicReference<>(repository.load());
        this.senderExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "minepanel-discord-webhook");
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }

    public DiscordWebhookConfig getConfig() {
        return configReference.get();
    }

    public DiscordWebhookConfig updateConfig(DiscordWebhookConfig config) {
        repository.save(config);
        configReference.set(config);
        return config;
    }

    public void handlePanelLog(String kind, String source, String message, Instant timestamp) {
        DiscordWebhookConfig config = configReference.get();
        if (!config.enabled() || config.webhookUrl().isBlank()) {
            return;
        }

        if (!shouldSend(config, kind)) {
            return;
        }

        String renderedMessage = renderTemplate(config.messageTemplate(), kind, source, message, timestamp);
        String botName = config.botName().isBlank() ? "MinePanel" : config.botName();

        senderExecutor.execute(() -> send(config, botName, kind, renderedMessage, source, timestamp));
    }

    public void shutdown() {
        senderExecutor.shutdownNow();
    }

    private boolean shouldSend(DiscordWebhookConfig config, String kind) {
        return switch (kind) {
            case "CHAT" -> config.logChat();
            case "COMMAND", "CONSOLE_COMMAND" -> config.logCommands();
            case "AUTH" -> config.logAuth();
            case "AUDIT" -> config.logAudit();
            case "CONSOLE_RESPONSE" -> config.logConsoleResponse();
            case "SYSTEM" -> config.logSystem();
            default -> false;
        };
    }

    private void send(DiscordWebhookConfig config, String botName, String kind, String renderedMessage, String source, Instant timestamp) {
        try {
            Map<String, Object> payload;
            if (config.useEmbed()) {
                String title = renderTemplate(config.embedTitleTemplate(), kind, source, renderedMessage, timestamp);
                payload = Map.of(
                        "username", botName,
                        "embeds", List.of(Map.of(
                                "title", truncate(title, 256),
                                "description", truncate(renderedMessage, 4096),
                                "timestamp", timestamp.toString(),
                                "color", 3447003
                        ))
                );
            } else {
                payload = Map.of(
                        "username", botName,
                        "content", truncate(renderedMessage, 2000)
                );
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(config.webhookUrl()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                logger.warning("Discord webhook returned status " + response.statusCode());
            }
        } catch (Exception exception) {
            logger.warning("Discord webhook send failed: " + exception.getMessage());
        }
    }

    private String renderTemplate(String template, String kind, String source, String message, Instant timestamp) {
        String rawTemplate = (template == null || template.isBlank())
                ? "[{timestamp}] [{kind}] [{source}] {message}"
                : template;

        return rawTemplate
                .replace("{timestamp}", TIMESTAMP_FORMAT.format(timestamp))
                .replace("{kind}", kind == null ? "" : kind)
                .replace("{source}", source == null ? "" : source)
                .replace("{message}", message == null ? "" : message);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength - 1) + "...";
    }
}

