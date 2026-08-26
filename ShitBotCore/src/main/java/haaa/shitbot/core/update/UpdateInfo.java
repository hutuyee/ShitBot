package haaa.shitbot.core.update;

/** Immutable metadata for the latest published GitHub release. */
public final class UpdateInfo {
    private final String latestVersion;
    private final String releaseUrl;
    private final String publishedAt;

    public UpdateInfo(String latestVersion, String releaseUrl, String publishedAt) {
        this.latestVersion = text(latestVersion);
        this.releaseUrl = text(releaseUrl);
        this.publishedAt = text(publishedAt);
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getReleaseUrl() {
        return releaseUrl;
    }

    public String getPublishedAt() {
        return publishedAt;
    }

    /** The project intentionally treats any different release tag as an available update. */
    public boolean isUpdateAvailable(String currentVersion) {
        String current = normalizeVersion(currentVersion);
        String latest = normalizeVersion(latestVersion);
        return !current.isEmpty() && !latest.isEmpty() && !current.equalsIgnoreCase(latest);
    }

    public boolean isSameRelease(UpdateInfo other) {
        return other != null
                && latestVersion.equals(other.latestVersion)
                && releaseUrl.equals(other.releaseUrl)
                && publishedAt.equals(other.publishedAt);
    }

    private static String normalizeVersion(String version) {
        String normalized = text(version);
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
