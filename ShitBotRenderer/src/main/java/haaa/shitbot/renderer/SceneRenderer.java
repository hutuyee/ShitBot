package haaa.shitbot.renderer;

import haaa.shitbot.api.ImageRenderRequest;
import haaa.shitbot.api.ImageRenderResult;
import haaa.shitbot.api.spi.ImageTemplateEngineSettings;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SceneRenderer {
    private final ImageTemplateEngineSettings settings;
    private final SecureImageLoader images;

    SceneRenderer(ImageTemplateEngineSettings settings, SecureImageLoader images) {
        this.settings = settings;
        this.images = images;
    }

    @SuppressWarnings("unchecked")
    ImageRenderResult render(TemplateSnapshot snapshot,
                             ImageRenderRequest request,
                             long deadlineNanos) throws IOException {
        Map<String, Object> canvas = map(snapshot.getScene().get("canvas"));
        int width = integer(canvas.get("width"), 0);
        int height = integer(canvas.get("height"), 0);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            paintCanvas(graphics, canvas, width, height);
            PaintState state = new PaintState(graphics, snapshot, new BindingResolver(request),
                    new Counter(settings.getMaximumLayers()), deadlineNanos);
            paintNodes(list(snapshot.getScene().get("layers")), state, 0.0D, 0.0D);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(32768, width * height / 4));
        if (!ImageIO.write(image, "png", output)) throw new IOException("No PNG writer is available");
        return new ImageRenderResult(output.toByteArray(), width, height, "image/png",
                snapshot.getSuggestedFileName(), snapshot.getInfo().getId(),
                snapshot.getInfo().getVersion());
    }

    private void paintCanvas(Graphics2D graphics, Map<String, Object> canvas, int width, int height) {
        Color start = color(canvas.get("gradient-start"), null);
        Color end = color(canvas.get("gradient-end"), null);
        if (start != null && end != null) {
            graphics.setPaint(new GradientPaint(0, 0, start, width, height, end));
            graphics.fillRect(0, 0, width, height);
        } else {
            graphics.setColor(color(canvas.get("background"), new Color(0, 0, 0, 0)));
            graphics.fillRect(0, 0, width, height);
        }
    }

    private void paintNodes(List<?> nodes, PaintState state, double originX, double originY) throws IOException {
        for (Object value : nodes) {
            check(state);
            paintNode(map(value), state, originX, originY);
        }
    }

    private void paintNode(Map<String, Object> node,
                           PaintState state,
                           double originX,
                           double originY) throws IOException {
        state.counter.hit();
        BindingResolver bindings = state.bindings;
        if (node.containsKey("visible") && !bindings.bool(node.get("visible"), true)) return;
        if (node.containsKey("when") && !bindings.truthy(node.get("when"))) return;

        String type = bindings.text(node.get("type"), "").toLowerCase(Locale.ROOT);
        if ("condition".equals(type)) {
            paintCondition(node, state, originX, originY);
            return;
        }
        if ("loop".equals(type)) {
            paintLoop(node, state, originX, originY);
            return;
        }

        double x = originX + bindings.number(node.get("x"), 0.0D);
        double y = originY + bindings.number(node.get("y"), 0.0D);
        float opacity = (float) clamp(bindings.number(node.get("opacity"), 1.0D), 0.0D, 1.0D);
        java.awt.Composite oldComposite = state.graphics.getComposite();
        Shape oldClip = state.graphics.getClip();
        if (opacity < 1.0F) state.graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
        try {
            if ("rectangle".equals(type)) paintRectangle(node, state, x, y);
            else if ("circle".equals(type)) paintCircle(node, state, x, y);
            else if ("line".equals(type)) paintLine(node, state, x, y);
            else if ("text".equals(type)) paintText(node, state, x, y);
            else if ("image".equals(type) || "avatar".equals(type)) {
                paintImage(node, state, x, y, "avatar".equals(type));
            } else if ("progress".equals(type)) paintProgress(node, state, x, y);
            else if ("group".equals(type)) paintGroup(node, state, x, y);
            else if ("stack".equals(type)) paintStack(node, state, x, y);
            else if ("grid".equals(type)) paintGrid(node, state, x, y);
        } finally {
            state.graphics.setComposite(oldComposite);
            state.graphics.setClip(oldClip);
        }
    }

    private void paintCondition(Map<String, Object> node,
                                PaintState state,
                                double originX,
                                double originY) throws IOException {
        Object actual = state.bindings.value(node.get("condition"));
        boolean matches;
        if (node.containsKey("equals")) {
            Object expected = state.bindings.value(node.get("equals"));
            matches = actual == null ? expected == null : String.valueOf(actual).equals(String.valueOf(expected));
        } else {
            matches = state.bindings.truthy(node.get("condition"));
        }
        paintNodes(list(node.get(matches ? "then" : "else")), state, originX, originY);
    }

    private void paintLoop(Map<String, Object> node,
                           PaintState state,
                           double originX,
                           double originY) throws IOException {
        List<Object> items = iterable(state.bindings.value(node.get("items")));
        int requested = (int) state.bindings.number(node.get("maximum-items"), settings.getMaximumLoopItems());
        int maximum = Math.min(settings.getMaximumLoopItems(), Math.max(0, requested));
        String name = state.bindings.text(node.get("as"), "item");
        double offsetX = state.bindings.number(node.get("item-offset-x"), 0.0D);
        double offsetY = state.bindings.number(node.get("item-offset-y"), 0.0D);
        int count = Math.min(items.size(), maximum);
        for (int index = 0; index < count; index++) {
            PaintState nested = state.withBindings(state.bindings.with(name, items.get(index))
                    .with("index", Integer.valueOf(index))
                    .with("first", Boolean.valueOf(index == 0))
                    .with("last", Boolean.valueOf(index == count - 1)));
            paintNodes(list(node.get("children")), nested,
                    originX + offsetX * index, originY + offsetY * index);
        }
    }

    private void paintRectangle(Map<String, Object> node, PaintState state, double x, double y) {
        double width = state.bindings.number(node.get("width"), 0.0D);
        double height = state.bindings.number(node.get("height"), 0.0D);
        double radius = Math.max(0.0D, state.bindings.number(node.get("radius"), 0.0D));
        Shape shape = radius <= 0.0D
                ? new java.awt.geom.Rectangle2D.Double(x, y, width, height)
                : new RoundRectangle2D.Double(x, y, width, height, radius * 2.0D, radius * 2.0D);
        Color fill = color(state.bindings.value(first(node, "fill", "color")), null);
        if (fill != null) {
            state.graphics.setColor(fill);
            state.graphics.fill(shape);
        }
        stroke(node, state, shape);
    }

    private void paintCircle(Map<String, Object> node, PaintState state, double x, double y) {
        double width = state.bindings.number(node.get("width"),
                state.bindings.number(node.get("diameter"), 0.0D));
        double height = state.bindings.number(node.get("height"), width);
        Shape shape = new Ellipse2D.Double(x, y, width, height);
        Color fill = color(state.bindings.value(first(node, "fill", "color")), null);
        if (fill != null) {
            state.graphics.setColor(fill);
            state.graphics.fill(shape);
        }
        stroke(node, state, shape);
    }

    private void paintLine(Map<String, Object> node, PaintState state, double x, double y) {
        double localX = state.bindings.number(node.get("x"), 0.0D);
        double localY = state.bindings.number(node.get("y"), 0.0D);
        double x2 = node.containsKey("x2")
                ? x - localX + state.bindings.number(node.get("x2"), localX)
                : x + state.bindings.number(node.get("width"), 0.0D);
        double y2 = node.containsKey("y2")
                ? y - localY + state.bindings.number(node.get("y2"), localY)
                : y + state.bindings.number(node.get("height"), 0.0D);
        state.graphics.setColor(color(state.bindings.value(first(node, "color", "stroke-color")), Color.WHITE));
        state.graphics.setStroke(new BasicStroke((float) Math.max(0.1D,
                state.bindings.number(first(node, "stroke-width", "width-px"), 1.0D)),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        state.graphics.draw(new Line2D.Double(x, y, x2, y2));
    }

    private void paintText(Map<String, Object> node, PaintState state, double x, double y) {
        String text = state.bindings.text(node.get("text"), "");
        int size = (int) clamp(state.bindings.number(node.get("font-size"), 18.0D), 6.0D, 512.0D);
        int fontStyle = Font.PLAIN;
        String style = state.bindings.text(node.get("font-style"), "plain").toLowerCase(Locale.ROOT);
        if (style.contains("bold")) fontStyle |= Font.BOLD;
        if (style.contains("italic")) fontStyle |= Font.ITALIC;
        Font font = new Font(state.bindings.text(node.get("font-family"), "SansSerif"), fontStyle, size);
        state.graphics.setFont(font);
        state.graphics.setColor(color(state.bindings.value(node.get("color")), Color.WHITE));
        FontMetrics metrics = state.graphics.getFontMetrics();
        double width = state.bindings.number(node.get("width"), 0.0D);
        int maxLines = (int) clamp(state.bindings.number(node.get("maximum-lines"), 100.0D), 1.0D, 100.0D);
        List<String> lines = width <= 0.0D ? splitLines(text) : wrap(text, metrics, (int) width, maxLines);
        double lineHeight = state.bindings.number(node.get("line-height"), metrics.getHeight());
        String align = state.bindings.text(node.get("align"), "left").toLowerCase(Locale.ROOT);
        double baseline = y + metrics.getAscent();
        for (int index = 0; index < lines.size() && index < maxLines; index++) {
            String line = lines.get(index);
            double drawX = x;
            if (width > 0.0D && "center".equals(align)) drawX += (width - metrics.stringWidth(line)) / 2.0D;
            else if (width > 0.0D && "right".equals(align)) drawX += width - metrics.stringWidth(line);
            state.graphics.drawString(line, (float) drawX, (float) (baseline + index * lineHeight));
        }
    }

    private void paintImage(Map<String, Object> node,
                            PaintState state,
                            double x,
                            double y,
                            boolean avatar) throws IOException {
        Object source = state.bindings.value(first(node, "source", avatar ? "avatar" : "source"));
        if (avatar && !node.containsKey("source") && !node.containsKey("avatar")) {
            source = playerAvatarSource(node, state.bindings);
        }
        BufferedImage image = images.load(state.snapshot, source);
        if (image == null) return;
        int width = (int) clamp(state.bindings.number(node.get("width"), image.getWidth()),
                1.0D, settings.getMaximumWidth());
        int height = (int) clamp(state.bindings.number(node.get("height"), image.getHeight()),
                1.0D, settings.getMaximumHeight());
        String fit = state.bindings.text(node.get("fit"), "cover").toLowerCase(Locale.ROOT);
        String shape = state.bindings.text(node.get("shape"), avatar ? "circle" : "square");
        Shape clip;
        if ("circle".equalsIgnoreCase(shape)) {
            clip = new Ellipse2D.Double(x, y, width, height);
        } else {
            double radius = Math.max(0.0D, state.bindings.number(node.get("radius"), 0.0D));
            clip = radius <= 0.0D ? new java.awt.geom.Rectangle2D.Double(x, y, width, height)
                    : new RoundRectangle2D.Double(x, y, width, height, radius * 2.0D, radius * 2.0D);
        }
        Graphics2D imageGraphics = (Graphics2D) state.graphics.create();
        try {
            imageGraphics.clip(clip);
            if (state.bindings.bool(node.get("pixelated"), avatar)) {
                imageGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            }
            drawFitted(imageGraphics, image, x, y, width, height, fit);
        } finally {
            imageGraphics.dispose();
        }
        stroke(node, state, clip);
    }

    private Object playerAvatarSource(Map<String, Object> node, BindingResolver bindings) throws IOException {
        String player = bindings.text(node.get("player"), "").trim();
        if (player.isEmpty()) return null;
        String template = bindings.text("${data.player-avatar.url-template}", "").trim();
        if (template.isEmpty()) {
            throw new IOException("Player avatar layers require the player-avatar provider "
                    + "with template-only: true in manifest.yml");
        }
        return template.replace("%player%", URLEncoder.encode(player, StandardCharsets.UTF_8.name()));
    }

    private void paintProgress(Map<String, Object> node, PaintState state, double x, double y) {
        double width = state.bindings.number(node.get("width"), 200.0D);
        double height = state.bindings.number(node.get("height"), 18.0D);
        double radius = state.bindings.number(node.get("radius"), height / 2.0D);
        double value = state.bindings.number(node.get("value"), 0.0D);
        double maximum = state.bindings.number(node.get("maximum"), 100.0D);
        double ratio = maximum <= 0.0D ? 0.0D : clamp(value / maximum, 0.0D, 1.0D);
        Shape background = new RoundRectangle2D.Double(x, y, width, height, radius * 2.0D, radius * 2.0D);
        state.graphics.setColor(color(state.bindings.value(node.get("background")), new Color(255, 255, 255, 48)));
        state.graphics.fill(background);
        if (ratio > 0.0D) {
            Shape fill = new RoundRectangle2D.Double(x, y, width * ratio, height,
                    radius * 2.0D, radius * 2.0D);
            state.graphics.setColor(color(state.bindings.value(node.get("fill")), new Color(78, 203, 140)));
            state.graphics.fill(fill);
        }
        stroke(node, state, background);
    }

    private void paintGroup(Map<String, Object> node, PaintState state, double x, double y) throws IOException {
        if (state.bindings.bool(node.get("clip"), false)) {
            double width = state.bindings.number(node.get("width"), 0.0D);
            double height = state.bindings.number(node.get("height"), 0.0D);
            double radius = state.bindings.number(node.get("radius"), 0.0D);
            state.graphics.clip(radius <= 0.0D
                    ? new java.awt.geom.Rectangle2D.Double(x, y, width, height)
                    : new RoundRectangle2D.Double(x, y, width, height, radius * 2.0D, radius * 2.0D));
        }
        paintNodes(list(node.get("children")), state, x, y);
    }

    private void paintStack(Map<String, Object> node, PaintState state, double x, double y) throws IOException {
        List<BoundNode> children = expandLayoutChildren(list(node.get("children")), state);
        boolean horizontal = "horizontal".equalsIgnoreCase(state.bindings.text(node.get("direction"), "vertical"));
        double gap = state.bindings.number(node.get("gap"), 0.0D);
        double cursor = 0.0D;
        for (BoundNode child : children) {
            PaintState nested = state.withBindings(child.bindings);
            paintNode(child.node, nested, x + (horizontal ? cursor : 0.0D), y + (horizontal ? 0.0D : cursor));
            cursor += dimension(child.node, child.bindings, horizontal ? "width" : "height") + gap;
        }
    }

    private void paintGrid(Map<String, Object> node, PaintState state, double x, double y) throws IOException {
        List<BoundNode> children = expandLayoutChildren(list(node.get("children")), state);
        int columns = (int) Math.max(1.0D, state.bindings.number(node.get("columns"), 1.0D));
        double columnGap = state.bindings.number(node.get("column-gap"), 0.0D);
        double rowGap = state.bindings.number(node.get("row-gap"), 0.0D);
        double cellWidth = state.bindings.number(node.get("cell-width"), 0.0D);
        double cellHeight = state.bindings.number(node.get("cell-height"), 0.0D);
        for (int index = 0; index < children.size(); index++) {
            BoundNode child = children.get(index);
            double width = cellWidth > 0.0D ? cellWidth : dimension(child.node, child.bindings, "width");
            double height = cellHeight > 0.0D ? cellHeight : dimension(child.node, child.bindings, "height");
            int column = index % columns;
            int row = index / columns;
            paintNode(child.node, state.withBindings(child.bindings),
                    x + column * (width + columnGap), y + row * (height + rowGap));
        }
    }

    private List<BoundNode> expandLayoutChildren(List<?> nodes, PaintState state) throws IOException {
        List<BoundNode> result = new ArrayList<BoundNode>();
        for (Object value : nodes) {
            Map<String, Object> node = map(value);
            String type = state.bindings.text(node.get("type"), "").toLowerCase(Locale.ROOT);
            if ("condition".equals(type)) {
                Object actual = state.bindings.value(node.get("condition"));
                boolean match = node.containsKey("equals")
                        ? String.valueOf(actual).equals(String.valueOf(state.bindings.value(node.get("equals"))))
                        : state.bindings.truthy(node.get("condition"));
                result.addAll(expandLayoutChildren(list(node.get(match ? "then" : "else")), state));
            } else if ("loop".equals(type)) {
                List<Object> items = iterable(state.bindings.value(node.get("items")));
                int maximum = Math.min(settings.getMaximumLoopItems(), Math.max(0,
                        (int) state.bindings.number(node.get("maximum-items"), settings.getMaximumLoopItems())));
                String name = state.bindings.text(node.get("as"), "item");
                int count = Math.min(items.size(), maximum);
                for (int index = 0; index < count; index++) {
                    PaintState nested = state.withBindings(state.bindings.with(name, items.get(index))
                            .with("index", Integer.valueOf(index))
                            .with("first", Boolean.valueOf(index == 0))
                            .with("last", Boolean.valueOf(index == count - 1)));
                    result.addAll(expandLayoutChildren(list(node.get("children")), nested));
                }
            } else {
                result.add(new BoundNode(node, state.bindings));
            }
        }
        return result;
    }

    private double dimension(Map<String, Object> node, BindingResolver bindings, String axis) {
        double explicit = bindings.number(node.get(axis), 0.0D);
        if (explicit > 0.0D) return explicit;
        if ("text".equals(String.valueOf(node.get("type")))) {
            return "height".equals(axis) ? bindings.number(node.get("line-height"),
                    bindings.number(node.get("font-size"), 18.0D) * 1.3D) : 0.0D;
        }
        return 0.0D;
    }

    private void drawFitted(Graphics2D graphics,
                            BufferedImage image,
                            double x,
                            double y,
                            int width,
                            int height,
                            String fit) {
        if ("stretch".equals(fit)) {
            graphics.drawImage(image, (int) x, (int) y, width, height, null);
            return;
        }
        double scale = "contain".equals(fit)
                ? Math.min(width / (double) image.getWidth(), height / (double) image.getHeight())
                : Math.max(width / (double) image.getWidth(), height / (double) image.getHeight());
        int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int drawX = (int) Math.round(x + (width - drawWidth) / 2.0D);
        int drawY = (int) Math.round(y + (height - drawHeight) / 2.0D);
        graphics.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
    }

    private void stroke(Map<String, Object> node, PaintState state, Shape shape) {
        Color stroke = color(state.bindings.value(node.get("stroke-color")), null);
        double width = state.bindings.number(node.get("stroke-width"), 0.0D);
        if (stroke != null && width > 0.0D) {
            state.graphics.setColor(stroke);
            state.graphics.setStroke(new BasicStroke((float) width));
            state.graphics.draw(shape);
        }
    }

    private List<String> wrap(String text, FontMetrics metrics, int maximumWidth, int maximumLines) {
        List<String> lines = new ArrayList<String>();
        for (String paragraph : splitLines(text)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (int offset = 0; offset < paragraph.length();) {
                int codePoint = paragraph.codePointAt(offset);
                String part = new String(Character.toChars(codePoint));
                if (current.length() > 0 && metrics.stringWidth(current.toString() + part) > maximumWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                    if (lines.size() >= maximumLines) return lines;
                }
                current.append(part);
                offset += Character.charCount(codePoint);
            }
            if (current.length() > 0) lines.add(current.toString());
            if (lines.size() >= maximumLines) return lines;
        }
        return lines;
    }

    private List<String> splitLines(String text) {
        String[] values = (text == null ? "" : text).replace("\r", "").split("\n", -1);
        List<String> lines = new ArrayList<String>();
        Collections.addAll(lines, values);
        return lines;
    }

    private void check(PaintState state) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Image render was cancelled");
        if (System.nanoTime() > state.deadlineNanos) throw new IOException("Image render timed out");
    }

    private void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) throws IOException {
        if (!(value instanceof Map<?, ?>)) throw new IOException("Image layer must be a mapping");
        return (Map<String, Object>) value;
    }

    private List<?> list(Object value) {
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }

    private List<Object> iterable(Object value) {
        List<Object> result = new ArrayList<Object>();
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) result.add(item);
        } else if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) result.add(Array.get(value, index));
        } else if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<String, Object>();
                item.put("key", entry.getKey());
                item.put("value", entry.getValue());
                result.add(item);
            }
        }
        return result;
    }

    private Object first(Map<String, Object> values, String first, String second) {
        return values.containsKey(first) ? values.get(first) : values.get(second);
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value == null ? "" : String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Color color(Object value, Color fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        if (text.startsWith("#")) text = text.substring(1);
        try {
            if (text.length() == 6) return new Color(Integer.parseInt(text, 16));
            if (text.length() == 8) return new Color((int) Long.parseLong(text, 16), true);
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class PaintState {
        private final Graphics2D graphics;
        private final TemplateSnapshot snapshot;
        private final BindingResolver bindings;
        private final Counter counter;
        private final long deadlineNanos;

        private PaintState(Graphics2D graphics,
                           TemplateSnapshot snapshot,
                           BindingResolver bindings,
                           Counter counter,
                           long deadlineNanos) {
            this.graphics = graphics;
            this.snapshot = snapshot;
            this.bindings = bindings;
            this.counter = counter;
            this.deadlineNanos = deadlineNanos;
        }

        private PaintState withBindings(BindingResolver bindings) {
            return new PaintState(graphics, snapshot, bindings, counter, deadlineNanos);
        }
    }

    private static final class BoundNode {
        private final Map<String, Object> node;
        private final BindingResolver bindings;

        private BoundNode(Map<String, Object> node, BindingResolver bindings) {
            this.node = node;
            this.bindings = bindings;
        }
    }

    private static final class Counter {
        private final int maximum;
        private int value;

        private Counter(int maximum) { this.maximum = maximum; }

        private void hit() throws IOException {
            if (++value > maximum) throw new IOException("Expanded image layers exceed the configured limit");
        }
    }
}
