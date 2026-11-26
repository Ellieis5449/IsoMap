package lgbt.elizabeth.isomap.client.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
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

public class OrganizeData {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Integer> blockIds = new LinkedHashMap<>();

    public static Map<String, Integer> biomeIds;

    public static byte[] serializeSection(String[][][] blocks, String[][][] biomes, byte[] light, float modVer) {

        int[] blockList = new int[4096];
        List<String> unique = new ArrayList<>();
        Map<String, Integer> blockIdx = new HashMap<>();

        int i = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    String s = blocks[x][y][z];

                    Integer index = blockIdx.get(s);
                    if (index == null) {
                        index = unique.size();
                        unique.add(s);
                        blockIdx.put(s, index);
                    }
                    blockList[i++] = index;
                }
            }
        }

        int[] biomeList = new int[64];
        List<String> uniqueBiomes = new ArrayList<>();
        Map<String, Integer> biomeIdx = new HashMap<>();

        int bi = 0;
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    String s = biomes[x][y][z];

                    Integer index = biomeIdx.get(s);
                    if (index == null) {
                        index = uniqueBiomes.size();
                        uniqueBiomes.add(s);
                        biomeIdx.put(s, index);
                    }
                    biomeList[bi++] = index;
                }
            }
        }

        //Body
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

        //Header
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

        //Assemble
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

        //Header

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
            //empty
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    public record SectionWrite(int sx, int sy, int sz, byte[] data, boolean onlyIfPresent) {}

    private static final int HEADER = 4;

    public static synchronized void modifyFopsSections(Path file, List<SectionWrite> writes) throws IOException {
        byte[] old = Files.readAllBytes(file);
        ByteBuffer ob = ByteBuffer.wrap(old);
        byte[][] entries = new byte[512][];
        for (int s = 0; s < 512; s++) {
            int p = HEADER + s * 6;
            int off = ob.getInt(p);
            if (off == -1) continue;
            int len = ob.getShort(p + 4) & 0xFFFF;
            entries[s] = Arrays.copyOfRange(old, off, off + len);
        }

        boolean changed = false;
        for (SectionWrite w : writes) {
            int slot = (int) (morton(w.sx(), w.sy(), w.sz()) & 511);
            if (entries[slot] == null && w.onlyIfPresent()) continue;
            if (entries[slot] != null && Arrays.equals(entries[slot], w.data())) continue;
            entries[slot] = w.data();
            changed = true;
        }
        if (!changed) return;

        int total = HEADER + 512 * 6;
        for (byte[] e : entries) if (e != null) total += e.length;

        ByteBuffer out = ByteBuffer.allocate(total);
        out.put(old, 0, HEADER);
        int pos = HEADER + 512 * 6;
        for (byte[] e : entries) {
            if (e == null) { out.putInt(-1); out.putShort((short) -1); }
            else { out.putInt(pos); out.putShort((short) e.length); pos += e.length; }
        }
        for (byte[] e : entries) if (e != null) out.put(e);

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, out.array());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.write(file, out.array());
            Files.deleteIfExists(tmp);
        }
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
