package haaa.shitbotspigot.placeholder;

import haaa.shitbot.api.PlayerBinding;
import haaa.shitbot.api.ShitBotApi;
import haaa.shitbot.core.runtime.ShitBotRuntime;
import haaa.shitbotspigot.ShitBotSpigot;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Optional PAPI expansion. SQL runs asynchronously; PAPI callbacks only read bounded snapshots. */
public final class ShitBotExpansion extends PlaceholderExpansion implements AutoCloseable {
    private final ShitBotSpigot plugin;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>(64, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > 512;
        }
    };
    private volatile boolean closed;

    public ShitBotExpansion(ShitBotSpigot plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "shitbot"; }
    @Override public String getAuthor() { return String.join(", ", plugin.getDescription().getAuthors()); }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        if ("version".equals(key)) return getVersion();
        ShitBotRuntime runtime = plugin.getRuntime();
        if ("ready".equals(key)) return String.valueOf(!closed && runtime != null && runtime.isReady());
        if ("connected".equals(key)) return String.valueOf(!closed && runtime != null
                && runtime.getOneBotClient().isConnected());
        if (!"bound".equals(key) && !"whitelisted".equals(key) && !"qq".equals(key)) return null;
        if (closed || runtime == null || !runtime.isReady() || player == null) return "";
        String playerName = player.getName();
        if (playerName == null || playerName.isEmpty()) return "";
        ShitBotApi api = runtime.getApi();
        Entry entry;
        synchronized (entries) {
            if (closed) return "";
            long now = System.currentTimeMillis();
            entry = entries.get(playerName);
            if (entry == null || entry.api != api || now >= entry.expiresAt) {
                final Entry loading = new Entry(api, now + 10_000L);
                entries.put(playerName, loading);
                api.getBinding(playerName).whenComplete((binding, error) -> {
                    if (!closed && api.isReady()) {
                        loading.binding = error == null ? binding : null;
                        loading.expiresAt = System.currentTimeMillis() + (error == null ? 5_000L : 2_000L);
                    }
                });
                entry = loading;
            }
        }
        Optional<PlayerBinding> binding = entry.binding;
        if (binding == null) return ""; // Cache miss/failure is unknown, rather than a negative login decision.
        if ("whitelisted".equals(key)) return String.valueOf(binding.isPresent());
        if ("bound".equals(key)) return String.valueOf(binding.isPresent() && binding.get().hasQqOwner());
        return binding.isPresent() ? binding.get().getQqId().orElse("") : "";
    }

    @Override public void close() {
        closed = true;
        unregister();
        synchronized (entries) { entries.clear(); }
    }

    private static final class Entry {
        final ShitBotApi api;
        volatile long expiresAt;
        volatile Optional<PlayerBinding> binding;
        Entry(ShitBotApi api, long expiresAt) {
            this.api = api;
            this.expiresAt = expiresAt;
        }
    }
}
