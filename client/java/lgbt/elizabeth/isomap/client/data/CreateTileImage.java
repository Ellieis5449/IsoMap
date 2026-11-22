package lgbt.elizabeth.isomap.client.data;

import com.mojang.blaze3d.platform.NativeImage;
import lgbt.elizabeth.isomap.client.config.IsoMapConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class CreateTileImage {

    private static final int COLS = 50;
    private static final int TILE = 4;

    private static final int FRONT = 40;
    private static final int SIDE = 20;
    private static final int POT_COLOR = 0xFF7A4B3A;
    private static final int BANNER_STAND_COLOR = 0xFF554328;

    private static int waterAlpha() {
        return IsoMapConfig.waterTint & 0xFF;
    }


    private static final int GLASS_ALPHA = 0x70;

    private static final int LEAVES_ALPHA = 0xA0;
    private static final int PLANT_ALPHA = 0xC0;
    private static final int PLANT_SHADE = 0;

    private static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("redstone_wire", "redstone_dust_dot"),
            Map.entry("campfire", "campfire_log"),
            Map.entry("soul_campfire", "campfire_log"),
            Map.entry("water", "water_still"),
            Map.entry("lava", "lava_still"),
            Map.entry("fire", "fire_0"),
            Map.entry("soul_fire", "soul_fire_0"),
            Map.entry("chest", "oak_planks"),
            Map.entry("trapped_chest", "oak_planks"),
            Map.entry("ender_chest", "obsidian"),
            Map.entry("bamboo", "bamboo_stalk"),
            Map.entry("azalea_bush", "azalea_plant"),
            Map.entry("flowering_azalea_bush", "flowering_azalea_side"),
            Map.entry("pitcher_plant", "pitcher_crop_top_stage_4"),
            Map.entry("decorated_pot", "terracotta"),
            Map.entry("pointed_dripstone", "pointed_dripstone_up_tip"),
            Map.entry("shulker_box", "purple_concrete"),
            Map.entry("suspicious_sand", "suspicious_sand_0"),
            Map.entry("suspicious_gravel", "suspicious_gravel_0"),
            Map.entry("frosted_ice", "frosted_ice_0"),
            Map.entry("snow_block", "snow"),
            Map.entry("magma_block", "magma"),
            Map.entry("dried_kelp_block", "dried_kelp"),
            Map.entry("sticky_piston", "piston"),
            Map.entry("dispenser", "furnace"),
            Map.entry("dropper", "furnace"),
            Map.entry("water_cauldron", "cauldron"),
            Map.entry("lava_cauldron", "cauldron"),
            Map.entry("powder_snow_cauldron", "cauldron"),
            Map.entry("copper", "copper_block"),
            Map.entry("infested_stone", "stone"),
            Map.entry("infested_cobblestone", "cobblestone"),
            Map.entry("infested_stone_bricks", "stone_bricks"),
            Map.entry("infested_mossy_stone_bricks", "mossy_stone_bricks"),
            Map.entry("infested_cracked_stone_bricks", "cracked_stone_bricks"),
            Map.entry("infested_chiseled_stone_bricks", "chiseled_stone_bricks"),
            Map.entry("infested_deepslate", "deepslate")
    );

    public static void createIndex(ResourceManager rm) throws IOException {
        Map<String, Integer> ids = OrganizeData.getBlockIds();
        if (ids.isEmpty()) throw new IllegalStateException("Block ids not loaded; call loadBlockIds first");

        int maxId = ids.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        int rows = (maxId + 1 + COLS - 1) / COLS;
        Sheet sheet = new Sheet(COLS, rows);

        for (Block block : BuiltInRegistries.BLOCK) {
            String key = BuiltInRegistries.BLOCK.getKey(block).toString();
            Integer id = ids.get(key);
            if (id == null) continue;

            sheet.moveTo(id % COLS, id / COLS);
            drawBlock(sheet, rm, block);

            if (sheet.isBlank()) {
                System.out.println("empty tile: " + id + " " + key);
            }
        }

        BufferedImage img = new BufferedImage(COLS * TILE, rows * TILE, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                img.setRGB(x, y, sheet.pixels[x][y]);
            }
        }

        Path tileset = FabricLoader.getInstance().getConfigDir().resolve("IsoMap/Tileset/IsoMap_TileSet.png");
        Files.createDirectories(tileset.getParent());
        ImageIO.write(img, "png", tileset.toFile());
    }

    //Drawing

    private static void drawBlock(Sheet s, ResourceManager rm, Block block) {
        String name = BuiltInRegistries.BLOCK.getKey(block).getPath();

        NativeImage tex = loadBlockTexture(rm, getBaseBlock(block));
        boolean drawn = drawShape(s, block, name, tex);
        close(tex);

        if (!drawn) drawOwnTexture(s, rm, block, name);
        s.applyAlpha(alphaFor(block, name));
    }

    private static boolean drawShape(Sheet s, Block block, String name, NativeImage tex) {
        if (block instanceof SlabBlock) s.slab(tex);
        else if (block instanceof StairBlock) s.cube(tex);
        else if (block instanceof WallBlock) s.wall(tex);
        else if (block instanceof DoorBlock) s.door(tex);
        else if (block instanceof FenceBlock) s.fence(tex);
        else if (block instanceof FenceGateBlock) s.fenceGate(tex);
        else if (block instanceof TrapDoorBlock
                || block instanceof PressurePlateBlock
                || block instanceof WeightedPressurePlateBlock
                || block instanceof CarpetBlock || name.endsWith("_carpet")
                || block instanceof BaseRailBlock
                || block instanceof SnowLayerBlock) s.face(tex, 1, 2, 2, 2, 0);
        else if (block instanceof BannerBlock || block instanceof WallBannerBlock) {
            s.face(tex, 1, 0, 2, 3, 2, 2, 1, 0);
            if (block instanceof BannerBlock) s.put(2, 3, BANNER_STAND_COLOR);
        }
        else if (block instanceof WallTorchBlock) s.face(tex, 1, 0, 1, 2, 7, 7, 1, 0);
        else if (block instanceof BaseTorchBlock) s.face(tex, 1, 1, 1, 2, 7, 7, 1, 0);
        else if (block instanceof ButtonBlock) s.face(tex, 1, 1, 1, 1, 1, 1, 1, 0);
        else if (block instanceof LeverBlock) s.face(tex, 1, 0, 1, 1, 7, 7, 1, 0);
        else if (block instanceof LadderBlock || block instanceof EndRodBlock) s.face(tex, 1, 2, 2, 2, FRONT);
        else if (block instanceof RedStoneWireBlock) s.redstoneWire(tex);
        else if (block instanceof RepeaterBlock || block instanceof ComparatorBlock) s.face(tex, 1, 2, 2, 2, 7, 7, 1, 0);
        else if (block instanceof IronBarsBlock) s.fillAverage(tex, 1, 1, 2, 2, 0);
        else if (block instanceof ChainBlock) s.fillAverage(tex, 1, 1, 1, 2, 0);
        else if (block instanceof LanternBlock) s.lantern(tex);
        else if (block instanceof CandleBlock) s.face(tex, 1, 2, 1, 2, 0, 7, 1, 0);
        else if (block instanceof CakeBlock || block instanceof CandleCakeBlock) drawCake(s, tex);
        else if (block instanceof LightningRodBlock) s.face(tex, 1, 2, 1, 2, 0, 7, 1, 0);
        else if (block instanceof CampfireBlock) s.slab(tex);
        else if (block instanceof AnvilBlock) s.cube(tex);
        else if (block instanceof IronBarsBlock) {
            s.fillAverage(tex, 1, 1, 2, 2, 0);
            if (name.contains("bars")) s.put(2, 2, 0);
        }
        else if (block instanceof FlowerPotBlock) {
            s.fill(1, 2, 1, 1, POT_COLOR);
            if (!name.equals("flower_pot")) s.fill(1, 1, 1, 1, averageColor(tex));
        }
        else if (block instanceof WallSignBlock) s.face(tex, 1, 1, 2, 1, 0, 7, 1, 0);
        else if (block instanceof StandingSignBlock) {
            s.face(tex, 1, 2, 2, 2, 0, 7, 1, 0);
            s.put(2, 3, 0);
        }
        else if (block instanceof WallHangingSignBlock || block instanceof CeilingHangingSignBlock) {
            s.face(tex, 1, 1, 2, 1, 0, 7, 1, 0);
        }
        else return false;
        return true;
    }

    private static void drawOwnTexture(Sheet s, ResourceManager rm, Block block, String name) {
        NativeImage tex = loadEntityTexture(rm, block);
        if (tex == null) tex = loadBlockTexture(rm, block);
        if (tex == null) tex = loadBlockTexture(rm, getBaseBlock(block));
        if (tex == null) return;

        try {
            if (block instanceof AbstractSkullBlock) {
                int scale = Math.max(1, tex.getWidth() / 64);
                s.face(tex, 2, 2, 1, 2, 8 * scale, 8 * scale, 4 * scale, 0);
            } else if (block instanceof BedBlock) {
                s.slab(tex);
            } else if (name.equals("bamboo")) {
                s.fillAverage(tex, 1, 1, 1, 2, PLANT_SHADE);
            } else if (isPlant(block)) {
                s.fillAverage(tex, 1, 2, 2, 2, PLANT_SHADE);
                s.put(2, 2, 0);
            } else if (block instanceof CactusBlock) {
                s.cube(tex);
            } else if (block instanceof KelpBlock) {
                s.fillAverage(tex, 1, 1, 2, 2, FRONT);
                s.put(2, 2, 0);
            } else if (block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock) {
                s.flatCube(averageColor(tex));
            } else if (block instanceof PointedDripstoneBlock || block instanceof AmethystClusterBlock || name.contains("spike")) {
                int c = averageColor(tex);
                s.put(1, 1, c);
                s.put(1, 2, darken(c, SIDE));
            } else {
                drawGeneric(s, rm, block, name, tex);
            }
        } finally {
            tex.close();
        }
    }

    private static void drawGeneric(Sheet s, ResourceManager rm, Block block, String name, NativeImage tex) {
        NativeImage top = loadBlockTopTexture(rm, block);
        boolean sparse = isSparse(tex) || (top != null && isSparse(top));

        if (name.contains("glass") || name.endsWith("_leaves")) {
            s.flatCube(averageColor(tex));
        } else if (name.equals("grass_block") || name.equals("dirt_path")
                || name.equals("podzol") || name.equals("mycelium")) {
            NativeImage dirt = readTexture(rm, "minecraft", "textures/block/dirt.png");
            s.cube(top != null ? top : tex, dirt != null ? dirt : tex);
            close(dirt);
        } else if (sparse) {
            s.fillAverage(tex, 1, 2, 2, 2, PLANT_SHADE);
            s.put(2, 2, 0);
        } else {
            s.cube(top != null ? top : tex, tex);
        }

        if (name.contains("glass")) {
            s.flatCube(averageColor(tex));
        } else if (name.endsWith("_leaves")) {
            s.flatCube(averageColor(tex));
            s.put(2, 2, 0);
        }

        close(top);
    }

    private static void drawCake(Sheet s, NativeImage side) {
        int c = averageColor(side);
        s.fill(1, 2, 2, 2, darken(c, FRONT));
        s.fill(0, 2, 1, 1, darken(c, SIDE));
        s.fill(3, 2, 1, 1, darken(c, SIDE));
        s.fill(1, 1, 2, 2, c);
    }

    //Tile canvas

    private static final class Sheet {
        final int[][] pixels;
        private int originX, originY;

        Sheet(int cols, int rows) {
            pixels = new int[cols * TILE][rows * TILE];
        }

        void moveTo(int tx, int ty) {
            originX = tx * TILE;
            originY = ty * TILE;
        }

        void put(int x, int y, int argb) {
            pixels[originX + x][originY + y] = argb;
        }

        void fill(int x, int y, int w, int h, int argb) {
            for (int iy = 0; iy < h; iy++)
                for (int ix = 0; ix < w; ix++)
                    put(x + ix, y + iy, argb);
        }

        @SuppressWarnings("SameParameterValue")
        void fillAverage(NativeImage tex, int x, int y, int w, int h, int shade) {
            fill(x, y, w, h, darken(averageColor(tex), shade));
        }

        void face(NativeImage tex, int x, int y, int w, int h, int sx, int sy, int step, int shade) {
            if (tex == null) return;
            for (int iy = 0; iy < h; iy++)
                for (int ix = 0; ix < w; ix++)
                    put(x + ix, y + iy, darken(tex.getPixel(sx + ix * step, sy + iy * step), shade));
        }

        void face(NativeImage tex, int x, int y, int w, int h, int shade) {
            face(tex, x, y, w, h, 0, 0, 1, shade);
        }

        void flatCube(int c) {
            fill(1, 2, 2, 2, darken(c, FRONT));
            fill(0, 1, 1, 2, darken(c, SIDE));
            fill(3, 1, 1, 2, darken(c, SIDE));
            fill(1, 0, 2, 2, c);
        }

        void applyAlpha(int alpha) {
            if (alpha >= 255) return;
            for (int x = 0; x < TILE; x++) {
                for (int y = 0; y < TILE; y++) {
                    int c = pixels[originX + x][originY + y];
                    int a = (c >>> 24) * alpha / 255;
                    pixels[originX + x][originY + y] = (a << 24) | (c & 0xFFFFFF);
                }
            }
        }

        boolean isBlank() {
            for (int x = 0; x < TILE; x++)
                for (int y = 0; y < TILE; y++)
                    if ((pixels[originX + x][originY + y] >>> 24) != 0) return false;
            return true;
        }

        void cube(NativeImage top, NativeImage side) {
            face(side, 1, 2, 2, 2, FRONT);
            face(side, 0, 1, 1, 2, SIDE);
            face(side, 3, 1, 1, 2, SIDE);
            face(top, 1, 0, 2, 2, 0);
        }

        void cube(NativeImage tex) {
            cube(tex, tex);
        }

        void slab(NativeImage tex) {
            face(tex, 1, 2, 2, 2, FRONT);
            face(tex, 0, 2, 1, 1, SIDE);
            face(tex, 3, 2, 1, 1, SIDE);
            face(tex, 1, 1, 2, 2, 0);
        }

        void wall(NativeImage tex) {
            face(tex, 1, 1, 2, 2, FRONT);
            face(tex, 0, 2, 1, 1, SIDE);
            face(tex, 3, 2, 1, 1, SIDE);
            face(tex, 1, 1, 2, 1, 0);
        }

        void door(NativeImage tex) {
            face(tex, 1, 1, 2, 2, 0);
            face(tex, 1, 2, 2, 2, FRONT);
            face(tex, 0, 2, 1, 2, SIDE);
            face(tex, 3, 2, 1, 1, SIDE);
        }

        void lantern(NativeImage tex) {
            face(tex, 1, 2, 2, 2, FRONT);
            face(tex, 1, 1, 2, 2, 0);
            face(tex, 0, 2, 1, 2, SIDE);
            face(tex, 3, 2, 1, 1, SIDE);
        }

        void fence(NativeImage tex) {
            face(tex, 1, 1, 2, 2, FRONT);
            put(2, 2, 0);
            face(tex, 0, 1, 1, 1, SIDE);
            face(tex, 3, 1, 1, 2, SIDE);
        }

        void fenceGate(NativeImage tex) {
            face(tex, 1, 1, 2, 2, FRONT);
            face(tex, 0, 1, 1, 2, SIDE);
            face(tex, 3, 1, 1, 2, SIDE);
        }

        void redstoneWire(NativeImage tex) {
            if (tex == null) return;
            for (int iy = 0; iy < 2; iy++) {
                for (int ix = 0; ix < 2; ix++) {
                    int c = tex.getPixel(7 + ix, 7 + iy);
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    if (r == g && g == b) c = (c & 0xFF000000) | (r << 16);
                    put(1 + ix, 2 + iy, c);
                }
            }
        }
    }

    //Textures

    private static NativeImage readTexture(ResourceManager rm, String namespace, String path) {
        Optional<Resource> res = rm.getResource(Identifier.fromNamespaceAndPath(namespace, path));
        if (res.isEmpty()) return null;
        try (InputStream in = res.get().open()) {
            return NativeImage.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static NativeImage loadBlockTexture(ResourceManager rm, Block block) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);

        String name = key.getPath()
                .replaceFirst("^waxed_", "")
                .replaceFirst("^potted_", "")
                .replace("wall_torch", "torch")
                .replaceFirst("_pane$", "")
                .replaceFirst("_bed$", "_wool")
                .replaceFirst("_shulker_box$", "_concrete")
                .replaceFirst("_wood$", "_log")
                .replaceFirst("_hyphae$", "_stem")
                .replaceFirst("^(\\w+_)?candle_cake$", "cake")
                .replaceFirst("_wall_fan$", "_fan")
                .replaceFirst("^(chipped|damaged)_anvil$", "anvil")
                .replaceFirst("_golem_statue$", "");
        name = ALIAS.getOrDefault(name, name);
        String plain = name.replaceFirst("^smooth_", "");

        String[] tries = { name, name + "_side", name + "_top", name + "_bottom",
                plain + "_top", plain + "_block_bottom",
                name + "_stage7", name + "_stage3", name + "_stage2", name + "_stage1" };
        for (String t : tries) {
            NativeImage img = readTexture(rm, key.getNamespace(), "textures/block/" + t + ".png");
            if (img != null) return img;
        }
        return null;
    }

    private static NativeImage loadBlockTopTexture(ResourceManager rm, Block block) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        String name = ALIAS.getOrDefault(key.getPath(), key.getPath());
        return readTexture(rm, key.getNamespace(), "textures/block/" + name + "_top.png");
    }

    private static NativeImage loadEntityTexture(ResourceManager rm, Block block) {
        String name = BuiltInRegistries.BLOCK.getKey(block).getPath();

        String path = switch (block) {
            case ShulkerBoxBlock _ -> name.equals("shulker_box")
                    ? "shulker/shulker"
                    : "shulker/shulker_" + name.replace("_shulker_box", "");
            case EnderChestBlock _ -> "chest/ender";
            case ChestBlock _ -> name.startsWith("trapped") ? "chest/trapped" : "chest/normal";
            case AbstractSkullBlock _ -> skullTexture(name);
            default -> null;
        };

        return path == null ? null : readTexture(rm, "minecraft", "textures/entity/" + path + ".png");
    }

    private static String skullTexture(String name) {
        if (name.startsWith("wither_skeleton")) return "skeleton/wither_skeleton";
        if (name.startsWith("skeleton")) return "skeleton/skeleton";
        if (name.startsWith("zombie")) return "zombie/zombie";
        if (name.startsWith("creeper")) return "creeper/creeper";
        if (name.startsWith("piglin")) return "piglin/piglin";
        if (name.startsWith("player")) return "player/wide/steve";
        if (name.startsWith("dragon")) return "enderdragon/dragon";
        return null;
    }

    static Block getBaseBlock(Block block) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        String stem = baseStem(key.getPath());

        for (String n : new String[] { stem + "_planks", stem, stem + "s", stem + "_block", stem + "_wool" }) {
            Block b = BuiltInRegistries.BLOCK
                    .getOptional(Identifier.fromNamespaceAndPath(key.getNamespace(), n))
                    .orElse(null);
            if (b != null) return b;
        }
        return block;
    }

    private static String baseStem(String name) {
        name = switch (name) {
            case "petrified_oak_slab" -> "oak_planks_slab";
            case "light_weighted_pressure_plate" -> "gold_block_slab";
            case "heavy_weighted_pressure_plate" -> "iron_block_slab";
            default -> name;
        };

        return name.replaceAll(
                "_(stairs|slab|wall|pressure_plate|fence_gate|fence|button|carpet"
                        + "|wall_hanging_sign|hanging_sign|wall_sign|sign|wall_banner|banner)$", "");
    }

    private static void close(NativeImage img) {
        if (img != null) img.close();
    }


    //Colors
    static int darken(int argb, int percent) {
        int a = argb & 0xFF000000;
        int r = (((argb >> 16) & 0xFF) * (100 - percent)) / 100;
        int g = (((argb >> 8) & 0xFF) * (100 - percent)) / 100;
        int b = ((argb & 0xFF) * (100 - percent)) / 100;
        return a | (r << 16) | (g << 8) | b;
    }

    private static int alphaFor(Block block, String name) {
        if (name.equals("water")) return waterAlpha();
        if (name.contains("glass")) return GLASS_ALPHA;
        if (name.endsWith("_leaves")) return LEAVES_ALPHA;
        if (isPlant(block)) return PLANT_ALPHA;
        return 255;
    }

    private static boolean isPlant(Block block) {
        return block instanceof VegetationBlock || block instanceof VineBlock || block instanceof SugarCaneBlock;
    }

    private static int averageColor(NativeImage img) {
        if (img == null) return 0;
        int w = img.getWidth();
        int h = Math.min(img.getHeight(), w);
        long r = 0, g = 0, b = 0;
        int n = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = img.getPixel(x, y);
                if ((c >>> 24) < 128) continue;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0;
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    private static boolean isSparse(NativeImage img) {
        if ((img.getPixel(0, 0) >>> 24) < 128) return true;
        int w = img.getWidth();
        int h = Math.min(img.getHeight(), w);
        int clear = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((img.getPixel(x, y) >>> 24) < 128) clear++;
        return clear * 2 > w * h;
    }
}