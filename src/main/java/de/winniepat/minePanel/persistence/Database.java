package de.winniepat.minePanel.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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

            statement.execute("CREATE TABLE IF NOT EXISTS sessions ("
                    + "token TEXT PRIMARY KEY,"
                    + "user_id INTEGER NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "expires_at INTEGER NOT NULL,"
                    + "FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE"
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
        // sqlite connections are short-lived and closed per operation
    }
}

