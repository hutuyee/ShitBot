package haaa.shitbot.core.update;

import java.util.Arrays;

/** Release metadata sent over the authenticated proxy-to-backend console socket. */
public final class BackendUpdatePayload {
    private final String latestVersion;
    private final String releaseUrl;
    private final ReleaseAsset jarAsset;
    private final ReleaseAsset checksumAsset;

    public BackendUpdatePayload(String latestVersion,
                                String releaseUrl,
                                ReleaseAsset jarAsset,
                                ReleaseAsset checksumAsset) {
        this.latestVersion = text(latestVersion);
        this.releaseUrl = text(releaseUrl);
        this.jarAsset = jarAsset;
        this.checksumAsset = checksumAsset;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getReleaseUrl() {
        return releaseUrl;
    }

    public ReleaseAsset getJarAsset() {
        return jarAsset;
    }

    public ReleaseAsset getChecksumAsset() {
        return checksumAsset;
    }

    public UpdateInfo toUpdateInfo() {
        return new UpdateInfo(latestVersion, releaseUrl, "",
                Arrays.asList(jarAsset, checksumAsset));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
