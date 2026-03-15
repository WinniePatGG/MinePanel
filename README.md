# MinePanel

Paper plugin with an embedded admin web panel.

## Features

- Embedded web server for admin panel pages and API
- First-launch owner bootstrap using a one-time setup token
- Secure login with BCrypt password hashing and server-side session storage
- User management with roles (`OWNER`, `ADMIN`, `VIEWER`) and permission checks
- Captures in-game chat and command activity to persistent logs
- Reads `logs/latest.log` tail for live console visibility
- Stores panel log history in SQLite and exports a full shutdown snapshot into the server `logs` directory
- Extension framework for loading MinePanel extensions from `plugins/MinePanel/extensions`
- Default panel works without extensions; extension features only appear when extension jars are installed

## Configuration

`src/main/resources/config.yml` defaults:

```yaml
web:
  host: 127.0.0.1
  port: 8080
  sessionTtlMinutes: 120
security:
  bootstrapTokenLength: 32

```

## Build

```powershell
.\gradlew.bat shadowJar
```

Output jar: `build/libs/MinePanel-<version>.jar`

Build core + extension jars:

```powershell
.\gradlew.bat assemble
```

Outputs:

- Core plugin: `build/libs/MinePanel-<version>.jar`
- Reports extension: `build/libs/extensions/MinePanel-Extension-Reports-<version>.jar`
- Player management extension: `build/libs/extensions/MinePanel-Extension-PlayerManagement-<version>.jar`

## Extensions

- Core scans `plugins/MinePanel/extensions` for extension jars.
- Loading is restart-based: drop an extension jar into `plugins/MinePanel/extensions`, restart the server, and the extension is loaded automatically.
- External jars should provide implementations of `de.winniepat.minePanel.extensions.MinePanelExtension` via Java `ServiceLoader` (`META-INF/services/...`).
- Extensions can register API routes and sidebar tabs and can use the provided `ExtensionContext` services.
- Runtime commands are supported through `ExtensionContext.commandRegistry()` so extension commands become available right after restart without editing MinePanel `plugin.yml`.

Minimal external extension flow:

1. Build your extension jar with an implementation of `MinePanelExtension`.
2. Add `META-INF/services/de.winniepat.minePanel.extensions.MinePanelExtension` containing your implementation class name.
3. In `onEnable`, register commands via `context.commandRegistry().register(...)`.
4. Optionally return `navigationTabs()` and register API routes via `registerWebRoutes(...)`.
5. Copy jar to `plugins/MinePanel/extensions` and restart the server.

Quick local install for the included extension jars:

```powershell
.\gradlew.bat installExtensionsToRunServer
```

This copies extension jars to `run/plugins/MinePanel/extensions`.

## First launch

1. Start server with plugin installed.
2. Check server console for `First launch setup token: ...`.
3. Open `http://127.0.0.1:8080/setup`.
4. Enter token + owner credentials.
5. Log in and manage users from dashboard.

## Security note

This panel is intended to run behind a reverse proxy with HTTPS in production.
Use firewall rules to restrict direct access to the web panel port.

