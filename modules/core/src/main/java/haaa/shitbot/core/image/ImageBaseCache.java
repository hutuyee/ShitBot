package haaa.shitbot.core.image;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Runtime-local immutable bases, bounded by both entry count and total pixel memory. */
public final class ImageBaseCache {
    private static final long MAX_PIXELS = 8L * 1024L * 1024L;
    private final Map<String, BufferedImage> bases = new LinkedHashMap<String, BufferedImage>(4, 0.75F, true);
    private long pixels;

    public synchronized void prepare(String key, int width, int height, Consumer<Graphics2D> painter) {
        base(key, width, height, painter);
    }

    /** Every caller draws on an independent image, never on the cached base. */
    public synchronized BufferedImage copy(String key, int width, int height, Consumer<Graphics2D> painter) {
        BufferedImage base = base(key, width, height, painter);
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(base, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    public synchronized void clear() {
        bases.clear();
        pixels = 0L;
    }

    private BufferedImage base(String key, int width, int height, Consumer<Graphics2D> painter) {
        String cacheKey = width + "x" + height + ":" + key;
        BufferedImage cached = bases.get(cacheKey);
        if (cached != null) return cached;
        BufferedImage created = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = created.createGraphics();
        try {
            painter.accept(graphics);
        } finally {
            graphics.dispose();
        }
        long size = (long) width * height;
        if (size <= MAX_PIXELS) {
            Iterator<BufferedImage> oldest = bases.values().iterator();
            while (oldest.hasNext() && (bases.size() >= 4 || pixels + size > MAX_PIXELS)) {
                BufferedImage removed = oldest.next();
                pixels -= (long) removed.getWidth() * removed.getHeight();
                oldest.remove();
            }
            bases.put(cacheKey, created);
            pixels += size;
        }
        return created;
    }
}
