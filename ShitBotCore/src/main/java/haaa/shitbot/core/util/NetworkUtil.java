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
                || isIpv4LoopbackLiteral(normalized);
    }

    private static boolean isIpv4LoopbackLiteral(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) {
            return false;
        }
        for (String octet : octets) {
            // Do not exempt DNS names such as 127.example.org or ambiguous numeric forms.
            if (octet.isEmpty() || octet.length() > 3
                    || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return false;
            }
            int value = 0;
            for (int index = 0; index < octet.length(); index++) {
                char digit = octet.charAt(index);
                if (digit < '0' || digit > '9') return false;
                value = value * 10 + digit - '0';
            }
            if (value > 255) return false;
        }
        return true;
    }
}
