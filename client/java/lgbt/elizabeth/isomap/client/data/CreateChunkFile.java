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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.ArrayDeque;

public class CreateChunkFile {

    //Full Air Section
    private static final int[][][] AIR = new int[16][16][16];


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

    public static int[][][] readChunk(int cx, int cz, int cy) {

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;

        if (level == null) {
            return null;
        }

        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {
            return null;
        }

        LevelChunkSection[] sections = chunk.getSections();
        if (cy < 0 || cy >= sections.length) {
            return null;
        }

        LevelChunkSection section = sections[cy];
        if (section.hasOnlyAir()) return AIR;

        int[][][] blocks = new int[16][16][16];
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int baseX = cx << 4, baseZ = cz << 4;
        int baseY = (chunk.getMinSectionY() + cy) << 4;

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState s = section.getBlockState(x, y, z);
                    if (s.isAir()) continue;

                    int id = BuiltInRegistries.BLOCK.getId(s.getBlock());
                    int wx = baseX + x, wy = baseY + y, wz = baseZ + z;

                    int light = 0;
                    for (int ox = 0; ox <= 1; ox++)
                        for (int oy = 0; oy <= 1; oy++)
                            for (int oz = 0; oz <= 1; oz++) {
                                if (ox + oy + oz == 0) continue;
                                light = Math.max(light, getBlockLightLevel(level, mp, wx + ox, wy + oy, wz + oz));
                            }

                    blocks[x][y][z] = (light << 12) | id;
                }
            }
        }
        return blocks;
    }


    //Saving Chunks
    public static void encodeSection(int cx, int cz, int cy, float modVer) throws FileNotFoundException {

        int[][][] blocks = readChunk(cx, cz, cy);
        if (blocks == null) return;

        if (IsoMapConfig.repSolidSecAir) {
            if (isFullyEnclosed(cx, cz, cy, blocks)) return;
        }

        String worldName = getWorldIdentifier();

        String seed = IsomapClient.getSeedString();

        String dimension = getWorldDimension();

        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Path worldDirectory = configDirectory.resolve("IsoMap/" + worldName + "/" + seed + "/" + dimension);

        try {
            Files.createDirectories(worldDirectory);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Path chunkFilePath = worldDirectory.resolve(cx + "_" + cz + "_" + cy + ".fops");

        try (FileOutputStream fos = new FileOutputStream(chunkFilePath.toFile());
             GZIPOutputStream gzip = new GZIPOutputStream(fos);
             DataOutputStream out = new DataOutputStream(gzip)) {

            out.writeBytes("IsoMap Alpha " + modVer);

            int worldY;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        worldY = cy * 16 + y;
                        if (IsoMapConfig.saveSectionedY && (worldY < IsoMapConfig.minSectionedYBlock || worldY > IsoMapConfig.maxSectionedYBlock)) {
                            out.writeShort(0);
                        }
                        else {
                            out.writeShort(blocks[x][y][z]);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void saveRenderDist(int playerX, int playerZ, float modVer) {
        int maxCy = 0;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            maxCy = getMaxYSection(level);
        }

        int renderDistance = Minecraft.getInstance().options.renderDistance().get();

        if (renderDistance > 4) {
            renderDistance -= (renderDistance/2);
        }

        for (int cy = 0; cy <= maxCy; cy++) {
            for (int cx = playerX + renderDistance; cx >= playerX - renderDistance; cx--) {
                for (int cz = playerZ + renderDistance; cz >= playerZ - renderDistance; cz--) {

                    try {
                        encodeSection(cx, cz, cy, modVer);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }


    //Loading Chunks
    public static int[][][] decodeSection(int cx, int cz, int cy) {

        String worldName = getWorldIdentifier();

        String seed = IsomapClient.getSeedString();

        String dimension = getWorldDimension();

        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Path worldDirectory = configDirectory.resolve("IsoMap/" + worldName + "/" + seed + "/" + dimension);

        Path chunkFilePath = worldDirectory.resolve(cx + "_" + cz + "_" + cy + ".fops");

        int[][][] blocks = new int[16][16][16];
        try (FileInputStream fis = new FileInputStream(chunkFilePath.toFile());
             GZIPInputStream gzip = new GZIPInputStream(fis);
             DataInputStream in = new DataInputStream(gzip)) {

            in.skipNBytes(16);

            int worldY;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        worldY = cy * 16 + y;
                        if (worldY < IsoMapConfig.minSectionedYBlock || worldY > IsoMapConfig.maxSectionedYBlock) {
                            blocks[x][y][z] = in.readUnsignedShort();
                            blocks[x][y][z] = 0;
                        }
                        else {
                            blocks[x][y][z] = in.readUnsignedShort();
                        }
                    }
                }
            }

        return blocks;

        } catch (NoSuchFileException | EOFException e) {
            return null;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }


    //Lighting
    public static int[] TRANSPARENT_IDS = {
            0,25,26,27,28,29,30,31,32,35,88,89,90,91,92,93,94,95,96,97,98,101,110,111,112,113,114,115,116,117,118,119,120,121,122,123,124,125,126,127,128,129,
            130,131,132,133,134,135,136,137,156,157,158,159,160,161,162,163,164,165,166,167,168,169,170,171,172,173,180,181,182,183,184,185,186,187,188,189,
            190,191,192,194,195,196,197,202,207,210,211,212,213,214,215,216,217,218,219,220,221,222,224,225,226,227,228,229,230,231,232,233,234,235,236,237,
            238,239,240,241,242,243,244,245,246,247,248,249,250,251,252,253,254,255,256,257,258,259,260,261,262,263,264,265,266,267,268,269,270,273,274,275,
            276,277,280,282,284,290,291,292,293,295,298,299,300,301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318,319,320,321,322,323,
            324,325,341,342,343,344,345,346,347,348,349,350,351,352,353,354,355,356,357,358,359,362,363,364,365,366,367,368,369,374,379,382,384,385,386,387,
            388,389,390,391,392,394,396,401,402,408,409,410,411,412,413,414,415,416,417,418,419,420,421,422,423,424,425,426,427,428,429,430,431,432,433,434,
            435,436,437,438,439,440,441,442,443,444,445,446,447,448,449,450,451,452,453,454,455,456,457,458,459,460,461,462,463,464,465,466,467,468,469,471,
            472,473,474,477,482,500,501,502,503,504,505,506,507,508,509,510,511,512,513,514,515,523,524,525,526,538,539,540,541,542,543,544,545,546,547,548,
            549,550,551,552,553,557,558,559,560,561,562,563,564,565,566,567,568,569,570,571,572,573,574,575,576,577,578,579,580,581,582,583,584,585,586,587,
            588,589,590,591,592,593,594,599,600,601,602,603,604,605,606,607,608,609,610,611,612,613,614,615,616,617,618,619,620,621,622,623,628,629,630,631,
            632,633,634,635,636,637,638,639,640,641,642,643,644,645,646,647,648,649,650,651,652,653,654,655,662,663,664,665,666,667,670,675,742,743,745,746,
            747,758,759,760,761,762,763,764,765,766,767,768,769,770,771,772,773,774,775,776,777,778,779,780,781,782,783,784,785,786,787,788,790,791,792,793,
            794,795,796,811,812,813,814,815,816,817,818,819,820,821,822,823,824,825,826,827,828,829,830,831,832,833,834,835,836,837,844,845,847,848,849,850,
            851,852,853,854,855,856,857,858,859,860,861,866,867,869,870,875,876,878,879,880,881,882,885,886,887,888,889,890,891,892,893,894,897,898,899,900,
            901,902,903,904,913,919,920,921,922,926,927,932,933,934,937,938,939,940,944,945,946,947,948,949,950,951,952,953,954,955,956,957,958,959,960,961,
            962,963,964,965,966,967,968,969,970,971,972,973,974,975,976,977,978,979,980,981,982,983,985,987,989,991,994,996,1000,1002,1004,1006,1008,1010,
            1013,1015,1017,1019,1021,1023,1026,1028,1029,1031,1032,1033,1068,1069,1070,1071,1072,1073,1074,1075,1076,1077,1078,1079,1080,1081,1082,1083,1084,
            1085,1086,1087,1088,1089,1090,1091,1092,1093,1094,1095,1096,1097,1098,1099,1116,1117,1118,1119,1120,1121,1122,1123,1124,1125,1126,1127,1128,1129,
            1130,1131,1133,1134,1135,1136,1137,1138,1139,1140,1141,1142,1143,1145,1146,1147,1148,1154,1155,1158,1159,1162,1163,1166,1167,1176,1177,1181,1183,
            1185,1186,1187,1189,1190,1191,1192,1193,1194,1195
    };

    private static boolean[] buildTransparencyTable() {
        int max = 0;

        for (int id : TRANSPARENT_IDS) max = Math.max(max, id);
        boolean[] lookup = new boolean[max + 1];
        for (int id : TRANSPARENT_IDS) lookup[id] = true;
        return lookup;
    }

    private static boolean isTransparent(int id) {
        boolean[] transparentLookup = buildTransparencyTable();

        return id >= 0 && id < transparentLookup.length && transparentLookup[id];
    }

    private static boolean isFullyEnclosed(int cx, int cz, int cy, int[][][] blocks) {

        boolean[] solid = new boolean[16 * 16 * 16];
        boolean anySolid = false;


        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    boolean isSolid = !isTransparent(blocks[x][y][z] & 0xFFF);
                    solid[blockIndex(x, y, z)] = isSolid;
                    anySolid |= isSolid;

                    if (isSolid && isOnBoundary(x, y, z) && touchesOtherSection(cx, cz, cy, blocks, x, y, z)) {
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
                    if (touchesOtherSection(cx, cz, cy, blocks, x, y, z)) {
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

    private static boolean isTransparentNeighbor(int cx, int cz, int cy, int[][][] blocks, int x, int y, int z) {
        if (x >= 0 && x <= 15 && y >= 0 && y <= 15 && z >= 0 && z <= 15) {
            return isTransparent(blocks[x][y][z] & 0xFFF);
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return true;

        int wx = cx * 16 + x;
        int wy = level.getMinY() + cy * 16 + y;
        int wz = cz * 16 + z;

        BlockState state = level.getBlockState(new BlockPos(wx, wy, wz));
        int id = BuiltInRegistries.BLOCK.getId(state.getBlock());
        return isTransparent(id);
    }

    private static boolean touchesOtherSection(int cx, int cz, int cy, int[][][] blocks, int x, int y, int z) {
        return (x == 0  && isTransparentNeighbor(cx, cz, cy, blocks, x - 1, y, z))
                || (x == 15 && isTransparentNeighbor(cx, cz, cy, blocks, x + 1, y, z))
                || (y == 0  && isTransparentNeighbor(cx, cz, cy, blocks, x, y - 1, z))
                || (y == 15 && isTransparentNeighbor(cx, cz, cy, blocks, x, y + 1, z))
                || (z == 0  && isTransparentNeighbor(cx, cz, cy, blocks, x, y, z - 1))
                || (z == 15 && isTransparentNeighbor(cx, cz, cy, blocks, x, y, z + 1));
    }

}