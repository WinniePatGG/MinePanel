package de.winniepat.minePanel.integrations;

public record DiscordWebhookConfig(
        boolean enabled,
        String webhookUrl,
        boolean useEmbed,
        String botName,
        String messageTemplate,
        String embedTitleTemplate,
        boolean logChat,
        boolean logCommands,
        boolean logAuth,
        boolean logAudit,
        boolean logConsoleResponse,
        boolean logSystem
) {

    public static DiscordWebhookConfig defaults() {
        return new DiscordWebhookConfig(
                false,
                "",
                true,
                "MinePanel",
                "[{timestamp}] [{kind}] [{source}] {message}",
                "MinePanel {kind}",
                true,
                true,
                true,
                true,
                true,
                true
        );
    }
}

