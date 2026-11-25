package lgbt.elizabeth.isomap.client.data;

import lgbt.elizabeth.isomap.client.IsomapClient;
import lgbt.elizabeth.isomap.client.config.IsoMapConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;

public class CreateChunkFile {

    //Full Air Section
    private static final String[][][] AIR = new String[16][16][16];
    static {
        for (String[][] plane : AIR)
            for (String[] row : plane)
                Arrays.fill(row, "minecraft:air");
    }

    private static final Map<Block, String> NAME_CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> SAVED_HASH = new ConcurrentHashMap<>();
    private static final Map<Long, Long> SETTLE_AT = new ConcurrentHashMap<>();
    private static volatile String cacheWorld = "";
    private static final long SETTLE_MS = 2500;
    private static final long REFRESH_MS = 300_000;
    private static final int CACHE_LIMIT = 400_000;


    //getMaxYSection
    public static int getMaxYSection(ClientLevel level) {
        return level.getSectionsCount() - 1;
    }


    //Sanitize String
    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }


    //Get World name & Dimension
    public static String getWorldIdentifier() {
        Minecraft mc = Minecraft.getInstance();

        IntegratedServer singleplayer = mc.getSingleplayerServer();
        if (singleplayer != null) {
            Path worldPath = singleplayer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();

            String saveFolderName = worldPath.getFileName().toString();
            return sanitize("singleplayer_" + saveFolderName);
        }

        ServerData server = mc.getCurrentServer();
        String address = server != null ? server.ip : "unknown_server";



        String brand = "no_brand";
        ClientPacketListener connection = mc.getConnection();
        if (connection != null) {
            String b = connection.serverBrand();
            if (b != null) brand = b;
        }

        return sanitize(address + "_" + brand);
    }

    public static String getWorldDimension() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;

        String dimension = level != null
                ? level.dimension().identifier().toString()
                : "unknown_dimension";

        return sanitize(dimension);
    }


    //Process Chunk Data
    private static int getBlockLightLevel(ClientLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        p.set(x, y, z);
        return Math.max(level.getBrightness(LightLayer.BLOCK, p),
                level.getBrightness(LightLayer.SKY, p));
    }

    public static String[][][] readChunkBlocks(int sx, int sz, int sy) {

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;

        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        if (chunk == null) return null;

        LevelChunkSection[] sections = chunk.getSections();
        if (sy < 0 || sy >= sections.length) return null;

        LevelChunkSection section = sections[sy];
        if (section.hasOnlyAir()) return AIR;

        String[][][] blocks = new String[16][16][16];

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState s = section.getBlockState(x, y, z);
                    if (s.isAir()) continue;

                    blocks[x][y][z] = NAME_CACHE.computeIfAbsent(s.getBlock(), b -> BuiltInRegistries.BLOCK.getKey(b).toString());
                }
            }
        }
        return blocks;
    }

    public static String[][][] readChunkBiomes(int sx, int sz, int sy) {

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;

        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        if (chunk == null) return null;

        LevelChunkSection[] sections = chunk.getSections();
        if (sy < 0 || sy >= sections.length) return null;

        LevelChunkSection section = sections[sy];

        String[][][] biomes = new String[4][4][4];

        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    Holder<Biome> holder = section.getNoiseBiome(x, y, z);

                    biomes[x][y][z] = holder.unwrapKey()
                            .map(key -> key.identifier().toString())
                            .orElse("minecraft:plains");
                }
            }
        }
        return biomes;
    }


    //Saving Chunks
    static OrganizeData.SectionWrite prepareSection(int sx, int sz, int sy, float modVer) {
        String[][][] blocks = readChunkBlocks(sx, sz, sy);
        if (blocks == null) return null;

        long key = OrganizeData.morton(sx, sy, sz);
        int hash = blocks == AIR ? 1 : Arrays.deepHashCode(blocks);
        long now = System.currentTimeMillis();
        Integer prev = SAVED_HASH.get(key);
        if (prev != null && prev == hash) {
            Long due = SETTLE_AT.get(key);
            if (due == null || now < due) return null;
            SETTLE_AT.put(key, now + REFRESH_MS + ThreadLocalRandom.current().nextLong(REFRESH_MS / 2));
        } else {
            SAVED_HASH.put(key, hash);
            SETTLE_AT.put(key, now + SETTLE_MS);
        }

        boolean airOnly = blocks == AIR;
        if (!airOnly && IsoMapConfig.repSolidSecAir && isFullyEnclosed(sx, sz, sy, blocks)) return null;

        String[][][] biomes = readChunkBiomes(sx, sz, sy);
        byte[] light = airOnly ? new byte[4096] : readChunkLight(sx, sz, sy);
        byte[] data = OrganizeData.serializeSection(blocks, biomes, light, modVer);
        return new OrganizeData.SectionWrite(sx, sy, sz, data, airOnly);
    }

    public static void saveRenderDist(int playerX, int playerZ, float modVer) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        String worldKey = getWorldIdentifier() + "|" + IsomapClient.getSeedString() + "|"
                + getWorldDimension() + "|" + IsoMapConfig.repSolidSecAir;
        if (!worldKey.equals(cacheWorld) || SAVED_HASH.size() > CACHE_LIMIT) {
            SAVED_HASH.clear();
            SETTLE_AT.clear();
            cacheWorld = worldKey;
        }

        int minY = level.getMinY();
        int minSy = 0;
        int maxSy = getMaxYSection(level);
        if (IsoMapConfig.saveSectionedY) {
            minSy = Math.max(0, (IsoMapConfig.minSectionedYBlock - minY) >> 4);
            maxSy = Math.min(maxSy, (IsoMapConfig.maxSectionedYBlock - minY) >> 4);
        }
        if (maxSy < minSy) return;

        int rd = mc.options.renderDistance().get();
        if (rd > 4) rd -= rd / 2;

        int minCx = playerX - rd, maxCx = playerX + rd, minCz = playerZ - rd, maxCz = playerZ + rd;

        List<int[]> regionsXZ = new ArrayList<>();
        for (int rx = minCx >> 3; rx <= maxCx >> 3; rx++)
            for (int rz = minCz >> 3; rz <= maxCz >> 3; rz++)
                regionsXZ.add(new int[]{rx, rz});
        regionsXZ.sort(Comparator.comparingLong(r -> {
            long dx = r[0] * 8L + 4 - playerX, dz = r[1] * 8L + 4 - playerZ;
            return dx * dx + dz * dz;
        }));

        for (int[] r : regionsXZ) {
            for (int ry = minSy >> 3; ry <= maxSy >> 3; ry++) {
                List<OrganizeData.SectionWrite> writes = new ArrayList<>();
                for (int cx = Math.max(minCx, r[0] << 3); cx <= Math.min(maxCx, (r[0] << 3) + 7); cx++)
                    for (int cz = Math.max(minCz, r[1] << 3); cz <= Math.min(maxCz, (r[1] << 3) + 7); cz++)
                        for (int sy = Math.max(minSy, ry << 3); sy <= Math.min(maxSy, (ry << 3) + 7); sy++) {
                            try {
                                var w = prepareSection(cx, cz, sy, modVer);
                                if (w != null) writes.add(w);
                            } catch (Throwable t) {
                                System.err.println("prepareSection failed at " + cx + "," + cz + "," + sy);
                                t.printStackTrace();
                            }
                        }
                if (writes.isEmpty()) continue;

                var first = writes.getFirst();
                Path file = getPath(first.sx(), first.sz(), first.sy());
                try {
                    if (Files.notExists(file)) {
                        if (writes.stream().allMatch(OrganizeData.SectionWrite::onlyIfPresent)) continue;
                        OrganizeData.createFops(first.sx(), first.sy(), first.sz(), modVer, file);
                    }
                    OrganizeData.modifyFopsSections(file, writes);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static @NonNull Path getPath(int sx, int sz, int sy) {
        String worldName = getWorldIdentifier();

        String seed = IsomapClient.getSeedString();

        String dimension = getWorldDimension();

        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Path worldDirectory = configDirectory.resolve("IsoMap/" + worldName + "/" + seed + "/" + dimension);

        int rx = sx >> 3, ry = sy >> 3, rz = sz >> 3;
        String fileName = "r." + rx + "." + ry + "." + rz + ".fops";

        return worldDirectory.resolve(fileName);
    }



    public record RegionParse(long key, int[] blockData, int[] biomeData, byte[] lightData) {}

    //Loading Chunks
    public static RegionParse[] decodeRegion(int rx, int rz, int ry) throws IOException {

        String worldName = getWorldIdentifier();

        String seed = IsomapClient.getSeedString();

        String dimension = getWorldDimension();

        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Path worldDirectory = configDirectory.resolve("IsoMap/" + worldName + "/" + seed + "/" + dimension);

        String fileName = "r." + rx + "." + ry + "." + rz + ".fops";

        Path fileDirectory = worldDirectory.resolve(fileName);

        byte[] fops = Files.readAllBytes(fileDirectory);

        int[] offsets = new int[512];

        ByteBuffer fb = ByteBuffer.wrap(fops);
        for (int slot = 0; slot < 512; slot++) {
            int pos = 4 + slot * 6;
            offsets[slot] = fb.getInt(pos);
        }

        RegionParse[] regionData = new RegionParse[512];

        for (int i = 0; i < 512; i++) {
            long key = (OrganizeData.morton(rx, ry, rz) << 9) | i;
            int offset = offsets[i];
            if (offset == -1) continue;

            int n = fb.getShort(offset) & 0xFFFF;
            int m = fb.get(offset + 2 + n * 6) & 0xFF;

            int bodyLenPos = offset + 2 + n * 6 + 1 + m * 6;
            int bodyLen = fb.getInt(bodyLenPos);
            int bodyStart = bodyLenPos + 4 + 1 + 4;

            byte[] body = Arrays.copyOfRange(fops, bodyStart, bodyStart + bodyLen);

            int depthByte = fb.get(bodyLenPos + 4) & 0xFF;

            int blockDepth = depthByte >> 4;
            int biomeDepth = depthByte & 0x0F;

            byte[] raw;
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                raw = gz.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }


            int[] blockPalette = new int[n];
            for (int p = 0; p < n; p++) {
                blockPalette[p] = fb.getInt(offset + 2 + p * 6);
            }

            int biomePaletteStart = offset + 2 + n * 6 + 1;

            int[] biomePalette = new int[m];
            for (int p = 0; p < m; p++) {
                biomePalette[p] = fb.getInt(biomePaletteStart + p * 6);
            }


            int[] blockIndices = new int[4096];
            for (int i1 = 0; i1 < 4096; i1++) {
                blockIndices[i1] = readIndex(raw, i1 * blockDepth, blockDepth);
            }

            int biomeStart = 4096 * blockDepth;
            int[] biomeIndices = new int[64];
            for (int i1 = 0; i1 < 64; i1++) {
                biomeIndices[i1] = readIndex(raw, biomeStart + i1 * biomeDepth, biomeDepth);
            }

            int[] blockIds = new int[4096];
            for (int i1 = 0; i1 < 4096; i1++) {
                blockIds[i1] = blockPalette[blockIndices[i1]];
            }

            int[] biomeIds = new int[64];
            for (int i1 = 0; i1 < 64; i1++) {
                biomeIds[i1] = biomePalette[biomeIndices[i1]];
            }

            int lightStart = biomeStart + 64 * biomeDepth;
            byte[] light = new byte[4096];
            if (raw.length >= lightStart + 4096) System.arraycopy(raw, lightStart, light, 0, 4096);

            regionData[i] = new RegionParse(key, blockIds, biomeIds, light);


        }

        return regionData;
    }

    public static CombinedRegion combineRegion(CreateChunkFile.RegionParse[] region) {

        int[][][] blocks = new int[128][128][128];
        int[][][] biomes = new int[32][32][32];

        for (CreateChunkFile.RegionParse s : region) {
            if (s == null) continue;


            int slot = (int) (s.key() & 511);
            int lx = compact(slot);
            int ly = compact(slot >> 1);
            int lz = compact(slot >> 2);

            //blocks
            int bx = lx * 16, by = ly * 16, bz = lz * 16;
            int[] ids = s.blockData();
            byte[] lt = s.lightData();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int n = x * 256 + y * 16 + z;
                        blocks[bx + x][by + y][bz + z] = (ids[n] & 0xFFF) | ((lt[n] & 0xF) << 12);
                    }
                }
            }

            //biomes
            int cx = lx * 4, cy = ly * 4, cz = lz * 4;
            int[] bio = s.biomeData();
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        biomes[cx + x][cy + y][cz + z] = bio[x * 16 + y * 4 + z];
                    }
                }
            }
        }

        return new CombinedRegion(blocks, biomes);
    }

    public record CombinedRegion(int[][][] blocks, int[][][] biomes) {}

    public static int compact(int v) {
        v &= 0b001001001;
        v = (v | (v >> 2)) & 0b1000011;
        v = (v | (v >> 4)) & 0b0000111;
        return v;
    }



    static int readIndex(byte[] data, int pos, int depth) {
        int value = 0;
        for (int k = 0; k < depth; k++) {
            value = (value << 8) | (data[pos + k] & 0xFF);
        }
        return value;
    }


    //Lighting
    private static final Map<String, Boolean> TRANSPARENT_CACHE = new HashMap<>();

    private static boolean isTransparentState(BlockState state) {
        return state.isAir() || !state.isSolidRender();
    }

    public static byte[] readChunkLight(int sx, int sz, int sy) {
        byte[] light = new byte[4096];
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return light;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int baseX = sx * 16, baseY = level.getMinY() + sy * 16, baseZ = sz * 16;

        final int[] dx = {0, 1, -1, 0,  0, 0,  0};
        final int[] dy = {0, 0,  0, 1, -1, 0,  0};
        final int[] dz = {0, 0,  0, 0,  0, 1, -1};

        int i = 0;
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++) {
                    int best = 0;
                    for (int d = 0; d < 7 && best < 15; d++) {
                        best = Math.max(best, getBlockLightLevel(level, p,
                                baseX + x + dx[d], baseY + y + dy[d], baseZ + z + dz[d]));
                    }
                    light[i++] = (byte) best;
                }
        return light;
    }

    private static boolean isTransparent(String name) {
        if (name == null) return true;

        return TRANSPARENT_CACHE.computeIfAbsent(name, n -> {
            Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(n));
            return isTransparentState(block.defaultBlockState());
        });
    }

    private static boolean isFullyEnclosed(int sx, int sz, int sy, String[][][] blocks) {

        boolean[] solid = new boolean[16 * 16 * 16];
        boolean anySolid = false;


        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    boolean isSolid = !isTransparent(blocks[x][y][z]);
                    solid[blockIndex(x, y, z)] = isSolid;
                    anySolid |= isSolid;

                    if (isSolid && isOnBoundary(x, y, z) && touchesOtherSection(sx, sz, sy, blocks, x, y, z)) {
                        return false;
                    }
                }
            }
        }

        if (!anySolid) return false;

        boolean[] openAir = new boolean[16 * 16 * 16];
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int i = blockIndex(x, y, z);
                    if (solid[i] || openAir[i]) continue;
                    if (touchesOtherSection(sx, sz, sy, blocks, x, y, z)) {
                        openAir[i] = true;
                        queue.add(i);
                    }
                }
            }
        }

        int[] dx = {1, -1, 0, 0, 0, 0};
        int[] dy = {0, 0, 1, -1, 0, 0};
        int[] dz = {0, 0, 0, 0, 1, -1};

        while (!queue.isEmpty()) {
            int cur = queue.poll();
            int x = cur >> 8, y = (cur >> 4) & 0xF, z = cur & 0xF;
            for (int d = 0; d < 6; d++) {
                int nx = x + dx[d], ny = y + dy[d], nz = z + dz[d];
                if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;
                int ni = blockIndex(nx, ny, nz);
                if (solid[ni]) return false;
                if (!openAir[ni]) {
                    openAir[ni] = true;
                    queue.add(ni);
                }
            }
        }

        return true;
    }

    private static boolean isOnBoundary(int x, int y, int z) {
        return x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15;
    }

    private static int blockIndex(int x, int y, int z) {
        return (x << 8) | (y << 4) | z;
    }

    private static boolean isTransparentNeighbor(int sx, int sz, int sy, String[][][] blocks, int x, int y, int z) {
        if (x >= 0 && x <= 15 && y >= 0 && y <= 15 && z >= 0 && z <= 15) {
            return isTransparent(blocks[x][y][z]);
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return true;

        int wx = sx * 16 + x;
        int wy = level.getMinY() + sy * 16 + y;
        int wz = sz * 16 + z;

        return isTransparentState(level.getBlockState(new BlockPos(wx, wy, wz)));
    }

    private static boolean touchesOtherSection(int sx, int sz, int sy, String[][][] blocks, int x, int y, int z) {
        return (x == 0  && isTransparentNeighbor(sx, sz, sy, blocks, x - 1, y, z))
                || (x == 15 && isTransparentNeighbor(sx, sz, sy, blocks, x + 1, y, z))
                || (y == 0  && isTransparentNeighbor(sx, sz, sy, blocks, x, y - 1, z))
                || (y == 15 && isTransparentNeighbor(sx, sz, sy, blocks, x, y + 1, z))
                || (z == 0  && isTransparentNeighbor(sx, sz, sy, blocks, x, y, z - 1))
                || (z == 15 && isTransparentNeighbor(sx, sz, sy, blocks, x, y, z + 1));
    }

}