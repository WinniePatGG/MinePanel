package de.winniepat.minePanel.extensions.tickets;

import de.winniepat.minePanel.extensions.ExtensionContext;
import de.winniepat.minePanel.extensions.ExtensionNavigationTab;
import de.winniepat.minePanel.extensions.ExtensionWebRegistry;
import de.winniepat.minePanel.extensions.MinePanelExtension;
import de.winniepat.minePanel.users.PanelPermission;
import org.bukkit.event.HandlerList;

import java.time.Instant;
import java.util.*;

public final class TicketSystemExtension implements MinePanelExtension {

    private ExtensionContext context;
    private TicketRepository ticketRepository;
    private TicketMenuListener menuListener;

    @Override
    public String id() {
        return "ticket-system";
    }

    @Override
    public String displayName() {
        return "Ticket System";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
        this.ticketRepository = new TicketRepository(context.database());
        this.ticketRepository.initializeSchema();
    }

    @Override
    public void onEnable() {
        this.menuListener = new TicketMenuListener(ticketRepository, context.knownPlayerRepository(), context.panelLogger());
        context.plugin().getServer().getPluginManager().registerEvents(menuListener, context.plugin());

        boolean registered = context.commandRegistry().register(
                id(),
                "ticket",
                "Create a support ticket for server staff",
                "/ticket",
                "minepanel.ticket",
                List.of("support"),
                new TicketCommand(menuListener)
        );

        if (!registered) {
            context.plugin().getLogger().warning("Could not register /ticket command for ticket extension.");
        }
    }

    @Override
    public void onDisable() {
        if (menuListener != null) {
            HandlerList.unregisterAll(menuListener);
            menuListener = null;
        }
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        webRegistry.get("/api/extensions/tickets", PanelPermission.VIEW_TICKETS, (request, response, user) -> {
            String status = request.queryParams("status");
            List<Map<String, Object>> tickets = ticketRepository.listTickets(status).stream().map(this::toPayload).toList();
            return webRegistry.json(response, 200, Map.of("tickets", tickets));
        });

        webRegistry.post("/api/extensions/tickets/:id/close", PanelPermission.MANAGE_TICKETS, (request, response, user) -> {
            long ticketId = parseTicketId(request.params("id"));
            if (ticketId <= 0) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_ticket_id"));
            }

            boolean updated = ticketRepository.updateStatus(ticketId, "CLOSED", user.username(), Instant.now().toEpochMilli());
            if (!updated) {
                return webRegistry.json(response, 404, Map.of("error", "ticket_not_found"));
            }

            context.panelLogger().log("AUDIT", user.username(), "Closed ticket #" + ticketId);
            return webRegistry.json(response, 200, Map.of("ok", true));
        });

        webRegistry.post("/api/extensions/tickets/:id/reopen", PanelPermission.MANAGE_TICKETS, (request, response, user) -> {
            long ticketId = parseTicketId(request.params("id"));
            if (ticketId <= 0) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_ticket_id"));
            }

            boolean updated = ticketRepository.updateStatus(ticketId, "OPEN", user.username(), Instant.now().toEpochMilli());
            if (!updated) {
                return webRegistry.json(response, 404, Map.of("error", "ticket_not_found"));
            }

            context.panelLogger().log("AUDIT", user.username(), "Reopened ticket #" + ticketId);
            return webRegistry.json(response, 200, Map.of("ok", true));
        });
    }

    @Override
    public List<ExtensionNavigationTab> navigationTabs() {
        return List.of(new ExtensionNavigationTab("server", "Tickets", "/dashboard/tickets"));
    }

    private long parseTicketId(String rawId) {
        try {
            return Long.parseLong(rawId);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private Map<String, Object> toPayload(PlayerTicket ticket) {
        return Map.of(
                "id", ticket.id(),
                "creatorUuid", ticket.creatorUuid().toString(),
                "creatorName", ticket.creatorName(),
                "category", ticket.category(),
                "description", ticket.description(),
                "status", ticket.status(),
                "createdAt", ticket.createdAt(),
                "updatedAt", ticket.updatedAt(),
                "handledBy", ticket.handledBy(),
                "handledAt", ticket.handledAt()
        );
    }
}

