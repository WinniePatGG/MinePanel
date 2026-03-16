package de.winniepat.minePanel.extensions.worldbackups;

import de.winniepat.minePanel.MinePanel;
import org.bukkit.World;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class WorldBackupService {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);
    private static final Set<String> WORLD_KEYS = Set.of("overworld", "nether", "end");

    private final MinePanel plugin;
    private final Path backupsRoot;

    public WorldBackupService(MinePanel plugin, Path backupsRoot) {
        this.plugin = plugin;
        this.backupsRoot = backupsRoot;
    }

    public List<String> supportedWorldKeys() {
        return List.of("overworld", "nether", "end");
    }

    public BackupCreateResult createBackup(String worldKey, String requestedName) {
        String normalizedWorldKey = normalizeWorldKey(worldKey);
        if (normalizedWorldKey == null) {
            return BackupCreateResult.error("invalid_world");
        }

        String safeName = sanitizeBackupName(requestedName);
        if (safeName.isBlank()) {
            return BackupCreateResult.error("invalid_name");
        }

        WorldSnapshot snapshot = captureWorldSnapshot(normalizedWorldKey);
        if (snapshot == null) {
            return BackupCreateResult.error("world_not_found");
        }

        Path worldBackupDir = backupsRoot.resolve(normalizedWorldKey);
        String fileName = FILE_TIMESTAMP.format(Instant.now()) + "__" + safeName + ".zip";
        Path tempZip = null;
        try {
            Files.createDirectories(backupsRoot);
            Files.createDirectories(worldBackupDir);
            Path zipFile = resolveUniqueZipPath(worldBackupDir, fileName);
            tempZip = resolveUniqueTempPath(worldBackupDir);
            zipDirectory(snapshot.worldFolder(), tempZip);

            try {
                Files.move(tempZip, zipFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(tempZip, zipFile, StandardCopyOption.REPLACE_EXISTING);
            }

            long sizeBytes = Files.size(zipFile);
            long createdAt = Files.getLastModifiedTime(zipFile).toMillis();
            return BackupCreateResult.success(new WorldBackupEntry(zipFile.getFileName().toString(), safeName, createdAt, sizeBytes, normalizedWorldKey));
        } catch (Exception exception) {
            return BackupCreateResult.error("backup_failed", buildErrorDetails(exception));
        } finally {
            if (tempZip != null) {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            }
        }
    }

    private Path resolveUniqueZipPath(Path worldBackupDir, String baseFileName) throws IOException {
        Path initial = worldBackupDir.resolve(baseFileName).normalize();
        if (!Files.exists(initial)) {
            return initial;
        }

        String base = baseFileName;
        String suffix = ".zip";
        if (baseFileName.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            base = baseFileName.substring(0, baseFileName.length() - suffix.length());
        }

        for (int i = 2; i <= 9999; i++) {
            Path candidate = worldBackupDir.resolve(base + "-" + i + suffix).normalize();
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IOException("could_not_allocate_unique_backup_file_name");
    }

    private Path resolveUniqueTempPath(Path worldBackupDir) throws IOException {
        for (int i = 0; i < 10_000; i++) {
            String candidateName = "backup-" + UUID.randomUUID() + ".tmp";
            Path candidate = worldBackupDir.resolve(candidateName).normalize();
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IOException("could_not_allocate_unique_temp_file_name");
    }

    private String buildErrorDetails(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }

    public List<WorldBackupEntry> listBackups(String worldKey) {
        String normalizedWorldKey = normalizeWorldKey(worldKey);
        if (normalizedWorldKey == null) {
            return List.of();
        }

        Path worldBackupDir = backupsRoot.resolve(normalizedWorldKey);
        if (!Files.isDirectory(worldBackupDir)) {
            return List.of();
        }

        try (var files = Files.list(worldBackupDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .map(path -> toEntry(path, normalizedWorldKey))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(WorldBackupEntry::createdAt).reversed())
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    public BackupDeleteResult deleteBackup(String worldKey, String fileName) {
        String normalizedWorldKey = normalizeWorldKey(worldKey);
        if (normalizedWorldKey == null) {
            return BackupDeleteResult.error("invalid_world");
        }

        if (fileName == null || fileName.isBlank()) {
            return BackupDeleteResult.error("invalid_file_name");
        }

        String trimmedFileName = fileName.trim();
        if (!trimmedFileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return BackupDeleteResult.error("invalid_file_name");
        }
        if (trimmedFileName.contains("/") || trimmedFileName.contains("\\") || trimmedFileName.contains("..")) {
            return BackupDeleteResult.error("invalid_file_name");
        }

        Path worldBackupDir = backupsRoot.resolve(normalizedWorldKey).normalize();
        Path target = worldBackupDir.resolve(trimmedFileName).normalize();
        if (!target.startsWith(worldBackupDir)) {
            return BackupDeleteResult.error("invalid_file_name");
        }

        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            return BackupDeleteResult.error("backup_not_found");
        }

        try {
            Files.delete(target);
            return BackupDeleteResult.success(trimmedFileName, normalizedWorldKey);
        } catch (Exception exception) {
            return BackupDeleteResult.error("delete_failed", buildErrorDetails(exception));
        }
    }

    private WorldSnapshot captureWorldSnapshot(String worldKey) {
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                World world = resolveWorld(worldKey);
                if (world == null) {
                    return null;
                }
                world.save();
                return new WorldSnapshot(world.getName(), world.getWorldFolder().toPath());
            }).get(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException exception) {
            return null;
        }
    }

    private World resolveWorld(String worldKey) {
        String baseLevelName = plugin.getServer().getWorlds().isEmpty()
                ? "world"
                : plugin.getServer().getWorlds().get(0).getName();

        return switch (worldKey) {
            case "overworld" -> firstWorldByNames(List.of(baseLevelName, "world"), World.Environment.NORMAL);
            case "nether" -> firstWorldByNames(List.of(baseLevelName + "_nether", "world_nether"), World.Environment.NETHER);
            case "end" -> firstWorldByNames(List.of(baseLevelName + "_the_end", "world_the_end", baseLevelName + "_end"), World.Environment.THE_END);
            default -> null;
        };
    }

    private World firstWorldByNames(List<String> candidates, World.Environment fallbackEnvironment) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            World world = plugin.getServer().getWorld(candidate);
            if (world != null) {
                return world;
            }
        }

        return plugin.getServer().getWorlds().stream()
                .filter(world -> world.getEnvironment() == fallbackEnvironment)
                .findFirst()
                .orElse(null);
    }

    private WorldBackupEntry toEntry(Path file, String worldKey) {
        try {
            String fileName = file.getFileName().toString();
            String displayName = parseNameFromFileName(fileName);
            long createdAt = Files.getLastModifiedTime(file).toMillis();
            long sizeBytes = Files.size(file);
            return new WorldBackupEntry(fileName, displayName, createdAt, sizeBytes, worldKey);
        } catch (IOException exception) {
            return null;
        }
    }

    private String parseNameFromFileName(String fileName) {
        int separatorIndex = fileName.indexOf("__");
        if (separatorIndex < 0) {
            return fileName;
        }

        String withoutPrefix = fileName.substring(separatorIndex + 2);
        if (withoutPrefix.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return withoutPrefix.substring(0, withoutPrefix.length() - 4);
        }
        return withoutPrefix;
    }

    private void zipDirectory(Path sourceDirectory, Path zipFile) throws IOException {
        try (ZipOutputStream zipStream = new ZipOutputStream(Files.newOutputStream(zipFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            Files.walkFileTree(sourceDirectory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    Path relative = sourceDirectory.relativize(directory);
                    if (!relative.toString().isEmpty()) {
                        String entryName = normalizeEntryName(relative) + "/";
                        try {
                            zipStream.putNextEntry(new ZipEntry(entryName));
                            zipStream.closeEntry();
                        } catch (IOException ignored) {
                            // Ignore duplicate/invalid directory entries and continue.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }

                    Path relative = sourceDirectory.relativize(file);
                    if (shouldSkipFile(relative)) {
                        return FileVisitResult.CONTINUE;
                    }

                    if (!Files.isReadable(file)) {
                        return FileVisitResult.CONTINUE;
                    }

                    String entryName = normalizeEntryName(relative);
                    try {
                        zipStream.putNextEntry(new ZipEntry(entryName));
                        Files.copy(file, zipStream);
                        zipStream.closeEntry();
                    } catch (IOException ignored) {
                        // Skip files that are temporarily locked by the OS or server process.
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Continue if a file cannot be read due to locks/permissions.
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private boolean shouldSkipFile(Path relativePath) {
        String name = relativePath.getFileName() == null ? "" : relativePath.getFileName().toString().toLowerCase(Locale.ROOT);
        return "session.lock".equals(name);
    }

    private String normalizeEntryName(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }

    private String sanitizeBackupName(String rawName) {
        if (rawName == null) {
            return "";
        }

        StringBuilder sanitized = new StringBuilder();
        String trimmed = rawName.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                sanitized.append(c);
            } else if (c == ' ' || c == '.') {
                sanitized.append('_');
            }
        }

        String value = sanitized.toString().replaceAll("_+", "_");
        if (value.length() > 48) {
            return value.substring(0, 48);
        }
        return value;
    }

    private String normalizeWorldKey(String worldKey) {
        if (worldKey == null) {
            return null;
        }
        String normalized = worldKey.trim().toLowerCase(Locale.ROOT);
        return WORLD_KEYS.contains(normalized) ? normalized : null;
    }

    public record WorldBackupEntry(String fileName, String name, long createdAt, long sizeBytes, String world) {
    }

    public record BackupCreateResult(boolean success, String error, String details, WorldBackupEntry backup) {
        static BackupCreateResult success(WorldBackupEntry backup) {
            return new BackupCreateResult(true, "", "", backup);
        }

        static BackupCreateResult error(String error) {
            return new BackupCreateResult(false, error, "", null);
        }

        static BackupCreateResult error(String error, String details) {
            return new BackupCreateResult(false, error, details == null ? "" : details, null);
        }
    }

    public record BackupDeleteResult(boolean success, String error, String details, String fileName, String world) {
        static BackupDeleteResult success(String fileName, String world) {
            return new BackupDeleteResult(true, "", "", fileName, world);
        }

        static BackupDeleteResult error(String error) {
            return new BackupDeleteResult(false, error, "", "", "");
        }

        static BackupDeleteResult error(String error, String details) {
            return new BackupDeleteResult(false, error, details == null ? "" : details, "", "");
        }
    }

    private record WorldSnapshot(String worldName, Path worldFolder) {
    }
}

