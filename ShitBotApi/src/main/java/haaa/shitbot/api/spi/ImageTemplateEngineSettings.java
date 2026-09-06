package haaa.shitbot.api.spi;

import java.nio.file.Path;

/** Validated resource limits passed to the optional renderer component. */
public final class ImageTemplateEngineSettings {
    private final Path templatesDirectory;
    private final int maximumWidth;
    private final int maximumHeight;
    private final long maximumPixels;
    private final int maximumLayers;
    private final int maximumLoopItems;
    private final long maximumAssetBytes;
    private final long maximumTemplateAssetBytes;
    private final int renderTimeoutMillis;
    private final int renderThreads;
    private final int maximumQueuedRenders;
    private final boolean remoteImagesEnabled;
    private final boolean editorEnabled;
    private final String editorBindAddress;
    private final int editorPort;
    private final int editorLoginSeconds;
    private final long editorMaximumUploadBytes;

    public ImageTemplateEngineSettings(Path templatesDirectory,
                                       int maximumWidth,
                                       int maximumHeight,
                                       long maximumPixels,
                                       int maximumLayers,
                                       int maximumLoopItems,
                                       long maximumAssetBytes,
                                       long maximumTemplateAssetBytes,
                                       int renderTimeoutMillis,
                                       int renderThreads,
                                       int maximumQueuedRenders,
                                       boolean remoteImagesEnabled,
                                       boolean editorEnabled,
                                       String editorBindAddress,
                                       int editorPort,
                                       int editorLoginSeconds,
                                       long editorMaximumUploadBytes) {
        this.templatesDirectory = templatesDirectory;
        this.maximumWidth = maximumWidth;
        this.maximumHeight = maximumHeight;
        this.maximumPixels = maximumPixels;
        this.maximumLayers = maximumLayers;
        this.maximumLoopItems = maximumLoopItems;
        this.maximumAssetBytes = maximumAssetBytes;
        this.maximumTemplateAssetBytes = maximumTemplateAssetBytes;
        this.renderTimeoutMillis = renderTimeoutMillis;
        this.renderThreads = renderThreads;
        this.maximumQueuedRenders = maximumQueuedRenders;
        this.remoteImagesEnabled = remoteImagesEnabled;
        this.editorEnabled = editorEnabled;
        this.editorBindAddress = editorBindAddress;
        this.editorPort = editorPort;
        this.editorLoginSeconds = editorLoginSeconds;
        this.editorMaximumUploadBytes = editorMaximumUploadBytes;
    }

    public Path getTemplatesDirectory() { return templatesDirectory; }
    public int getMaximumWidth() { return maximumWidth; }
    public int getMaximumHeight() { return maximumHeight; }
    public long getMaximumPixels() { return maximumPixels; }
    public int getMaximumLayers() { return maximumLayers; }
    public int getMaximumLoopItems() { return maximumLoopItems; }
    public long getMaximumAssetBytes() { return maximumAssetBytes; }
    public long getMaximumTemplateAssetBytes() { return maximumTemplateAssetBytes; }
    public int getRenderTimeoutMillis() { return renderTimeoutMillis; }
    public int getRenderThreads() { return renderThreads; }
    public int getMaximumQueuedRenders() { return maximumQueuedRenders; }
    public boolean isRemoteImagesEnabled() { return remoteImagesEnabled; }
    public boolean isEditorEnabled() { return editorEnabled; }
    public String getEditorBindAddress() { return editorBindAddress; }
    public int getEditorPort() { return editorPort; }
    public int getEditorLoginSeconds() { return editorLoginSeconds; }
    public long getEditorMaximumUploadBytes() { return editorMaximumUploadBytes; }
}
