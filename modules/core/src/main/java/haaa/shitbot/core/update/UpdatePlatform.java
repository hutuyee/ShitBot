package haaa.shitbot.core.update;

/** Expected Release asset and JAR contents for each supported platform. */
public enum UpdatePlatform {
    SPIGOT("ShitBotSpigot-", "plugin.yml", "haaa/shitbotspigot/ShitBotSpigot.class"),
    BUNGEE("ShitBotBungee-", "bungee.yml", "haaa/shitbotbungee/ShitBotBungee.class"),
    VELOCITY("ShitBotVelocity-", "velocity-plugin.json", "haaa/shitbotvelocity/ShitBotVelocity.class"),
    NUKKIT("ShitBotNukkit-", "plugin.yml", "haaa/shitbotnukkit/ShitBotNukkit.class");

    private final String assetPrefix;
    private final String descriptorPath;
    private final String mainClassPath;

    UpdatePlatform(String assetPrefix, String descriptorPath, String mainClassPath) {
        this.assetPrefix = assetPrefix;
        this.descriptorPath = descriptorPath;
        this.mainClassPath = mainClassPath;
    }

    public String getAssetPrefix() {
        return assetPrefix;
    }

    public String getDescriptorPath() {
        return descriptorPath;
    }

    public String getMainClassPath() {
        return mainClassPath;
    }

    public boolean matchesJarName(String fileName) {
        return fileName != null
                && fileName.startsWith(assetPrefix)
                && fileName.endsWith(".jar")
                && fileName.length() > assetPrefix.length() + 4;
    }
}
