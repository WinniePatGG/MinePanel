package de.winniepat.minePanel.auth;

import de.winniepat.minePanel.persistence.Database;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

public final class SessionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Database database;
    private final int sessionTtlMinutes;

    public SessionService(Database database, int sessionTtlMinutes) {
        this.database = database;
        this.sessionTtlMinutes = sessionTtlMinutes;
    }

    public String createSession(long userId) {
        cleanupExpiredSessions();

        String token = generateToken();
        long now = Instant.now().toEpochMilli();
        long expiresAt = now + (sessionTtlMinutes * 60_000L);

        String sql = "INSERT INTO sessions(token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, token);
            statement.setLong(2, userId);
            statement.setLong(3, now);
            statement.setLong(4, expiresAt);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create session", exception);
        }
        return token;
    }

    public Optional<Long> resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        long now = Instant.now().toEpochMilli();
        String sql = "SELECT user_id FROM sessions WHERE token = ? AND expires_at > ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, token);
            statement.setLong(2, now);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(resultSet.getLong("user_id"));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not resolve session", exception);
        }
        return Optional.empty();
    }

    public void deleteSession(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        String sql = "DELETE FROM sessions WHERE token = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, token);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete session", exception);
        }
    }

    public void cleanupExpiredSessions() {
        String sql = "DELETE FROM sessions WHERE expires_at <= ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not clean expired sessions", exception);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

