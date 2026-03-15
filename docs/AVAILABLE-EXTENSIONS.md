# Available MinePanel Extensions

## 1) Reports Extension

- **Jar:** `MinePanel-Extension-Reports-<version>.jar`
- **Extension ID:** `report-system`
- **Purpose:** Player report workflow from in-game to web panel moderation.

### Features

- Adds in-game `/report <player> <reason>` command.
- Stores reports in panel database.
- Adds **Reports** tab under Server category (`/dashboard/reports`).
- Allows staff to:
  - list reports
  - resolve reports
  - ban suspects directly from report review

### Permissions

- Web actions use panel permission `MANAGE_PLAYERS`.
- In-game command permission is `minepanel.report`.

---

## 2) Player Management Extension

- **Jar:** `MinePanel-Extension-PlayerManagement-<version>.jar`
- **Extension ID:** `player-management`
- **Purpose:** Advanced moderation controls centered around player mute management.

### Features

- Adds temporary/permanent mute storage and enforcement.
- Blocks chat for muted players.
- Exposes mute state used by the Players tab.
- Supports mute/unmute from panel actions.

### Permissions

- Web actions use panel permission `MANAGE_PLAYERS`.

---

## 3) LuckPerms Integration Extension

- **Jar:** `MinePanel-Extension-LuckPerms-<version>.jar`
- **Extension ID:** `luckperms`
- **Purpose:** Show LuckPerms profile details in MinePanel Players tab.

### Features

- Detects LuckPerms at runtime.
- For selected players, provides:
  - primary group
  - prefix/suffix
  - inherited groups
  - granted permissions (limited list + total count)
- Players tab displays this data when extension is installed and LuckPerms is present.

### Requirements

- LuckPerms plugin must be installed on the server.

### Permissions

- Web route uses panel permission `VIEW_DASHBOARD`.
