package haaa.shitbot.core.update;

/** Download metadata for one GitHub Release asset. */
public final class ReleaseAsset {
    private final String name;
    private final String downloadUrl;
    private final long size;
    private final String digest;

    public ReleaseAsset(String name, String downloadUrl, long size, String digest) {
        this.name = text(name);
        this.downloadUrl = text(downloadUrl);
        this.size = Math.max(0L, size);
        this.digest = text(digest);
    }

    public String getName() {
        return name;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public long getSize() {
        return size;
    }

    public String getDigest() {
        return digest;
    }

    public boolean isSameAsset(ReleaseAsset other) {
        return other != null
                && name.equals(other.name)
                && downloadUrl.equals(other.downloadUrl)
                && size == other.size
                && digest.equals(other.digest);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
