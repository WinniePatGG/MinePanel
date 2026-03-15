package de.winniepat.minePanel.extensions;

import de.winniepat.minePanel.MinePanel;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public final class ExtensionManager {

    private final MinePanel plugin;
    private final ExtensionContext context;
    private final List<MinePanelExtension> loadedExtensions = new ArrayList<>();
    private final List<URLClassLoader> classLoaders = new ArrayList<>();
    private final Set<String> loadedIds = new HashSet<>();
    private final Map<String, String> extensionSourceById = new HashMap<>();
    private Path extensionsDirectory;

    public ExtensionManager(MinePanel plugin, ExtensionContext context) {
        this.plugin = plugin;
        this.context = context;
    }

    public void registerBuiltIn(MinePanelExtension extension) {
        registerLoadedExtension(extension, "built-in");
    }

    public void loadFromDirectory(Path extensionsDirectory) {
        this.extensionsDirectory = extensionsDirectory;
        try {
            Files.createDirectories(extensionsDirectory);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create extensions directory: " + exception.getMessage());
            return;
        }

        try (Stream<Path> files = Files.list(extensionsDirectory)) {
            files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(this::loadJarExtensions);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not scan extensions directory: " + exception.getMessage());
        }
    }

    public synchronized List<Map<String, Object>> installedExtensions() {
        List<Map<String, Object>> installed = new ArrayList<>();
        for (MinePanelExtension extension : loadedExtensions) {
            String id = extension.id() == null ? "" : extension.id();
            String source = extensionSourceById.getOrDefault(id.toLowerCase(Locale.ROOT), "unknown");
            installed.add(Map.of(
                    "id", id,
                    "displayName", extension.displayName() == null ? id : extension.displayName(),
                    "source", source
            ));
        }

        installed.sort(Comparator.comparing(item -> String.valueOf(item.get("id")), String.CASE_INSENSITIVE_ORDER));
        return installed;
    }

    public synchronized List<Map<String, Object>> availableArtifacts() {
        List<String> artifacts = scanArtifactFileNames();
        Set<String> loadedArtifacts = new HashSet<>();
        for (String source : extensionSourceById.values()) {
            if (source != null && source.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                loadedArtifacts.add(source);
            }
        }

        List<Map<String, Object>> available = new ArrayList<>();
        for (String artifact : artifacts) {
            available.add(Map.of(
                    "fileName", artifact,
                    "loaded", loadedArtifacts.contains(artifact)
            ));
        }
        return available;
    }

    public void enableAll() {
        for (MinePanelExtension extension : loadedExtensions) {
            try {
                extension.onEnable();
                plugin.getLogger().info("Enabled extension " + extension.id() + " (" + extension.displayName() + ")");
            } catch (Exception exception) {
                context.commandRegistry().unregisterForExtension(extension.id());
                plugin.getLogger().warning("Could not enable extension " + extension.id() + ": " + exception.getMessage());
            }
        }
    }

    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        for (MinePanelExtension extension : loadedExtensions) {
            try {
                extension.registerWebRoutes(webRegistry);
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not register web routes for extension " + extension.id() + ": " + exception.getMessage());
            }
        }
    }

    public List<ExtensionNavigationTab> navigationTabs() {
        List<ExtensionNavigationTab> tabs = new ArrayList<>();
        for (MinePanelExtension extension : loadedExtensions) {
            try {
                tabs.addAll(extension.navigationTabs());
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not read tabs for extension " + extension.id() + ": " + exception.getMessage());
            }
        }
        return tabs;
    }

    public void disableAll() {
        ListIterator<MinePanelExtension> iterator = loadedExtensions.listIterator(loadedExtensions.size());
        while (iterator.hasPrevious()) {
            MinePanelExtension extension = iterator.previous();
            try {
                extension.onDisable();
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not disable extension " + extension.id() + ": " + exception.getMessage());
            } finally {
                context.commandRegistry().unregisterForExtension(extension.id());
            }
        }

        context.commandRegistry().unregisterAll();

        for (URLClassLoader classLoader : classLoaders) {
            try {
                classLoader.close();
            } catch (IOException ignored) {
                // Best effort cleanup.
            }
        }
        classLoaders.clear();
    }

    private void loadJarExtensions(Path jarFile) {
        try {
            URL url = jarFile.toUri().toURL();
            URLClassLoader classLoader = new URLClassLoader(new URL[]{url}, plugin.getClass().getClassLoader());
            classLoaders.add(classLoader);

            ServiceLoader<MinePanelExtension> serviceLoader = ServiceLoader.load(MinePanelExtension.class, classLoader);
            int found = 0;
            for (MinePanelExtension extension : serviceLoader) {
                found++;
                registerLoadedExtension(extension, jarFile.getFileName().toString());
            }

            if (found == 0) {
                plugin.getLogger().warning("No MinePanel extension entry found in " + jarFile.getFileName() + ". Add META-INF/services/" + MinePanelExtension.class.getName());
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not load extension jar " + jarFile.getFileName() + ": " + exception.getMessage());
        }
    }

    private void registerLoadedExtension(MinePanelExtension extension, String source) {
        if (extension == null || extension.id() == null || extension.id().isBlank()) {
            plugin.getLogger().warning("Skipping extension with invalid id from " + source);
            return;
        }

        String id = extension.id().trim().toLowerCase(Locale.ROOT);
        if (!loadedIds.add(id)) {
            plugin.getLogger().warning("Skipping duplicate extension id: " + extension.id());
            return;
        }

        try {
            extension.onLoad(context);
            loadedExtensions.add(extension);
            extensionSourceById.put(id, source);
            plugin.getLogger().info("Loaded extension " + extension.id() + " from " + source);
        } catch (Exception exception) {
            loadedIds.remove(id);
            plugin.getLogger().warning("Could not initialize extension " + extension.id() + ": " + exception.getMessage());
        }
    }

    private List<String> scanArtifactFileNames() {
        if (extensionsDirectory == null) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(extensionsDirectory)) {
            return files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .map(path -> path.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read extension artifacts: " + exception.getMessage());
            return List.of();
        }
    }
}

