package de.winniepat.minePanel.logs;

import de.winniepat.minePanel.integrations.DiscordWebhookService;
import de.winniepat.minePanel.persistence.LogRepository;

import java.time.Instant;

public final class PanelLogger {

    private final LogRepository logRepository;
    private final DiscordWebhookService discordWebhookService;

    public PanelLogger(LogRepository logRepository, DiscordWebhookService discordWebhookService) {
        this.logRepository = logRepository;
        this.discordWebhookService = discordWebhookService;
    }

    public synchronized void log(String kind, String source, String message) {
        Instant now = Instant.now();
        String safeMessage = message == null ? "" : message;
        logRepository.appendLog(kind, source, safeMessage);

        discordWebhookService.handlePanelLog(kind, source, safeMessage, now);
    }
}

