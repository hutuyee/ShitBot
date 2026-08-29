package haaa.shitbot.core.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import haaa.shitbot.core.util.JsonUtil;

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

    public byte[] encode(InventorySnapshot snapshot) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", InventorySnapshot.FORMAT_VERSION);
        root.addProperty("playerName", snapshot.getPlayerName());
        root.addProperty("playerUuid", snapshot.getPlayerUuid());
        root.addProperty("serverName", snapshot.getServerName());
        root.addProperty("capturedAt", snapshot.getCapturedAt());
        JsonArray items = new JsonArray();
        root.add("items", items);
        for (InventorySnapshot.Item item : snapshot.getItems()) {
            JsonObject node = new JsonObject();
            items.add(node);
            node.addProperty("slot", item.getSlot());
            node.addProperty("registryId", item.getRegistryId());
            node.addProperty("materialName", item.getMaterialName());
            node.addProperty("displayName", item.getDisplayName());
            node.addProperty("amount", item.getAmount());
            node.addProperty("damage", item.getDamage());
            node.addProperty("maximumDurability", item.getMaximumDurability());
            if (item.getCustomModelData() != null) {
                node.addProperty("customModelData", item.getCustomModelData().intValue());
            }
            if (!item.getItemModel().isEmpty()) {
                node.addProperty("itemModel", item.getItemModel());
            }
            writeFloats(node, "customModelFloats", item.getCustomModelFloats());
            writeFlags(node, "customModelFlags", item.getCustomModelFlags());
            writeStrings(node, "customModelStrings", item.getCustomModelStrings());
            writeColors(node, "customModelColors", item.getCustomModelColors());
            if (!item.getProfileTextureHash().isEmpty()) {
                node.addProperty("profileTextureHash", item.getProfileTextureHash());
            }
            node.addProperty("enchanted", item.isEnchanted());
            node.addProperty("unbreakable", item.isUnbreakable());
        }

        byte[] json = JsonUtil.toBytes(root);
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
        JsonElement parsed = JsonUtil.parse(json);
        JsonObject root = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        int format = JsonUtil.integer(root, "format", -1);
        if (format < MIN_SUPPORTED_FORMAT || format > InventorySnapshot.FORMAT_VERSION) {
            throw new IOException("Unsupported inventory snapshot format: " + format);
        }

        List<InventorySnapshot.Item> items = new ArrayList<InventorySnapshot.Item>();
        JsonElement itemNodes = JsonUtil.get(root, "items");
        if (itemNodes != null && itemNodes.isJsonArray()) {
            int count = 0;
            for (JsonElement node : itemNodes.getAsJsonArray()) {
                if (++count > MAX_ITEMS) break;
                int slot = JsonUtil.integer(node, "slot", -1);
                if (slot < 0 || slot >= InventorySnapshot.TOTAL_SLOTS) continue;
                JsonElement customModelNode = JsonUtil.get(node, "customModelData");
                Integer customModelData = !JsonUtil.isNumber(customModelNode)
                        ? null : Integer.valueOf(JsonUtil.integer(customModelNode, 0));
                items.add(new InventorySnapshot.Item(
                        slot,
                        JsonUtil.string(node, "registryId", ""),
                        JsonUtil.string(node, "materialName", "UNKNOWN"),
                        JsonUtil.string(node, "displayName", ""),
                        JsonUtil.integer(node, "amount", 1),
                        JsonUtil.integer(node, "damage", 0),
                        JsonUtil.integer(node, "maximumDurability", 0),
                        customModelData,
                        format >= 2 ? JsonUtil.string(node, "itemModel", "") : "",
                        format >= 2 ? readFloats(JsonUtil.get(node, "customModelFloats")) : null,
                        format >= 2 ? readFlags(JsonUtil.get(node, "customModelFlags")) : null,
                        format >= 2 ? readStrings(JsonUtil.get(node, "customModelStrings")) : null,
                        format >= 2 ? readColors(JsonUtil.get(node, "customModelColors")) : null,
                        format >= 3 ? JsonUtil.string(node, "profileTextureHash", "") : "",
                        JsonUtil.booleanValue(node, "enchanted", false),
                        JsonUtil.booleanValue(node, "unbreakable", false)));
            }
        }
        return new InventorySnapshot(
                JsonUtil.string(root, "playerName", "unknown"),
                JsonUtil.string(root, "playerUuid", ""),
                JsonUtil.string(root, "serverName", "unknown"),
                JsonUtil.longValue(root, "capturedAt", 0L),
                items);
    }

    private void writeFloats(JsonObject node, String name, List<Float> values) {
        if (values.isEmpty()) return;
        JsonArray array = new JsonArray();
        node.add(name, array);
        for (Float value : values) array.add(new JsonPrimitive(value));
    }

    private void writeFlags(JsonObject node, String name, List<Boolean> values) {
        if (values.isEmpty()) return;
        JsonArray array = new JsonArray();
        node.add(name, array);
        for (Boolean value : values) array.add(new JsonPrimitive(value));
    }

    private void writeStrings(JsonObject node, String name, List<String> values) {
        if (values.isEmpty()) return;
        JsonArray array = new JsonArray();
        node.add(name, array);
        for (String value : values) array.add(new JsonPrimitive(value));
    }

    private void writeColors(JsonObject node, String name, List<Integer> values) {
        if (values.isEmpty()) return;
        JsonArray array = new JsonArray();
        node.add(name, array);
        for (Integer value : values) array.add(new JsonPrimitive(value));
    }

    private List<Float> readFloats(JsonElement node) {
        List<Float> values = new ArrayList<Float>();
        if (node != null && node.isJsonArray()) {
            for (JsonElement value : node.getAsJsonArray()) {
                if (values.size() >= MAX_COMPONENT_VALUES) break;
                if (JsonUtil.isNumber(value)) {
                    values.add(Float.valueOf((float) JsonUtil.doubleValue(value, 0.0D)));
                }
            }
        }
        return values;
    }

    private List<Boolean> readFlags(JsonElement node) {
        List<Boolean> values = new ArrayList<Boolean>();
        if (node != null && node.isJsonArray()) {
            for (JsonElement value : node.getAsJsonArray()) {
                if (values.size() >= MAX_COMPONENT_VALUES) break;
                if (JsonUtil.isBoolean(value)) {
                    values.add(Boolean.valueOf(JsonUtil.booleanValue(value, false)));
                }
            }
        }
        return values;
    }

    private List<String> readStrings(JsonElement node) {
        List<String> values = new ArrayList<String>();
        if (node != null && node.isJsonArray()) {
            for (JsonElement value : node.getAsJsonArray()) {
                if (values.size() >= MAX_COMPONENT_VALUES) break;
                if (JsonUtil.isString(value)) values.add(JsonUtil.string(value, ""));
            }
        }
        return values;
    }

    private List<Integer> readColors(JsonElement node) {
        List<Integer> values = new ArrayList<Integer>();
        if (node != null && node.isJsonArray()) {
            for (JsonElement value : node.getAsJsonArray()) {
                if (values.size() >= MAX_COMPONENT_VALUES) break;
                if (JsonUtil.isNumber(value)) {
                    values.add(Integer.valueOf(JsonUtil.integer(value, 0)));
                }
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
