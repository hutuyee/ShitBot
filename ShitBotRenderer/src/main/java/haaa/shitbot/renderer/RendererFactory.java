package haaa.shitbot.renderer;

import haaa.shitbot.api.spi.ImageTemplateEngine;
import haaa.shitbot.api.spi.ImageTemplateEngineFactory;
import haaa.shitbot.api.spi.ImageTemplateEngineHost;
import haaa.shitbot.api.spi.ImageTemplateEngineSettings;

public final class RendererFactory implements ImageTemplateEngineFactory {
    @Override
    public ImageTemplateEngine create(ImageTemplateEngineSettings settings,
                                      ImageTemplateEngineHost host) {
        return new RendererEngine(settings, host);
    }
}
