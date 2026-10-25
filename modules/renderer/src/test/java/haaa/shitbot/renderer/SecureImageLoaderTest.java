package haaa.shitbot.renderer;

import haaa.shitbot.api.ImageTemplateInfo;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;

import static org.junit.Assert.*;

public class SecureImageLoaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void acceptsPngBytesDataUrisAndLocalAssets() throws Exception {
        byte[] png = png(2, 3);
        Path root = temporary.getRoot().toPath();
        Files.createDirectory(root.resolve("assets"));
        Files.write(root.resolve("assets/icon.png"), png);
        try (SecureImageLoader images = loader()) {
            assertEquals(2, images.load(snapshot(), png).getWidth());
            assertEquals(3, images.load(snapshot(), "data:image/png;base64," + Base64.getEncoder().encodeToString(png)).getHeight());
            assertEquals(2, images.load(snapshot(), "assets/icon.png").getWidth());
            assertNull(images.load(snapshot(), null));
            assertNull(images.load(snapshot(), " "));
        }
    }

    @Test
    public void rejectsTraversalAndAbsoluteAssetPaths() throws Exception {
        // An actual image outside assets ensures rejection is not just a missing-file error.
        Files.write(temporary.getRoot().toPath().resolve("outside.png"), png(1, 1));
        try (SecureImageLoader images = loader()) {
            for (String path : new String[] {"../outside.png", "assets/../outside.png", "..\\outside.png", "/outside.png", "C:\\outside.png"}) {
                assertEquals("Invalid template asset path",
                        assertThrows(IOException.class, () -> images.load(snapshot(), path)).getMessage());
            }
        }
    }

    @Test
    public void disabledRemoteImagesAndNonHttpsSchemesFailBeforeNetworkAccess() throws Exception {
        try (SecureImageLoader images = loader()) {
            assertEquals("Remote template images are disabled", assertThrows(IOException.class,
                    () -> images.load(snapshot(), "https://example.invalid/image.png")).getMessage());
            for (String url : new String[] {"http://example.invalid/image.png", "file:///etc/passwd", "ftp://example.invalid/image.png"}) {
                assertEquals("Only HTTPS remote images are supported",
                        assertThrows(IOException.class, () -> images.load(snapshot(), url)).getMessage());
            }
        }
    }

    @Test
    public void rejectsMalformedUnsupportedAndOversizedImageData() throws Exception {
        try (SecureImageLoader images = loader()) {
            for (String data : new String[] {"data:image/png,raw", "data:image/svg+xml;base64,PHN2Zz4=", "data:image/png;base64,***"}) {
                assertThrows(IOException.class, () -> images.load(snapshot(), data));
            }
            assertThrows(IOException.class, () -> images.load(snapshot(), new byte[0]));
            assertThrows(IOException.class, () -> images.load(snapshot(), new byte[4097]));
            assertThrows(IOException.class, () -> images.load(snapshot(), new byte[] {1, 2, 3}));
        }
    }

    @Test
    public void dimensionLimitsApplyToEncodedAndAlreadyDecodedImages() throws Exception {
        byte[] oversized = png(2049, 1);
        try (SecureImageLoader images = loader()) {
            assertEquals("Image asset dimensions exceed configured limits",
                    assertThrows(IOException.class, () -> images.load(snapshot(), oversized)).getMessage());
            assertThrows(IOException.class,
                    () -> images.load(snapshot(), new BufferedImage(2049, 1, BufferedImage.TYPE_INT_ARGB)));
        }
    }

    private SecureImageLoader loader() {
        return new SecureImageLoader(RendererTestSupport.settings(temporary.getRoot().toPath()));
    }

    private TemplateSnapshot snapshot() {
        return new TemplateSnapshot(new ImageTemplateInfo("test-card", "Test", 1, 2, 3, null),
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                temporary.getRoot().toPath(), "card.png");
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return bytes.toByteArray();
    }
}
