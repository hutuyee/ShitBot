package haaa.shitbot.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Objects;

/** Immutable PNG output shared by native images and published templates. */
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

    /** Reads dimensions from the PNG header without decoding or allocating a pixel canvas. */
    public static ImageRenderResult fromPng(byte[] bytes, String suggestedFileName,
                                            String templateId, long templateVersion) {
        if (bytes == null || bytes.length < 33) {
            throw new IllegalArgumentException("PNG header is missing");
        }
        ByteBuffer header = ByteBuffer.wrap(bytes);
        if (header.getLong() != 0x89504e470d0a1a0aL
                || header.getInt() != 13 || header.getInt() != 0x49484452) {
            throw new IllegalArgumentException("Invalid PNG header");
        }
        int width = header.getInt();
        int height = header.getInt();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("PNG dimensions must be positive");
        }
        return new ImageRenderResult(bytes, width, height, "image/png", suggestedFileName,
                templateId, templateVersion);
    }

    /** Returns a defensive copy. Prefer openStream/writeTo for streaming integrations. */
    public byte[] getBytes() { return bytes.clone(); }
    public int getByteSize() { return bytes.length; }

    /** Independent, read-only stream; opening it does not copy the PNG. */
    public InputStream openStream() {
        final ByteBuffer data = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
        return new InputStream() {
            @Override public int read() {
                return data.hasRemaining() ? data.get() & 0xff : -1;
            }

            @Override public int read(byte[] target, int offset, int length) {
                Objects.requireNonNull(target, "target");
                if (offset < 0 || length < 0 || offset > target.length - length) {
                    throw new IndexOutOfBoundsException();
                }
                if (length == 0) return 0;
                if (!data.hasRemaining()) return -1;
                int count = Math.min(length, data.remaining());
                data.get(target, offset, count);
                return count;
            }

            @Override public long skip(long count) {
                int skipped = (int) Math.min(Math.max(0L, count), data.remaining());
                data.position(data.position() + skipped);
                return skipped;
            }

            @Override public int available() { return data.remaining(); }
        };
    }

    /** Synchronous streaming write with a small buffer; does not close or flush the output. */
    public void writeTo(OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        InputStream input = openStream();
        byte[] buffer = new byte[Math.min(8192, bytes.length)];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    /** Standard padded Base64 without a prefix or line breaks. Encoded on demand, never retained. */
    public String getBase64() { return Base64.getEncoder().encodeToString(bytes); }

    /** Browser-compatible data URI; this is inline data, not a hosted HTTP URL. */
    public String getDataUri() { return "data:" + contentType + ";base64," + getBase64(); }

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
