package haaa.shitbotspigot.update;

import haaa.shitbotspigot.ShitBotSpigot;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes older copies only after a newer ShitBot instance has been loaded. */
public final class SpigotPluginCleanup {
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^[vV]?([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    private SpigotPluginCleanup() {
    }

    public static void deleteOldJars(ShitBotSpigot plugin) {
        Path directory = plugin.getDataFolder().toPath().toAbsolutePath().normalize().getParent();
        if (directory == null) {
            return;
        }
        PluginDescriptionFile current = plugin.getDescription();
        try {
            Path runningJar = plugin.getPluginJarPath().toRealPath();
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory)) {
                for (Path jar : jars) {
                    if (!jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                            || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    try {
                        if (Files.isSameFile(jar, runningJar)) {
                            continue;
                        }
                        // The loader closes the descriptor's JAR before returning, allowing deletion on Windows.
                        PluginDescriptionFile candidate = plugin.getPluginLoader().getPluginDescription(jar.toFile());
                        if (!current.getName().equals(candidate.getName())
                                || !current.getMain().equals(candidate.getMain())) {
                            continue;
                        }
                        Integer comparison = compareVersions(candidate.getVersion(), current.getVersion());
                        if (comparison == null) {
                            plugin.getLogger().warning("Cannot compare ShitBot version " + candidate.getVersion()
                                    + " with loaded version " + current.getVersion() + "; keeping " + jar);
                        } else if (comparison < 0) {
                            Files.delete(jar);
                            plugin.getLogger().info("Deleted old ShitBot JAR: " + jar);
                        } else if (comparison > 0) {
                            plugin.getLogger().warning("A newer ShitBot JAR exists at " + jar
                                    + " but the server loaded " + current.getVersion()
                                    + ". Stop the server and remove the older JAR before restarting.");
                        }
                    } catch (InvalidDescriptionException ignored) {
                        // An unrelated or unreadable plugin descriptor must never trigger deletion.
                    } catch (IOException | SecurityException exception) {
                        plugin.getLogger().warning("Unable to clean up old ShitBot JAR " + jar
                                + ": " + exception.getMessage());
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException exception) {
            plugin.getLogger().warning("Unable to scan old ShitBot JARs: " + exception.getMessage());
        }
    }

    /** Returns null for unknown version formats so cleanup never guesses which JAR is older. */
    private static Integer compareVersions(String first, String second) {
        Matcher left = VERSION_PATTERN.matcher(first.trim());
        Matcher right = VERSION_PATTERN.matcher(second.trim());
        if (!left.matches() || !right.matches()) {
            return null;
        }
        String[] leftNumbers = left.group(1).split("\\.");
        String[] rightNumbers = right.group(1).split("\\.");
        for (int index = 0; index < Math.max(leftNumbers.length, rightNumbers.length); index++) {
            BigInteger leftNumber = index < leftNumbers.length ? new BigInteger(leftNumbers[index]) : BigInteger.ZERO;
            BigInteger rightNumber = index < rightNumbers.length ? new BigInteger(rightNumbers[index]) : BigInteger.ZERO;
            int comparison = leftNumber.compareTo(rightNumber);
            if (comparison != 0) {
                return comparison;
            }
        }
        String leftQualifier = left.group(2);
        String rightQualifier = right.group(2);
        if (leftQualifier == null || rightQualifier == null) {
            return leftQualifier == rightQualifier ? 0 : leftQualifier == null ? 1 : -1;
        }
        String[] leftParts = leftQualifier.split("\\.");
        String[] rightParts = rightQualifier.split("\\.");
        for (int index = 0; index < Math.min(leftParts.length, rightParts.length); index++) {
            String leftPart = leftParts[index];
            String rightPart = rightParts[index];
            boolean leftNumeric = leftPart.matches("[0-9]+");
            boolean rightNumeric = rightPart.matches("[0-9]+");
            int comparison;
            if (leftNumeric && rightNumeric) {
                comparison = new BigInteger(leftPart).compareTo(new BigInteger(rightPart));
            } else if (leftNumeric != rightNumeric) {
                comparison = leftNumeric ? -1 : 1;
            } else {
                comparison = leftPart.compareTo(rightPart);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }
}
