package haaa.shitbot.api;

/** PNG output and immutable template metadata returned by the rendering API. */
public final class ImageRenderResult {
    private final byte[] bytes;
    private final int width;
    private final int height;
    private final String contentType;
    private final String suggestedFileName;
    private final String templateId;
    private final long templateVersion;

    public ImageRenderResult(byte[] bytes,
                             int width,
                             int height,
                             String contentType,
                             String suggestedFileName,
                             String templateId,
                             long templateVersion) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("rendered image cannot be empty");
        }
        this.bytes = bytes.clone();
        this.width = width;
        this.height = height;
        this.contentType = text(contentType, "image/png");
        this.suggestedFileName = text(suggestedFileName, "image.png");
        this.templateId = text(templateId, "unknown");
        this.templateVersion = Math.max(1L, templateVersion);
    }

    public byte[] getBytes() { return bytes.clone(); }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getContentType() { return contentType; }
    public String getSuggestedFileName() { return suggestedFileName; }
    public String getTemplateId() { return templateId; }
    public long getTemplateVersion() { return templateVersion; }

    private static String text(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }
}
