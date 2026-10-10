package haaa.shitbot.renderer;

import haaa.shitbot.api.ImageDataProviderSpec;
import haaa.shitbot.api.ImageRenderRequest;
import haaa.shitbot.api.spi.ImageTemplateEngineHost;
import haaa.shitbot.api.spi.ImageTemplateEngineSettings;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class RendererTestSupport {
    static final ImageTemplateEngineHost HOST = new ImageTemplateEngineHost() {
        @Override
        public CompletableFuture<Map<String, Object>> resolveData(String id, List<ImageDataProviderSpec> providers,
                                                                   ImageRenderRequest request) {
            return CompletableFuture.completedFuture(Collections.<String, Object>emptyMap());
        }

        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message, Throwable throwable) { }
    };

    private RendererTestSupport() { }

    static ImageTemplateEngineSettings settings(Path root) {
        return new ImageTemplateEngineSettings(root, 2048, 2048, 4_000_000L, 256, 8,
                4096, 8192, 5000, 1, 4, false, false, "127.0.0.1", 0, 60, 4096);
    }

    static String manifest(String name) {
        return "schema-version: template-v1\nid: test-card\nname: " + name + "\nproviders: []\n";
    }

    static String scene(int width) {
        return "schema-version: scene-v1\ncanvas: {width: " + width + ", height: 16}\nlayers: []\n";
    }
}
