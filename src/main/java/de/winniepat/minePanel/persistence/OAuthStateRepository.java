package de.winniepat.minePanel.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

public final class OAuthStateRepository {

    private final Database database;

    public OAuthStateRepository(Database database) {
        this.database = database;
    }

    public void createState(String state, String provider, String mode, Long userId, long expiresAtMillis) {
        cleanupExpired();

        String sql = "INSERT INTO oauth_states(state, provider, mode, user_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)";
        long now = Instant.now().toEpochMilli();

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state);
            statement.setString(2, normalize(provider));
            statement.setString(3, normalize(mode));
            if (userId == null) {
                statement.setNull(4, java.sql.Types.INTEGER);
            } else {
                statement.setLong(4, userId);
            }
            statement.setLong(5, now);
            statement.setLong(6, expiresAtMillis);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create OAuth state", exception);
        }
    }

    public Optional<OAuthState> consumeState(String state, String provider, String mode) {
        String normalizedProvider = normalize(provider);
        String normalizedMode = normalize(mode);
        String selectSql = "SELECT state, provider, mode, user_id, created_at, expires_at FROM oauth_states WHERE state = ? AND provider = ? AND mode = ?";
        String deleteSql = "DELETE FROM oauth_states WHERE state = ?";

        try (Connection connection = database.getConnection();
             PreparedStatement selectStatement = connection.prepareStatement(selectSql);
             PreparedStatement deleteStatement = connection.prepareStatement(deleteSql)) {
            connection.setAutoCommit(false);

            selectStatement.setString(1, state);
            selectStatement.setString(2, normalizedProvider);
            selectStatement.setString(3, normalizedMode);

            OAuthState oauthState = null;
            try (ResultSet resultSet = selectStatement.executeQuery()) {
                if (resultSet.next()) {
                    Long userId = null;
                    long rawUserId = resultSet.getLong("user_id");
                    if (!resultSet.wasNull()) {
                        userId = rawUserId;
                    }
                    oauthState = new OAuthState(
                            resultSet.getString("state"),
                            resultSet.getString("provider"),
                            resultSet.getString("mode"),
                            userId,
                            resultSet.getLong("created_at"),
                            resultSet.getLong("expires_at")
                    );
                }
            }

            if (oauthState == null) {
                connection.rollback();
                return Optional.empty();
            }

            deleteStatement.setString(1, state);
            deleteStatement.executeUpdate();

            if (oauthState.expiresAtMillis() <= Instant.now().toEpochMilli()) {
                connection.commit();
                return Optional.empty();
            }

            connection.commit();
            return Optional.of(oauthState);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not consume OAuth state", exception);
        }
    }

    public void cleanupExpired() {
        String sql = "DELETE FROM oauth_states WHERE expires_at <= ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not cleanup OAuth states", exception);
        }
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public record OAuthState(
            String state,
            String provider,
            String mode,
            Long userId,
            long createdAtMillis,
            long expiresAtMillis
    ) {
    }
}

