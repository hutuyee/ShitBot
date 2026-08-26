package haaa.shitbot.core.console;

import haaa.shitbot.core.config.ConfigSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConsoleSettingsFactory {
    private ConsoleSettingsFactory() {
    }

    public static ConsoleSettings create(ConfigSource source) {
        Set<String> claimedAliases = new LinkedHashSet<String>();
        List<String> tpsAliases = uniqueAliases(
                listOrDefault(source.getStringList("tps.aliases"), "TPS", "tps", "服务器TPS"),
                claimedAliases);
        ConsoleSettings.Tps tps = new ConsoleSettings.Tps(
                source.getBoolean("tps.enabled", true),
                tpsAliases,
                source.getString("tps.permission", ""),
                source.getString("tps.server", ""),
                source.getString("tps.message", "服务器 TPS（%source%）：%result%"),
                source.getString("tps.failed", "TPS 获取失败：%result%"));

        ConsoleSettings.BackendListener backendListener = new ConsoleSettings.BackendListener(
                source.getBoolean("backend-transport.listener.enabled", false),
                source.getString("backend-transport.listener.bind-address", "127.0.0.1"),
                source.getInt("backend-transport.listener.port", 25580),
                source.getString("backend-transport.listener.token", ""),
                source.getString("backend-transport.listener.server-name", "backend"));
        List<ConsoleSettings.BackendEndpoint> endpoints = new ArrayList<ConsoleSettings.BackendEndpoint>();
        for (String name : source.getSectionKeys("backend-transport.endpoints")) {
            String path = "backend-transport.endpoints." + name;
            endpoints.add(new ConsoleSettings.BackendEndpoint(
                    name,
                    source.getString(path + ".host", "127.0.0.1"),
                    source.getInt(path + ".port", 25580),
                    source.getString(path + ".token", "")));
        }
        ConsoleSettings.BackendTransport backendTransport = new ConsoleSettings.BackendTransport(
                source.getInt("backend-transport.connect-timeout-ms", 3000),
                source.getInt("backend-transport.read-timeout-seconds", 40),
                source.getString("backend-transport.default-server", ""),
                backendListener,
                endpoints);

        List<ConsoleSettings.Shortcut> shortcuts = new ArrayList<ConsoleSettings.Shortcut>();
        for (String name : source.getSectionKeys("shortcuts")) {
            String path = "shortcuts." + name;
            List<String> aliases = uniqueAliases(source.getStringList(path + ".aliases"), claimedAliases);
            String command = source.getString(path + ".command", "").trim();
            if (aliases.isEmpty() || command.isEmpty()) {
                continue;
            }
            shortcuts.add(new ConsoleSettings.Shortcut(
                    name,
                    source.getBoolean(path + ".enabled", true),
                    aliases,
                    command,
                    source.getString(path + ".permission", "shitbot.admin"),
                    source.getBoolean(path + ".allow-unbound", false),
                    ConsoleSettings.Target.from(source.getString(path + ".target", "backend")),
                    source.getString(path + ".server", ""),
                    durationSeconds(source.getString(path + ".capture-seconds", "5"), 5),
                    source.getString(path + ".message", "执行成功（%source%）：\n%result%"),
                    source.getString(path + ".failed", "执行失败（%source%）：\n%result%")));
        }

        return new ConsoleSettings(
                source.getBoolean("enabled", true),
                source.getInt("request-timeout-seconds", 15),
                source.getInt("command-cooldown-seconds", 5),
                source.getString("messages.not-bound", "%at% 请先绑定游戏 ID。"),
                source.getString("messages.no-permission", "%at% 你绑定的角色没有权限执行该操作。"),
                source.getString("messages.unavailable", "%at% 没有可用的子服，或请求执行超时。"),
                source.getString("messages.result-timeout", "%at% %result%"),
                source.getString("messages.invalid-target", "%at% 用法：%command% [目标子服]"),
                backendTransport,
                tps,
                shortcuts);
    }

    private static List<String> uniqueAliases(List<String> aliases, Set<String> claimed) {
        List<String> result = new ArrayList<String>();
        if (aliases == null) {
            return result;
        }
        for (String alias : aliases) {
            if (alias == null || alias.trim().isEmpty()) {
                continue;
            }
            String clean = alias.trim();
            if (claimed.add(clean.toLowerCase(Locale.ROOT))) {
                result.add(clean);
            }
        }
        return result;
    }

    private static int durationSeconds(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String clean = value.trim().toLowerCase(Locale.ROOT);
        if (clean.endsWith("s")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        try {
            return Integer.parseInt(clean);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> listOrDefault(List<String> values, String... fallback) {
        return values == null || values.isEmpty() ? Arrays.asList(fallback) : values;
    }
}
