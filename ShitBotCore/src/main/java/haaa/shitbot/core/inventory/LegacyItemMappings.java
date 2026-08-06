package haaa.shitbot.core.inventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Name and data-value compatibility for the pre-flattening Bukkit/vanilla asset names.
 * The resolver deliberately returns several historical spellings because 1.7 texture
 * names, 1.8 model names and 1.13+ registry names are not identical.
 */
final class LegacyItemMappings {
    private static final String[] COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };
    private static final String[] MODERN_COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
    private static final Map<String, String[]> ALIASES;

    static {
        Map<String, String[]> aliases = new LinkedHashMap<String, String[]>();
        put(aliases, "wood", "oak_planks", "planks", "planks_oak");
        put(aliases, "wooden_door", "oak_door", "door_wood");
        put(aliases, "wood_stairs", "oak_stairs");
        put(aliases, "spruce_wood_stairs", "spruce_stairs");
        put(aliases, "birch_wood_stairs", "birch_stairs");
        put(aliases, "jungle_wood_stairs", "jungle_stairs");
        put(aliases, "acacia_stairs", "acacia_stairs");
        put(aliases, "dark_oak_stairs", "dark_oak_stairs");
        put(aliases, "trap_door", "oak_trapdoor", "trapdoor");
        put(aliases, "wood_plate", "oak_pressure_plate", "pressure_plate_wood");
        put(aliases, "stone_plate", "stone_pressure_plate", "pressure_plate_stone");
        put(aliases, "gold_plate", "light_weighted_pressure_plate", "pressure_plate_weighted_light");
        put(aliases, "iron_plate", "heavy_weighted_pressure_plate", "pressure_plate_weighted_heavy");
        put(aliases, "wood_button", "oak_button", "button_wood");
        put(aliases, "stone_button", "stone_button", "button_stone");
        put(aliases, "iron_door_block", "iron_door", "door_iron");
        put(aliases, "piston_base", "piston", "piston_normal");
        put(aliases, "piston_sticky_base", "sticky_piston", "piston_sticky");
        put(aliases, "piston_extension", "piston_head", "piston_top_normal");
        put(aliases, "rails", "rail", "rail_normal");
        put(aliases, "powered_rail", "powered_rail", "rail_golden");
        put(aliases, "detector_rail", "detector_rail", "rail_detector");
        put(aliases, "activator_rail", "activator_rail", "rail_activator");
        put(aliases, "wood_door", "oak_door", "door_wood");
        put(aliases, "fence", "oak_fence", "fence_oak");
        put(aliases, "fence_gate", "oak_fence_gate", "fence_gate_oak");
        put(aliases, "smooth_stairs", "stone_brick_stairs");
        put(aliases, "cobble_wall", "cobblestone_wall");
        put(aliases, "enchantment_table", "enchanting_table");
        put(aliases, "nether_warts", "nether_wart");
        put(aliases, "portal", "nether_portal");
        put(aliases, "cake_block", "cake");
        put(aliases, "flower_pot", "flower_pot");
        put(aliases, "standing_banner", "banner");
        put(aliases, "wall_banner", "banner");
        put(aliases, "double_stone_slab2", "red_sandstone_slab");
        put(aliases, "stone_slab2", "red_sandstone_slab");
        put(aliases, "huge_mushroom_1", "brown_mushroom_block", "mushroom_block_inside");
        put(aliases, "huge_mushroom_2", "red_mushroom_block", "mushroom_block_inside");
        put(aliases, "wood_sword", "wooden_sword");
        put(aliases, "wood_spade", "wooden_shovel", "wood_shovel");
        put(aliases, "wood_pickaxe", "wooden_pickaxe");
        put(aliases, "wood_axe", "wooden_axe");
        put(aliases, "wood_hoe", "wooden_hoe");
        put(aliases, "gold_sword", "golden_sword");
        put(aliases, "gold_spade", "golden_shovel", "gold_shovel");
        put(aliases, "gold_pickaxe", "golden_pickaxe");
        put(aliases, "gold_axe", "golden_axe");
        put(aliases, "gold_hoe", "golden_hoe");
        put(aliases, "gold_helmet", "golden_helmet");
        put(aliases, "gold_chestplate", "golden_chestplate");
        put(aliases, "gold_leggings", "golden_leggings");
        put(aliases, "gold_boots", "golden_boots");
        put(aliases, "iron_spade", "iron_shovel");
        put(aliases, "stone_spade", "stone_shovel");
        put(aliases, "diamond_spade", "diamond_shovel");
        put(aliases, "sulphur", "gunpowder");
        put(aliases, "mushroom_soup", "mushroom_stew");
        put(aliases, "snow_ball", "snowball");
        put(aliases, "watch", "clock");
        put(aliases, "carrot_stick", "carrot_on_a_stick");
        put(aliases, "fireball", "fire_charge");
        put(aliases, "iron_barding", "iron_horse_armor");
        put(aliases, "gold_barding", "golden_horse_armor", "gold_horse_armor");
        put(aliases, "diamond_barding", "diamond_horse_armor");
        put(aliases, "rabbit_stew", "rabbit_stew");
        put(aliases, "item_frame", "item_frame");
        put(aliases, "armor_stand", "armor_stand");
        put(aliases, "banner", "banner");
        put(aliases, "exp_bottle", "experience_bottle");
        put(aliases, "firework", "firework_rocket", "fireworks");
        put(aliases, "firework_charge", "firework_star", "fireworks_charge");
        put(aliases, "book_and_quill", "writable_book", "book_writable");
        put(aliases, "written_book", "written_book", "book_written");
        put(aliases, "mob_spawner", "spawner", "mob_spawner");
        put(aliases, "pork", "porkchop", "porkchop_raw");
        put(aliases, "grilled_pork", "cooked_porkchop", "porkchop_cooked");
        put(aliases, "raw_beef", "beef", "beef_raw");
        put(aliases, "cooked_beef", "cooked_beef", "beef_cooked");
        put(aliases, "raw_chicken", "chicken", "chicken_raw");
        put(aliases, "cooked_chicken", "cooked_chicken", "chicken_cooked");
        put(aliases, "mutton", "mutton", "mutton_raw");
        put(aliases, "cooked_mutton", "cooked_mutton", "mutton_cooked");
        put(aliases, "rabbit", "rabbit", "rabbit_raw");
        put(aliases, "cooked_rabbit", "cooked_rabbit", "rabbit_cooked");
        put(aliases, "clay_brick", "brick");
        put(aliases, "nether_brick_item", "nether_brick");
        put(aliases, "nether_stalk", "nether_wart");
        put(aliases, "seeds", "wheat_seeds", "seeds_wheat");
        put(aliases, "pumpkin_seeds", "pumpkin_seeds", "seeds_pumpkin");
        put(aliases, "melon_seeds", "melon_seeds", "seeds_melon");
        put(aliases, "carrot_item", "carrot");
        put(aliases, "potato_item", "potato");
        put(aliases, "poisonous_potato", "poisonous_potato", "potato_poisonous");
        put(aliases, "speckled_melon", "glistering_melon_slice", "melon_speckled");
        put(aliases, "melon", "melon_slice", "melon");
        put(aliases, "sugar_cane", "sugar_cane", "reeds");
        put(aliases, "sugar_cane_block", "sugar_cane", "reeds");
        put(aliases, "redstone", "redstone", "redstone_dust");
        put(aliases, "diode", "repeater", "repeater_off");
        put(aliases, "diode_block_off", "repeater", "repeater_off");
        put(aliases, "diode_block_on", "repeater", "repeater_on");
        put(aliases, "redstone_comparator_off", "comparator", "comparator_off");
        put(aliases, "redstone_comparator_on", "comparator", "comparator_on");
        put(aliases, "redstone_torch_off", "redstone_torch", "redstone_torch_off");
        put(aliases, "redstone_torch_on", "redstone_torch", "redstone_torch_on");
        put(aliases, "redstone_comparator", "comparator", "comparator_off");
        put(aliases, "eye_of_ender", "ender_eye", "ender_eye");
        put(aliases, "ender_pearl", "ender_pearl");
        put(aliases, "slime_ball", "slime_ball", "slimeball");
        put(aliases, "magma_cream", "magma_cream", "magma_cream");
        put(aliases, "brewing_stand_item", "brewing_stand");
        put(aliases, "cauldron_item", "cauldron");
        put(aliases, "flower_pot_item", "flower_pot");
        put(aliases, "minecart", "minecart", "minecart_normal");
        put(aliases, "storage_minecart", "chest_minecart", "minecart_chest");
        put(aliases, "powered_minecart", "furnace_minecart", "minecart_furnace");
        put(aliases, "explosive_minecart", "tnt_minecart", "minecart_tnt");
        put(aliases, "hopper_minecart", "hopper_minecart", "minecart_hopper");
        put(aliases, "command_minecart", "command_block_minecart", "minecart_command_block");
        put(aliases, "boat", "oak_boat", "boat");
        put(aliases, "sign", "oak_sign", "sign");
        put(aliases, "sign_post", "oak_sign", "sign");
        put(aliases, "wall_sign", "oak_sign", "sign");
        put(aliases, "bed", "red_bed", "bed");
        put(aliases, "bed_block", "red_bed", "bed");
        put(aliases, "skull", "skeleton_skull", "skull_skeleton");
        put(aliases, "skull_item", "skeleton_skull", "skull_skeleton");
        put(aliases, "gold_record", "music_disc_13", "record_13");
        put(aliases, "green_record", "music_disc_cat", "record_cat");
        put(aliases, "record_3", "music_disc_blocks", "record_blocks");
        put(aliases, "record_4", "music_disc_chirp", "record_chirp");
        put(aliases, "record_5", "music_disc_far", "record_far");
        put(aliases, "record_6", "music_disc_mall", "record_mall");
        put(aliases, "record_7", "music_disc_mellohi", "record_mellohi");
        put(aliases, "record_8", "music_disc_stal", "record_stal");
        put(aliases, "record_9", "music_disc_strad", "record_strad");
        put(aliases, "record_10", "music_disc_ward", "record_ward");
        put(aliases, "record_11", "music_disc_11", "record_11");
        put(aliases, "record_12", "music_disc_wait", "record_wait");
        put(aliases, "command", "command_block");
        put(aliases, "workbench", "crafting_table");
        put(aliases, "soil", "farmland");
        put(aliases, "crops", "wheat");
        put(aliases, "stationary_water", "water");
        put(aliases, "stationary_lava", "lava");
        put(aliases, "burning_furnace", "furnace");
        put(aliases, "glowing_redstone_ore", "redstone_ore");
        put(aliases, "redstone_lamp_on", "redstone_lamp");
        put(aliases, "redstone_lamp_off", "redstone_lamp");
        put(aliases, "daylight_detector_inverted", "daylight_detector");
        put(aliases, "hard_clay", "terracotta", "hardened_clay");
        put(aliases, "smooth_brick", "stone_bricks", "stonebrick");
        put(aliases, "thin_glass", "glass_pane");
        put(aliases, "iron_fence", "iron_bars");
        put(aliases, "water_lily", "lily_pad");
        put(aliases, "mycel", "mycelium");
        put(aliases, "ender_stone", "end_stone");
        put(aliases, "ender_portal_frame", "end_portal_frame");
        put(aliases, "ender_portal", "end_portal");
        put(aliases, "jack_o_lantern", "jack_o_lantern", "pumpkin_face_on");
        put(aliases, "web", "cobweb");
        put(aliases, "long_grass", "grass", "tallgrass");
        put(aliases, "dead_bush", "dead_bush", "deadbush");
        put(aliases, "yellow_flower", "dandelion", "flower_dandelion");
        put(aliases, "red_rose", "poppy", "flower_rose");
        put(aliases, "double_plant", "sunflower", "double_plant_sunflower");
        put(aliases, "monster_egg", "spawn_egg", "spawn_egg");
        put(aliases, "monster_eggs", "infested_stone", "stonebrick");
        put(aliases, "empty_map", "map", "map_empty");
        put(aliases, "map", "filled_map", "map_filled");
        put(aliases, "potion", "potion", "potion_bottle_drinkable");
        put(aliases, "glass_bottle", "glass_bottle", "potion_bottle_empty");
        put(aliases, "golden_carrot", "golden_carrot", "carrot_golden");
        put(aliases, "golden_apple", "golden_apple", "apple_golden");
        put(aliases, "enchanted_book", "enchanted_book", "book_enchanted");
        put(aliases, "name_tag", "name_tag", "name_tag");
        put(aliases, "lead", "lead", "lead");
        put(aliases, "leash", "lead", "lead");
        // 1.8-1.12 block texture files use planks_<wood>, while models and
        // flattened registries use <wood>_planks. Keep both directions explicit.
        for (String wood : WOODS) {
            put(aliases, wood + "_planks", "planks_" + wood);
            put(aliases, "planks_" + wood, wood + "_planks");
        }
        ALIASES = Collections.unmodifiableMap(aliases);
    }

    private LegacyItemMappings() {
    }

    static List<String> bareNames(InventorySnapshot.Item item) {
        Set<String> result = new LinkedHashSet<String>();
        String registryPath = path(item.getRegistryId());
        String material = normalize(item.getMaterialName());

        // Data-specific candidates must win over generic pre-flattening names.
        // Otherwise WOOD:2 may stop at a generic minecraft:wood model/texture
        // before reaching birch_planks / blocks/planks_birch.
        addVariants(result, material, item.getLegacyData());
        if (!registryPath.equals(material)) {
            addVariants(result, registryPath, item.getLegacyData());
        }
        add(result, registryPath);
        add(result, material);
        addAliases(result, registryPath);
        addAliases(result, material);
        addAlgorithmicAliases(result, registryPath);
        addAlgorithmicAliases(result, material);

        // Expand aliases introduced by variants as well (for example
        // birch_planks -> planks_birch in 1.8 texture packs).
        List<String> current = new ArrayList<String>(result);
        for (String value : current) {
            addAliases(result, value);
            addAlgorithmicAliases(result, value);
        }
        return new ArrayList<String>(result);
    }

    static List<String> modelPaths(InventorySnapshot.Item item) {
        Set<String> result = new LinkedHashSet<String>();
        for (String name : bareNames(item)) {
            if (name.startsWith("item/") || name.startsWith("block/") || name.startsWith("builtin/")) {
                add(result, name);
            } else {
                add(result, "item/" + name);
                add(result, "block/" + name);
            }
        }
        return new ArrayList<String>(result);
    }

    static List<String> texturePaths(InventorySnapshot.Item item) {
        Set<String> result = new LinkedHashSet<String>();
        for (String name : bareNames(item)) {
            String bare = stripCategory(name);
            add(result, "item/" + bare);
            add(result, "items/" + bare);
            add(result, "block/" + bare);
            add(result, "blocks/" + bare);
            if (name.startsWith("item/") || name.startsWith("items/")
                    || name.startsWith("block/") || name.startsWith("blocks/")) {
                add(result, name);
            }
        }
        return new ArrayList<String>(result);
    }

    static boolean isPlayerHead(InventorySnapshot.Item item) {
        String material = normalize(item.getMaterialName());
        String registryPath = path(item.getRegistryId());
        if ("player_head".equals(registryPath) || "player_wall_head".equals(registryPath)
                || "player_head".equals(material) || "player_wall_head".equals(material)) {
            return true;
        }
        return ("skull_item".equals(material) || "skull".equals(material))
                && item.getLegacyData() == 3;
    }

    static boolean likelyBlock(InventorySnapshot.Item item) {
        String material = normalize(item.getMaterialName());
        if (material.endsWith("_sword") || material.endsWith("_spade") || material.endsWith("_shovel")
                || material.endsWith("_pickaxe") || material.endsWith("_axe") || material.endsWith("_hoe")
                || material.endsWith("_helmet") || material.endsWith("_chestplate")
                || material.endsWith("_leggings") || material.endsWith("_boots")) {
            return false;
        }
        return item.getMaximumDurability() == 0 && (material.contains("block")
                || material.contains("stone") || material.contains("wood") || material.contains("log")
                || material.contains("planks") || material.contains("ore") || material.contains("glass")
                || material.contains("wool") || material.contains("clay") || material.contains("brick")
                || material.contains("sand") || material.contains("dirt") || material.contains("grass")
                || material.contains("leaves") || material.contains("stairs") || material.contains("slab")
                || material.contains("step") || material.contains("fence") || material.contains("door")
                || material.contains("rail") || material.contains("torch") || material.contains("chest")
                || material.contains("furnace") || material.contains("table") || material.contains("anvil")
                || material.contains("carpet") || material.contains("snow") || material.contains("ice")
                || material.contains("pumpkin") || material.contains("melon") || material.contains("mushroom")
                || material.contains("flower") || material.contains("plant") || material.contains("cactus")
                || material.contains("quartz") || material.contains("prismarine") || material.contains("terracotta"));
    }

    private static void addVariants(Set<String> result, String material, int data) {
        int meta = Math.max(0, data);
        if ("stone".equals(material)) {
            String[] modern = {"stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite"};
            String[] old = {"stone", "stone_granite", "stone_granite_smooth", "stone_diorite", "stone_diorite_smooth", "stone_andesite", "stone_andesite_smooth"};
            addAt(result, modern, meta); addAt(result, old, meta);
        } else if ("dirt".equals(material)) {
            addAt(result, new String[]{"dirt", "coarse_dirt", "podzol"}, meta);
        } else if ("sand".equals(material)) {
            addAt(result, new String[]{"sand", "red_sand"}, meta);
        } else if ("sandstone".equals(material)) {
            addAt(result, new String[]{"sandstone", "chiseled_sandstone", "smooth_sandstone"}, meta);
            addAt(result, new String[]{"sandstone_normal", "sandstone_carved", "sandstone_smooth"}, meta);
        } else if ("red_sandstone".equals(material)) {
            addAt(result, new String[]{"red_sandstone", "chiseled_red_sandstone", "smooth_red_sandstone"}, meta);
            addAt(result, new String[]{"red_sandstone_normal", "red_sandstone_carved", "red_sandstone_smooth"}, meta);
        } else if ("wood".equals(material) || "wood_step".equals(material) || "wood_double_step".equals(material)) {
            int index = meta & 7;
            if (index < WOODS.length) {
                add(result, WOODS[index] + "_planks");
                add(result, "planks_" + WOODS[index]);
            }
        } else if ("sapling".equals(material)) {
            int index = meta & 7;
            if (index < WOODS.length) {
                add(result, WOODS[index] + "_sapling");
                add(result, "sapling_" + WOODS[index]);
            }
        } else if ("log".equals(material) || "leaves".equals(material)) {
            String[] woods = {"oak", "spruce", "birch", "jungle"};
            int index = meta & 3;
            String type = material.equals("log") ? "log" : "leaves";
            add(result, woods[index] + "_" + type);
            add(result, type + "_" + woods[index]);
        } else if ("log_2".equals(material) || "leaves_2".equals(material)) {
            String wood = (meta & 1) == 0 ? "acacia" : "dark_oak";
            String type = material.equals("log_2") ? "log" : "leaves";
            add(result, wood + "_" + type);
            add(result, type + "_" + wood);
        } else if ("wool".equals(material) || "carpet".equals(material)
                || "stained_glass".equals(material) || "stained_glass_pane".equals(material)
                || "stained_clay".equals(material)) {
            int index = meta & 15;
            String modernColor = MODERN_COLORS[index];
            String oldColor = COLORS[index];
            if ("wool".equals(material)) {
                add(result, modernColor + "_wool"); add(result, "wool_colored_" + oldColor);
            } else if ("carpet".equals(material)) {
                add(result, modernColor + "_carpet"); add(result, "carpet_" + oldColor);
            } else if ("stained_glass".equals(material)) {
                add(result, modernColor + "_stained_glass"); add(result, "glass_" + oldColor);
            } else if ("stained_glass_pane".equals(material)) {
                add(result, modernColor + "_stained_glass_pane"); add(result, "glass_pane_" + oldColor);
            } else {
                add(result, modernColor + "_terracotta"); add(result, "hardened_clay_stained_" + oldColor);
            }
        } else if ("ink_sack".equals(material)) {
            String[] modern = {"ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli", "purple_dye",
                    "cyan_dye", "light_gray_dye", "gray_dye", "pink_dye", "lime_dye", "yellow_dye",
                    "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal"};
            String[] old = {"dye_powder_black", "dye_powder_red", "dye_powder_green", "dye_powder_brown",
                    "dye_powder_blue", "dye_powder_purple", "dye_powder_cyan", "dye_powder_silver",
                    "dye_powder_gray", "dye_powder_pink", "dye_powder_lime", "dye_powder_yellow",
                    "dye_powder_light_blue", "dye_powder_magenta", "dye_powder_orange", "dye_powder_white"};
            addAt(result, modern, meta & 15); addAt(result, old, meta & 15);
        } else if ("coal".equals(material)) {
            add(result, (meta & 1) == 1 ? "charcoal" : "coal");
        } else if ("raw_fish".equals(material)) {
            addAt(result, new String[]{"cod", "salmon", "tropical_fish", "pufferfish"}, meta & 3);
            addAt(result, new String[]{"fish_cod_raw", "fish_salmon_raw", "fish_clownfish_raw", "fish_pufferfish_raw"}, meta & 3);
        } else if ("cooked_fish".equals(material)) {
            int index = Math.min(1, meta & 3);
            addAt(result, new String[]{"cooked_cod", "cooked_salmon"}, index);
            addAt(result, new String[]{"fish_cod_cooked", "fish_salmon_cooked"}, index);
        } else if ("skull_item".equals(material) || "skull".equals(material)) {
            String[] modern = {"skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head", "creeper_head", "dragon_head"};
            String[] old = {"skull_skeleton", "skull_wither", "skull_zombie", "skull_steve", "skull_creeper", "skull_dragon"};
            addAt(result, modern, Math.min(meta, modern.length - 1));
            addAt(result, old, Math.min(meta, old.length - 1));
        } else if ("step".equals(material) || "double_step".equals(material)) {
            int index = meta & 7;
            String[] modern = {"stone_slab", "sandstone_slab", "petrified_oak_slab", "cobblestone_slab",
                    "brick_slab", "stone_brick_slab", "nether_brick_slab", "quartz_slab"};
            String[] old = {"stone_slab", "sandstone_slab", "wooden_slab", "cobblestone_slab",
                    "brick_slab", "stone_brick_slab", "nether_brick_slab", "quartz_slab"};
            addAt(result, modern, index); addAt(result, old, index);
        } else if ("monster_eggs".equals(material)) {
            int index = Math.min(meta, 5);
            addAt(result, new String[]{"infested_stone", "infested_cobblestone", "infested_stone_bricks",
                    "infested_mossy_stone_bricks", "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks"}, index);
            addAt(result, new String[]{"stone", "cobblestone", "stonebrick", "stonebrick_mossy",
                    "stonebrick_cracked", "stonebrick_carved"}, index);
        } else if ("smooth_brick".equals(material)) {
            addAt(result, new String[]{"stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks"}, meta & 3);
            addAt(result, new String[]{"stonebrick", "stonebrick_mossy", "stonebrick_cracked", "stonebrick_carved"}, meta & 3);
        } else if ("prismarine".equals(material)) {
            addAt(result, new String[]{"prismarine", "prismarine_bricks", "dark_prismarine"}, Math.min(meta, 2));
            addAt(result, new String[]{"prismarine_rough", "prismarine_bricks", "prismarine_dark"}, Math.min(meta, 2));
        } else if ("quartz_block".equals(material)) {
            addAt(result, new String[]{"quartz_block", "chiseled_quartz_block", "quartz_pillar"}, Math.min(meta, 2));
            addAt(result, new String[]{"quartz_block_side", "quartz_block_chiseled", "quartz_block_lines"}, Math.min(meta, 2));
        } else if ("anvil".equals(material)) {
            int state = (meta >> 2) & 3;
            addAt(result, new String[]{"anvil", "chipped_anvil", "damaged_anvil"}, Math.min(state, 2));
            addAt(result, new String[]{"anvil_base", "anvil_base", "anvil_base"}, Math.min(state, 2));
        } else if ("potion".equals(material)) {
            boolean splash = (meta & 0x4000) != 0;
            add(result, splash ? "splash_potion" : "potion");
            add(result, splash ? "potion_bottle_splash" : "potion_bottle_drinkable");
        } else if ("stone_slab2".equals(material) || "double_stone_slab2".equals(material)) {
            add(result, "red_sandstone_slab");
            add(result, "red_sandstone_normal");
        } else if ("golden_apple".equals(material)) {
            add(result, (meta & 1) == 1 ? "enchanted_golden_apple" : "golden_apple");
            add(result, (meta & 1) == 1 ? "apple_golden_overlay" : "apple_golden");
        } else if ("long_grass".equals(material)) {
            addAt(result, new String[]{"dead_bush", "grass", "fern"}, Math.min(meta, 2));
            addAt(result, new String[]{"deadbush", "tallgrass", "fern"}, Math.min(meta, 2));
        } else if ("red_rose".equals(material)) {
            String[] flowers = {"poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
                    "white_tulip", "pink_tulip", "oxeye_daisy"};
            String[] old = {"flower_rose", "flower_blue_orchid", "flower_allium", "flower_houstonia",
                    "flower_tulip_red", "flower_tulip_orange", "flower_tulip_white", "flower_tulip_pink", "flower_oxeye_daisy"};
            addAt(result, flowers, Math.min(meta, flowers.length - 1));
            addAt(result, old, Math.min(meta, old.length - 1));
        } else if ("double_plant".equals(material)) {
            String[] plants = {"sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony"};
            String[] old = {"double_plant_sunflower", "double_plant_syringa", "double_plant_grass",
                    "double_plant_fern", "double_plant_rose", "double_plant_paeonia"};
            addAt(result, plants, Math.min(meta & 7, plants.length - 1));
            addAt(result, old, Math.min(meta & 7, old.length - 1));
        }
    }

    private static void addAlgorithmicAliases(Set<String> result, String value) {
        if (value == null || value.isEmpty()) return;
        if (value.startsWith("legacy_")) add(result, value.substring("legacy_".length()));
        if (value.startsWith("wood_")) add(result, "wooden_" + value.substring("wood_".length()));
        if (value.startsWith("gold_")) add(result, "golden_" + value.substring("gold_".length()));
        if (value.endsWith("_spade")) add(result, value.substring(0, value.length() - 6) + "_shovel");
        if (value.endsWith("_item")) add(result, value.substring(0, value.length() - 5));
        if (value.endsWith("_block")) add(result, value.substring(0, value.length() - 6));
    }

    private static void addAliases(Set<String> result, String value) {
        String[] aliases = ALIASES.get(value);
        if (aliases != null) result.addAll(Arrays.asList(aliases));
    }

    private static String path(String identifier) {
        if (identifier == null) return "";
        int colon = identifier.indexOf(':');
        return normalize(colon < 0 ? identifier : identifier.substring(colon + 1));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String clean = value.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("legacy_")) clean = clean.substring("legacy_".length());
        return clean.replaceAll("[^a-z0-9_./-]", "_");
    }

    private static String stripCategory(String value) {
        if (value.startsWith("item/")) return value.substring(5);
        if (value.startsWith("items/")) return value.substring(6);
        if (value.startsWith("block/")) return value.substring(6);
        if (value.startsWith("blocks/")) return value.substring(7);
        return value;
    }

    private static void addAt(Set<String> result, String[] values, int index) {
        if (index >= 0 && index < values.length) add(result, values[index]);
    }

    private static void add(Set<String> result, String value) {
        String clean = normalize(value);
        if (!clean.isEmpty() && !clean.contains("..")) result.add(clean);
    }

    private static void put(Map<String, String[]> map, String key, String... values) {
        map.put(key, values);
    }
}
