package de.winniepat.minePanel.extensions.tickets;

import de.winniepat.minePanel.persistence.Database;

import java.sql.*;
import java.util.*;

public final class TicketRepository {

    private final Database database;

    public TicketRepository(Database database) {
        this.database = database;
    }

    public void initializeSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS player_tickets ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "creator_uuid TEXT NOT NULL,"
                + "creator_name TEXT NOT NULL,"
                + "category TEXT NOT NULL,"
                + "description TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "handled_by TEXT NOT NULL DEFAULT '',"
                + "handled_at INTEGER NOT NULL DEFAULT 0"
                + ")";

        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize ticket schema", exception);
        }
    }

    public long createTicket(UUID creatorUuid, String creatorName, String category, String description, long createdAt) {
        String sql = "INSERT INTO player_tickets(creator_uuid, creator_name, category, description, status, created_at, updated_at, handled_by, handled_at) "
                + "VALUES (?, ?, ?, ?, 'OPEN', ?, ?, '', 0)";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, creatorUuid.toString());
            statement.setString(2, creatorName == null ? "Unknown" : creatorName);
            statement.setString(3, category == null || category.isBlank() ? "Other" : category.trim());
            statement.setString(4, description == null ? "" : description.trim());
            statement.setLong(5, createdAt);
            statement.setLong(6, createdAt);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                }
            }
            return 0L;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create ticket", exception);
        }
    }

    public List<PlayerTicket> listTickets(String status) {
        boolean filterStatus = status != null && !status.isBlank();
        String sql = filterStatus
                ? "SELECT * FROM player_tickets WHERE status = ? ORDER BY created_at DESC, id DESC"
                : "SELECT * FROM player_tickets ORDER BY created_at DESC, id DESC";

        List<PlayerTicket> tickets = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (filterStatus) {
                statement.setString(1, status.trim().toUpperCase(Locale.ROOT));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tickets.add(read(resultSet));
                }
            }
            return tickets;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list tickets", exception);
        }
    }

    public boolean updateStatus(long id, String status, String handledBy, long handledAt) {
        if (id <= 0 || status == null || status.isBlank()) {
            return false;
        }

        String sql = "UPDATE player_tickets SET status = ?, updated_at = ?, handled_by = ?, handled_at = ? WHERE id = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.trim().toUpperCase(Locale.ROOT));
            statement.setLong(2, handledAt);
            statement.setString(3, handledBy == null ? "" : handledBy);
            statement.setLong(4, handledAt);
            statement.setLong(5, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update ticket status", exception);
        }
    }

    private PlayerTicket read(ResultSet resultSet) throws SQLException {
        return new PlayerTicket(
                resultSet.getLong("id"),
                UUID.fromString(resultSet.getString("creator_uuid")),
                resultSet.getString("creator_name"),
                resultSet.getString("category"),
                resultSet.getString("description"),
                resultSet.getString("status"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"),
                resultSet.getString("handled_by"),
                resultSet.getLong("handled_at")
        );
    }
}

