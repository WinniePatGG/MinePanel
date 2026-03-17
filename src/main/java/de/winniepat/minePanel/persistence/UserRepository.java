package de.winniepat.minePanel.persistence;

import de.winniepat.minePanel.users.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

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

    public long countOwners() {
        String sql = "SELECT COUNT(*) AS amount FROM users WHERE role = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UserRole.OWNER.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.getLong("amount");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count owners", exception);
        }
    }

    public int demoteExtraOwnersToAdmin() {
        String selectSql = "SELECT id FROM users WHERE role = ? ORDER BY id ASC";
        String updateSql = "UPDATE users SET role = ? WHERE id = ?";

        try (Connection connection = database.getConnection();
             PreparedStatement selectStatement = connection.prepareStatement(selectSql);
             PreparedStatement updateStatement = connection.prepareStatement(updateSql)) {
            connection.setAutoCommit(false);
            selectStatement.setString(1, UserRole.OWNER.name());

            List<Long> ownerIds = new ArrayList<>();
            try (ResultSet resultSet = selectStatement.executeQuery()) {
                while (resultSet.next()) {
                    ownerIds.add(resultSet.getLong("id"));
                }
            }

            if (ownerIds.size() <= 1) {
                connection.rollback();
                return 0;
            }

            int updated = 0;
            for (int i = 1; i < ownerIds.size(); i++) {
                long userId = ownerIds.get(i);
                updateStatement.setString(1, UserRole.ADMIN.name());
                updateStatement.setLong(2, userId);
                if (updateStatement.executeUpdate() > 0) {
                    savePermissions(connection, userId, UserRole.ADMIN.defaultPermissions());
                    updated++;
                }
            }

            connection.commit();
            return updated;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not normalize owner accounts", exception);
        }
    }

    public PanelUser createUser(String username, String passwordHash, UserRole role) {
        return createUser(username, passwordHash, role, role.defaultPermissions());
    }

    public PanelUser createUser(String username, String passwordHash, UserRole role, Set<PanelPermission> permissions) {
        String sql = "INSERT INTO users(username, password_hash, role, created_at) VALUES (?, ?, ?, ?)";
        long createdAt = Instant.now().toEpochMilli();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            connection.setAutoCommit(false);
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, role.name());
            statement.setLong(4, createdAt);
            statement.executeUpdate();

            long userId;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    userId = keys.getLong(1);
                } else {
                    throw new IllegalStateException("Could not create user (missing generated key)");
                }
            }

            Set<PanelPermission> sanitized = sanitizePermissions(permissions, role);
            savePermissions(connection, userId, sanitized);
            connection.commit();
            return new PanelUser(userId, username, role, sanitized, createdAt);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create user", exception);
        }
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
                PanelUser user = mapUser(connection, resultSet);
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
                    return Optional.of(mapUser(connection, resultSet));
                }
                return Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not find user by id", exception);
        }
    }

    public List<PanelUser> findAllUsers() {
        String sql = "SELECT id, username, role, created_at FROM users ORDER BY id ASC";
        String permissionSql = "SELECT user_id, permission FROM user_permissions ORDER BY user_id ASC";
        List<PanelUser> users = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement permissionStatement = connection.prepareStatement(permissionSql);
             ResultSet permissionResultSet = permissionStatement.executeQuery();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            Map<Long, Set<PanelPermission>> permissionsByUser = new HashMap<>();
            while (permissionResultSet.next()) {
                long userId = permissionResultSet.getLong("user_id");
                String rawPermission = permissionResultSet.getString("permission");
                PanelPermission permission;
                try {
                    permission = PanelPermission.valueOf(rawPermission);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                permissionsByUser.computeIfAbsent(userId, ignored -> EnumSet.noneOf(PanelPermission.class)).add(permission);
            }

            while (resultSet.next()) {
                long userId = resultSet.getLong("id");
                UserRole role = UserRole.fromString(resultSet.getString("role"));
                Set<PanelPermission> permissions = permissionsByUser.getOrDefault(userId, role.defaultPermissions());
                users.add(new PanelUser(
                        userId,
                        resultSet.getString("username"),
                        role,
                        EnumSet.copyOf(permissions),
                        resultSet.getLong("created_at")
                ));
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
            connection.setAutoCommit(false);
            statement.setString(1, role.name());
            statement.setLong(2, userId);
            boolean changed = statement.executeUpdate() > 0;
            if (!changed) {
                connection.rollback();
                return false;
            }

            savePermissions(connection, userId, role.defaultPermissions());
            connection.commit();
            return true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update user role", exception);
        }
    }

    public boolean updatePermissions(long userId, Set<PanelPermission> permissions) {
        Optional<PanelUser> target = findById(userId);
        if (target.isEmpty()) {
            return false;
        }

        Set<PanelPermission> sanitized = sanitizePermissions(permissions, target.get().role());
        String sql = "SELECT id FROM users WHERE id = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    connection.rollback();
                    return false;
                }
            }

            savePermissions(connection, userId, sanitized);
            connection.commit();
            return true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update user permissions", exception);
        }
    }

    public boolean deleteUser(long userId) {
        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete user", exception);
        }
    }

    private PanelUser mapUser(Connection connection, ResultSet resultSet) throws SQLException {
        long userId = resultSet.getLong("id");
        UserRole role = UserRole.fromString(resultSet.getString("role"));
        return new PanelUser(
                userId,
                resultSet.getString("username"),
                role,
                loadPermissions(connection, userId, role),
                resultSet.getLong("created_at")
        );
    }

    private Set<PanelPermission> loadPermissions(Connection connection, long userId, UserRole role) throws SQLException {
        String sql = "SELECT permission FROM user_permissions WHERE user_id = ?";
        Set<PanelPermission> permissions = EnumSet.noneOf(PanelPermission.class);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String rawPermission = resultSet.getString("permission");
                    try {
                        permissions.add(PanelPermission.valueOf(rawPermission));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore unknown permissions from older/newer versions.
                    }
                }
            }
        }

        if (permissions.isEmpty()) {
            return role.defaultPermissions();
        }
        return permissions;
    }

    private void savePermissions(Connection connection, long userId, Set<PanelPermission> permissions) throws SQLException {
        try (PreparedStatement deleteStatement = connection.prepareStatement("DELETE FROM user_permissions WHERE user_id = ?")) {
            deleteStatement.setLong(1, userId);
            deleteStatement.executeUpdate();
        }

        String insertSql = "INSERT INTO user_permissions(user_id, permission) VALUES (?, ?)";
        try (PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
            for (PanelPermission permission : permissions) {
                insertStatement.setLong(1, userId);
                insertStatement.setString(2, permission.name());
                insertStatement.addBatch();
            }
            insertStatement.executeBatch();
        }
    }

    private Set<PanelPermission> sanitizePermissions(Set<PanelPermission> permissions, UserRole role) {
        if (role == UserRole.OWNER) {
            return EnumSet.allOf(PanelPermission.class);
        }

        Set<PanelPermission> source = permissions == null || permissions.isEmpty() ? role.defaultPermissions() : permissions;
        Set<PanelPermission> sanitized = EnumSet.noneOf(PanelPermission.class);
        for (PanelPermission permission : source) {
            if (permission == null || !permission.assignable()) {
                continue;
            }
            sanitized.add(permission);
        }
        sanitized.add(PanelPermission.ACCESS_PANEL);
        return sanitized;
    }
}

