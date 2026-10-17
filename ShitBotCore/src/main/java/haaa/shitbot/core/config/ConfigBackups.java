package haaa.shitbot.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
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

    /** Runs before configuration loading, including when the JAR was replaced manually. */
    public void beforeVersion(String version) throws IOException {
        if (version == null || version.trim().isEmpty()) {
            return;
        }
        String currentVersion = version.trim();
        Path versionFile = backupDirectory.resolve("last-version.txt");
        String previousVersion = Files.notExists(versionFile) ? ""
                : new String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim();
        if (currentVersion.equals(previousVersion)) {
            return;
        }
        snapshotConfiguration("version-" + (previousVersion.isEmpty() ? "unknown" : previousVersion)
                + "-to-" + currentVersion);
        // Advance the marker only after every existing configuration file has been copied.
        Files.createDirectories(backupDirectory);
        Path temporary = Files.createTempFile(backupDirectory, ".last-version-", ".tmp");
        try {
            Files.write(temporary, (currentVersion + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, versionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, versionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The updater calls this after verification and before replacing the installed JAR. */
    public Path beforeUpdate(String currentVersion, String nextVersion) throws IOException {
        return snapshotConfiguration("update-" + currentVersion + "-to-" + nextVersion);
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

    private Path snapshotConfiguration(String reason) throws IOException {
        final List<Path> files = new ArrayList<Path>();
        for (String resource : new String[]{"config.yml", "commands.yml"}) {
            Path file = directory.resolve(resource);
            if (!Files.notExists(file)) {
                files.add(file);
            }
        }
        for (String resource : new String[]{"lang", "templates"}) {
            Path folder = directory.resolve(resource);
            if (Files.notExists(folder)) {
                continue;
            }
            // Follow administrator-managed links so their active configuration is also preserved.
            Files.walkFileTree(folder, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                                files.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }
        Collections.sort(files);
        return createSnapshot(files, reason);
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
