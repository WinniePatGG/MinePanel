package de.winniepat.minePanel.extensions.reports;

import de.winniepat.minePanel.persistence.Database;

import java.sql.*;
import java.util.*;

public final class ReportRepository {

    private final Database database;

    public ReportRepository(Database database) {
        this.database = database;
    }

    public void initializeSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS player_reports ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "reporter_uuid TEXT NOT NULL,"
                + "reporter_name TEXT NOT NULL,"
                + "suspect_uuid TEXT NOT NULL,"
                + "suspect_name TEXT NOT NULL,"
                + "reason TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "reviewed_by TEXT NOT NULL DEFAULT '',"
                + "reviewed_at INTEGER NOT NULL DEFAULT 0"
                + ")";

        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize report schema", exception);
        }
    }

    public long createReport(UUID reporterUuid, String reporterName, UUID suspectUuid, String suspectName, String reason, long createdAt) {
        String sql = "INSERT INTO player_reports(reporter_uuid, reporter_name, suspect_uuid, suspect_name, reason, status, created_at, reviewed_by, reviewed_at) "
                + "VALUES (?, ?, ?, ?, ?, 'OPEN', ?, '', 0)";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, reporterUuid.toString());
            statement.setString(2, reporterName);
            statement.setString(3, suspectUuid.toString());
            statement.setString(4, suspectName);
            statement.setString(5, reason == null ? "" : reason);
            statement.setLong(6, createdAt);
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                }
            }
            return 0L;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create player report", exception);
        }
    }

    public List<PlayerReport> listReports(String status) {
        boolean filterStatus = status != null && !status.isBlank();
        String sql = filterStatus
                ? "SELECT * FROM player_reports WHERE status = ? ORDER BY created_at DESC, id DESC"
                : "SELECT * FROM player_reports ORDER BY created_at DESC, id DESC";

        List<PlayerReport> reports = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (filterStatus) {
                statement.setString(1, status.trim().toUpperCase(Locale.ROOT));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    reports.add(read(resultSet));
                }
            }
            return reports;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list reports", exception);
        }
    }

    public Optional<PlayerReport> findById(long id) {
        String sql = "SELECT * FROM player_reports WHERE id = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(read(resultSet));
                }
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read report", exception);
        }
    }

    public boolean markResolved(long id, String reviewedBy, long reviewedAt) {
        String sql = "UPDATE player_reports SET status = 'RESOLVED', reviewed_by = ?, reviewed_at = ? WHERE id = ? AND status = 'OPEN'";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reviewedBy == null ? "" : reviewedBy);
            statement.setLong(2, reviewedAt);
            statement.setLong(3, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not resolve report", exception);
        }
    }

    private PlayerReport read(ResultSet resultSet) throws SQLException {
        return new PlayerReport(
                resultSet.getLong("id"),
                UUID.fromString(resultSet.getString("reporter_uuid")),
                resultSet.getString("reporter_name"),
                UUID.fromString(resultSet.getString("suspect_uuid")),
                resultSet.getString("suspect_name"),
                resultSet.getString("reason"),
                resultSet.getString("status"),
                resultSet.getLong("created_at"),
                resultSet.getString("reviewed_by"),
                resultSet.getLong("reviewed_at")
        );
    }
}

