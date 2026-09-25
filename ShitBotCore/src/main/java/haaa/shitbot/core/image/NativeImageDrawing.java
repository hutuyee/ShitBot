package haaa.shitbot.core.image;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;

/** Shared, inexpensive surfaces for the cached Java2D image backgrounds. */
public final class NativeImageDrawing {
    private NativeImageDrawing() { }

    public static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    public static Color mix(Color from, Color to, float amount) {
        return new Color(Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount), from.getAlpha());
    }

    public static void glow(Graphics2D graphics, int x, int y, int radius, Color color) {
        graphics.setPaint(new RadialGradientPaint(x, y, radius, new float[]{0F, 1F},
                new Color[]{color, alpha(color, 0)}));
        graphics.fillRect(x - radius, y - radius, radius * 2, radius * 2);
    }

    public static void surface(Graphics2D graphics, int x, int y, int width, int height,
                               int radius, Color background, Color border, Color shadow) {
        for (int spread = 6; spread >= 2; spread -= 2) {
            graphics.setColor(alpha(shadow, shadow.getAlpha() / 6));
            graphics.fillRoundRect(x - spread, y + 3 - spread / 2,
                    width + spread * 2, height + spread, radius + spread, radius + spread);
        }
        graphics.setPaint(new GradientPaint(x, y, mix(background, Color.WHITE, 0.035F),
                x, y + height, background));
        graphics.fillRoundRect(x, y, width, height, radius, radius);
        graphics.setColor(border);
        graphics.drawRoundRect(x, y, width, height, radius, radius);
    }
}
