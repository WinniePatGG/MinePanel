# MinePanel

Paper plugin with an embedded admin web panel.

## Features

- Embedded web server for admin panel pages and API
- First-launch owner bootstrap using a one-time setup token
- Secure login with BCrypt password hashing and server-side session storage
- User management with roles (`OWNER`, `ADMIN`, `VIEWER`) and permission checks
- Captures in-game chat and command activity to persistent logs
- Reads `logs/latest.log` tail for live console visibility
- Stores log history in SQLite and also writes daily files in a separate panel log directory

## Configuration

`src/main/resources/config.yml` defaults:

```yaml
web:
  host: 127.0.0.1
  port: 8080
  sessionTtlMinutes: 120
security:
  bootstrapTokenLength: 32
logging:
  separateDirectory: panel-logs
```

## Build

```powershell
.\gradlew.bat shadowJar
```

Output jar: `build/libs/MinePanel-1.0.jar`

## First launch

1. Start server with plugin installed.
2. Check server console for `First launch setup token: ...`.
3. Open `http://127.0.0.1:8080/setup`.
4. Enter token + owner credentials.
5. Log in and manage users from dashboard.

## Security note

This panel is intended to run behind a reverse proxy with HTTPS in production.
Use firewall rules to restrict direct access to the web panel port.

