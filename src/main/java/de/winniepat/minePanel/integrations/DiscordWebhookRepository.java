package de.winniepat.minePanel.integrations;

import de.winniepat.minePanel.persistence.Database;

import java.sql.*;

public final class DiscordWebhookRepository {

    private final Database database;

    public DiscordWebhookRepository(Database database) {
        this.database = database;
    }

    public DiscordWebhookConfig load() {
        String sql = "SELECT enabled, webhook_url, use_embed, bot_name, message_template, embed_title_template, "
                + "log_chat, log_commands, log_auth, log_audit, log_console_response, log_system "
                + "FROM discord_webhook_config WHERE id = 1";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return new DiscordWebhookConfig(
                        resultSet.getInt("enabled") == 1,
                        resultSet.getString("webhook_url"),
                        resultSet.getInt("use_embed") == 1,
                        resultSet.getString("bot_name"),
                        resultSet.getString("message_template"),
                        resultSet.getString("embed_title_template"),
                        resultSet.getInt("log_chat") == 1,
                        resultSet.getInt("log_commands") == 1,
                        resultSet.getInt("log_auth") == 1,
                        resultSet.getInt("log_audit") == 1,
                        resultSet.getInt("log_console_response") == 1,
                        resultSet.getInt("log_system") == 1
                );
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not load Discord webhook config", exception);
        }

        DiscordWebhookConfig defaults = DiscordWebhookConfig.defaults();
        save(defaults);
        return defaults;
    }

    public void save(DiscordWebhookConfig config) {
        String sql = "INSERT INTO discord_webhook_config(" 
                + "id, enabled, webhook_url, use_embed, bot_name, message_template, embed_title_template, "
                + "log_chat, log_commands, log_auth, log_audit, log_console_response, log_system"
                + ") VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET "
                + "enabled=excluded.enabled, "
                + "webhook_url=excluded.webhook_url, "
                + "use_embed=excluded.use_embed, "
                + "bot_name=excluded.bot_name, "
                + "message_template=excluded.message_template, "
                + "embed_title_template=excluded.embed_title_template, "
                + "log_chat=excluded.log_chat, "
                + "log_commands=excluded.log_commands, "
                + "log_auth=excluded.log_auth, "
                + "log_audit=excluded.log_audit, "
                + "log_console_response=excluded.log_console_response, "
                + "log_system=excluded.log_system";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, config.enabled() ? 1 : 0);
            statement.setString(2, nullSafe(config.webhookUrl()));
            statement.setInt(3, config.useEmbed() ? 1 : 0);
            statement.setString(4, nullSafe(config.botName()));
            statement.setString(5, nullSafe(config.messageTemplate()));
            statement.setString(6, nullSafe(config.embedTitleTemplate()));
            statement.setInt(7, config.logChat() ? 1 : 0);
            statement.setInt(8, config.logCommands() ? 1 : 0);
            statement.setInt(9, config.logAuth() ? 1 : 0);
            statement.setInt(10, config.logAudit() ? 1 : 0);
            statement.setInt(11, config.logConsoleResponse() ? 1 : 0);
            statement.setInt(12, config.logSystem() ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save Discord webhook config", exception);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

