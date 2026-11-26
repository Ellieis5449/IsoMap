package lgbt.elizabeth.isomap.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import lgbt.elizabeth.isomap.client.IsomapClient;
import lgbt.elizabeth.isomap.client.config.IsoMapConfig;
import lgbt.elizabeth.isomap.client.data.CreateChunkFile;
import lgbt.elizabeth.isomap.client.data.OrganizeData;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

public final class MapSession {

    private static volatile MapSession current;

    private record TileResult(int rcx, int rcz, int idx, int w, int[] argb, short[] z) {}
    private record RegionBuffer(Region region, Region.Composite buf) {}
    private record RegionFile(Path path, int x, int z, int y) {}
    private record Key(int x, int y, int z) {}

    private static final int REMOVE_MARGIN = 8;

    private final String key;
    private final int sig;
    private final int maxCy;
    private final int levelMinY;
    private final boolean straight;
    private final int direction;
    private final Path dir;
    private volatile boolean open = true;
    private volatile int boundsSig;

    public final int originCx, originCz;
    private volatile int playerCx, playerCz;

    private final ExecutorService rebuildExecutor;
    private final ExecutorService batchPool;
    private ScheduledExecutorService scheduler;
    private int lastGen = -1;

    private final Queue<TileResult> pendingTiles = new ConcurrentLinkedQueue<>();
    private final Queue<RegionBuffer> readyBuffers = new ConcurrentLinkedQueue<>();
    private final Semaphore room = new Semaphore(64);

    private final Deque<Region> dirtyQueue = new ArrayDeque<>();
    private final Map<Long, Region> regions = new HashMap<>();
    final List<Region> regionOrder = new ArrayList<>();
    private boolean regionOrderDirty = false;

    private final Set<Key> rendered = ConcurrentHashMap.newKeySet();
    private final Map<Key, Long> mtimes = new ConcurrentHashMap<>();
    private final Map<Key, Long> crcs = new ConcurrentHashMap<>();
    private final Map<Key, Integer> fails = new ConcurrentHashMap<>();
    private volatile boolean retry = false;

    private int[] atlas = new int[0];
    private int atlasW, atlasH;
    private boolean[] opq = new boolean[4096];
    private boolean[] blank = new boolean[4096];
    private final byte[] tintType = new byte[4096];
    private final boolean[] grassTop = new boolean[4096];
    private Biome[] biomeById = new Biome[0];
    private int[] foliageC = new int[0], waterC = new int[0], dryC = new int[0];

    public static MapSession get() { return current; }

    public static void clientTick(Minecraft mc) {
        try {
            MapSession s = current;
            if (mc.level == null || mc.player == null) {
                if (s != null) stop();
                return;
            }
            if (s != null && (!s.key.equals(sessionKey()) || s.sig != configSig())) {
                stop();
                s = null;
            }
            if (s == null) {
                if (OrganizeData.biomeIds == null) return;
                s = new MapSession(mc);
                current = s;
                s.begin();
            }
            s.tick(mc);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static void stop() {
        MapSession s = current;
        current = null;
        if (s != null) s.shutdown();
    }

    private static String sessionKey() {
        return CreateChunkFile.getWorldIdentifier() + "|" + IsomapClient.getSeedString() + "|" + CreateChunkFile.getWorldDimension();
    }

    private static int configSig() {
        return Objects.hash(IsoMapConfig.direction, IsoMapConfig.brightness, IsoMapConfig.workersPerCell);
    }

    private static int computeBoundsSig() {
        return Objects.hash(IsoMapConfig.minSectionedYBlock, IsoMapConfig.maxSectionedYBlock,
                IsoMapConfig.maxRadius, IsoMapConfig.waterTint);
    }

    private static ThreadFactory daemon() {
        return r -> { Thread t = new Thread(r); t.setDaemon(true); return t; };
    }

    private MapSession(Minecraft mc) {
        ClientLevel level = mc.level;
        assert level != null;
        assert mc.player != null;

        this.key = sessionKey();
        this.sig = configSig();
        this.maxCy = CreateChunkFile.getMaxYSection(level);
        this.levelMinY = level.getMinY();
        this.boundsSig = computeBoundsSig();

        int dirIndex = IsoMapConfig.direction;
        this.straight = (dirIndex % 2 == 0);
        this.direction = straight ? dirIndex / 2 : (4 - dirIndex / 2) & 3;
        MapScreen.straight = this.straight;

        this.dir = FabricLoader.getInstance().getConfigDir().resolve("IsoMap/"
                + CreateChunkFile.getWorldIdentifier() + "/" + IsomapClient.getSeedString()
                + "/" + CreateChunkFile.getWorldDimension());

        BlockPos pos = mc.player.blockPosition();
        this.originCx = pos.getX() >> 4;
        this.originCz = pos.getZ() >> 4;
        this.playerCx = originCx;
        this.playerCz = originCz;

        this.rebuildExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() - 1), daemon());
        this.batchPool = Executors.newFixedThreadPool(Math.max(1, IsoMapConfig.workersPerCell), daemon());

        loadBiomes(level);
        buildTintTable();
        try (NativeImage ts = loadTileset()) {
            prepareAtlas(ts);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void begin() {
        IsomapClient.requestSave(originCx, originCz);
        scheduler = Executors.newSingleThreadScheduledExecutor(daemon());
        scheduler.scheduleWithFixedDelay(this::pipelineStep, 0, 250, TimeUnit.MILLISECONDS);
    }

    private void shutdown() {
        open = false;
        if (scheduler != null) scheduler.shutdownNow();
        rebuildExecutor.shutdownNow();
        batchPool.shutdownNow();
        for (Region r : regionOrder) r.release();
        regions.clear();
        regionOrder.clear();
        dirtyQueue.clear();
        pendingTiles.clear();
        readyBuffers.clear();
    }

    public float[] panTo(BlockPos pos) {
        int bx = pos.getX() - originCx * 16;
        int bz = pos.getZ() - originCz * 16;
        int[] r = rotatedChunkPos(rot(), bx, bz, 1);
        int yRel = pos.getY() - levelMinY;
        float px, py;
        if (straight) {
            px = 2f * r[0];
            py = 2f * r[1] - 2f * yRel;
        } else {
            px = 2f * (r[0] - r[1]);
            py = (r[0] + r[1]) - 2f * yRel;
        }
        return new float[]{-px, -py};
    }

    private void tick(Minecraft mc) {
        int bs = computeBoundsSig();
        if (bs != boundsSig) {
            boundsSig = bs;
            mtimes.clear();
            crcs.clear();
            fails.clear();
            retry = true;
            if (IsoMapConfig.saveSectionedY) IsomapClient.requestSave(playerCx, playerCz);
        }

        assert mc.player != null;
        BlockPos p = mc.player.blockPosition();
        playerCx = p.getX() >> 4;
        playerCz = p.getZ() >> 4;

        fileIncomingTiles();
        sortRegionsIfNeeded();
        uploadFinishedBuffer();
        startRebuilds();
    }

    private void fileIncomingTiles() {
        long deadline = System.nanoTime() + 3_000_000;

        TileResult tr;
        while ((tr = pendingTiles.poll()) != null) {
            room.release();

            int rx = Math.floorDiv(tr.rcx(), MapScreen.R), rz = Math.floorDiv(tr.rcz(), MapScreen.R);
            int ry = tr.idx() / MapScreen.R;
            long k = (((long) rx & 0xFFFFF) << 40) | (((long) rz & 0xFFFFF) << 20) | ry;

            Region region = regions.get(k);
            if (region == null) {
                if (tr.argb() == null) continue;
                region = new Region(rx, rz, ry);
                regions.put(k, region);
                regionOrder.add(region);
                regionOrderDirty = true;
            }

            final TileResult f = tr;
            boolean changed = region.tiles.removeIf(t -> t.cx() == f.rcx() && t.cz() == f.rcz() && t.idx() == f.idx());
            if (tr.argb() != null) {
                region.tiles.add(new MapScreen.Tile(tr.rcx(), tr.rcz(), tr.idx(), tr.w(), tr.argb(), tr.z()));
                changed = true;
            }
            if (changed) {
                region.dirty = true;
                if (!region.queued) {
                    region.queued = true;
                    dirtyQueue.add(region);
                }
            }

            if (System.nanoTime() > deadline) break;
        }
    }

    private void sortRegionsIfNeeded() {
        if (!regionOrderDirty) return;
        regionOrder.sort(Comparator.comparingInt((Region q) -> (straight ? q.rz : q.rx + q.rz) + q.ry)
                .thenComparingInt(q -> q.ry)
                .thenComparingInt(q -> q.rx));
        regionOrderDirty = false;
    }

    private void uploadFinishedBuffer() {
        RegionBuffer rb = readyBuffers.poll();
        if (rb == null) return;

        Region region = rb.region();
        region.applyBuffer(rb.buf());
        region.building = false;

        if (region.dirty && !region.queued) {
            region.queued = true;
            dirtyQueue.add(region);
        }
    }

    private void startRebuilds() {
        if (rebuildExecutor.isShutdown()) return;
        long deadline = System.nanoTime() + 3_000_000;
        int toCheck = dirtyQueue.size();

        for (int i = 0; i < toCheck && System.nanoTime() < deadline; i++) {
            Region r = dirtyQueue.poll();
            if (r == null) break;
            r.queued = false;

            if (!r.dirty || r.building) continue;

            long now = System.nanoTime();
            if (r.built && now - r.lastBuildNs < 300_000_000) {
                r.queued = true;
                dirtyQueue.add(r);
                continue;
            }

            r.dirty = false;
            r.building = true;
            r.built = true;
            r.lastBuildNs = now;

            List<MapScreen.Tile> snapshot = new ArrayList<>(r.tiles);
            try {
                rebuildExecutor.submit(() -> {
                    try {
                        readyBuffers.add(new RegionBuffer(r, r.compositeBuffer(snapshot, maxCy)));
                    } catch (Throwable t) {
                        t.printStackTrace();
                        r.building = false;
                    }
                });
            } catch (RejectedExecutionException e) {
                r.building = false;
            }
        }
    }

    private void pipelineStep() {
        try {
            if (!open) return;
            int gen = IsomapClient.saveGen.get();
            if (gen != lastGen || IsomapClient.isSaving() || retry) {
                lastGen = gen;
                retry = scanAndDispatch();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private boolean scanAndDispatch() {
        final int T = 8;
        final int yLo = yLo(), yHi = yHi();
        final boolean empty = yHi < yLo;
        final int cyLo = yLo / (16 * T), cyHi = Math.min(maxCy / T, yHi / (16 * T));
        final int pcx = playerCx, pcz = playerCz;
        final int px = Math.floorDiv(pcx, T), pz = Math.floorDiv(pcz, T);

        for (Key k : rendered) {
            if (empty || outOfRange(k.x(), k.y(), k.z(), cyLo, cyHi, pcx, pcz, REMOVE_MARGIN)) {
                rendered.remove(k);
                mtimes.remove(k);
                crcs.remove(k);
                queueRemoval(k);
            }
        }
        if (empty || !Files.isDirectory(dir)) return false;

        boolean again = false;
        long nowMs = System.currentTimeMillis();
        List<RegionFile> changed = new ArrayList<>();

        try (var stream = Files.newDirectoryStream(dir, "r.*.fops")) {
            for (Path p : stream) {
                if (!open) return false;
                String n = p.getFileName().toString();
                String[] a = n.substring(0, n.length() - 5).split("\\.");
                if (a.length != 4) continue;

                int x, cy, z;
                try {
                    x = Integer.parseInt(a[1]);
                    cy = Integer.parseInt(a[2]);
                    z = Integer.parseInt(a[3]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (outOfRange(x, cy, z, cyLo, cyHi, pcx, pcz, 0)) continue;

                Key k = new Key(x, cy, z);
                long mt = Files.getLastModifiedTime(p).toMillis();
                Long oldMt = mtimes.get(k);
                if (oldMt != null && oldMt == mt) continue;

                if (nowMs - mt < 300) { again = true; continue; }

                long crc;
                try {
                    CRC32 c = new CRC32();
                    c.update(Files.readAllBytes(p));
                    crc = c.getValue();
                } catch (IOException e) {
                    again = true;
                    continue;
                }

                mtimes.put(k, mt);
                Long oldCrc = crcs.put(k, crc);
                if (oldCrc != null && oldCrc == crc) continue;

                changed.add(new RegionFile(p, x, z, cy));
            }
        } catch (IOException ignored) {
        }

        changed.sort(Comparator.comparingLong(sf -> {
            long dx = sf.x() - px, dz = sf.z() - pz;
            return dx * dx + dz * dz;
        }));

        for (RegionFile sf : changed) {
            try {
                batchPool.submit(() -> renderOne(sf));
            } catch (RejectedExecutionException e) {
                return again;
            }
        }
        return again;
    }

    private boolean outOfRange(int x, int cy, int z, int cyLo, int cyHi, int pcx, int pcz, int margin) {
        if (cy < cyLo || cy > cyHi) return true;
        int r = IsoMapConfig.maxRadius;
        if (r <= 0) return false;
        r += margin;
        int nx = Math.max(x * 8, Math.min(pcx, x * 8 + 7)) - pcx;
        int nz = Math.max(z * 8, Math.min(pcz, z * 8 + 7)) - pcz;
        return (long) nx * nx + (long) nz * nz > (long) r * r;
    }

    private void queueRemoval(Key k) {
        try {
            batchPool.submit(() -> {
                if (!open || failedToGetRoom()) return;
                int[] rel = rotatedChunkPos(rot(), k.x() * 8 - originCx, k.z() * 8 - originCz, 8);
                pendingTiles.add(new TileResult(rel[0], rel[1], k.y() * 8, 0, null, null));
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private void renderOne(RegionFile sf) {
        if (!open || failedToGetRoom()) return;

        final int n = 128;
        final int w = straight ? 2 * n : 4 * n;
        final int h = 4 * n;
        Key k = new Key(sf.x(), sf.y(), sf.z());

        try {
            var region = CreateChunkFile.decodeRegion(sf.x(), sf.z(), sf.y());
            var combined = CreateChunkFile.combineRegion(region);

            short[] depth = new short[w * h];
            int[] argb = buildRegionImage(combined.blocks(), combined.biomes(), depth,
                    sf.x() * 128, sf.z() * 128, sf.y() * 128);

            int[] rel = rotatedChunkPos(rot(), sf.x() * 8 - originCx, sf.z() * 8 - originCz, 8);
            pendingTiles.add(new TileResult(rel[0], rel[1], sf.y() * 8, w, argb, argb == null ? null : depth));
            if (argb != null) rendered.add(k); else rendered.remove(k);
            fails.remove(k);
        } catch (Throwable t) {
            room.release();
            t.printStackTrace();
            if (fails.merge(k, 1, Integer::sum) < 3) {
                mtimes.remove(k);
                crcs.remove(k);
                retry = true;
            }
        }
    }

    private boolean failedToGetRoom() {
        try {
            while (!room.tryAcquire(50, TimeUnit.MILLISECONDS)) {
                if (!open) return true;
            }
            return !open;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private int yLo() { return Math.max(0, IsoMapConfig.minSectionedYBlock - levelMinY); }
    private int yHi() { return Math.min(maxCy * 16 + 15, IsoMapConfig.maxSectionedYBlock - levelMinY); }

    private int rot() { return straight ? (4 - direction) & 3 : (direction + 3) & 3; }

    private static int[] rotatedChunkPos(int rot, int dx, int dz, int size) {
        return switch (rot) {
            case 1  -> new int[]{-dz - size, dx};
            case 2  -> new int[]{-dx - size, -dz - size};
            case 3  -> new int[]{dz,         -dx - size};
            default -> new int[]{dx,         dz};
        };
    }

    private int[] buildRegionImage(int[][][] blocks, int[][][] biomes, short[] depth, int originX, int originZ, int baseY) {
        if (blocks == null) return null;
        boolean drawn = false;

        final int N = blocks.length;
        final int w = straight ? 2 * N : 4 * N;
        final int h = 4 * N;
        int[] image = new int[w * h];

        final int atlasCellsPerRow = 50;
        final int tileW = 4, tileH = 4;
        final int[] atlas = this.atlas;
        final boolean[] opq = this.opq, blank = this.blank;
        final int atlasW = this.atlasW, atlasH = this.atlasH;
        final Biome[] bm = this.biomeById;
        final int[] fC = foliageC, wC = waterC, dC = dryC;

        final int rot = rot();
        final float floor = 0.15f + 0.85f * (IsoMapConfig.brightness / 100);
        final int steps = straight ? N : 2 * N - 1;

        final int syLo = Math.max(0, yLo() - baseY), syHi = Math.min(N - 1, yHi() - baseY);
        if (syHi < syLo) return null;

        for (int sy = syLo; sy <= syHi; sy++) {
            for (int step = 0; step < steps; step++) {
                for (int vx = N - 1; vx >= 0; vx--) {
                    int vz = straight ? step : step - vx;
                    if (vz < 0 || vz >= N) continue;

                    int sx, sz;
                    switch (rot) {
                        case 1  -> { sx = vz;         sz = N - 1 - vx; }
                        case 2  -> { sx = N - 1 - vx; sz = N - 1 - vz; }
                        case 3  -> { sx = N - 1 - vz; sz = vx; }
                        default -> { sx = vx;         sz = vz; }
                    }

                    int id = blocks[sx][sy][sz] & 0xFFF;
                    if (id == 0 || blank[id]) continue;

                    if (sy < syHi && vz < N - 1 && (straight || vx < N - 1)
                            && opq[blockAt(blocks, rot, vx, vz, sy + 1)]
                            && opq[blockAt(blocks, rot, vx, vz + 1, sy)]
                            && (straight || opq[blockAt(blocks, rot, vx + 1, vz, sy)])) continue;

                    int ax = (id % atlasCellsPerRow) * tileW;
                    int ay = (id / atlasCellsPerRow) * tileH;
                    if (ax + tileW > atlasW || ay + tileH > atlasH) continue;

                    float shade = floor + (1f - floor) * (blockLight(blocks, sx, sy, sz) / 15f);

                    int tint = 0;
                    byte tt = tintType[id];
                    if (tt != 0) {
                        int bid = biomeAt(biomes, N, sx, sy, sz);
                        if (bid >= 0 && bid < bm.length && bm[bid] != null) {
                            int rgb = switch (tt) {
                                case 1  -> bm[bid].getGrassColor(originX + sx, originZ + sz);
                                case 2  -> fC[bid];
                                case 4  -> dC[bid];
                                case 5  -> 0x619961;
                                case 6  -> 0x80A755;
                                default -> wC[bid];
                            };
                            tint = 0xFF000000 | rgb;
                        }
                    }
                    final boolean topOnly = grassTop[id];

                    int px, py, d;
                    if (straight) {
                        px = 2 * vx;
                        py = 2 * (N - 1) + 2 * vz - 2 * sy;
                        d  = vz + sy;
                    } else {
                        px = 2 * (N - 1) + 2 * (vx - vz);
                        py = 2 * (N - 1) + vx + vz - 2 * sy;
                        d  = vx + vz + sy;
                    }

                    for (int dx = 0; dx < tileW; dx++) {
                        for (int dy = 0; dy < tileH; dy++) {
                            int tx = px + dx, ty = py + dy;
                            if (tx < 0 || tx >= w || ty < 0 || ty >= h) continue;

                            int argb = atlas[(ay + dy) * atlasW + ax + dx];
                            if ((argb >>> 24) == 0) continue;
                            if (tint != 0 && (!topOnly || dy < 2)) argb = multiply(argb, tint);
                            if (tt == 3) argb = (argb & 0x00FFFFFF) | (IsoMapConfig.waterTint << 24);
                            if ((argb >>> 24) == 0) continue;

                            int i = ty * w + tx;
                            image[i] = MapScreen.over(shaded(argb, shade), image[i]);
                            depth[i] = (short) d;
                            drawn = true;
                        }
                    }
                }
            }
        }
        return drawn ? image : null;
    }

    private void loadBiomes(ClientLevel level) {
        Registry<Biome> reg = level.registryAccess().lookupOrThrow(Registries.BIOME);
        Map<String, Integer> ids = OrganizeData.biomeIds;
        int max = ids.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;

        Biome[] arr = new Biome[max];
        int[] f = new int[max], wt = new int[max], dr = new int[max];
        for (var e : ids.entrySet()) {
            Biome b = reg.getValue(Identifier.parse(e.getKey()));
            if (b == null) continue;
            int id = e.getValue();
            arr[id] = b;
            f[id] = b.getFoliageColor();
            wt[id] = b.getWaterColor();
            dr[id] = b.getDryFoliageColor();
        }
        biomeById = arr;
        foliageC = f;
        waterC = wt;
        dryC = dr;
    }

    private void buildTintTable() {
        for (var e : OrganizeData.getBlockIds().entrySet()) {
            int id = e.getValue() & 0xFFF;
            String p = e.getKey().replace("minecraft:", "");
            byte t = 0;

            if (p.equals("grass_block") || p.equals("short_grass") || p.equals("tall_grass")
                    || p.equals("fern") || p.equals("large_fern") || p.equals("bush")
                    || p.equals("sugar_cane") || p.equals("potted_fern")
                    || p.equals("attached_melon_stem") || p.equals("attached_pumpkin_stem")
                    || p.equals("melon_stem") || p.equals("pumpkin_stem")) {
                t = 1;
            } else if (p.equals("spruce_leaves")) {
                t = 5;
            } else if (p.equals("birch_leaves")) {
                t = 6;
            } else if ((p.endsWith("_leaves")
                    && !p.equals("cherry_leaves") && !p.equals("pale_oak_leaves")
                    && !p.equals("azalea_leaves") && !p.equals("flowering_azalea_leaves")
                    && !p.contains("poplar"))
                    || p.equals("vine") || p.equals("lily_pad")) {
                t = 2;
            } else if (p.equals("water") || p.equals("bubble_column") || p.equals("water_cauldron")) {
                t = 3;
            } else if (p.equals("leaf_litter")) {
                t = 4;
            }
            tintType[id] = t;
            grassTop[id] = p.equals("grass_block");
        }
    }

    private static NativeImage loadTileset() throws IOException {
        Path tileset = FabricLoader.getInstance().getConfigDir().resolve("IsoMap/Tileset/IsoMap_TileSet.png");
        if (Files.exists(tileset)) {
            try (InputStream in = Files.newInputStream(tileset)) {
                return NativeImage.read(in);
            }
        }
        String fallback = "/textures/IsoMap_TileSet_Fallback.png";
        try (InputStream in = MapSession.class.getResourceAsStream(fallback)) {
            if (in == null) throw new FileNotFoundException("Missing resource: " + fallback);
            return NativeImage.read(in);
        }
    }

    private void prepareAtlas(NativeImage ts) {
        atlasW = ts.getWidth();
        atlasH = ts.getHeight();
        int[] a = new int[atlasW * atlasH];
        for (int y = 0; y < atlasH; y++)
            for (int x = 0; x < atlasW; x++)
                a[y * atlasW + x] = ts.getPixel(x, y);

        boolean[] o = new boolean[4096];
        boolean[] bl = new boolean[4096];
        for (int id = 1; id < 4096; id++) {
            int ax = (id % 50) * 4, ay = (id / 50) * 4;
            if (ax + 3 >= atlasW || ay + 3 >= atlasH) continue;
            boolean allOpaque = true, allClear = true;
            for (int dy = 0; dy < 4; dy++) {
                for (int dx = 0; dx < 4; dx++) {
                    int al = a[(ay + dy) * atlasW + ax + dx] >>> 24;
                    if (al != 255) allOpaque = false;
                    if (al != 0) allClear = false;
                }
            }
            o[id] = allOpaque && tintType[id] != 3;
            bl[id] = allClear;
        }
        atlas = a;
        opq = o;
        blank = bl;
    }
    private static int blockAt(int[][][] b, int rot, int vx, int vz, int sy) {
        int m = b.length - 1;
        int v = switch (rot) {
            case 1  -> b[vz][sy][m - vx];
            case 2  -> b[m - vx][sy][m - vz];
            case 3  -> b[m - vz][sy][vx];
            default -> b[vx][sy][vz];
        };
        return v & 0xFFF;
    }

    private static int blockLight(int[][][] b, int sx, int sy, int sz) {
        int n = b.length;
        int light = b[sx][sy][sz] >>> 12;
        if (sy + 1 < n) light = Math.max(light, b[sx][sy + 1][sz] >>> 12);
        if (sy > 0)     light = Math.max(light, b[sx][sy - 1][sz] >>> 12);
        if (sx + 1 < n) light = Math.max(light, b[sx + 1][sy][sz] >>> 12);
        if (sx > 0)     light = Math.max(light, b[sx - 1][sy][sz] >>> 12);
        if (sz + 1 < n) light = Math.max(light, b[sx][sy][sz + 1] >>> 12);
        if (sz > 0)     light = Math.max(light, b[sx][sy][sz - 1] >>> 12);
        return light;
    }
    private static int biomeAt(int[][][] biomes, int N, int sx, int sy, int sz) {
        if (biomes == null) return -1;
        int bl = biomes.length;
        return biomes[sx * bl / N][sy * bl / N][sz * bl / N];
    }
    private static int multiply(int argb, int tint) {
        int r = (argb >> 16 & 255) * (tint >> 16 & 255) / 255;
        int g = (argb >> 8  & 255) * (tint >> 8  & 255) / 255;
        int b = (argb       & 255) * (tint       & 255) / 255;
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }
    private static int shaded(int argb, float shade) {
        int r = (int) ((argb >> 16 & 255) * shade);
        int g = (int) ((argb >> 8 & 255) * shade);
        int b = (int) ((argb & 255) * shade);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }
}