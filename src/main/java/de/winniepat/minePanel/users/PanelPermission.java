package de.winniepat.minePanel.users;

public enum PanelPermission {
    // Legacy keys kept for compatibility with older route guards.
    VIEW_DASHBOARD("Legacy", "View Dashboard", null, false),
    VIEW_LOGS("Legacy", "View Logs", null, false),

    ACCESS_PANEL("Panel", "Access Panel", null, true),

    VIEW_OVERVIEW("Server", "View Overview", null, true),
    VIEW_CONSOLE("Server", "View Console", null, true),
    SEND_CONSOLE("Server", "Send Console Commands", null, true),
    VIEW_RESOURCES("Server", "View Resources", null, true),
    VIEW_PLAYERS("Server", "View Players", null, true),
    MANAGE_PLAYERS("Server", "Manage Players", null, true),
    VIEW_BANS("Server", "View Bans", null, true),
    MANAGE_BANS("Server", "Manage Bans", null, true),
    VIEW_PLUGINS("Server", "View Plugins", null, true),
    MANAGE_PLUGINS("Server", "Install Plugins", null, true),

    VIEW_USERS("Panel", "View Users", null, true),
    MANAGE_USERS("Panel", "Manage Users", null, true),
    VIEW_DISCORD_WEBHOOK("Panel", "View Discord Webhook", null, true),
    MANAGE_DISCORD_WEBHOOK("Panel", "Manage Discord Webhook", null, true),
    VIEW_THEMES("Panel", "View Themes", null, true),
    MANAGE_THEMES("Panel", "Manage Themes", null, true),
    VIEW_EXTENSIONS("Panel", "View Extensions", null, true),
    MANAGE_EXTENSIONS("Panel", "Manage Extensions", null, true),

    VIEW_BACKUPS("Extensions", "View Backups", "world-backups", true),
    MANAGE_BACKUPS("Extensions", "Manage Backups", "world-backups", true),
    VIEW_REPORTS("Extensions", "View Reports", "report-system", true),
    MANAGE_REPORTS("Extensions", "Manage Reports", "report-system", true),
    VIEW_TICKETS("Extensions", "View Tickets", "ticket-system", true),
    MANAGE_TICKETS("Extensions", "Manage Tickets", "ticket-system", true),
    VIEW_PLAYER_MANAGEMENT("Extensions", "View Player Management", "player-management", true),
    MANAGE_PLAYER_MANAGEMENT("Extensions", "Manage Player Management", "player-management", true),
    VIEW_PLAYER_STATS("Extensions", "View Player Stats", "player-stats", true),
    VIEW_LUCKPERMS("Extensions", "View LuckPerms Data", "luckperms", true);

    private final String category;
    private final String label;
    private final String extensionId;
    private final boolean assignable;

    PanelPermission(String category, String label, String extensionId, boolean assignable) {
        this.category = category;
        this.label = label;
        this.extensionId = extensionId;
        this.assignable = assignable;
    }

    public String category() {
        return category;
    }

    public String label() {
        return label;
    }

    public String extensionId() {
        return extensionId;
    }

    public boolean assignable() {
        return assignable;
    }
}

