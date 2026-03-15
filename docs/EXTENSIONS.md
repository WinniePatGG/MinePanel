# Writing MinePanel Extensions

This guide explains how to create your own MinePanel extension jar and load it with the main `MinePanel` plugin.

The goal is simple:

1. Build an extension jar.
2. Put it in `plugins/MinePanel/extensions`.
3. Restart the server.
4. Your routes, tabs, and commands appear in the panel.

---

## 1) How Extensions Work

MinePanel loads extensions with Java `ServiceLoader`.

Your extension must:

- Implement `de.winniepat.minePanel.extensions.MinePanelExtension`
- Be listed in this file inside your jar:
  - `META-INF/services/de.winniepat.minePanel.extensions.MinePanelExtension`

MinePanel will call lifecycle hooks in this order:

1. `onLoad(context)`
2. `onEnable()`
3. `registerWebRoutes(webRegistry)`
4. `navigationTabs()`

On shutdown/reload it calls:

- `onDisable()`

---

## 2) Minimal Extension Project Structure

Use a separate Gradle project for each extension (recommended):

```text
my-minepanel-extension/
  build.gradle
  settings.gradle
  src/main/java/com/example/MyExtension.java
  src/main/resources/META-INF/services/de.winniepat.minePanel.extensions.MinePanelExtension
```

---

## 3) Gradle Setup (Minimal)

`build.gradle` example:

```groovy
plugins {
    id 'java'
}

group = 'com.example'
version = '1.0.0'

repositories {
    mavenCentral()
    maven {
        url = 'https://repo.papermc.io/repository/maven-public/'
    }
}

dependencies {
    // Paper types for commands/events
    compileOnly 'io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT'

    // MinePanel API types are inside the MinePanel plugin jar.
    // Point this to your local MinePanel jar while developing.
    compileOnly files('libs/MinePanel-alpha-4.jar')
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

Note: Keep MinePanel as `compileOnly`. Do not shade MinePanel into your extension jar.

---

## 4) Service Registration File

Create this file:

`src/main/resources/META-INF/services/de.winniepat.minePanel.extensions.MinePanelExtension`

File content must be the full class name of your extension implementation, for example:

```text
com.example.MyExtension
```

---

## 5) Minimal Extension Class

```java
package com.example;

import de.winniepat.minePanel.extensions.ExtensionContext;
import de.winniepat.minePanel.extensions.ExtensionNavigationTab;
import de.winniepat.minePanel.extensions.ExtensionWebRegistry;
import de.winniepat.minePanel.extensions.MinePanelExtension;
import de.winniepat.minePanel.users.PanelPermission;

import java.util.List;
import java.util.Map;

public final class MyExtension implements MinePanelExtension {

    private ExtensionContext context;

    @Override
    public String id() {
        return "my-extension";
    }

    @Override
    public String displayName() {
        return "My Extension";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
    }

    @Override
    public void onEnable() {
        context.panelLogger().log("SYSTEM", id(), "My extension enabled");
    }

    @Override
    public void onDisable() {
        context.panelLogger().log("SYSTEM", id(), "My extension disabled");
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry web) {
        web.get("/api/extensions/my-extension/ping", PanelPermission.VIEW_DASHBOARD,
                (req, res, user) -> web.json(res, 200, Map.of("ok", true, "user", user.username())));
    }

    @Override
    public List<ExtensionNavigationTab> navigationTabs() {
        return List.of(new ExtensionNavigationTab("server", "My Extension", "/dashboard/my-extension"));
    }
}
```

---

## 6) Register Runtime Commands (Optional)

MinePanel provides a command registry for extensions.

Example in `onEnable()`:

```java
boolean ok = context.commandRegistry().register(
        id(),
        "myext",
        "My extension command",
        "/myext",
        "minepanel.report",
        List.of("myextension"),
        (sender, command, label, args) -> {
            sender.sendMessage("My extension command works.");
            return true;
        }
);
```

Important:

- Command names must be unique.
- MinePanel automatically unregisters extension commands on disable/reload.

---

## 7) Using the Database (Optional)

Use `context.database()` to store your extension data.

Best practice:

- Create your own table(s) in `onLoad()` using `CREATE TABLE IF NOT EXISTS`.
- Prefix table names with your extension id to avoid conflicts.

Example table name:

- `my_extension_reports`

---

## 8) Security and Permissions

For web routes, always choose the minimum required `PanelPermission`:

- `VIEW_DASHBOARD`
- `VIEW_LOGS`
- `SEND_CONSOLE`
- `MANAGE_PLAYERS`
- `MANAGE_USERS`

Do not expose routes without permission checks.

---

## 9) Build and Install

Build your extension jar:

```powershell
./gradlew.bat build
```

Copy jar to:

```text
plugins/MinePanel/extensions
```

Restart server.

Then open MinePanel and verify:

- Your extension appears in the Extensions page as installed.
- Your tab appears in the sidebar (if you returned one).
- Your API routes respond.

---

## 10) Troubleshooting

If extension does not load:

1. Check server log for `Could not load extension jar` or `No MinePanel extension entry found`.
2. Verify service file path and class name are correct.
3. Ensure extension class is `public` and has a no-arg constructor.
4. Ensure you did not shade MinePanel classes into your extension.
5. Ensure Java version matches server runtime (Java 21 here).

If routes/tabs do not show:

1. Confirm extension is listed in Extensions page.
2. Confirm `registerWebRoutes(...)` and `navigationTabs()` are implemented.
3. Confirm user role has needed panel permissions.

---

## 11) Suggested Development Workflow

1. Start with one route (`/ping`) and one tab.
2. Add database schema.
3. Add commands.
4. Add UI page and polling.
5. Add permission checks and error handling.

Keep each extension focused on one feature area. Smaller extensions are easier to maintain.

