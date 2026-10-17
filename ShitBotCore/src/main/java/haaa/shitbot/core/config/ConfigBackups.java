package haaa.shitbot.core.config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Preserves original configuration bytes before automatic changes. */
public final class ConfigBackups {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String PENDING_PREFIX = ".pending-";

    private final Path directory;
    private final Path backupDirectory;
    private final Consumer<String> logger;

    public ConfigBackups(Path directory, Consumer<String> logger) {
        this.directory = directory.toAbsolutePath().normalize();
        this.backupDirectory = this.directory.resolve("config-backups");
        this.logger = logger;
    }

    /** Missing files have no previous contents to preserve. Backup failures stop the caller. */
    public Path beforeWrite(Path configuredFile) throws IOException {
        Path file = configuredFile.toAbsolutePath().normalize();
        if (!file.startsWith(directory) || file.equals(directory)) {
            throw new IOException("Configuration path escapes the data directory: " + file);
        }
        if (Files.notExists(file)) {
            return null;
        }
        return createSnapshot(Collections.singletonList(file), "before-write-" + file.getFileName());
    }

    private Path createSnapshot(List<Path> files, String reason) throws IOException {
        if (files.isEmpty()) {
            return null;
        }
        Files.createDirectories(backupDirectory);
        String prefix = TIMESTAMP.format(LocalDateTime.now()) + "-" + safeName(reason) + "-";
        Path pending = Files.createTempDirectory(backupDirectory, PENDING_PREFIX + prefix);
        // An interrupted/failed copy remains visibly pending and can never replace a completed backup.
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                throw new IOException("Unable to back up configuration file: " + file);
            }
            Path destination = pending.resolve(directory.relativize(file));
            Files.createDirectories(destination.getParent());
            Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path backup = pending.resolveSibling(pending.getFileName().toString().substring(PENDING_PREFIX.length()));
        try {
            Files.move(pending, backup, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(pending, backup);
        }
        logger.accept("Configuration backup created: " + backup);
        return backup;
    }

    private String safeName(String value) {
        String name = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.length() > 100 ? name.substring(0, 100) : name;
    }
}
