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
import net.minecraft.world.level.block.Block;
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
    private static boolean[] buildTransparencyTable() {
        boolean[] lookup = new boolean[BuiltInRegistries.BLOCK.size()];
        for (int id = 0; id < lookup.length; id++) {
            Block block = BuiltInRegistries.BLOCK.byId(id);
            BlockState state = block.defaultBlockState();
            lookup[id] = !state.isSolidRender();
        }
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