package de.winniepat.minePanel.extensions.reports;

import com.google.gson.Gson;
import de.winniepat.minePanel.extensions.*;
import de.winniepat.minePanel.logs.PanelLogger;
import de.winniepat.minePanel.persistence.KnownPlayerRepository;
import de.winniepat.minePanel.users.PanelPermission;
import org.bukkit.BanList;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ReportSystemExtension implements MinePanelExtension {

    private final Gson gson = new Gson();
    private ExtensionContext context;
    private ReportRepository reportRepository;

    @Override
    public String id() {
        return "report-system";
    }

    @Override
    public String displayName() {
        return "Report System";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
        this.reportRepository = new ReportRepository(context.database());
        this.reportRepository.initializeSchema();
    }

    @Override
    public void onEnable() {
        KnownPlayerRepository knownPlayerRepository = context.knownPlayerRepository();
        PanelLogger panelLogger = context.panelLogger();
        boolean registered = context.commandRegistry().register(
                id(),
                "report",
                "Report a player to MinePanel moderators",
                "/report <player> <reason>",
                "minepanel.report",
                List.of(),
                new ReportCommand(reportRepository, knownPlayerRepository, panelLogger)
        );

        if (!registered) {
            context.plugin().getLogger().warning("Could not register /report command for report extension.");
        }
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        webRegistry.get("/api/extensions/reports", PanelPermission.VIEW_REPORTS, (request, response, user) -> {
            String status = request.queryParams("status");
            List<Map<String, Object>> reports = reportRepository.listReports(status).stream().map(this::toReportPayload).toList();
            return webRegistry.json(response, 200, Map.of("reports", reports));
        });

        webRegistry.post("/api/extensions/reports/:id/resolve", PanelPermission.MANAGE_REPORTS, (request, response, user) -> {
            long reportId = parseReportId(request.params("id"));
            if (reportId <= 0) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_report_id"));
            }

            boolean updated = reportRepository.markResolved(reportId, user.username(), Instant.now().toEpochMilli());
            if (!updated) {
                return webRegistry.json(response, 404, Map.of("error", "report_not_found_or_closed"));
            }

            context.panelLogger().log("AUDIT", user.username(), "Resolved player report #" + reportId);
            return webRegistry.json(response, 200, Map.of("ok", true));
        });

        webRegistry.post("/api/extensions/reports/:id/ban", PanelPermission.MANAGE_REPORTS, (request, response, user) -> {
            long reportId = parseReportId(request.params("id"));
            if (reportId <= 0) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_report_id"));
            }

            Optional<PlayerReport> report = reportRepository.findById(reportId);
            if (report.isEmpty()) {
                return webRegistry.json(response, 404, Map.of("error", "report_not_found"));
            }

            BanPayload payload = gson.fromJson(request.body(), BanPayload.class);
            Integer durationMinutes = payload == null ? null : payload.durationMinutes();
            String reason = payload == null || isBlank(payload.reason())
                    ? "Banned by report review (#" + reportId + ")"
                    : payload.reason().trim();

            BanResult banResult = banPlayer(report.get(), durationMinutes, reason, user.username());
            if (!banResult.success()) {
                return webRegistry.json(response, 500, Map.of("error", "ban_failed", "details", banResult.error()));
            }

            reportRepository.markResolved(reportId, user.username(), Instant.now().toEpochMilli());
            context.panelLogger().log("AUDIT", user.username(), "Banned " + report.get().suspectName() + " from report #" + reportId + ": " + reason);
            java.util.Map<String, Object> resultPayload = new java.util.HashMap<>();
            resultPayload.put("ok", true);
            resultPayload.put("username", report.get().suspectName());
            resultPayload.put("expiresAt", banResult.expiresAt());
            return webRegistry.json(response, 200, resultPayload);
        });
    }

    @Override
    public List<ExtensionNavigationTab> navigationTabs() {
        return List.of(new ExtensionNavigationTab("server", "Reports", "/dashboard/reports"));
    }

    private Map<String, Object> toReportPayload(PlayerReport report) {
        return Map.of(
                "id", report.id(),
                "reporterUuid", report.reporterUuid().toString(),
                "reporterName", report.reporterName(),
                "suspectUuid", report.suspectUuid().toString(),
                "suspectName", report.suspectName(),
                "reason", report.reason(),
                "status", report.status(),
                "createdAt", report.createdAt(),
                "reviewedBy", report.reviewedBy(),
                "reviewedAt", report.reviewedAt()
        );
    }

    private long parseReportId(String rawId) {
        try {
            return Long.parseLong(rawId);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private BanResult banPlayer(PlayerReport report, Integer durationMinutes, String reason, String actor) {
        try {
            return context.plugin().getServer().getScheduler().callSyncMethod(context.plugin(), () -> {
                Date expiresAt = null;
                if (durationMinutes != null && durationMinutes > 0) {
                    int clampedMinutes = Math.min(durationMinutes, 43_200);
                    expiresAt = Date.from(Instant.now().plusSeconds(clampedMinutes * 60L));
                }

                context.plugin().getServer().getBanList(BanList.Type.NAME)
                        .addBan(report.suspectName(), reason, expiresAt, "MinePanelReport:" + actor);

                Player online = context.plugin().getServer().getPlayer(report.suspectUuid());
                if (online == null) {
                    online = context.plugin().getServer().getPlayerExact(report.suspectName());
                }
                if (online != null) {
                    online.kickPlayer("Banned. Reason: " + reason);
                }

                return new BanResult(true, expiresAt == null ? null : expiresAt.getTime(), "");
            }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new BanResult(false, null, "interrupted");
        } catch (ExecutionException | TimeoutException exception) {
            return new BanResult(false, null, exception.getClass().getSimpleName());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record BanPayload(Integer durationMinutes, String reason) {
    }

    private record BanResult(boolean success, Long expiresAt, String error) {
    }
}

