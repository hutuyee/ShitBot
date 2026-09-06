package haaa.shitbot.api.spi;

/** Loaded from META-INF/services in the optional ShitBotRenderer JAR. */
public interface ImageTemplateEngineFactory {
    ImageTemplateEngine create(ImageTemplateEngineSettings settings, ImageTemplateEngineHost host);
}
