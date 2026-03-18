package de.winniepat.minePanel.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class OAuthAccountRepository {

    private final Database database;

    public OAuthAccountRepository(Database database) {
        this.database = database;
    }

    public Optional<Long> findUserIdByProviderSubject(String provider, String providerUserId) {
        String normalizedProvider = normalizeProvider(provider);
        String sql = "SELECT user_id FROM oauth_accounts WHERE provider = ? AND provider_user_id = ?";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedProvider);
            statement.setString(2, providerUserId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(resultSet.getLong("user_id"));
                }
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not query OAuth account by provider subject", exception);
        }
    }

    public Optional<OAuthAccountLink> findByUserAndProvider(long userId, String provider) {
        String normalizedProvider = normalizeProvider(provider);
        String sql = "SELECT user_id, provider, provider_user_id, display_name, email, avatar_url, linked_at, updated_at "
                + "FROM oauth_accounts WHERE user_id = ? AND provider = ?";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, normalizedProvider);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(map(resultSet));
                }
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not query OAuth account by user and provider", exception);
        }
    }

    public List<OAuthAccountLink> listByUserId(long userId) {
        String sql = "SELECT user_id, provider, provider_user_id, display_name, email, avatar_url, linked_at, updated_at "
                + "FROM oauth_accounts WHERE user_id = ? ORDER BY provider ASC";

        List<OAuthAccountLink> links = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    links.add(map(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list OAuth links for user", exception);
        }
        return links;
    }

    public void upsertLink(long userId, String provider, String providerUserId, String displayName, String email, String avatarUrl) {
        String normalizedProvider = normalizeProvider(provider);
        long now = Instant.now().toEpochMilli();
        String sql = "INSERT INTO oauth_accounts(user_id, provider, provider_user_id, display_name, email, avatar_url, linked_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(user_id, provider) DO UPDATE SET "
                + "provider_user_id = excluded.provider_user_id, "
                + "display_name = excluded.display_name, "
                + "email = excluded.email, "
                + "avatar_url = excluded.avatar_url, "
                + "updated_at = excluded.updated_at";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, normalizedProvider);
            statement.setString(3, providerUserId);
            statement.setString(4, safe(displayName));
            statement.setString(5, safe(email));
            statement.setString(6, safe(avatarUrl));
            statement.setLong(7, now);
            statement.setLong(8, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not upsert OAuth account link", exception);
        }
    }

    public boolean unlink(long userId, String provider) {
        String normalizedProvider = normalizeProvider(provider);
        String sql = "DELETE FROM oauth_accounts WHERE user_id = ? AND provider = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, normalizedProvider);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not unlink OAuth account", exception);
        }
    }

    private OAuthAccountLink map(ResultSet resultSet) throws SQLException {
        return new OAuthAccountLink(
                resultSet.getLong("user_id"),
                resultSet.getString("provider"),
                resultSet.getString("provider_user_id"),
                resultSet.getString("display_name"),
                resultSet.getString("email"),
                resultSet.getString("avatar_url"),
                resultSet.getLong("linked_at"),
                resultSet.getLong("updated_at")
        );
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record OAuthAccountLink(
            long userId,
            String provider,
            String providerUserId,
            String displayName,
            String email,
            String avatarUrl,
            long linkedAt,
            long updatedAt
    ) {
    }
}

