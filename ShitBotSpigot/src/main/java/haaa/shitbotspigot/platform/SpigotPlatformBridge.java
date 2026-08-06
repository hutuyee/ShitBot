package haaa.shitbotspigot.platform;

import haaa.shitbot.core.chat.ChatPart;
import haaa.shitbot.core.inventory.InventorySnapshot;
import haaa.shitbot.core.platform.PlatformBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class SpigotPlatformBridge implements PlatformBridge {
    private final JavaPlugin plugin;

    public SpigotPlatformBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Path getDataDirectory() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public String getPlatformName() {
        return "Spigot";
    }

    @Override
    public CompletableFuture<Map<String, List<String>>> captureOnlinePlayers() {
        final CompletableFuture<Map<String, List<String>>> future = new CompletableFuture<Map<String, List<String>>>();
        Runnable capture = new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> players = new ArrayList<String>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player != null) {
                            players.add(player.getName());
                        }
                    }
                    Map<String, List<String>> snapshot = new LinkedHashMap<String, List<String>>();
                    if (!players.isEmpty()) {
                        snapshot.put(Bukkit.getServer().getName(), players);
                    }
                    future.complete(snapshot);
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            }
        };
        runOnPrimaryThread(capture);
        return future;
    }

    @Override
    public CompletableFuture<List<InventorySnapshot>> captureOnlineInventories() {
        final CompletableFuture<List<InventorySnapshot>> future =
                new CompletableFuture<List<InventorySnapshot>>();
        runOnPrimaryThread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<InventorySnapshot> snapshots = new ArrayList<InventorySnapshot>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
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
    public CompletableFuture<Map<String, InventorySnapshot>> captureInventories(List<String> playerNames) {
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
        runOnPrimaryThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, InventorySnapshot> result =
                            new LinkedHashMap<String, InventorySnapshot>();
                    for (String playerName : requested) {
                        Player player = Bukkit.getPlayerExact(playerName);
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

    /** Takes a synchronous snapshot for Bukkit events that still expose the Player object. */
    public InventorySnapshot captureInventorySnapshot(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Inventory snapshots must be captured on the Bukkit primary thread");
        }
        return player == null ? null : snapshot(player);
    }

    private InventorySnapshot snapshot(Player player) {
        PlayerInventory inventory = player.getInventory();
        List<InventorySnapshot.Item> items = new ArrayList<InventorySnapshot.Item>();
        ItemStack[] contents = inventory.getContents();
        int storageLength = Math.min(InventorySnapshot.STORAGE_SIZE, contents == null ? 0 : contents.length);
        for (int slot = 0; slot < storageLength; slot++) {
            addItem(items, slot, contents[slot]);
        }

        ItemStack[] armor = inventory.getArmorContents();
        if (armor != null) {
            if (armor.length > 0) addItem(items, InventorySnapshot.BOOTS_SLOT, armor[0]);
            if (armor.length > 1) addItem(items, InventorySnapshot.LEGGINGS_SLOT, armor[1]);
            if (armor.length > 2) addItem(items, InventorySnapshot.CHESTPLATE_SLOT, armor[2]);
            if (armor.length > 3) addItem(items, InventorySnapshot.HELMET_SLOT, armor[3]);
        }
        addItem(items, InventorySnapshot.OFFHAND_SLOT, readOffhand(inventory));
        return new InventorySnapshot(
                player.getName(),
                player.getUniqueId() == null ? "" : player.getUniqueId().toString(),
                Bukkit.getServer().getName(),
                System.currentTimeMillis(),
                items);
    }

    private void addItem(List<InventorySnapshot.Item> items, int slot, ItemStack stack) {
        if (stack == null || stack.getType() == null || stack.getType() == Material.AIR
                || stack.getAmount() <= 0) {
            return;
        }
        Material material = stack.getType();
        ItemMeta meta = null;
        try {
            meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        } catch (Throwable ignored) {
            // A broken third-party ItemMeta must not abort the whole player's snapshot.
        }
        String displayName = humanize(material.name());
        if (meta != null) {
            try {
                if (meta.hasDisplayName()) {
                    String customName = ChatColor.stripColor(meta.getDisplayName());
                    if (customName != null && !customName.trim().isEmpty()) {
                        displayName = customName.trim();
                    }
                }
            } catch (Throwable ignored) {
                // Keep the material-derived name.
            }
        }
        items.add(new InventorySnapshot.Item(
                slot,
                registryId(material),
                material.name(),
                displayName,
                stack.getAmount(),
                damage(stack, meta),
                Math.max(0, material.getMaxDurability()),
                customModelData(meta),
                !stack.getEnchantments().isEmpty(),
                booleanMethod(meta, "isUnbreakable")));
    }

    private String registryId(Material material) {
        try {
            Method getKey = material.getClass().getMethod("getKey");
            Object key = getKey.invoke(material);
            if (key != null) {
                String value = key.toString().toLowerCase(Locale.ROOT);
                if (value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
            // Bukkit 1.8 has no NamespacedKey; use the stable material fallback.
        }
        String name = material.name().toLowerCase(Locale.ROOT);
        if (name.startsWith("legacy_")) {
            name = name.substring("legacy_".length());
        }
        return "minecraft:" + name;
    }

    private int damage(ItemStack stack, ItemMeta meta) {
        if (meta != null) {
            try {
                Method getDamage = meta.getClass().getMethod("getDamage");
                Object value = getDamage.invoke(meta);
                if (value instanceof Number) {
                    return Math.max(0, ((Number) value).intValue());
                }
            } catch (Throwable ignored) {
                // Pre-1.13 durability is stored on ItemStack.
            }
        }
        try {
            return Math.max(0, stack.getDurability());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Integer customModelData(ItemMeta meta) {
        if (meta == null) {
            return null;
        }
        try {
            Method has = meta.getClass().getMethod("hasCustomModelData");
            if (!Boolean.TRUE.equals(has.invoke(meta))) {
                return null;
            }
            Method get = meta.getClass().getMethod("getCustomModelData");
            Object value = get.invoke(meta);
            return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean booleanMethod(Object target, String methodName) {
        if (target == null) {
            return false;
        }
        try {
            Object result = target.getClass().getMethod(methodName).invoke(target);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ItemStack readOffhand(PlayerInventory inventory) {
        try {
            Method method = inventory.getClass().getMethod("getItemInOffHand");
            Object result = method.invoke(inventory);
            return result instanceof ItemStack ? (ItemStack) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String humanize(String materialName) {
        StringBuilder result = new StringBuilder();
        for (String word : materialName.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1));
        }
        return result.toString();
    }

    private void runOnPrimaryThread(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    @Override
    public void executeOnPlatformThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        runOnPrimaryThread(runnable);
    }

    @Override
    public void broadcastMessage(final String message) {
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                Bukkit.broadcastMessage(message == null ? "" : message);
            }
        });
    }

    @Override
    public void broadcastRichMessage(final List<ChatPart> parts) {
        executeOnPlatformThread(new Runnable() {
            @Override
            public void run() {
                List<BaseComponent> components = new ArrayList<BaseComponent>();
                if (parts != null) {
                    for (ChatPart part : parts) {
                        if (part == null || part.getText().isEmpty()) {
                            continue;
                        }
                        BaseComponent[] parsed = TextComponent.fromLegacyText(part.getText());
                        for (BaseComponent component : parsed) {
                            if (part.hasClickUrl()) {
                                component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, part.getClickUrl()));
                                String hover = part.getHoverText().isEmpty() ? "点击打开" : part.getHoverText();
                                component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        TextComponent.fromLegacyText("§7" + hover)));
                            }
                            components.add(component);
                        }
                    }
                }
                BaseComponent[] output = components.toArray(new BaseComponent[components.size()]);
                Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
                for (Player player : onlinePlayers) {
                    player.spigot().sendMessage(output);
                }
            }
        });
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
        plugin.getLogger().log(Level.SEVERE, message, throwable);
    }
}
