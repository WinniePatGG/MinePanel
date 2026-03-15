# MinePanel

![MinePanel](https://img.shields.io/badge/MinePanel-Web%20Panel%20for%20Paper-4f8cff?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-21-ff8a65?style=for-the-badge)
![Paper](https://img.shields.io/badge/Paper-1.21.x-58d68d?style=for-the-badge)
![Extensions](https://img.shields.io/badge/Extensions-Modular-9b59b6?style=for-the-badge)

MinePanel is a Paper plugin that runs an embedded web panel for Minecraft server administration.

The core plugin provides a secure web UI (login, roles, sessions, dashboard pages, logs, users, etc.) and an extension system so extra features can be added as separate jars.

## What You Get

### Core (works without extensions)

- 🖥️ Embedded HTTP server for panel pages and API.
- 🔐 First-launch bootstrap flow to create the owner account.
- 🛡️ Secure auth with BCrypt + server-side sessions.
- 👥 Role/permission based panel access (`OWNER`, `ADMIN`, `VIEWER`).
- 📜 Console page with live panel log updates and command sending.
- 📊 Overview page with:
  - TPS, memory, CPU cards
  - TPS/Memory/CPU time-series charts
  - Join/leave heatmaps (day x hour)
- 🎮 Players page with profile details and activity info.
- 🔌 Plugin and bans pages.
- 🎨 Themes page.
- 🧩 Extension management page.

### Extension system

- Core scans `plugins/MinePanel/extensions` on startup.
- If no extension jars are installed, you get the default panel only.
- If extension jars are present, their routes/tabs/features are loaded after restart.
- Extensions are discovered via Java `ServiceLoader` using:
  - `META-INF/services/de.winniepat.minePanel.extensions.MinePanelExtension`

## Runtime Configuration

Default config in `src/main/resources/config.yml`:

```yaml
web:
  host: 127.0.0.1
  port: 8080
  sessionTtlMinutes: 120

security:
  bootstrapTokenLength: 32
```

## Build

### Build core + extension jars

```powershell
.\gradlew.bat assemble
```

### Build only core shadow jar

```powershell
.\gradlew.bat shadowJar
```

## Build Outputs

- Core plugin jar:
  - `build/libs/MinePanel-<version>.jar`
- Reports extension jar:
  - `build/libs/extensions/MinePanel-Extension-Reports-<version>.jar`
- Player-management extension jar:
  - `build/libs/extensions/MinePanel-Extension-PlayerManagement-<version>.jar`
- LuckPerms extension jar:
  - `build/libs/extensions/MinePanel-Extension-LuckPerms-<version>.jar`

## Installation

1. Copy core jar to your server `plugins` folder.
2. Start server once.
3. Check console for the bootstrap token.
4. Open panel in browser: `http://127.0.0.1:8080` (or your configured host/port).
5. Complete setup and create owner account.

## Installing Extensions

1. Build or download extension jars.
2. Put them in:
   - `plugins/MinePanel/extensions`
3. Restart the server.
4. New extension tabs/features become available.

## Extension Links

- Modrinth author page: `https://modrinth.com/user/WinniePatGG`
- Browse MinePanel-related plugins on Modrinth: `https://modrinth.com/plugins?query=minepanel`
- Tip: The panel catalog already filters available extensions to projects by `WinniePatGG`.

## Included Extension Artifacts in This Repo

This repository can build two extension jars:

- `MinePanel-Extension-Reports-*`
  - Adds report system features.
- `MinePanel-Extension-PlayerManagement-*`
  - Adds moderation/mute related player-management features.
- `MinePanel-Extension-LuckPerms-*`
  - Adds LuckPerms player details in Players tab (groups, permissions, prefix/suffix).

## Writing Third-Party Extensions

Detailed step-by-step guide:

- `docs/EXTENSIONS.md`

Implement `de.winniepat.minePanel.extensions.MinePanelExtension`.

Typical flow:

1. Implement extension class (`id`, `displayName`, lifecycle hooks).
2. Add service descriptor file:
   - `META-INF/services/de.winniepat.minePanel.extensions.MinePanelExtension`
3. (Optional) Register panel routes via `registerWebRoutes(...)`.
4. (Optional) Add sidebar tabs via `navigationTabs()`.
5. (Optional) Register runtime commands via `ExtensionContext.commandRegistry()`.
6. Package jar and drop into `plugins/MinePanel/extensions`.

## Security Notes

- Use a reverse proxy + HTTPS in production.
- Restrict direct access to panel port (`web.port`) with firewall/network rules.
- Treat bootstrap tokens and owner credentials as sensitive secrets.



