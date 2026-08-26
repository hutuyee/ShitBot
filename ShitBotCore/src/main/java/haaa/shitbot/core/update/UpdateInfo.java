package haaa.shitbot.core.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable metadata for the latest published GitHub release. */
public final class UpdateInfo {
    private final String latestVersion;
    private final String releaseUrl;
    private final String publishedAt;
    private final List<ReleaseAsset> assets;

    public UpdateInfo(String latestVersion, String releaseUrl, String publishedAt) {
        this(latestVersion, releaseUrl, publishedAt, null);
    }

    public UpdateInfo(String latestVersion,
                      String releaseUrl,
                      String publishedAt,
                      List<ReleaseAsset> assets) {
        this.latestVersion = text(latestVersion);
        this.releaseUrl = text(releaseUrl);
        this.publishedAt = text(publishedAt);
        List<ReleaseAsset> copied = new ArrayList<ReleaseAsset>();
        if (assets != null) {
            for (ReleaseAsset asset : assets) {
                if (asset != null && !asset.getName().isEmpty() && !asset.getDownloadUrl().isEmpty()) {
                    copied.add(asset);
                }
            }
        }
        this.assets = Collections.unmodifiableList(copied);
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

    public List<ReleaseAsset> getAssets() {
        return assets;
    }

    public ReleaseAsset findJarAsset(UpdatePlatform platform) {
        ReleaseAsset found = null;
        if (platform == null) {
            return null;
        }
        for (ReleaseAsset asset : assets) {
            if (!platform.matchesJarName(asset.getName())) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = asset;
        }
        return found;
    }

    public ReleaseAsset findAsset(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        for (ReleaseAsset asset : assets) {
            if (name.equals(asset.getName())) {
                return asset;
            }
        }
        return null;
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
                && publishedAt.equals(other.publishedAt)
                && sameAssets(other.assets);
    }

    private boolean sameAssets(List<ReleaseAsset> otherAssets) {
        if (otherAssets == null || assets.size() != otherAssets.size()) {
            return false;
        }
        for (int index = 0; index < assets.size(); index++) {
            if (!assets.get(index).isSameAsset(otherAssets.get(index))) {
                return false;
            }
        }
        return true;
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
