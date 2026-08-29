package haaa.shitbotnukkit.platform;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.command.CommandSender;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.item.Item;
import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.console.ConsoleRequest;
import haaa.shitbot.core.console.ConsoleResult;
import haaa.shitbot.core.console.ConsoleSettings;
import haaa.shitbot.core.inventory.InventorySnapshot;
import haaa.shitbot.core.platform.PlatformBridge;
import haaa.shitbotnukkit.ShitBotNukkit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class NukkitPlatformBridge implements PlatformBridge, AutoCloseable {
    private final ShitBotNukkit plugin;
    private final Server server;
    private final NukkitConsoleGateway consoleGateway;

    public NukkitPlatformBridge(ShitBotNukkit plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.consoleGateway = new NukkitConsoleGateway(plugin, this);
    }

    @Override
    public Path getDataDirectory() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public String getPlatformName() {
        return "Nukkit-MOT";
    }

    @Override
    public CompletableFuture<ConsoleResult> executeConsoleRequest(ConsoleRequest request) {
        return consoleGateway.execute(request);
    }

    public void configureConsole(ConsoleSettings settings) {
        consoleGateway.configure(settings);
    }

    @Override
    public CompletableFuture<Map<String, List<String>>> captureOnlinePlayers() {
        final CompletableFuture<Map<String, List<String>>> future =
                new CompletableFuture<Map<String, List<String>>>();
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> players = new ArrayList<String>();
                    for (Player player : server.getOnlinePlayers().values()) {
                        if (player != null && player.isOnline()) {
                            players.add(player.getName());
                        }
                    }
                    Map<String, List<String>> snapshot = new LinkedHashMap<String, List<String>>();
                    if (!players.isEmpty()) {
                        snapshot.put(serverName(), players);
                    }
                    future.complete(snapshot);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<List<InventorySnapshot>> captureOnlineInventories() {
        final CompletableFuture<List<InventorySnapshot>> future =
                new CompletableFuture<List<InventorySnapshot>>();
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<InventorySnapshot> snapshots = new ArrayList<InventorySnapshot>();
                    for (Player player : server.getOnlinePlayers().values()) {
                        if (player != null && player.isOnline()) {
                            snapshots.add(snapshot(player));
                        }
                    }
                    future.complete(snapshots);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Map<String, InventorySnapshot>> captureInventories(
            List<String> playerNames) {
        final Set<String> requested = new LinkedHashSet<String>();
        if (playerNames != null) {
            for (String playerName : playerNames) {
                if (playerName != null && !playerName.trim().isEmpty()) {
                    requested.add(playerName.trim());
                }
            }
        }
        if (requested.isEmpty()) {
            return CompletableFuture.completedFuture(
                    Collections.<String, InventorySnapshot>emptyMap());
        }
        final CompletableFuture<Map<String, InventorySnapshot>> future =
                new CompletableFuture<Map<String, InventorySnapshot>>();
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, InventorySnapshot> result =
                            new LinkedHashMap<String, InventorySnapshot>();
                    for (String playerName : requested) {
                        Player player = server.getPlayerExact(playerName);
                        if (player != null && player.isOnline()) {
                            result.put(playerName, snapshot(player));
                        }
                    }
                    future.complete(result);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            }
        });
        return future;
    }

    public InventorySnapshot captureInventorySnapshot(Player player) {
        if (player != null && !server.isPrimaryThread()) {
            throw new IllegalStateException("Nukkit inventory snapshots must run on the primary thread");
        }
        return player == null ? null : snapshot(player);
    }

    private InventorySnapshot snapshot(Player player) {
        PlayerInventory inventory = player.getInventory();
        List<InventorySnapshot.Item> items = new ArrayList<InventorySnapshot.Item>();
        for (int slot = 0; slot < InventorySnapshot.STORAGE_SIZE; slot++) {
            addItem(items, slot, inventory.getItem(slot));
        }
        Item[] armor = inventory.getArmorContents();
        if (armor != null) {
            if (armor.length > 0) addItem(items, InventorySnapshot.HELMET_SLOT, armor[0]);
            if (armor.length > 1) addItem(items, InventorySnapshot.CHESTPLATE_SLOT, armor[1]);
            if (armor.length > 2) addItem(items, InventorySnapshot.LEGGINGS_SLOT, armor[2]);
            if (armor.length > 3) addItem(items, InventorySnapshot.BOOTS_SLOT, armor[3]);
        }
        addItem(items, InventorySnapshot.OFFHAND_SLOT,
                player.getOffhandInventory().getItem(0));
        return new InventorySnapshot(
                player.getName(),
                player.getUniqueId() == null ? "" : player.getUniqueId().toString(),
                serverName(),
                System.currentTimeMillis(),
                items);
    }

    private void addItem(List<InventorySnapshot.Item> items, int slot, Item item) {
        if (item == null || item.isNull() || item.getCount() <= 0) {
            return;
        }
        String registryId;
        try {
            registryId = item.getNamespaceId();
        } catch (Throwable ignored) {
            registryId = "minecraft:item_" + Math.max(0, item.getId());
        }
        if (registryId == null || registryId.trim().isEmpty()) {
            registryId = "minecraft:item_" + Math.max(0, item.getId());
        }
        String materialName = registryPath(registryId).toUpperCase(Locale.ROOT);
        String displayName;
        try {
            displayName = stripFormatting(item.getDisplayName());
        } catch (Throwable ignored) {
            displayName = humanize(materialName);
        }
        if (displayName.isEmpty()) {
            displayName = humanize(materialName);
        }
        int maximumDurability;
        try {
            maximumDurability = Math.max(0, item.getMaxDurability());
        } catch (Throwable ignored) {
            maximumDurability = 0;
        }
        int damage;
        try {
            damage = Math.max(0, item.getDamage());
        } catch (Throwable ignored) {
            damage = 0;
        }
        boolean enchanted;
        try {
            enchanted = item.hasEnchantments();
        } catch (Throwable ignored) {
            enchanted = false;
        }
        items.add(new InventorySnapshot.Item(
                slot,
                registryId,
                materialName,
                displayName,
                item.getCount(),
                damage,
                maximumDurability,
                null,
                enchanted,
                false));
    }

    private String registryPath(String registryId) {
        int colon = registryId.indexOf(':');
        String path = colon < 0 ? registryId : registryId.substring(colon + 1);
        return path.replace('/', '_').replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private String humanize(String materialName) {
        String[] words = materialName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) builder.append(word.substring(1));
        }
        return builder.length() == 0 ? "Unknown" : builder.toString();
    }

    private String stripFormatting(String value) {
        return value == null ? "" : value.replaceAll("(?i)§[0-9A-FK-ORX]", "").trim();
    }

    @Override
    public void executeOnPlatformThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (server.isPrimaryThread()) {
            runnable.run();
        } else {
            server.getScheduler().scheduleTask(plugin, runnable);
        }
    }

    public void executeOnSenderThread(CommandSender sender, Runnable runnable) {
        executeOnPlatformThread(runnable);
    }

    @Override
    public void broadcastMessage(final String message) {
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                server.broadcastMessage(message == null ? "" : message);
            }
        });
    }

    @Override
    public void disconnectPlayers(List<String> playerNames, final String reason) {
        if (playerNames == null || playerNames.isEmpty()) {
            return;
        }
        final Set<String> exactNames = new HashSet<String>();
        for (String playerName : playerNames) {
            if (playerName != null && !playerName.trim().isEmpty()) {
                exactNames.add(playerName.trim());
            }
        }
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                for (Player player : server.getOnlinePlayers().values()) {
                    if (player != null && exactNames.contains(player.getName())) {
                        player.kick(reason == null ? "" : reason, false);
                    }
                }
            }
        });
    }

    @Override
    public void broadcastRichMessage(List<ChatPart> parts) {
        StringBuilder builder = new StringBuilder();
        if (parts != null) {
            for (ChatPart part : parts) {
                if (part != null) {
                    builder.append(part.getText());
                    if (part.hasClickUrl()) {
                        builder.append(" (").append(part.getClickUrl()).append(')');
                    }
                }
            }
        }
        broadcastMessage(builder.toString());
    }

    public String serverName() {
        String motd = server.getMotd();
        String clean = stripFormatting(motd).replace('\r', ' ').replace('\n', ' ').trim();
        if (clean.isEmpty()) {
            return "Nukkit-MOT";
        }
        return clean.length() <= 64 ? clean : clean.substring(0, 61) + "...";
    }

    @Override
    public void info(String message) {
        plugin.getLogger().info(message);
    }

    @Override
    public void warn(String message) {
        plugin.getLogger().warning(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        if (throwable == null) {
            plugin.getLogger().error(message);
        } else {
            plugin.getLogger().error(message, throwable);
        }
    }

    @Override
    public void close() {
        consoleGateway.close();
    }
}
