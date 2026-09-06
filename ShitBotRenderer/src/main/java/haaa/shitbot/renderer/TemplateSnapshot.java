package haaa.shitbot.renderer;

import haaa.shitbot.api.ImageTemplateInfo;

import java.nio.file.Path;
import java.util.Map;

final class TemplateSnapshot {
    private final ImageTemplateInfo info;
    private final Map<String, Object> manifest;
    private final Map<String, Object> scene;
    private final Path directory;
    private final String suggestedFileName;

    TemplateSnapshot(ImageTemplateInfo info,
                     Map<String, Object> manifest,
                     Map<String, Object> scene,
                     Path directory,
                     String suggestedFileName) {
        this.info = info;
        this.manifest = manifest;
        this.scene = scene;
        this.directory = directory;
        this.suggestedFileName = suggestedFileName;
    }

    ImageTemplateInfo getInfo() { return info; }
    Map<String, Object> getManifest() { return manifest; }
    Map<String, Object> getScene() { return scene; }
    Path getDirectory() { return directory; }
    String getSuggestedFileName() { return suggestedFileName; }
}
