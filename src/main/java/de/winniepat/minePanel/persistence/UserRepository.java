package de.winniepat.minePanel.persistence;

import de.winniepat.minePanel.users.PanelUser;
import de.winniepat.minePanel.users.PanelUserAuth;
import de.winniepat.minePanel.users.UserRole;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class UserRepository {

    private final Database database;

    public UserRepository(Database database) {
        this.database = database;
    }

    public long countUsers() {
        String sql = "SELECT COUNT(*) AS amount FROM users";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.getLong("amount");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count users", exception);
        }
    }

    public PanelUser createUser(String username, String passwordHash, UserRole role) {
        String sql = "INSERT INTO users(username, password_hash, role, created_at) VALUES (?, ?, ?, ?)";
        long createdAt = Instant.now().toEpochMilli();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, role.name());
            statement.setLong(4, createdAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return new PanelUser(keys.getLong(1), username, role, createdAt);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create user", exception);
        }
        throw new IllegalStateException("Could not create user (missing generated key)");
    }

    public Optional<PanelUserAuth> findByUsername(String username) {
        String sql = "SELECT id, username, password_hash, role, created_at FROM users WHERE username = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                PanelUser user = mapUser(resultSet);
                String passwordHash = resultSet.getString("password_hash");
                return Optional.of(new PanelUserAuth(user, passwordHash));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not find user by username", exception);
        }
    }

    public Optional<PanelUser> findById(long userId) {
        String sql = "SELECT id, username, role, created_at FROM users WHERE id = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapUser(resultSet));
                }
                return Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not find user by id", exception);
        }
    }

    public List<PanelUser> findAllUsers() {
        String sql = "SELECT id, username, role, created_at FROM users ORDER BY id ASC";
        List<PanelUser> users = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                users.add(mapUser(resultSet));
            }
            return users;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list users", exception);
        }
    }

    public boolean updateRole(long userId, UserRole role) {
        String sql = "UPDATE users SET role = ? WHERE id = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, role.name());
            statement.setLong(2, userId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update user role", exception);
        }
    }

    private PanelUser mapUser(ResultSet resultSet) throws SQLException {
        return new PanelUser(
                resultSet.getLong("id"),
                resultSet.getString("username"),
                UserRole.fromString(resultSet.getString("role")),
                resultSet.getLong("created_at")
        );
    }
}

