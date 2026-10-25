package haaa.shitbot.core.config;

import java.util.List;
import java.util.Set;

/** Minimal configuration abstraction implemented by Bukkit, Bungee and Velocity loaders. */
public interface ConfigSource {
    String getString(String path, String fallback);
    boolean getBoolean(String path, boolean fallback);
    int getInt(String path, int fallback);
    long getLong(String path, long fallback);
    List<String> getStringList(String path);
    List<Long> getLongList(String path);

    default Set<String> getSectionKeys(String path) {
        return java.util.Collections.emptySet();
    }
}
