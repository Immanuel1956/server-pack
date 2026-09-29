#!/usr/bin/env python3
"""
Writes src/main/resources/features/sell/prices.yml: a sell price for every item a survival
player can get, worked out from the vanilla recipes.

    python3 tools/gen_prices.py <mcmeta data-json checkout> <mcmeta summary checkout>

(git clone --branch 1.21.10-data-json / 1.21.10-summary https://github.com/misode/mcmeta)

How prices are found:
  * RAW below: things that are mined, farmed, fished or dropped, set by hand.
  * Everything else: the cheapest recipe that makes it, from the ingredients' prices divided by
    how many it makes. Prices are rounded down at every step, so a crafted item never sells for
    more than what went into it: crafting can't make money. Smelting adds 25% (fuel and time).
  * A raw price is lowered when a recipe makes the item for less (crafting it and selling it
    would otherwise make money).
  * UNOBTAINABLE items get no price at all.
"""
import glob
import json
import math
import os
import sys

# ── Raw prices (one item) ──────────────────────────────────────────────────
RAW = {
    # Farming
    "wheat": 4, "wheat_seeds": 0.5, "carrot": 3, "potato": 3, "poisonous_potato": 1, "beetroot": 4,
    "beetroot_seeds": 0.5, "melon_slice": 1, "pumpkin": 8, "sugar_cane": 3, "cactus": 3, "bamboo": 1,
    "kelp": 1, "cocoa_beans": 3, "nether_wart": 5, "sweet_berries": 2, "glow_berries": 3, "apple": 5,
    "chorus_fruit": 6, "brown_mushroom": 4, "red_mushroom": 4, "egg": 2, "blue_egg": 2, "brown_egg": 2,
    "honeycomb": 6, "honey_bottle": 8, "torchflower_seeds": 10, "pitcher_pod": 10, "torchflower": 12,
    "pitcher_plant": 12, "sniffer_egg": 200, "turtle_egg": 20, "turtle_scute": 30, "armadillo_scute": 15,
    "crimson_fungus": 2, "warped_fungus": 2, "crimson_roots": 0.5, "warped_roots": 0.5,
    "nether_sprouts": 0.5, "twisting_vines": 1, "weeping_vines": 1, "shroomlight": 5,
    "warped_wart_block": 3, "chorus_flower": 10, "sea_pickle": 2, "lily_pad": 1, "vine": 0.5,
    "glow_lichen": 0.5, "hanging_roots": 0.5, "spore_blossom": 20, "big_dripleaf": 2, "small_dripleaf": 2,
    "azalea": 2, "flowering_azalea": 3, "moss_block": 1, "pale_moss_block": 1, "pale_hanging_moss": 0.5,
    "creaking_heart": 50, "resin_clump": 3, "mangrove_propagule": 1, "mangrove_roots": 1,
    "dead_bush": 0.5, "short_grass": 0.25, "tall_grass": 0.5, "fern": 0.25, "large_fern": 0.5,
    "seagrass": 0.25, "bush": 0.25, "firefly_bush": 3, "short_dry_grass": 0.25, "tall_dry_grass": 0.5,
    "leaf_litter": 0.25, "pink_petals": 1, "wildflowers": 1, "cactus_flower": 2,
    "closed_eyeblossom": 2, "open_eyeblossom": 2, "wither_rose": 20, "sunflower": 2, "lilac": 2,
    "rose_bush": 2, "peony": 2, "dandelion": 1, "poppy": 1, "blue_orchid": 1, "allium": 1,
    "azure_bluet": 1, "red_tulip": 1, "orange_tulip": 1, "white_tulip": 1, "pink_tulip": 1,
    "oxeye_daisy": 1, "cornflower": 1, "lily_of_the_valley": 1, "dried_ghast": 100,
    # Mob drops and fishing
    "rotten_flesh": 1, "bone": 3, "string": 3, "spider_eye": 4, "gunpowder": 6, "ender_pearl": 20,
    "blaze_rod": 25, "ghast_tear": 40, "slime_ball": 8, "magma_cream": 10, "phantom_membrane": 15,
    "leather": 5, "feather": 2, "rabbit_hide": 3, "rabbit_foot": 20, "ink_sac": 3, "glow_ink_sac": 6,
    "prismarine_shard": 5, "prismarine_crystals": 6, "shulker_shell": 60, "beef": 4, "porkchop": 4,
    "chicken": 3, "mutton": 3, "rabbit": 3, "cod": 3, "salmon": 4, "pufferfish": 5, "tropical_fish": 5,
    "nautilus_shell": 40, "heart_of_the_sea": 500, "breeze_rod": 30, "echo_shard": 60,
    "totem_of_undying": 400, "trident": 300, "nether_star": 5000, "dragon_breath": 50,
    "dragon_egg": 20000, "elytra": 3000, "heavy_core": 1500, "wither_skeleton_skull": 300,
    "skeleton_skull": 50, "zombie_head": 50, "creeper_head": 80, "piglin_head": 80, "dragon_head": 1000,
    "goat_horn": 60, "ochre_froglight": 15, "verdant_froglight": 15, "pearlescent_froglight": 15,
    "cobweb": 3, "sponge": 30, "wet_sponge": 30, "enchanted_golden_apple": 2000, "experience_bottle": 20,
    "trial_key": 50, "ominous_trial_key": 150, "ominous_bottle": 50, "disc_fragment_5": 15,
    "iron_horse_armor": 60, "golden_horse_armor": 80, "diamond_horse_armor": 300,
    "chainmail_helmet": 20, "chainmail_chestplate": 32, "chainmail_leggings": 28, "chainmail_boots": 16,
    "name_tag": 40, "bell": 100, "saddle": 50, "enchanted_book": 50, "potion": 5, "splash_potion": 8,
    "lingering_potion": 10, "tipped_arrow": 3, "written_book": 5, "filled_map": 5, "firework_rocket": 2,
    "firework_star": 3, "milk_bucket": 32, "water_bucket": 31, "lava_bucket": 35,
    "powder_snow_bucket": 31, "cod_bucket": 35, "salmon_bucket": 36, "pufferfish_bucket": 37,
    "tropical_fish_bucket": 37, "axolotl_bucket": 60, "tadpole_bucket": 35, "bee_nest": 30,
    "sculk": 1, "sculk_vein": 0.5, "sculk_catalyst": 30, "sculk_sensor": 20, "sculk_shrieker": 30,
    "suspicious_stew": 8, "flow_banner_pattern": 50, "guster_banner_pattern": 50,
    "globe_banner_pattern": 50, "piglin_banner_pattern": 50, "netherite_upgrade_smithing_template": 500,
    "copper_golem_statue": 40,
    # Ores and minerals
    "coal": 4, "raw_iron": 8, "iron_ingot": 10, "raw_copper": 3, "copper_ingot": 4, "raw_gold": 12,
    "gold_ingot": 15, "redstone": 3, "lapis_lazuli": 4, "quartz": 5, "amethyst_shard": 6, "diamond": 100,
    "emerald": 60, "netherite_scrap": 400, "ancient_debris": 350, "flint": 1, "glowstone_dust": 2,
    "coal_ore": 4, "deepslate_coal_ore": 4, "iron_ore": 8, "deepslate_iron_ore": 8, "copper_ore": 3,
    "deepslate_copper_ore": 3, "gold_ore": 12, "deepslate_gold_ore": 12, "redstone_ore": 12,
    "deepslate_redstone_ore": 12, "lapis_ore": 20, "deepslate_lapis_ore": 20, "diamond_ore": 100,
    "deepslate_diamond_ore": 100, "emerald_ore": 60, "deepslate_emerald_ore": 60, "nether_gold_ore": 12,
    "nether_quartz_ore": 5, "amethyst_cluster": 8, "small_amethyst_bud": 2, "medium_amethyst_bud": 3,
    "large_amethyst_bud": 4, "raw_iron_block": 72, "raw_copper_block": 27, "raw_gold_block": 108,
    "gilded_blackstone": 10, "magma_block": 2, "glowstone": 6,
    # Blocks
    "cobblestone": 0.5, "stone": 1, "granite": 0.5, "diorite": 0.5, "andesite": 0.5, "deepslate": 0.5,
    "cobbled_deepslate": 0.5, "tuff": 0.5, "calcite": 1, "pointed_dripstone": 0.5, "basalt": 0.5,
    "blackstone": 0.5, "netherrack": 0.25, "soul_sand": 0.5, "soul_soil": 0.5, "end_stone": 1,
    "obsidian": 15, "crying_obsidian": 20, "sand": 0.5, "red_sand": 0.5, "gravel": 0.5, "clay_ball": 1,
    "dirt": 0.25, "grass_block": 0.5, "podzol": 0.5, "mycelium": 1, "rooted_dirt": 0.5, "mud": 0.25,
    "snowball": 0.1, "ice": 0.5, "prismarine": 20, "sea_lantern": 30, "terracotta": 1.5,
    "white_terracotta": 1.5, "orange_terracotta": 1.5, "yellow_terracotta": 1.5, "brown_terracotta": 1.5,
    "red_terracotta": 1.5, "light_gray_terracotta": 1.5, "crimson_nylium": 0.5, "warped_nylium": 0.5,
    "smooth_basalt": 0.5, "packed_ice": 4.5, "blue_ice": 40, "clay": 4, "nether_wart_block": 5,
    "bone_block": 9, "dripstone_block": 2, "reinforced_deepslate": 0,
    "tube_coral": 3, "brain_coral": 3, "bubble_coral": 3, "fire_coral": 3, "horn_coral": 3,
    "tube_coral_fan": 3, "brain_coral_fan": 3, "bubble_coral_fan": 3, "fire_coral_fan": 3, "horn_coral_fan": 3,
    "tube_coral_block": 3, "brain_coral_block": 3, "bubble_coral_block": 3, "fire_coral_block": 3, "horn_coral_block": 3,
    "dead_tube_coral": 1, "dead_brain_coral": 1, "dead_bubble_coral": 1, "dead_fire_coral": 1, "dead_horn_coral": 1,
    "dead_tube_coral_fan": 1, "dead_brain_coral_fan": 1, "dead_bubble_coral_fan": 1, "dead_fire_coral_fan": 1,
    "dead_horn_coral_fan": 1, "dead_tube_coral_block": 1, "dead_brain_coral_block": 1,
    "dead_bubble_coral_block": 1, "dead_fire_coral_block": 1, "dead_horn_coral_block": 1,
    "white_wool": 3, "brown_mushroom_block": 1, "red_mushroom_block": 1, "mushroom_stem": 1,
    "carved_pumpkin": 8, "copper_horse_armor": 30,
}

# Woods: logs, stripped logs, wood, saplings and leaves.
for wood in ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"]:
    for suffix, price in [("_log", 2), ("_wood", 2), ("_sapling", 1), ("_leaves", 0.5)]:
        RAW.setdefault(wood + suffix, price)
        RAW.setdefault("stripped_" + wood + suffix, price) if suffix in ("_log", "_wood") else None
RAW.pop("mangrove_sapling", None)
for stem in ["crimson", "warped"]:
    for suffix in ["_stem", "_hyphae"]:
        RAW[stem + suffix] = 2
        RAW["stripped_" + stem + suffix] = 2
RAW["stripped_bamboo_block"] = 9
RAW["azalea_leaves"] = 0.5
RAW["flowering_azalea_leaves"] = 1

# Copper that aged (exposed/weathered/oxidized) is worth what fresh copper is.
AGED = ["exposed_", "weathered_", "oxidized_"]
COPPER_THINGS = ["copper", "cut_copper", "cut_copper_stairs", "cut_copper_slab", "chiseled_copper",
                 "copper_door", "copper_trapdoor", "copper_grate", "copper_bulb", "copper_chest",
                 "copper_golem_statue", "copper_lantern", "copper_bars", "copper_chain", "lightning_rod"]


def aged_alias(item):
    """exposed_copper -> copper_block (what it was before it aged), or None."""
    for a in AGED:
        for w in ("", "waxed_"):
            if item.startswith(w + a):
                base = item[len(w + a):]
                if base in COPPER_THINGS:
                    return (w + ("copper_block" if base == "copper" else base))
    return None

# Pottery sherds, smithing templates and music discs: found in structures.
EXTRA_PATTERNS = [("_pottery_sherd", 30), ("_armor_trim_smithing_template", 100), ("music_disc_", 100)]

# Not obtainable in survival: no price.
UNOBTAINABLE = {
    "air", "bedrock", "barrier", "light", "structure_block", "structure_void", "jigsaw", "command_block",
    "chain_command_block", "repeating_command_block", "command_block_minecart", "debug_stick",
    "knowledge_book", "spawner", "trial_spawner", "vault", "end_portal_frame", "reinforced_deepslate",
    "budding_amethyst", "petrified_oak_slab", "player_head", "farmland", "dirt_path", "frogspawn",
    "chorus_plant", "test_block", "test_instance_block", "suspicious_sand", "suspicious_gravel",
}
UNOBTAINABLE_PATTERNS = ["_spawn_egg", "infested_"]

# Items a recipe gives back (the bucket of a milk bucket).
REMAINDER = {"milk_bucket": "bucket", "water_bucket": "bucket", "lava_bucket": "bucket",
             "powder_snow_bucket": "bucket", "honey_bottle": "glass_bottle", "dragon_breath": "glass_bottle"}

COOKING = {"minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking"}


def unobtainable(item):
    return item in UNOBTAINABLE or any(p in item for p in UNOBTAINABLE_PATTERNS)


def floor2(v):
    return math.floor(v * 100 + 1e-9) / 100


def main():
    data, summary = sys.argv[1], sys.argv[2]
    items = [i for i in json.load(open(os.path.join(summary, "registries/data.min.json")))["item"]]
    tags = {}
    for f in glob.glob(os.path.join(data, "data/minecraft/tags/item/**/*.json"), recursive=True):
        name = os.path.relpath(f, os.path.join(data, "data/minecraft/tags/item"))[:-5]
        tags[name] = json.load(open(f))["values"]

    def expand(ref, seen=None):
        seen = seen or set()
        ref = ref if isinstance(ref, str) else ref.get("id", ref.get("item", ref.get("tag")))
        if ref.startswith("#"):
            name = ref[1:].replace("minecraft:", "")
            if name in seen:
                return []
            seen.add(name)
            out = []
            for v in tags.get(name, []):
                out += expand(v if isinstance(v, str) else v["id"], seen)
            return out
        return [ref.replace("minecraft:", "")]

    def options(ing):
        if isinstance(ing, list):
            out = []
            for i in ing:
                out += options(i)
            return out
        if isinstance(ing, dict):
            if "tag" in ing:
                return expand("#" + ing["tag"])
            return expand(ing.get("item", ing.get("id")))
        return expand(ing)

    recipes = []  # (result, count, [ingredient option lists], cooking)
    for f in glob.glob(os.path.join(data, "data/minecraft/recipe/*.json")):
        r = json.load(open(f))
        t = r["type"]
        res = r.get("result")
        if not isinstance(res, dict) or "id" not in res:
            continue
        result = res["id"].replace("minecraft:", "")
        count = res.get("count", 1)
        if t == "minecraft:crafting_shaped":
            ings = []
            for row in r["pattern"]:
                for ch in row:
                    if ch != " ":
                        ings.append(options(r["key"][ch]))
        elif t == "minecraft:crafting_shapeless":
            ings = [options(i) for i in r["ingredients"]]
        elif t == "minecraft:crafting_transmute":
            ings = [options(r["input"]), options(r["material"])]
        elif t in COOKING or t == "minecraft:stonecutting":
            ings = [options(r["ingredient"])]
        elif t == "minecraft:smithing_transform":
            ings = [options(r["template"]), options(r["base"]), options(r["addition"])]
        else:
            continue
        recipes.append((result, count, ings, t in COOKING))

    raw = {k: v for k, v in RAW.items() if v > 0}
    for item in items:
        for pattern, price in EXTRA_PATTERNS:
            if pattern in item and item not in raw:
                raw[item] = price
    prices = dict(raw)
    lowered = {}
    for _ in range(100):
        changed = False
        for result, count, ings, cooking in recipes:
            if unobtainable(result):
                continue
            total = 0
            ok = True
            for opts in ings:
                known = [prices[o] - (prices.get(REMAINDER[o], 0) if o in REMAINDER else 0)
                         for o in opts if o in prices and not unobtainable(o)]
                if not known:
                    ok = False
                    break
                total += min(known)
            if not ok:
                continue
            each = floor2(total * (1.25 if cooking else 1) / count)
            if each <= 0:
                each = 0.01
            # Only a real saving counts: rounding down makes round trips (ingot -> 9 nuggets ->
            # ingot) a cent cheaper each time, which is not a cheaper way to make it.
            if result not in prices or each < prices[result] * 0.98:
                if result in raw:
                    lowered[result] = (raw[result], each)
                prices[result] = each
                changed = True
        # Aged copper follows its fresh block, concrete its powder (water hardens it), worn
        # anvils a share of a new one.
        for item in items:
            alias, share = aged_alias(item), 1
            if alias is None and item.endswith("_concrete"):
                alias = item + "_powder"
            if item == "chipped_anvil":
                alias, share = "anvil", 0.66
            if item == "damaged_anvil":
                alias, share = "anvil", 0.33
            if alias and alias in prices and prices.get(item) != floor2(prices[alias] * share):
                prices[item] = floor2(prices[alias] * share)
                changed = True
        if not changed:
            break

    missing = [i for i in items if not unobtainable(i) and i not in prices]
    for item, (was, now) in sorted(lowered.items()):
        print(f"lowered {item}: {was} -> {now} (a recipe makes it for less)", file=sys.stderr)
    if missing:
        print("NO PRICE: " + ", ".join(sorted(missing)), file=sys.stderr)

    out = os.path.join(os.path.dirname(__file__), "..", "src/main/resources/features/sell/prices.yml")
    hand = sorted(i for i in prices if i in raw and i not in lowered and i in items)
    made = sorted(i for i in prices if i not in hand and i in items)

    def fmt(v):
        return str(int(v)) if v == int(v) else f"{v:.2f}".rstrip("0").rstrip(".")

    with open(out, "w", encoding="utf-8") as w:
        w.write("""# Sell prices: what ONE item sells for (a stack of 64 carrots at 3 = 192).
#
# Every item a survival player can get has a price. Items that aren't listed can't be sold.
#   Add an item:     a new line, ITEM_NAME: price   (or /sell price <amount> holding it)
#   Take one out:    delete its line or set it to 0 (or /sell price remove holding it)
#   /vexcore reload (or the /sell price command) applies changes.
#
# The first part is set by hand (mined, farmed, fished, dropped). The second part was worked out
# from the vanilla recipes: a crafted item sells for what its ingredients sell for, never more,
# so crafting can't be used to make money. Change any price you like; if you raise a raw price,
# consider raising what is crafted from it too.
#
# Items listed under unobtainable: in config.yml can never be sold, whatever this file says.

# ── Mined, farmed, fished and dropped ──────────────────────────────────────
""")
        for i in hand:
            w.write(f"{i.upper()}: {fmt(prices[i])}\n")
        w.write("\n# ── Crafted, smelted and cut (from the recipes) ────────────────────────────\n")
        for i in made:
            w.write(f"{i.upper()}: {fmt(prices[i])}\n")
    print(f"{out}: {len(hand)} set by hand, {len(made)} from recipes, {len(missing)} without a price",
          file=sys.stderr)


if __name__ == "__main__":
    main()
