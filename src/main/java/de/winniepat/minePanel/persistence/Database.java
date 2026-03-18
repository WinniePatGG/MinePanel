package de.winniepat.minePanel.persistence;

import java.nio.file.*;
import java.sql.*;

public final class Database {

    private final Path databaseFile;

    public Database(Path databaseFile) {
        this.databaseFile = databaseFile;
    }

    public void initialize() {
        try {
            Files.createDirectories(databaseFile.getParent());
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create database directory", exception);
        }

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "username TEXT NOT NULL UNIQUE,"
                    + "password_hash TEXT NOT NULL,"
                    + "role TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS user_permissions ("
                    + "user_id INTEGER NOT NULL,"
                    + "permission TEXT NOT NULL,"
                    + "PRIMARY KEY(user_id, permission),"
                    + "FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS sessions ("
                    + "token TEXT PRIMARY KEY,"
                    + "user_id INTEGER NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "expires_at INTEGER NOT NULL,"
                    + "FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS oauth_accounts ("
                    + "user_id INTEGER NOT NULL,"
                    + "provider TEXT NOT NULL,"
                    + "provider_user_id TEXT NOT NULL,"
                    + "display_name TEXT NOT NULL DEFAULT '',"
                    + "email TEXT NOT NULL DEFAULT '',"
                    + "avatar_url TEXT NOT NULL DEFAULT '',"
                    + "linked_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL,"
                    + "PRIMARY KEY(user_id, provider),"
                    + "UNIQUE(provider, provider_user_id),"
                    + "FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS oauth_states ("
                    + "state TEXT PRIMARY KEY,"
                    + "provider TEXT NOT NULL,"
                    + "mode TEXT NOT NULL,"
                    + "user_id INTEGER,"
                    + "created_at INTEGER NOT NULL,"
                    + "expires_at INTEGER NOT NULL"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS panel_logs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "kind TEXT NOT NULL,"
                    + "source TEXT NOT NULL,"
                    + "message TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS known_players ("
                    + "uuid TEXT PRIMARY KEY,"
                    + "username TEXT NOT NULL,"
                    + "last_seen_at INTEGER NOT NULL"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS player_activity ("
                    + "uuid TEXT PRIMARY KEY,"
                    + "first_joined INTEGER NOT NULL DEFAULT 0,"
                    + "last_seen INTEGER NOT NULL DEFAULT 0,"
                    + "total_playtime_seconds INTEGER NOT NULL DEFAULT 0,"
                    + "total_sessions INTEGER NOT NULL DEFAULT 0,"
                    + "current_session_start INTEGER NOT NULL DEFAULT 0,"
                    + "last_ip TEXT NOT NULL DEFAULT '',"
                    + "last_country TEXT NOT NULL DEFAULT ''"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS join_leave_events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "event_type TEXT NOT NULL,"
                    + "player_uuid TEXT NOT NULL,"
                    + "player_name TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS discord_webhook_config ("
                    + "id INTEGER PRIMARY KEY,"
                    + "enabled INTEGER NOT NULL,"
                    + "webhook_url TEXT NOT NULL,"
                    + "use_embed INTEGER NOT NULL,"
                    + "bot_name TEXT NOT NULL,"
                    + "message_template TEXT NOT NULL,"
                    + "embed_title_template TEXT NOT NULL,"
                    + "log_chat INTEGER NOT NULL,"
                    + "log_commands INTEGER NOT NULL,"
                    + "log_auth INTEGER NOT NULL,"
                    + "log_audit INTEGER NOT NULL,"
                    + "log_console_response INTEGER NOT NULL,"
                    + "log_system INTEGER NOT NULL"
                    + ")");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize database", exception);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
    }

    public void close() {

    }
}

