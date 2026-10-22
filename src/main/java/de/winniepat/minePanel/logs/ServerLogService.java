package de.winniepat.minePanel.logs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public final class ServerLogService {

    private final Path logDirectory;
    private final Path latestLogFile;

    public ServerLogService(Path pluginDataFolder) {
        Path pluginsFolder = pluginDataFolder.getParent();
        Path serverRoot = pluginsFolder == null ? pluginDataFolder : pluginsFolder.getParent();
        if (serverRoot == null) {
            serverRoot = pluginDataFolder;
        }
        this.logDirectory = serverRoot.resolve("logs");
        this.latestLogFile = logDirectory.resolve("latest.log");
    }

    public List<String> readLatestLines(int lines) {
        return readLogLines("latest.log", lines);
    }

    public List<String> readLogLines(String logFileName, int lines) {
        Path targetFile = resolveLogFile(logFileName);
        if (targetFile == null || !Files.exists(targetFile)) {
            return Collections.emptyList();
        }

        int safeLines = Math.max(1, Math.min(lines, 2000));
        Deque<String> queue = new ArrayDeque<>(safeLines);

        try {
            for (String line : Files.readAllLines(targetFile, StandardCharsets.UTF_8)) {
                if (queue.size() == safeLines) {
                    queue.removeFirst();
                }
                queue.addLast(line);
            }
        } catch (IOException exception) {
            return List.of("Unable to read " + targetFile.getFileName() + ": " + exception.getMessage());
        }

        return new ArrayList<>(queue);
    }

    public List<String> listLogFiles() {
        if (!Files.exists(logDirectory) || !Files.isDirectory(logDirectory)) {
            return Collections.emptyList();
        }

        try (Stream<Path> files = Files.list(logDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(this::lastModifiedSafe).reversed())
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException exception) {
            return Collections.emptyList();
        }
    }

    private Path resolveLogFile(String logFileName) {
        String selected = (logFileName == null || logFileName.isBlank()) ? "latest.log" : logFileName;
        if (selected.contains("/") || selected.contains("\\")) {
            return null;
        }

        Path target = logDirectory.resolve(selected).normalize();
        if (!target.startsWith(logDirectory)) {
            return null;
        }
        return target;
    }

    private long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }
}

