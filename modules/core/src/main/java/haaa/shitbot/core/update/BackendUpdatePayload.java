package haaa.shitbot.core.update;

import java.util.Arrays;

/** Release metadata sent over the authenticated proxy-to-backend console socket. */
public final class BackendUpdatePayload {
    private final String latestVersion;
    private final String releaseUrl;
    private final ReleaseAsset jarAsset;
    private final ReleaseAsset checksumAsset;
    private final ReleaseAsset signatureAsset;

    public BackendUpdatePayload(String latestVersion,
                                String releaseUrl,
                                ReleaseAsset jarAsset,
                                ReleaseAsset checksumAsset,
                                ReleaseAsset signatureAsset) {
        this.latestVersion = text(latestVersion);
        this.releaseUrl = text(releaseUrl);
        this.jarAsset = jarAsset;
        this.checksumAsset = checksumAsset;
        this.signatureAsset = signatureAsset;
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

    public ReleaseAsset getSignatureAsset() {
        return signatureAsset;
    }

    public UpdateInfo toUpdateInfo() {
        return new UpdateInfo(latestVersion, releaseUrl, "",
                Arrays.asList(jarAsset, checksumAsset, signatureAsset));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
