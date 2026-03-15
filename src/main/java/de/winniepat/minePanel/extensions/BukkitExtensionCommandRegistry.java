package de.winniepat.minePanel.extensions;

import de.winniepat.minePanel.MinePanel;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.command.PluginIdentifiableCommand;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public final class BukkitExtensionCommandRegistry implements ExtensionCommandRegistry {

    private final MinePanel plugin;
    private final Map<String, List<RuntimeExtensionCommand>> commandsByExtensionId = new HashMap<>();

    public BukkitExtensionCommandRegistry(MinePanel plugin) {
        this.plugin = plugin;
    }

    @Override
    public synchronized boolean register(
            String extensionId,
            String name,
            String description,
            String usage,
            String permission,
            List<String> aliases,
            CommandExecutor executor,
            TabCompleter tabCompleter
    ) {
        if (executor == null || isBlank(extensionId) || isBlank(name)) {
            return false;
        }

        String normalizedExtensionId = extensionId.trim().toLowerCase(Locale.ROOT);
        String normalizedName = name.trim().toLowerCase(Locale.ROOT);

        CommandMap commandMap = getCommandMap();
        if (commandMap == null) {
            plugin.getLogger().warning("Could not register extension command '/" + normalizedName + "': command map unavailable");
            return false;
        }

        if (commandMap.getCommand(normalizedName) != null) {
            plugin.getLogger().warning("Could not register extension command '/" + normalizedName + "': command already exists");
            return false;
        }

        RuntimeExtensionCommand command = new RuntimeExtensionCommand(
                plugin,
                normalizedName,
                defaultString(description, "Extension command"),
                defaultString(usage, "/" + normalizedName),
                aliases == null ? List.of() : aliases,
                executor,
                tabCompleter
        );
        if (!isBlank(permission)) {
            command.setPermission(permission.trim());
        }

        boolean registered = commandMap.register("minepanelext", command);
        if (!registered) {
            plugin.getLogger().warning("Could not register extension command '/" + normalizedName + "': command map rejected registration");
            return false;
        }

        commandsByExtensionId.computeIfAbsent(normalizedExtensionId, ignored -> new ArrayList<>()).add(command);
        return true;
    }

    @Override
    public synchronized void unregisterForExtension(String extensionId) {
        if (isBlank(extensionId)) {
            return;
        }

        String normalizedExtensionId = extensionId.trim().toLowerCase(Locale.ROOT);
        List<RuntimeExtensionCommand> commands = commandsByExtensionId.remove(normalizedExtensionId);
        if (commands == null || commands.isEmpty()) {
            return;
        }

        CommandMap commandMap = getCommandMap();
        if (commandMap == null) {
            return;
        }

        Map<String, Command> knownCommands = getKnownCommands(commandMap);
        for (RuntimeExtensionCommand command : commands) {
            command.unregister(commandMap);
            removeCommandEntries(knownCommands, command);
        }
    }

    @Override
    public synchronized void unregisterAll() {
        List<String> extensionIds = new ArrayList<>(commandsByExtensionId.keySet());
        for (String extensionId : extensionIds) {
            unregisterForExtension(extensionId);
        }
        commandsByExtensionId.clear();
    }

    private void removeCommandEntries(Map<String, Command> knownCommands, RuntimeExtensionCommand command) {
        if (knownCommands == null || knownCommands.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<String, Command>> iterator = knownCommands.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Command> entry = iterator.next();
            if (entry.getValue() == command) {
                iterator.remove();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> getKnownCommands(CommandMap commandMap) {
        try {
            Field knownCommandsField = commandMap.getClass().getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            Object value = knownCommandsField.get(commandMap);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Command>) map;
            }
        } catch (Exception ignored) {
            // Best effort cleanup.
        }
        return Collections.emptyMap();
    }

    private CommandMap getCommandMap() {
        try {
            Method getCommandMapMethod = plugin.getServer().getClass().getMethod("getCommandMap");
            Object value = getCommandMapMethod.invoke(plugin.getServer());
            if (value instanceof CommandMap map) {
                return map;
            }
        } catch (Exception ignored) {
            // Supported on CraftServer-based implementations.
        }
        return null;
    }

    private String defaultString(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class RuntimeExtensionCommand extends Command implements PluginIdentifiableCommand {

        private final Plugin plugin;
        private final CommandExecutor executor;
        private final TabCompleter tabCompleter;

        private RuntimeExtensionCommand(
                Plugin plugin,
                String name,
                String description,
                String usage,
                List<String> aliases,
                CommandExecutor executor,
                TabCompleter tabCompleter
        ) {
            super(name, description, usage, aliases == null ? List.of() : aliases);
            this.plugin = plugin;
            this.executor = executor;
            this.tabCompleter = tabCompleter;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            if (testPermission(sender)) {
                return executor.onCommand(sender, this, commandLabel, args);
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (tabCompleter == null) {
                return List.of();
            }
            List<String> completions = tabCompleter.onTabComplete(sender, this, alias, args);
            return completions == null ? List.of() : completions;
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }
    }
}

