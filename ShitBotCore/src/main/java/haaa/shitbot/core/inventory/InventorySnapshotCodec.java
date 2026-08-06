package haaa.shitbot.core.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Versioned and bounded GZIP/JSON persistence codec for inventory snapshots. */
public final class InventorySnapshotCodec {
    private static final int MAX_COMPRESSED_BYTES = 512 * 1024;
    private static final int MAX_UNCOMPRESSED_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ITEMS = InventorySnapshot.TOTAL_SLOTS;

    private final ObjectMapper mapper = new ObjectMapper();

    public byte[] encode(InventorySnapshot snapshot) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("format", InventorySnapshot.FORMAT_VERSION);
        root.put("playerName", snapshot.getPlayerName());
        root.put("playerUuid", snapshot.getPlayerUuid());
        root.put("serverName", snapshot.getServerName());
        root.put("capturedAt", snapshot.getCapturedAt());
        ArrayNode items = root.putArray("items");
        for (InventorySnapshot.Item item : snapshot.getItems()) {
            ObjectNode node = items.addObject();
            node.put("slot", item.getSlot());
            node.put("registryId", item.getRegistryId());
            node.put("materialName", item.getMaterialName());
            node.put("displayName", item.getDisplayName());
            node.put("amount", item.getAmount());
            node.put("damage", item.getDamage());
            node.put("maximumDurability", item.getMaximumDurability());
            if (item.getCustomModelData() != null) {
                node.put("customModelData", item.getCustomModelData().intValue());
            }
            node.put("enchanted", item.isEnchanted());
            node.put("unbreakable", item.isUnbreakable());
        }

        byte[] json = mapper.writeValueAsBytes(root);
        if (json.length > MAX_UNCOMPRESSED_BYTES) {
            throw new IOException("Inventory snapshot JSON is too large: " + json.length + " bytes");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        GZIPOutputStream gzip = new GZIPOutputStream(output);
        gzip.write(json);
        gzip.finish();
        gzip.close();
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Inventory snapshot is too large: " + encoded.length + " bytes");
        }
        return encoded;
    }

    public InventorySnapshot decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Invalid inventory snapshot payload length");
        }
        byte[] json;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            json = readBounded(gzip, MAX_UNCOMPRESSED_BYTES);
        }
        JsonNode root = mapper.readTree(json);
        if (root == null || root.path("format").asInt(-1) != InventorySnapshot.FORMAT_VERSION) {
            throw new IOException("Unsupported inventory snapshot format");
        }

        List<InventorySnapshot.Item> items = new ArrayList<InventorySnapshot.Item>();
        JsonNode itemNodes = root.path("items");
        if (itemNodes.isArray()) {
            int count = 0;
            for (JsonNode node : itemNodes) {
                if (++count > MAX_ITEMS) {
                    break;
                }
                int slot = node.path("slot").asInt(-1);
                if (slot < 0 || slot >= InventorySnapshot.TOTAL_SLOTS) {
                    continue;
                }
                JsonNode customModelNode = node.get("customModelData");
                Integer customModelData = customModelNode == null || !customModelNode.isNumber()
                        ? null : Integer.valueOf(customModelNode.asInt());
                items.add(new InventorySnapshot.Item(
                        slot,
                        node.path("registryId").asText(""),
                        node.path("materialName").asText("UNKNOWN"),
                        node.path("displayName").asText(""),
                        node.path("amount").asInt(1),
                        node.path("damage").asInt(0),
                        node.path("maximumDurability").asInt(0),
                        customModelData,
                        node.path("enchanted").asBoolean(false),
                        node.path("unbreakable").asBoolean(false)));
            }
        }
        return new InventorySnapshot(
                root.path("playerName").asText("unknown"),
                root.path("playerUuid").asText(""),
                root.path("serverName").asText("unknown"),
                root.path("capturedAt").asLong(0L),
                items);
    }

    private byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new IOException("Inventory snapshot expands beyond the configured safety limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
