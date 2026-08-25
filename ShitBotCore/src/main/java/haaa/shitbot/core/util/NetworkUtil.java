package haaa.shitbot.core.util;

import java.util.Locale;

/** Lightweight host classification without DNS lookups or network I/O. */
public final class NetworkUtil {
    private NetworkUtil() {
    }

    public static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)
                || normalized.startsWith("127.");
    }
}
