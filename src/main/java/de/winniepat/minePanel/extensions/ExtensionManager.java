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

        cleanupDuplicateArtifactsOnStartup(extensionsDirectory);

        try (Stream<Path> files = Files.list(extensionsDirectory)) {
            files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(this::compareJarsNewestFirst)
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
                    .sorted(this::compareJarsNewestFirst)
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

    public synchronized boolean supportsSettings(String extensionId) {
        String normalizedId = normalizeExtensionId(extensionId);
        if (normalizedId.isBlank()) {
            return false;
        }

        for (MinePanelExtension extension : loadedExtensions) {
            if (!normalizeExtensionId(extension.id()).equals(normalizedId)) {
                continue;
            }
            return extension instanceof ExtensionConfigurable;
        }

        return false;
    }

    public synchronized boolean applySettings(String extensionId, String settingsJson) {
        String normalizedId = normalizeExtensionId(extensionId);
        if (normalizedId.isBlank()) {
            return false;
        }

        for (MinePanelExtension extension : loadedExtensions) {
            if (!normalizeExtensionId(extension.id()).equals(normalizedId)) {
                continue;
            }

            if (!(extension instanceof ExtensionConfigurable configurable)) {
                return false;
            }

            try {
                configurable.onSettingsUpdated(settingsJson == null ? "{}" : settingsJson);
                return true;
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not apply live settings for extension " + extension.id() + ": " + exception.getMessage());
                return false;
            }
        }

        return false;
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

    private int compareJarsNewestFirst(Path left, Path right) {
        long leftModified = lastModifiedMillis(left);
        long rightModified = lastModifiedMillis(right);
        int byModified = Long.compare(rightModified, leftModified);
        if (byModified != 0) {
            return byModified;
        }

        String leftName = left.getFileName().toString().toLowerCase(Locale.ROOT);
        String rightName = right.getFileName().toString().toLowerCase(Locale.ROOT);
        return rightName.compareTo(leftName);
    }

    private long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private void cleanupDuplicateArtifactsOnStartup(Path extensionDirectory) {
        List<Path> jars;
        try (Stream<Path> files = Files.list(extensionDirectory)) {
            jars = files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .toList();
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not inspect extension artifacts for startup cleanup: " + exception.getMessage());
            return;
        }

        if (jars.size() < 2) {
            return;
        }

        Map<String, Path> keepByKey = new HashMap<>();
        Map<String, ArtifactVersionToken> keepVersionByKey = new HashMap<>();

        for (Path jar : jars) {
            String fileName = jar.getFileName().toString();
            String key = extensionKeyFromArtifact(fileName);
            if (key.isBlank()) {
                continue;
            }

            ArtifactVersionToken currentVersion = parseArtifactVersion(fileName);
            Path keptPath = keepByKey.get(key);
            ArtifactVersionToken keptVersion = keepVersionByKey.get(key);

            if (keptPath == null || keptVersion == null) {
                keepByKey.put(key, jar);
                keepVersionByKey.put(key, currentVersion);
                continue;
            }

            int compare = compareArtifactVersions(currentVersion, keptVersion);
            if (compare > 0 || (compare == 0 && compareJarsNewestFirst(jar, keptPath) < 0)) {
                keepByKey.put(key, jar);
                keepVersionByKey.put(key, currentVersion);
            }
        }

        for (Path jar : jars) {
            String fileName = jar.getFileName().toString();
            String key = extensionKeyFromArtifact(fileName);
            if (key.isBlank()) {
                continue;
            }

            Path kept = keepByKey.get(key);
            if (kept == null || kept.equals(jar)) {
                continue;
            }

            try {
                Files.deleteIfExists(jar);
                plugin.getLogger().info("Removed old extension artifact on startup: " + fileName + " (kept " + kept.getFileName() + ")");
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not delete old extension artifact " + fileName + " on startup: " + exception.getMessage());
            }
        }
    }

    private String extensionKeyFromArtifact(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }

        String normalized = fileName.trim().toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".jar") || !normalized.startsWith("minepanel-extension-")) {
            return "";
        }

        normalized = normalized.substring(0, normalized.length() - 4);
        normalized = normalized.substring("minepanel-extension-".length());

        int alphaSplit = normalized.lastIndexOf("-alpha-");
        if (alphaSplit > 0) {
            normalized = normalized.substring(0, alphaSplit);
        } else {
            int semverSplit = normalized.lastIndexOf('-');
            if (semverSplit > 0) {
                String suffix = normalized.substring(semverSplit + 1);
                if (isNumericVersionSuffix(suffix)) {
                    normalized = normalized.substring(0, semverSplit);
                }
            }
        }

        return normalized.replaceAll("[^a-z0-9]", "");
    }

    private String normalizeExtensionId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return "";
        }

        return rawId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private ArtifactVersionToken parseArtifactVersion(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return ArtifactVersionToken.unknown();
        }

        String normalized = fileName.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".jar")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }

        int alphaSplit = normalized.lastIndexOf("-alpha-");
        if (alphaSplit > 0) {
            String buildRaw = normalized.substring(alphaSplit + "-alpha-".length());
            try {
                return ArtifactVersionToken.alpha(Integer.parseInt(buildRaw));
            } catch (NumberFormatException ignored) {
                return ArtifactVersionToken.unknown();
            }
        }

        int semverSplit = normalized.lastIndexOf('-');
        if (semverSplit > 0) {
            String suffix = normalized.substring(semverSplit + 1);
            if (isNumericVersionSuffix(suffix)) {
                List<Integer> parts = new ArrayList<>();
                for (String part : suffix.split("\\.")) {
                    if (part.isBlank()) {
                        continue;
                    }
                    try {
                        parts.add(Integer.parseInt(part));
                    } catch (NumberFormatException ignored) {
                        return ArtifactVersionToken.unknown();
                    }
                }
                if (!parts.isEmpty()) {
                    return ArtifactVersionToken.semver(parts);
                }
            }
        }

        return ArtifactVersionToken.unknown();
    }

    private boolean isNumericVersionSuffix(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                return false;
            }
        }
        return true;
    }

    private int compareArtifactVersions(ArtifactVersionToken left, ArtifactVersionToken right) {
        if (left.kind() != right.kind()) {
            return Integer.compare(left.kind().priority, right.kind().priority);
        }

        if (left.kind() == ArtifactVersionKind.ALPHA) {
            return Integer.compare(left.alphaBuild(), right.alphaBuild());
        }

        if (left.kind() == ArtifactVersionKind.SEMVER) {
            int max = Math.max(left.semverParts().size(), right.semverParts().size());
            for (int i = 0; i < max; i++) {
                int l = i < left.semverParts().size() ? left.semverParts().get(i) : 0;
                int r = i < right.semverParts().size() ? right.semverParts().get(i) : 0;
                if (l != r) {
                    return Integer.compare(l, r);
                }
            }
        }

        return 0;
    }

    private enum ArtifactVersionKind {
        UNKNOWN(0),
        ALPHA(1),
        SEMVER(2);

        private final int priority;

        ArtifactVersionKind(int priority) {
            this.priority = priority;
        }
    }

    private record ArtifactVersionToken(ArtifactVersionKind kind, int alphaBuild, List<Integer> semverParts) {
        private static ArtifactVersionToken unknown() {
            return new ArtifactVersionToken(ArtifactVersionKind.UNKNOWN, -1, List.of());
        }

        private static ArtifactVersionToken alpha(int build) {
            return new ArtifactVersionToken(ArtifactVersionKind.ALPHA, build, List.of());
        }

        private static ArtifactVersionToken semver(List<Integer> parts) {
            return new ArtifactVersionToken(ArtifactVersionKind.SEMVER, -1, parts == null ? List.of() : List.copyOf(parts));
        }
    }
}

