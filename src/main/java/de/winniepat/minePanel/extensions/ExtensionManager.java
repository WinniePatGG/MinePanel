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
    private final Set<String> loadedArtifacts = new HashSet<>();
    private final Map<String, String> extensionSourceById = new HashMap<>();
    private Path extensionsDirectory;
    private ExtensionWebRegistry activeWebRegistry;

    public ExtensionManager(MinePanel plugin, ExtensionContext context) {
        this.plugin = plugin;
        this.context = context;
    }

    public void registerBuiltIn(MinePanelExtension extension) {
        registerLoadedExtension(extension, "built-in");
    }

    public synchronized void loadFromDirectory(Path extensionsDirectory) {
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
                    .forEach(path -> loadJarExtensions(path, null));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not scan extensions directory: " + exception.getMessage());
        }
    }

    public synchronized ReloadResult reloadNewFromDirectory(ExtensionWebRegistry webRegistry) {
        if (webRegistry != null) {
            this.activeWebRegistry = webRegistry;
        }

        if (extensionsDirectory == null) {
            return new ReloadResult(0, List.of(), List.of("extensions_directory_unavailable"));
        }

        List<MinePanelExtension> newlyLoaded = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> files = Files.list(extensionsDirectory)) {
            List<Path> jars = files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();

            for (Path jarFile : jars) {
                scanned++;
                String artifactName = jarFile.getFileName().toString();
                if (loadedArtifacts.contains(artifactName)) {
                    continue;
                }
                loadJarExtensions(jarFile, newlyLoaded);
            }
        } catch (IOException exception) {
            warnings.add("could_not_scan_extensions_directory");
            plugin.getLogger().warning("Could not scan extensions directory: " + exception.getMessage());
        }

        for (MinePanelExtension extension : newlyLoaded) {
            try {
                extension.onEnable();
                plugin.getLogger().info("Enabled extension " + extension.id() + " (" + extension.displayName() + ")");
            } catch (Exception exception) {
                warnings.add("enable_failed:" + extension.id());
                context.commandRegistry().unregisterForExtension(extension.id());
                plugin.getLogger().warning("Could not enable extension " + extension.id() + ": " + exception.getMessage());
            }

            if (activeWebRegistry != null) {
                try {
                    extension.registerWebRoutes(activeWebRegistry);
                } catch (Exception exception) {
                    warnings.add("route_registration_failed:" + extension.id());
                    plugin.getLogger().warning("Could not register web routes for extension " + extension.id() + ": " + exception.getMessage());
                }
            }
        }

        List<String> loadedExtensionIds = newlyLoaded.stream().map(MinePanelExtension::id).toList();
        return new ReloadResult(scanned, loadedExtensionIds, warnings);
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

    public synchronized void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        this.activeWebRegistry = webRegistry;
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

    public synchronized void disableAll() {
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
        loadedArtifacts.clear();
        loadedIds.clear();
        extensionSourceById.clear();
        loadedExtensions.clear();
    }

    private void loadJarExtensions(Path jarFile, List<MinePanelExtension> newlyLoaded) {
        URLClassLoader classLoader = null;
        try {
            URL url = jarFile.toUri().toURL();
            classLoader = new URLClassLoader(new URL[]{url}, plugin.getClass().getClassLoader());

            ServiceLoader<MinePanelExtension> serviceLoader = ServiceLoader.load(MinePanelExtension.class, classLoader);
            int found = 0;
            int registered = 0;
            for (MinePanelExtension extension : serviceLoader) {
                found++;
                if (registerLoadedExtension(extension, jarFile.getFileName().toString())) {
                    registered++;
                    if (newlyLoaded != null) {
                        newlyLoaded.add(extension);
                    }
                }
            }

            if (found == 0) {
                plugin.getLogger().warning("No MinePanel extension entry found in " + jarFile.getFileName() + ". Add META-INF/services/" + MinePanelExtension.class.getName());
            }

            if (registered > 0) {
                classLoaders.add(classLoader);
                loadedArtifacts.add(jarFile.getFileName().toString());
                classLoader = null;
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not load extension jar " + jarFile.getFileName() + ": " + exception.getMessage());
        } finally {
            if (classLoader != null) {
                try {
                    classLoader.close();
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            }
        }
    }

    private boolean registerLoadedExtension(MinePanelExtension extension, String source) {
        if (extension == null || extension.id() == null || extension.id().isBlank()) {
            plugin.getLogger().warning("Skipping extension with invalid id from " + source);
            return false;
        }

        String id = extension.id().trim().toLowerCase(Locale.ROOT);
        if (!loadedIds.add(id)) {
            plugin.getLogger().warning("Skipping duplicate extension id: " + extension.id());
            return false;
        }

        try {
            extension.onLoad(context);
            loadedExtensions.add(extension);
            extensionSourceById.put(id, source);
            plugin.getLogger().info("Loaded extension " + extension.id() + " from " + source);
            return true;
        } catch (Exception exception) {
            loadedIds.remove(id);
            plugin.getLogger().warning("Could not initialize extension " + extension.id() + ": " + exception.getMessage());
            return false;
        }
    }

    public record ReloadResult(int scannedArtifactCount, List<String> loadedExtensionIds, List<String> warnings) {
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

