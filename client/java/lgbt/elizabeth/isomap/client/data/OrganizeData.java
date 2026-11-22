package lgbt.elizabeth.isomap.client.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import lgbt.elizabeth.isomap.client.IsomapClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.lang.reflect.Type;

import static lgbt.elizabeth.isomap.client.data.CreateChunkFile.getWorldDimension;
import static lgbt.elizabeth.isomap.client.data.CreateChunkFile.getWorldIdentifier;

public class OrganizeData {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Integer> blockIds = new LinkedHashMap<>();

    public static Map<String, Integer> biomeIds;



    public static byte[] serializeSection(String[][][] blocks, String[][][] biomes, byte[] light, float modVer) {

        int[] blockList = new int[4096];
        List<String> unique = new ArrayList<>();

        int i = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    String s = blocks[x][y][z];

                    int index = unique.indexOf(s);
                    if (index == -1) {
                        unique.add(s);
                        index = unique.size() - 1;
                    }
                    blockList[i++] = index;
                }
            }
        }

        int[] biomeList = new int[64];
        List<String> uniqueBiomes = new ArrayList<>();

        int bi = 0;
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    String s = biomes[x][y][z];

                    int index = uniqueBiomes.indexOf(s);
                    if (index == -1) {
                        uniqueBiomes.add(s);
                        index = uniqueBiomes.size() - 1;
                    }
                    biomeList[bi++] = index;
                }
            }
        }

        // ----body----
        int maxValue = unique.size() - 1;
        int d = 1;
        while (d < 4 && (maxValue >>> (8 * d)) != 0) d++;
        int depth = d;

        byte[] encodedBlocks = new byte[4096 * depth];
        for (int n = 0; n < 4096; n++) {
            for (int b = 0; b < depth; b++) {
                encodedBlocks[n * depth + b] = (byte) (blockList[n] >>> (8 * (depth - 1 - b)));
            }
        }

        int biomeMax = uniqueBiomes.size() - 1;
        int bd = 1;
        while (bd < 4 && (biomeMax >>> (8 * bd)) != 0) bd++;
        int biomeDepth = bd;

        byte[] encodedBiomes = new byte[64 * biomeDepth];
        for (int n = 0; n < 64; n++) {
            for (int b = 0; b < biomeDepth; b++) {
                encodedBiomes[n * biomeDepth + b] = (byte) (biomeList[n] >>> (8 * (biomeDepth - 1 - b)));
            }
        }

        byte[] raw = ByteBuffer.allocate(encodedBlocks.length + encodedBiomes.length + 4096)
                .put(encodedBlocks).put(encodedBiomes).put(light).array();

        byte[] body;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
            gz.finish();
            body = bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }



        // ----header----
        ByteBuffer blockPalette = ByteBuffer.allocate(unique.size() * 6);
        for (int n = 0; n < unique.size(); n++) {
            blockPalette.putInt(idOf(unique.get(n)));
            blockPalette.putShort((short) n);
        }

        ByteBuffer biomePalette = ByteBuffer.allocate(uniqueBiomes.size() * 6);
        for (int n = 0; n < uniqueBiomes.size(); n++) {
            biomePalette.putInt(biomeIdOf(uniqueBiomes.get(n)));
            biomePalette.putShort((short) n);
        }

        byte depthByte = (byte) ((depth << 4) | biomeDepth); // high nibble = blocks, low nibble = biomes

        byte[] ver = ("A" + modVer).getBytes(StandardCharsets.UTF_8);

        //----assemble----
        ByteBuffer out = ByteBuffer.allocate(
                2 + blockPalette.capacity() + 1 + biomePalette.capacity() + 4 + 1 + ver.length + body.length);

        out.putShort((short) unique.size());
        out.put(blockPalette.array());
        out.put((byte) uniqueBiomes.size());
        out.put(biomePalette.array());
        out.putInt(body.length);
        out.put(depthByte);
        out.put(ver);
        out.put(body);

        return out.array();
    }


    public static void createFops(int sx, int sy, int sz, float modVer, Path fileDirectory) {

        //header

        byte[] ver = ("A" + modVer).getBytes(StandardCharsets.UTF_8);


        //Table

        int rx = sx >> 3, ry = sy >> 3, rz = sz >> 3;
        byte[] sectionTable = new byte[512 * 6];

        for (int lx = 0; lx < 8; lx++) {
            for (int ly = 0; ly < 8; ly++) {
                for (int lz = 0; lz < 8; lz++) {
                    int csx = (rx << 3) + lx;
                    int csy = (ry << 3) + ly;
                    int csz = (rz << 3) + lz;

                    int slot = (int) (morton(csx, csy, csz) & 511);
                    int pos = slot * 6;

                    for (int b = 0; b < 6; b++) {
                        sectionTable[pos + b] = (byte) 0xFF;
                    }
                }
            }
        }

        byte[] header = ByteBuffer.allocate(ver.length + sectionTable.length)
                .put(ver)
                .put(sectionTable)
                .array();

        try {
            Files.createDirectories(fileDirectory.getParent());
            Files.write(fileDirectory, header, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            //
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }


    public static synchronized void modifyFopsSection(Path fileDirectory, int sx, int sy, int sz, byte[] sectionData) throws IOException {
        int slot = (int) (morton(sx, sy, sz) & 511);
        int pos = 4 + slot * 6;

        byte[] fops = readFile(fileDirectory);

        ByteBuffer fb = ByteBuffer.wrap(fops);

        int offset = fb.getInt(pos);
        int length = fb.getShort(pos + 4) & 0xFFFF;


        byte[] newFops;
        int newLength = sectionData.length;

        if (offset == -1) {
            //append it to end
            int newOffset = fops.length;

            newFops = Arrays.copyOf(fops, fops.length + newLength);
            System.arraycopy(sectionData, 0, newFops, newOffset, newLength);

            fb = ByteBuffer.wrap(newFops);
            fb.putInt(pos, newOffset);
            fb.putShort(pos + 4, (short) newLength);
        }
        else if (length >= newLength) {
            //clear, put in, move up
            int shrink = length - newLength;
            int oldEnd = offset + length;

            newFops = Arrays.copyOf(fops, fops.length);

            Arrays.fill(newFops, offset, oldEnd, (byte) 0);
            System.arraycopy(sectionData, 0, newFops, offset, newLength);

            if (shrink > 0) {
                System.arraycopy(newFops, oldEnd, newFops, offset + newLength, newFops.length - oldEnd);
                newFops = Arrays.copyOf(newFops, newFops.length - shrink);

                fb = ByteBuffer.wrap(newFops);
                for (int s = 0; s < 512; s++) {
                    int p = 4 + s * 6;
                    int o = fb.getInt(p);

                    if (o != -1 && o >= oldEnd) {
                        fb.putInt(p, o - shrink);
                    }
                }
            }
            else {
                fb = ByteBuffer.wrap(newFops);
            }

            fb.putShort(pos + 4, (short) newLength);
        }
        else {
            //clear, move everything up, append it to end
            int oldEnd = offset + length;
            int newOffset = fops.length - length;

            newFops = new byte[fops.length - length + newLength];

            System.arraycopy(fops, 0, newFops, 0, offset);
            System.arraycopy(fops, oldEnd, newFops, offset, fops.length - oldEnd);

            System.arraycopy(sectionData, 0, newFops, newOffset, newLength);

            fb = ByteBuffer.wrap(newFops);
            for (int s = 0; s < 512; s++) {
                int p = 4 + s * 6;
                int o = fb.getInt(p);

                if (o != -1 && o >= oldEnd) {
                    fb.putInt(p, o - length);
                }
            }

            fb.putInt(pos, newOffset);
            fb.putShort(pos + 4, (short) newLength);
        }

        try {
            Files.createDirectories(fileDirectory.getParent());
            Files.write(fileDirectory, newFops, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }








    public static byte[] readFile(Path filePath) throws IOException {
        return Files.readAllBytes(filePath);
    }
    static long morton(int x, int y, int z) {
        x += 1 << 20;
        y += 1 << 20;
        z += 1 << 20;
        long key = 0;
        for (int i = 0; i < 21; i++) {
            key |= (long) ((x >> i) & 1) << (3 * i);
            key |= (long) ((y >> i) & 1) << (3 * i + 1);
            key |= (long) ((z >> i) & 1) << (3 * i + 2);
        }
        return key;
    }

    public static int idOf(String name) {
        return blockIds.getOrDefault(name == null ? "minecraft:air" : name, blockIds.get("minecraft:air"));
    }

    public static int biomeIdOf(String name) {
        Integer id = biomeIds.get(name);
        if (id == null) throw new IllegalArgumentException("Unknown biome: " + name);
        return id;
    }

    private static final Type INDEX_TYPE = new TypeToken<LinkedHashMap<String, Integer>>() {}.getType();

    public static Map<String, Integer> getBlockIds() {
        return blockIds;
    }




    public static void loadBlockIds(Path file) throws IOException {
        List<String> names = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) names.add(id.toString());
        blockIds = loadIds(file, names);
    }

    public static void loadBiomeIds(Path file, Registry<Biome> registry) throws IOException {
        List<String> names = new ArrayList<>();
        for (Identifier id : registry.keySet()) names.add(id.toString());
        biomeIds = loadIds(file, names);
    }

    private static synchronized Map<String, Integer> loadIds(Path file, Collection<String> registryNames) throws IOException {
        Map<String, Integer> index = new LinkedHashMap<>();
        boolean changed = !Files.exists(file);

        if (!changed) {
            try (Reader r = Files.newBufferedReader(file)) {
                Map<String, Integer> existing = GSON.fromJson(r, INDEX_TYPE);
                if (existing == null) {
                    // Existing but empty file: likely an interrupted write. Never renumber from 0.
                    throw new IOException("ID file exists but is empty, refusing to renumber: " + file);
                }
                index.putAll(existing);
            } catch (JsonParseException e) {
                throw new IOException("ID file is corrupt, refusing to renumber: " + file, e);
            }

            Set<Integer> seen = new HashSet<>();
            for (Map.Entry<String, Integer> e : index.entrySet()) {
                if (e.getValue() == null || !seen.add(e.getValue())) {
                    throw new IOException("ID file has null/duplicate ID for '" + e.getKey() + "': " + file);
                }
            }
        }

        int next = index.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;

        for (String name : new TreeSet<>(registryNames)) {
            if (index.putIfAbsent(name, next) == null) {
                next++;
                changed = true;
            }
        }

        if (changed) writeAtomically(file, index);
        return index;
    }

    private static void writeAtomically(Path file, Map<String, Integer> index) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(index, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

}
