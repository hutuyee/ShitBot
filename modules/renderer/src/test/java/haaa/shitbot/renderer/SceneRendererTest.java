package haaa.shitbot.renderer;

import haaa.shitbot.api.ImageRenderRequest;
import haaa.shitbot.api.ImageRenderResult;
import haaa.shitbot.api.ImageTemplateInfo;
import haaa.shitbot.api.spi.ImageTemplateEngineSettings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Checks interior pixels of simple shapes, avoiding platform-dependent font rendering. */
public class SceneRendererTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void rendersPngDimensionsMetadataAndBoundRectangleColor() throws Exception {
        TemplateSnapshot snapshot = snapshot("- {type: rectangle, x: 2, y: 2, width: 8, height: 8, fill: '${context.color}'}\n");
        ImageRenderResult result = render(snapshot, Collections.singletonMap("color", "#ff0000"));
        assertEquals(32, result.getWidth());
        assertEquals(16, result.getHeight());
        assertEquals("image/png", result.getContentType());
        assertEquals("test-card", result.getTemplateId());
        assertEquals(2L, result.getTemplateVersion());
        assertEquals("card.png", result.getSuggestedFileName());
        BufferedImage image = png(result);
        assertEquals(32, image.getWidth());
        assertEquals(16, image.getHeight());
        assertEquals(0xffff0000, image.getRGB(5, 5));
        assertEquals(0xffffffff, image.getRGB(20, 10));
    }

    @Test
    public void conditionsChooseTheCorrectBranchAndHiddenNodesDoNotPaint() throws Exception {
        TemplateSnapshot snapshot = snapshot("- type: condition\n  condition: '${context.show}'\n"
                + "  then: [{type: rectangle, width: 16, height: 16, fill: '#00ff00'}]\n"
                + "  else: [{type: rectangle, width: 16, height: 16, fill: '#ff0000'}]\n"
                + "- {type: rectangle, visible: false, width: 32, height: 16, fill: '#000000'}\n");
        assertEquals(0xff00ff00, png(render(snapshot, Collections.singletonMap("show", true))).getRGB(5, 5));
        assertEquals(0xffff0000, png(render(snapshot, Collections.singletonMap("show", false))).getRGB(5, 5));
    }

    @Test
    public void loopsRespectItemLimitsAndBindEachIterationSeparately() throws Exception {
        TemplateSnapshot snapshot = snapshot("- type: loop\n  items: '${context.colors}'\n  as: color\n"
                + "  maximum-items: 2\n  item-offset-x: 10\n"
                + "  children: [{type: rectangle, width: 8, height: 8, fill: '${color}'}]\n");
        ImageRenderResult result = render(snapshot, Collections.singletonMap("colors", new String[] {"#ff0000", "#0000ff", "#00ff00"}));
        BufferedImage image = png(result);
        assertEquals(0xffff0000, image.getRGB(4, 4));
        assertEquals(0xff0000ff, image.getRGB(14, 4));
        assertEquals(0xffffffff, image.getRGB(24, 4));
    }

    @Test
    public void expiredDeadlinesStopRendering() throws Exception {
        TemplateSnapshot snapshot = snapshot("- {type: rectangle, width: 8, height: 8, fill: '#ff0000'}\n");
        ImageTemplateEngineSettings settings = RendererTestSupport.settings(temporary.getRoot().toPath());
        try (SecureImageLoader images = new SecureImageLoader(settings)) {
            SceneRenderer renderer = new SceneRenderer(settings, images);
            IOException error = assertThrows(IOException.class,
                    () -> renderer.render(snapshot, ImageRenderRequest.of(null), System.nanoTime() - TimeUnit.SECONDS.toNanos(1)));
            assertEquals("Image render timed out", error.getMessage());
        }
    }

    @Test
    public void expandedLoopsCannotBypassTheLayerBudget() throws Exception {
        String repeated = String.join("", Collections.nCopies(40,
                "    - {type: rectangle, width: 1, height: 1, fill: '#ff0000'}\n"));
        TemplateSnapshot snapshot = snapshot("- type: loop\n  items: '${context.items}'\n  children:\n" + repeated);
        IOException error = assertThrows(IOException.class,
                () -> render(snapshot, Collections.singletonMap("items", Collections.nCopies(8, "item"))));
        assertTrue(error.getMessage().contains("Expanded image layers"));
    }

    private TemplateSnapshot snapshot(String layers) throws IOException {
        Map<String, Object> scene = new YamlDocuments().loadMap("schema-version: scene-v1\n"
                + "canvas: {width: 32, height: 16, background: '#ffffff'}\nlayers:\n" + layers);
        ImageTemplateInfo info = new ImageTemplateInfo("test-card", "Test", 2, 32, 16, null);
        return new TemplateSnapshot(info, Collections.<String, Object>emptyMap(), scene,
                temporary.getRoot().toPath(), "card.png");
    }

    private ImageRenderResult render(TemplateSnapshot snapshot, Map<String, ?> context) throws IOException {
        ImageTemplateEngineSettings settings = RendererTestSupport.settings(temporary.getRoot().toPath());
        try (SecureImageLoader images = new SecureImageLoader(settings)) {
            return new SceneRenderer(settings, images).render(snapshot, ImageRenderRequest.of(context),
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
        }
    }

    private static BufferedImage png(ImageRenderResult result) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.getBytes()));
        assertNotNull(image);
        return image;
    }
}
