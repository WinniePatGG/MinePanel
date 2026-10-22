package de.winniepat.minePanel.extensions;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;

import java.util.List;

public interface ExtensionCommandRegistry {

    boolean register(
            String extensionId,
            String name,
            String description,
            String usage,
            String permission,
            List<String> aliases,
            CommandExecutor executor,
            TabCompleter tabCompleter
    );

    default boolean register(
            String extensionId,
            String name,
            String description,
            String usage,
            String permission,
            List<String> aliases,
            CommandExecutor executor
    ) {
        return register(extensionId, name, description, usage, permission, aliases, executor, null);
    }

    void unregisterForExtension(String extensionId);

    void unregisterAll();
}

