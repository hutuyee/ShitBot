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
    private static final int MIN_SUPPORTED_FORMAT = 1;
    private static final int MAX_COMPRESSED_BYTES = 512 * 1024;
    private static final int MAX_UNCOMPRESSED_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ITEMS = InventorySnapshot.TOTAL_SLOTS;
    private static final int MAX_COMPONENT_VALUES = 16;

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
            if (!item.getItemModel().isEmpty()) {
                node.put("itemModel", item.getItemModel());
            }
            writeFloats(node, "customModelFloats", item.getCustomModelFloats());
            writeFlags(node, "customModelFlags", item.getCustomModelFlags());
            writeStrings(node, "customModelStrings", item.getCustomModelStrings());
            writeColors(node, "customModelColors", item.getCustomModelColors());
            if (!item.getProfileTextureHash().isEmpty()) {
                node.put("profileTextureHash", item.getProfileTextureHash());
            }
            node.put("enchanted", item.isEnchanted());
            node.put("unbreakable", item.isUnbreakable());
        }

        byte[] json = mapper.writeValueAsBytes(root);
        if (json.length > MAX_UNCOMPRESSED_BYTES) {
            throw new IOException("Inventory snapshot JSON is too large: " + json.length + " bytes");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(json);
            gzip.finish();
        }
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
        int format = root == null ? -1 : root.path("format").asInt(-1);
        if (format < MIN_SUPPORTED_FORMAT || format > InventorySnapshot.FORMAT_VERSION) {
            throw new IOException("Unsupported inventory snapshot format: " + format);
        }

        List<InventorySnapshot.Item> items = new ArrayList<InventorySnapshot.Item>();
        JsonNode itemNodes = root.path("items");
        if (itemNodes.isArray()) {
            int count = 0;
            for (JsonNode node : itemNodes) {
                if (++count > MAX_ITEMS) break;
                int slot = node.path("slot").asInt(-1);
                if (slot < 0 || slot >= InventorySnapshot.TOTAL_SLOTS) continue;
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
                        format >= 2 ? node.path("itemModel").asText("") : "",
                        format >= 2 ? readFloats(node.path("customModelFloats")) : null,
                        format >= 2 ? readFlags(node.path("customModelFlags")) : null,
                        format >= 2 ? readStrings(node.path("customModelStrings")) : null,
                        format >= 2 ? readColors(node.path("customModelColors")) : null,
                        format >= 3 ? node.path("profileTextureHash").asText("") : "",
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

    private void writeFloats(ObjectNode node, String name, List<Float> values) {
        if (values.isEmpty()) return;
        ArrayNode array = node.putArray(name);
        for (Float value : values) array.add(value.floatValue());
    }

    private void writeFlags(ObjectNode node, String name, List<Boolean> values) {
        if (values.isEmpty()) return;
        ArrayNode array = node.putArray(name);
        for (Boolean value : values) array.add(value.booleanValue());
    }

    private void writeStrings(ObjectNode node, String name, List<String> values) {
        if (values.isEmpty()) return;
        ArrayNode array = node.putArray(name);
        for (String value : values) array.add(value);
    }

    private void writeColors(ObjectNode node, String name, List<Integer> values) {
        if (values.isEmpty()) return;
        ArrayNode array = node.putArray(name);
        for (Integer value : values) array.add(value.intValue());
    }

    private List<Float> readFloats(JsonNode node) {
        List<Float> values = new ArrayList<Float>();
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (values.size() >= MAX_COMPONENT_VALUES) break;
                if (value.isNumber()) values.add(Float.valueOf((float) value.asDouble()));
            }
        }
        return values;
    }

    private List<Boolean> readFlags(JsonNode node) {
        List<Boolean> values = new ArrayList<Boolean>();
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (values.size() >= MAX_COMPONENT_VALUES) break;
                if (value.isBoolean()) values.add(Boolean.valueOf(value.asBoolean()));
            }
        }
        return values;
    }

    private List<String> readStrings(JsonNode node) {
        List<String> values = new ArrayList<String>();
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (values.size() >= MAX_COMPONENT_VALUES) break;
                if (value.isTextual()) values.add(value.asText());
            }
        }
        return values;
    }

    private List<Integer> readColors(JsonNode node) {
        List<Integer> values = new ArrayList<Integer>();
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (values.size() >= MAX_COMPONENT_VALUES) break;
                if (value.isNumber()) values.add(Integer.valueOf(value.asInt()));
            }
        }
        return values;
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
