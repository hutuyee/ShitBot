package haaa.shitbot.core.update;

import java.nio.file.Path;

/** Outcome of installing a Release asset on disk. */
public final class UpdateInstallResult {
    public enum Status {
        UP_TO_DATE,
        ALREADY_INSTALLED,
        INSTALLED
    }

    private final Status status;
    private final String currentVersion;
    private final String latestVersion;
    private final String assetName;
    private final String sha256;
    private final Path installedPath;
    private final Path backupPath;

    private UpdateInstallResult(Status status,
                                String currentVersion,
                                String latestVersion,
                                String assetName,
                                String sha256,
                                Path installedPath,
                                Path backupPath) {
        this.status = status;
        this.currentVersion = text(currentVersion);
        this.latestVersion = text(latestVersion);
        this.assetName = text(assetName);
        this.sha256 = text(sha256);
        this.installedPath = installedPath;
        this.backupPath = backupPath;
    }

    public static UpdateInstallResult upToDate(String currentVersion, String latestVersion, Path path) {
        return new UpdateInstallResult(Status.UP_TO_DATE, currentVersion, latestVersion,
                "", "", path, null);
    }

    public static UpdateInstallResult alreadyInstalled(String currentVersion,
                                                       String latestVersion,
                                                       Path path) {
        return new UpdateInstallResult(Status.ALREADY_INSTALLED, currentVersion, latestVersion,
                "", "", path, null);
    }

    public static UpdateInstallResult installed(String currentVersion,
                                                String latestVersion,
                                                String assetName,
                                                String sha256,
                                                Path installedPath,
                                                Path backupPath) {
        return new UpdateInstallResult(Status.INSTALLED, currentVersion, latestVersion,
                assetName, sha256, installedPath, backupPath);
    }

    public Status getStatus() {
        return status;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getAssetName() {
        return assetName;
    }

    public String getSha256() {
        return sha256;
    }

    public Path getInstalledPath() {
        return installedPath;
    }

    public Path getBackupPath() {
        return backupPath;
    }

    public boolean isRestartRequired() {
        return status == Status.INSTALLED || status == Status.ALREADY_INSTALLED;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
