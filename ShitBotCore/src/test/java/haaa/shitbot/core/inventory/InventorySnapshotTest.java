package haaa.shitbot.core.inventory;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class InventorySnapshotTest {
    @Test
    public void normalizesSlotsAndKeepsTheLastItemForDuplicates() {
        InventorySnapshot.Item first = item(0, 1, 0, 0);
        InventorySnapshot.Item replacement = item(0, 2, 0, 0);
        InventorySnapshot.Item offhand = item(40, 3, 0, 0);
        List<InventorySnapshot.Item> items = new ArrayList<InventorySnapshot.Item>(
                Arrays.asList(offhand, first, null, item(-1, 1, 0, 0), item(41, 1, 0, 0), replacement));
        InventorySnapshot snapshot = new InventorySnapshot("Steve", "", "server", 1, items);
        items.clear();
        assertEquals(Arrays.asList(replacement, offhand), snapshot.getItems());
        assertSame(replacement, snapshot.getItem(0));
        assertEquals(2, snapshot.getOccupiedSlots());
        assertEquals(5, snapshot.getTotalItemCount());
        assertNull(snapshot.getItem(-1));
        assertNull(snapshot.getItem(41));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getItems().clear());
    }

    @Test
    public void clampsAmountsAndDurabilityWhilePreservingLegacyData() {
        assertEquals(1, item(0, -3, 0, 0).getAmount());
        assertEquals(1_000_000, item(0, Integer.MAX_VALUE, 0, 0).getAmount());
        InventorySnapshot.Item damaged = item(0, 1, 200, 100);
        assertEquals(100, damaged.getDamage());
        assertTrue(damaged.hasDurability());
        assertEquals(0, damaged.getLegacyData());
        InventorySnapshot.Item legacy = item(0, 1, 14, 0);
        assertEquals(14, legacy.getLegacyData());
        assertFalse(legacy.hasDurability());
    }

    @Test
    public void rejectsUnsafeModelPathsAndTextureHashes() {
        InventorySnapshot.Item item = new InventorySnapshot.Item(0, "../unsafe", "DIAMOND_SWORD", "", 1, 0, 100,
                null, "custom:../escape", null, null, null, null, "https://example.invalid/skin", false, true);
        assertEquals("minecraft:diamond_sword", item.getRegistryId());
        assertEquals("Diamond Sword", item.getDisplayName());
        assertEquals("", item.getItemModel());
        assertEquals("", item.getProfileTextureHash());
        assertFalse(item.hasDurability());
    }

    @Test
    public void componentsAreCopiedFilteredAndBounded() {
        List<Float> floats = new ArrayList<Float>(Arrays.asList(null, Float.NaN, Float.POSITIVE_INFINITY, 2.5F));
        List<String> strings = new ArrayList<String>(Collections.nCopies(20, " label "));
        InventorySnapshot.Item item = new InventorySnapshot.Item(0, "minecraft:stone", "STONE", "Stone", 1, 0, 0,
                7, "", floats, Arrays.asList(null, true), strings, Arrays.asList(null, 255), false, false);
        floats.clear();
        strings.clear();
        assertEquals(Collections.singletonList(2.5F), item.getCustomModelFloats());
        assertEquals(Collections.singletonList(true), item.getCustomModelFlags());
        assertEquals(Collections.nCopies(16, "label"), item.getCustomModelStrings());
        assertEquals(Collections.singletonList(255), item.getCustomModelColors());
        assertEquals(2.5F, item.getCustomModelFloat(0, -1), 0.0F);
        assertEquals(-1.0F, item.getCustomModelFloat(1, -1), 0.0F);
        assertThrows(UnsupportedOperationException.class, () -> item.getCustomModelFloats().add(9.0F));
    }

    @Test
    public void iconCacheIgnoresStackSizeButDistinguishesLegacyVariants() {
        assertEquals(item(0, 1, 0, 0).iconCacheKey(), item(5, 64, 0, 0).iconCacheKey());
        assertNotEquals(item(0, 1, 0, 0).iconCacheKey(), item(0, 1, 14, 0).iconCacheKey());
    }

    private static InventorySnapshot.Item item(int slot, int amount, int damage, int durability) {
        return new InventorySnapshot.Item(slot, "minecraft:stone", "STONE", "Stone", amount,
                damage, durability, null, false, false);
    }
}
