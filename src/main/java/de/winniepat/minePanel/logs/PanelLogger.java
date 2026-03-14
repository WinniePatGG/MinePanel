package de.winniepat.minePanel.logs;

import de.winniepat.minePanel.persistence.LogRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.logging.Logger;

public final class PanelLogger {

    private static final DateTimeFormatter FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Logger logger;
    private final LogRepository logRepository;
    private final Path logDirectory;

    public PanelLogger(Logger logger, LogRepository logRepository, Path logDirectory) {
        this.logger = logger;
        this.logRepository = logRepository;
        this.logDirectory = logDirectory;

        try {
            Files.createDirectories(logDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create panel log directory", exception);
        }
    }

    public synchronized void log(String kind, String source, String message) {
        String safeMessage = message == null ? "" : message;
        logRepository.appendLog(kind, source, safeMessage);

        String line = Instant.now().toString() + " [" + kind + "] [" + source + "] " + safeMessage + System.lineSeparator();
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        Path targetFile = logDirectory.resolve("panel-" + FILE_NAME_FORMAT.format(date) + ".log");

        try {
            Files.writeString(targetFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            logger.warning("Could not write panel log file: " + exception.getMessage());
        }
    }

    public synchronized void clearLogFiles() {
        try (var files = Files.list(logDirectory)) {
            files.filter(Files::isRegularFile)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            logger.warning("Could not delete panel log file " + path.getFileName() + ": " + exception.getMessage());
                        }
                    });
        } catch (IOException exception) {
            logger.warning("Could not clean panel log directory: " + exception.getMessage());
        }
    }
}

