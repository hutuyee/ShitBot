package haaa.shitbot.core.inventory;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

public class InventorySnapshotCodecTest {
    private final InventorySnapshotCodec codec = new InventorySnapshotCodec();

    @Test
    public void currentFormatPreservesComponentsTexturesAndOffhand() throws Exception {
        String texture = "0123456789abcdef0123456789abcdef";
        InventorySnapshot.Item item = new InventorySnapshot.Item(40, "minecraft:diamond_sword", "DIAMOND_SWORD",
                "测试剑😀", 1, 12, 1561, 42, "custom:sword", Arrays.asList(1.5F, 2.0F),
                Arrays.asList(true, false), Arrays.asList("blue", "红色"), Arrays.asList(0xff0000, 0x00ff00),
                texture, true, false);
        InventorySnapshot original = new InventorySnapshot("Steve", "00000000-0000-0000-0000-000000000001",
                "survival", 123456L, Collections.singletonList(item));

        InventorySnapshot decoded = codec.decode(codec.encode(original));
        assertEquals(original.getPlayerName(), decoded.getPlayerName());
        assertEquals(original.getPlayerUuid(), decoded.getPlayerUuid());
        assertEquals("survival", decoded.getServerName());
        assertEquals(123456L, decoded.getCapturedAt());
        assertEquals(1, decoded.getOccupiedSlots());
        InventorySnapshot.Item actual = decoded.getItem(40);
        assertNotNull(actual);
        assertEquals("minecraft:diamond_sword", actual.getRegistryId());
        assertEquals("DIAMOND_SWORD", actual.getMaterialName());
        assertEquals("测试剑😀", actual.getDisplayName());
        assertEquals(1, actual.getAmount());
        assertEquals(12, actual.getDamage());
        assertEquals(1561, actual.getMaximumDurability());
        assertEquals(Integer.valueOf(42), actual.getCustomModelData());
        assertEquals("custom:sword", actual.getItemModel());
        assertEquals(item.getCustomModelFloats(), actual.getCustomModelFloats());
        assertEquals(item.getCustomModelFlags(), actual.getCustomModelFlags());
        assertEquals(item.getCustomModelStrings(), actual.getCustomModelStrings());
        assertEquals(item.getCustomModelColors(), actual.getCustomModelColors());
        assertEquals(texture, actual.getProfileTextureHash());
        assertTrue(actual.isEnchanted());
        assertFalse(actual.isUnbreakable());
    }

    @Test
    public void versionOneRetainsLegacyDataAndIgnoresNewerComponents() throws Exception {
        InventorySnapshot decoded = codec.decode(gzip("{\"format\":1,\"playerName\":\"Alex\",\"items\":[{"
                + "\"slot\":0,\"registryId\":\"minecraft:wool\",\"materialName\":\"WOOL\",\"amount\":32,"
                + "\"damage\":14,\"customModelData\":17,\"itemModel\":\"custom:ignored\",\"customModelFloats\":[9]}]}"));
        InventorySnapshot.Item item = decoded.getItem(0);
        assertEquals("Alex", decoded.getPlayerName());
        assertEquals(32, item.getAmount());
        assertEquals(14, item.getLegacyData());
        assertFalse(item.hasDurability());
        assertEquals(Integer.valueOf(17), item.getCustomModelData());
        assertEquals(17.0F, item.getCustomModelFloat(0, -1), 0.0F);
        assertEquals("", item.getItemModel());
        assertTrue(item.getCustomModelFloats().isEmpty());
    }

    @Test
    public void versionTwoReadsComponentsWithoutAcceptingVersionThreeTextures() throws Exception {
        InventorySnapshot decoded = codec.decode(gzip("{\"format\":2,\"items\":[{\"slot\":39,"
                + "\"itemModel\":\"custom:hat\",\"customModelFloats\":[2.5],\"customModelFlags\":[true],"
                + "\"profileTextureHash\":\"0123456789abcdef0123456789abcdef\"}]}"));
        InventorySnapshot.Item item = decoded.getItem(39);
        assertEquals("custom:hat", item.getItemModel());
        assertEquals(2.5F, item.getCustomModelFloat(0, -1), 0.0F);
        assertTrue(item.getCustomModelFlag(0, false));
        assertEquals("", item.getProfileTextureHash());
    }

    @Test
    public void emptyInventoriesRoundTrip() throws Exception {
        InventorySnapshot empty = new InventorySnapshot("Steve", "", "lobby", 0, null);
        InventorySnapshot decoded = codec.decode(codec.encode(empty));
        assertTrue(decoded.getItems().isEmpty());
        assertEquals(0, decoded.getTotalItemCount());
        assertNull(decoded.getItem(0));
    }

    @Test
    public void rejectsUnsupportedVersionsAndNonObjectDocuments() {
        for (String json : new String[] {"{}", "[]", "null", "{\"format\":0}", "{\"format\":4}"}) {
            IOException error = assertThrows(IOException.class, () -> codec.decode(gzip(json)));
            assertTrue(error.getMessage().startsWith("Unsupported inventory snapshot format:"));
        }
    }

    @Test
    public void rejectsMissingOversizedAndCorruptCompressedPayloads() throws Exception {
        assertThrows(IOException.class, () -> codec.decode(null));
        assertThrows(IOException.class, () -> codec.decode(new byte[0]));
        assertThrows(IOException.class, () -> codec.decode(new byte[512 * 1024 + 1]));
        assertThrows(IOException.class, () -> codec.decode(new byte[] {1, 2, 3}));
        byte[] valid = gzip("{\"format\":3}");
        assertThrows(IOException.class, () -> codec.decode(Arrays.copyOf(valid, valid.length - 4)));
    }

    @Test
    public void rejectsSmallPayloadsThatExpandBeyondTheSafetyLimit() throws Exception {
        char[] repeated = new char[2 * 1024 * 1024 + 1];
        Arrays.fill(repeated, ' ');
        byte[] payload = gzip(new String(repeated));
        assertTrue(payload.length < 512 * 1024);
        IOException error = assertThrows(IOException.class, () -> codec.decode(payload));
        assertTrue(error.getMessage().contains("expands beyond"));
    }

    @Test
    public void invalidSlotsAreIgnoredAndComponentTypesAreFiltered() throws Exception {
        InventorySnapshot decoded = codec.decode(gzip("{\"format\":3,\"items\":[null,{\"slot\":-1},{\"slot\":41},"
                + "{\"slot\":0,\"customModelFloats\":[1,\"bad\",null,2],\"customModelFlags\":[true,1,null,false],"
                + "\"customModelStrings\":[\"blue\",1,null],\"customModelColors\":[255,\"bad\",null]}]}"));
        assertEquals(1, decoded.getOccupiedSlots());
        InventorySnapshot.Item item = decoded.getItem(0);
        assertEquals(Arrays.asList(1.0F, 2.0F), item.getCustomModelFloats());
        assertEquals(Arrays.asList(true, false), item.getCustomModelFlags());
        assertEquals(Collections.singletonList("blue"), item.getCustomModelStrings());
        assertEquals(Collections.singletonList(255), item.getCustomModelColors());
    }

    private static byte[] gzip(String json) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
