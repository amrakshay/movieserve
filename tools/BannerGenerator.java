/*
 * Draws the Android TV banner.
 *
 * Android TV shows an app on the home screen as a 320x180 banner. Most launchers print the app's
 * name underneath; some heavily themed ones show the banner alone, so the name is drawn into the
 * image rather than relied upon.
 *
 * Rendered with Graphics2D so the wordmark uses a real typeface with proper antialiasing, and the
 * banner stays rebuildable from source instead of living in the repository as an opaque binary.
 * A JDK is already needed to build the TV app, so this adds no new tooling.
 *
 *     java tools/BannerGenerator.java
 *
 * Writes tv-app/app/src/main/res/drawable-nodpi/banner.png
 */
import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class BannerGenerator {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;

    /** Deep blue falling to near-black, so the banner sits well on a dark home screen. */
    private static final Color TOP = new Color(0x15, 0x2F, 0x9A);
    private static final Color BOTTOM = new Color(0x04, 0x07, 0x1E);
    private static final Color ACCENT = new Color(0x5B, 0x8B, 0xFF);
    private static final Color WORD = new Color(0xFF, 0xFF, 0xFF);

    private static final String TEXT = "movieserve";

    /** Tried in order; the first one the machine actually has is used. */
    private static final String[] PREFERRED_FONTS = {
            "Helvetica Neue", "HelveticaNeue", "Helvetica", "Inter", "SF Pro Display",
            "Arial", Font.SANS_SERIF,
    };

    public static void main(String[] args) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        g.setPaint(new GradientPaint(0, 0, TOP, 0, HEIGHT, BOTTOM));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        drawPlayMark(g, 160, 66, 34);
        drawWordmark(g, 160, 136);

        g.dispose();

        Path out = Paths.get(System.getProperty("user.dir"))
                .resolve("tv-app/app/src/main/res/drawable-nodpi/banner.png");
        File file = out.toFile();
        file.getParentFile().mkdirs();
        ImageIO.write(image, "png", file);
        System.out.printf("wrote %s (%d bytes, %dx%d)%n", out, file.length(), WIDTH, HEIGHT);
    }

    /** A play triangle in a thin ring, centred on (cx, cy). */
    private static void drawPlayMark(Graphics2D g, double cx, double cy, double radius) {
        g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 90));
        g.setStroke(new BasicStroke(2f));
        g.draw(new java.awt.geom.Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));

        // Rounded join keeps the triangle's points soft rather than needle sharp.
        double size = radius * 0.86;
        Path2D triangle = new Path2D.Double();
        triangle.moveTo(cx - size * 0.38, cy - size * 0.56);
        triangle.lineTo(cx + size * 0.62, cy);
        triangle.lineTo(cx - size * 0.38, cy + size * 0.56);
        triangle.closePath();

        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(triangle);
        g.fill(triangle);
    }

    /** The wordmark, centred on cx with its baseline at y. */
    private static void drawWordmark(Graphics2D g, double cx, double y) {
        Font font = pickFont().deriveFont(Font.PLAIN, 30f);
        // A little tracking so the lowercase word reads as a mark rather than running together.
        font = font.deriveFont(java.util.Map.of(
                java.awt.font.TextAttribute.TRACKING, 0.06));
        g.setFont(font);

        FontRenderContext frc = g.getFontRenderContext();
        GlyphVector glyphs = font.createGlyphVector(frc, TEXT);
        Rectangle2D bounds = glyphs.getVisualBounds();
        double x = cx - bounds.getWidth() / 2 - bounds.getX();

        g.setColor(WORD);
        g.drawGlyphVector(glyphs, (float) x, (float) y);

        // A short accent rule under the word, to anchor it.
        double ruleWidth = bounds.getWidth() * 0.5;
        g.setColor(ACCENT);
        g.fill(new RoundRectangle2D.Double(cx - ruleWidth / 2, y + 12, ruleWidth, 3, 3, 3));
    }

    private static Font pickFont() {
        var available = java.util.Set.of(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames());
        for (String name : PREFERRED_FONTS) {
            if (available.contains(name)) {
                return new Font(name, Font.PLAIN, 30);
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 30);
    }
}
