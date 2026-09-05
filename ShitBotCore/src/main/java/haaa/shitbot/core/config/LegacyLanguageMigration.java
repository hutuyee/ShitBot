package haaa.shitbot.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the language entries that used to live in config-version 1. */
public final class LegacyLanguageMigration {
    public static final int LANGUAGE_CONFIG_VERSION = 2;
    public static final String MARKER_PATH = "_migration.legacy-config-v1";

    private static final String[][] STRING_MAPPINGS = new String[][]{
            {"onebot.notices.server-startup.message", "notices.server-startup"},
            {"onebot.notices.group-join-welcome.message", "notices.group-join-welcome"},
            {"onebot.commands.bind.usage", "commands.bind.usage"},
            {"onebot.commands.online-image.usage", "commands.online-image.usage"},
            {"onebot.commands.inventory.usage", "commands.inventory.usage"},
            {"image.title", "image.title"},
            {"inventory.title", "inventory.title"},
            {"messages.kick-unbound", "messages.kick-unbound"},
            {"messages.kick-after-unbind", "messages.kick-after-unbind"},
            {"messages.kick-database-unavailable", "messages.kick-database-unavailable"},
            {"messages.bind-usage", "messages.bind-usage"},
            {"messages.bind-success", "messages.bind-success"},
            {"messages.bind-invalid", "messages.bind-invalid"},
            {"messages.bind-expired", "messages.bind-expired"},
            {"messages.bind-qq-already-used", "messages.bind-qq-already-used"},
            {"messages.bind-qq-limit-reached", "messages.bind-qq-limit-reached"},
            {"messages.bind-player-already-used", "messages.bind-player-already-used"},
            {"messages.bind-database-error", "messages.bind-database-error"},
            {"messages.online-failed", "messages.online-failed"},
            {"messages.inventory-not-bound", "messages.inventory-not-bound"},
            {"messages.inventory-player-not-bound", "messages.inventory-player-not-bound"},
            {"messages.inventory-unavailable", "messages.inventory-unavailable"},
            {"messages.inventory-disabled", "messages.inventory-disabled"},
            {"messages.inventory-failed", "messages.inventory-failed"},
            {"messages.no-permission", "messages.no-permission"},
            {"messages.reload-started", "messages.reload-started"},
            {"messages.reload-success", "messages.reload-success"},
            {"messages.reload-failed", "messages.reload-failed"}
    };

    private static final String[][] LIST_MAPPINGS = new String[][]{
            {"onebot.commands.bind.aliases", "commands.bind.aliases"},
            {"onebot.commands.online-image.aliases", "commands.online-image.aliases"},
            {"onebot.commands.inventory.aliases", "commands.inventory.aliases"}
    };

    private LegacyLanguageMigration() {
    }

    public static boolean isRequired(ConfigSource legacyConfig, ConfigSource zhCnLanguage) {
        return legacyConfig.getInt("config-version", 1) < LANGUAGE_CONFIG_VERSION
                && !zhCnLanguage.getBoolean(MARKER_PATH, false);
    }

    public static Map<String, Object> collect(ConfigSource legacyConfig) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (String[] mapping : STRING_MAPPINGS) {
            if (hasPath(legacyConfig, mapping[0])) {
                values.put(mapping[1], legacyConfig.getString(mapping[0], ""));
            }
        }
        for (String[] mapping : LIST_MAPPINGS) {
            if (hasPath(legacyConfig, mapping[0])) {
                List<String> aliases = legacyConfig.getStringList(mapping[0]);
                values.put(mapping[1], aliases == null
                        ? new ArrayList<String>()
                        : new ArrayList<String>(aliases));
            }
        }
        return values;
    }

    private static boolean hasPath(ConfigSource source, String path) {
        int separator = path.lastIndexOf('.');
        if (separator <= 0 || separator == path.length() - 1) {
            return false;
        }
        String parent = path.substring(0, separator);
        String key = path.substring(separator + 1);
        Set<String> keys = source.getSectionKeys(parent);
        return keys != null && keys.contains(key);
    }
}
