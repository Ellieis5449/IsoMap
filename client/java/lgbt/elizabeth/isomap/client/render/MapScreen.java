package lgbt.elizabeth.isomap.client.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import lgbt.elizabeth.isomap.client.data.CreateChunkFile;
import lgbt.elizabeth.isomap.client.IsomapClient;
import lgbt.elizabeth.isomap.client.config.IsoMapConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.NonNull;

import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.util.concurrent.*;


import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static lgbt.elizabeth.isomap.client.data.CreateChunkFile.getWorldIdentifier;

public class MapScreen extends Screen {

    private volatile boolean open = true;

    //Screen pan
    private float panX = 0;
    private float panY = 0;

    float scale = 1;

    private static final float MIN_SCALE = 0.005f;
    private static final float MAX_SCALE = 10.0f;
    private static final float ZOOM_FACTOR = 1.15f;

    private boolean originSet = false;
    private int originCx = 0;
    private int originCz = 0;

    //Render Angle and Direction
    static boolean straight;
    private static int direction;

    private final static int WORKERS = IsoMapConfig.workersPerCell;

    private boolean regionOrderDirty = false;

    private boolean workersStarted = false;

    //Others
    private record Coverage(long[] any, long[] opaque) {}

    private record Entry(Path path, int cx, int cz, int idx, Coverage cov) {}

    private record SectionFile(Path path, int x, int z, int y) {}

    private record TileResult(int rcx, int rcz, int idx, int w, int[] argb, short[] z) {}
    record Tile(int cx, int cz, int idx, int w, int[] argb, short[] z) {
        int depth() { return ((straight ? cz : cx + cz) * 32) + 2 * 16 * idx; }
    }

    private record RegionBuffer(Region region, int[] buf) {}



    private final AtomicInteger pendingCount = new AtomicInteger();

    private final ExecutorService rebuildExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() - 1),
            r -> { Thread th = new Thread(r); th.setDaemon(true); return th; });

    private final ExecutorService batchPool = Executors.newFixedThreadPool(
            WORKERS * Math.max(1, IsoMapConfig.ySplit),
            r -> { Thread th = new Thread(r); th.setDaemon(true); return th; });

    private final Queue<TileResult> pendingTiles = new ConcurrentLinkedQueue<>();

    private final Queue<RegionBuffer> readyBuffers = new ConcurrentLinkedQueue<>();

    private final Deque<Region> dirtyQueue = new ArrayDeque<>();

    static final int R = 16;

    private final Map<Long, Region> regions = new HashMap<>();
    private final List<Region> regionOrder = new ArrayList<>();

    private final Semaphore room = new Semaphore(2000);

    private Map<Long, Long> columns = Map.of();

    public MapScreen(Component title) {
        super(title);
    }



    //Initialize Screen
    @Override
    protected void init() {
        int maxCy = 0;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            maxCy = CreateChunkFile.getMaxYSection(level);
        }

        long maxCells;

        if (IsoMapConfig.maxRadius == 0) {
            maxCells = 30000000L *maxCy;
        } else {
            int rings = Math.max(0, (int) Math.ceil((IsoMapConfig.maxRadius - IsoMapConfig.spiralCellSize / 2.0) / IsoMapConfig.spiralCellSize));
            maxCells = (2L * rings + 1) * (2L * rings + 1);
        }

        //Convert direction config value to direction and straight
        int dirIndex = IsoMapConfig.direction;
        straight = (dirIndex % 2 == 0);
        direction = straight
                ? dirIndex / 2
                : (4 - dirIndex / 2) & 3;


        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        BlockPos pos = player.blockPosition();

        if (mc.level != null) {
            CreateChunkFile.getMaxYSection(mc.level);
            loadBiomes(mc.level);
            loadBlockTints();
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        if (!originSet) {
            originCx = chunkX;
            originCz = chunkZ;
            originSet = true;
        }
        int relChunkX = chunkX - originCx;
        int relChunkZ = chunkZ - originCz;

        if (mc.level != null) {
            int sectionY = (pos.getY() - mc.level.getMinY()) >> 4;
            sectionY = Math.max(0, Math.min(maxCy, sectionY));

            panX = -(float) ((relChunkX - relChunkZ) * 32);
            panY = -(float) ((relChunkX + relChunkZ) * 16 - sectionY * 32);
        }

        if (workersStarted) return;
        workersStarted = true;

        Thread h = getThread(chunkX, chunkZ, maxCells);
        h.start();
    }

    private @NonNull Thread getThread(int chunkX, int chunkZ, long maxCells) {
        Thread t = new Thread(() -> CreateChunkFile.saveRenderDist(chunkX, chunkZ, IsomapClient.modVer));
        t.setDaemon(true);
        t.start();

        Thread h = new Thread(() -> {
            try {
                Path dir = sectionDirectory();
                Map<Long, Long> seen = new HashMap<>();
                Map<Long, Long> prev = new HashMap<>();

                while (open) {
                    boolean writerDone = !t.isAlive();   // check BEFORE listing so nothing is missed
                    Map<Long, Long> now = indexSections(dir);

                    Map<Long, Long> fresh = new HashMap<>();
                    for (var e : now.entrySet()) {
                        long ready = writerDone ? e.getValue()
                                : (e.getValue() & prev.getOrDefault(e.getKey(), 0L));
                        long f = ready & ~seen.getOrDefault(e.getKey(), 0L);
                        if (f != 0) fresh.put(e.getKey(), f);
                    }
                    prev = now;

                    if (!fresh.isEmpty()) {
                        columns = fresh;
                        renderAllSections(chunkX, chunkZ, originCx, originCz, maxCells);
                        for (var e : fresh.entrySet()) seen.merge(e.getKey(), e.getValue(), (a, b) -> a | b);
                    }

                    if (writerDone) break;
                    Thread.sleep(250);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        h.setDaemon(true);
        return h;
    }

    //Thread cleanup
    @Override
    public void removed() {

        if (tilesetImg != null) tilesetImg.close();

        open = false;
        for (Region r : regionOrder) {
            if (r.id != null) Minecraft.getInstance().getTextureManager().release(r.id);
        }
        regions.clear();
        regionOrder.clear();
        dirtyQueue.clear();
        pendingTiles.clear();
        pendingCount.set(0);
        readyBuffers.clear();
        rebuildExecutor.shutdownNow();
        batchPool.shutdownNow();

        Thread cleanup = new Thread(MapScreen::deleteBlankChunks);
        cleanup.setDaemon(true);
        cleanup.start();

        super.removed();
    }



    //Mouse drag
    @Override
    public boolean mouseDragged(@NonNull MouseButtonEvent event, double dragX, double dragY) {
        panX += (float) (dragX / scale);
        panY += (float) (dragY / scale);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        float oldScale = scale;
        float newScale = oldScale * (float) Math.pow(ZOOM_FACTOR, verticalAmount);
        newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        if (newScale == oldScale) return true;

        float mx = (float) mouseX - this.width / 2f;
        float my = (float) mouseY - this.height / 2f;

        panX += mx / newScale - mx / oldScale;
        panY += my / newScale - my / oldScale;

        scale = newScale;
        return true;
    }



    //Render Screen
    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        ClientLevel level = Minecraft.getInstance().level;
        int maxCy = level != null ? CreateChunkFile.getMaxYSection(level) : 0;

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        fileIncomingTiles();
        sortRegionsIfNeeded();
        uploadFinishedBuffers();
        startRebuilds(maxCy);

        String label = "IsoMap Version Alpha " + IsomapClient.modVer;
        int x = this.width - this.font.width(label);
        int y = this.height - this.font.lineHeight;
        graphics.text(this.font, label, x, y, 0xFFFFFFFF, true);
        drawRegions(graphics);
    }

    //Move finished tiles from the worker threads into their regions
    private void fileIncomingTiles() {
        long deadline = System.nanoTime() + 4_000_000;

        TileResult tr;
        while ((tr = pendingTiles.poll()) != null) {
            pendingCount.decrementAndGet();
            room.release();

            int rx = Math.floorDiv(tr.rcx(), R), rz = Math.floorDiv(tr.rcz(), R);
            int ry = tr.idx() / R;
            long key = (((long) rx & 0xFFFFF) << 40) | (((long) rz & 0xFFFFF) << 20) | ry;

            Region region = regions.get(key);
            if (region == null) {
                region = new Region(rx, rz, ry);
                regions.put(key, region);
                regionOrder.add(region);
                regionOrderDirty = true;
            }

            region.tiles.add(new Tile(tr.rcx(), tr.rcz(), tr.idx(), tr.w(), tr.argb(), tr.z()));
            region.dirty = true;
            if (!region.queued) {
                region.queued = true;
                dirtyQueue.add(region);
            }

            if (System.nanoTime() > deadline) break;
        }
    }

    //Keep regions in back-to-front draw order
    private void sortRegionsIfNeeded() {
        if (!regionOrderDirty) return;

        regionOrder.sort(Comparator.comparingInt((Region q) -> (straight ? q.rz : q.rx + q.rz) + q.ry)
                .thenComparingInt(q -> q.ry)
                .thenComparingInt(q -> q.rx));
        regionOrderDirty = false;
    }

    //Upload composited pixel buffers to the GPU
    private void uploadFinishedBuffers() {
        long deadline = System.nanoTime() + 3_000_000;

        RegionBuffer rb;
        while ((rb = readyBuffers.poll()) != null) {
            Region region = rb.region();
            region.applyBuffer(rb.buf());
            region.building = false;

            if (region.dirty && !region.queued) {
                region.queued = true;
                dirtyQueue.add(region);
            }

            if (System.nanoTime() >= deadline) break;
        }
    }

    //Start background composites for regions that have new tiles
    private void startRebuilds(int maxCy) {
        if (rebuildExecutor.isShutdown()) return;
        long deadline = System.nanoTime() + 4_000_000;
        int toCheck = dirtyQueue.size();

        for (int i = 0; i < toCheck && System.nanoTime() < deadline; i++) {
            Region r = dirtyQueue.poll();
            assert r != null;
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

            List<Tile> snapshot = new ArrayList<>(r.tiles);
            rebuildExecutor.submit(() -> {
                try {
                    int[] buf = r.compositeBuffer(snapshot, maxCy);
                    readyBuffers.add(new RegionBuffer(r, buf));
                } catch (Throwable t) {
                    t.printStackTrace();
                    r.building = false;
                }
            });
        }
    }

    //Draw every region that is on screen
    private void drawRegions(GuiGraphicsExtractor graphics) {
        float invScale = 1f / scale;
        int margin = 64;
        int worldMinX = (int) Math.floor((-this.width  / 2f) * invScale - panX) - margin;
        int worldMaxX = (int) Math.ceil ((this.width   / 2f) * invScale - panX) + margin;
        int worldMinY = (int) Math.floor((-this.height / 2f) * invScale - panY) - margin;
        int worldMaxY = (int) Math.ceil ((this.height  / 2f) * invScale - panY) + margin;

        graphics.pose().pushMatrix();
        graphics.pose().translate(this.width / 2f, this.height / 2f);
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(panX, panY);

        for (Region r : regionOrder) {
            if (r.tex == null) continue;
            if (r.x0 + r.w < worldMinX || r.x0 > worldMaxX) continue;
            if (r.y0 + r.h < worldMinY || r.y0 > worldMaxY) continue;

            graphics.blit(RenderPipelines.GUI_TEXTURED, r.id,
                    r.x0, r.y0,
                    0, 0,
                    r.w, r.h,
                    r.w, r.h);
        }

        graphics.pose().popMatrix();
    }



    //Render chunks
    private NativeImage tilesetImg;

    private void renderAllSections(int px, int pz, int originCx, int originCz, double maxCells) throws IOException {
        int maxCy = currentMaxCy();
        if (tilesetImg == null) {
            tilesetImg = loadTileset();
            prepareAtlas(tilesetImg);
        }
        NativeImage tileset = tilesetImg;
        Path dir = sectionDirectory();

        final int T = 8;

        int cyLo = Math.max(0, Math.floorDiv(IsoMapConfig.minSectionedYBlock, 16 * T));
        int cyHi = Math.min(maxCy / T, Math.floorDiv(IsoMapConfig.maxSectionedYBlock, 16 * T));
        if (cyHi < cyLo) return;

        int rpx = Math.floorDiv(px, T);
        int rpz = Math.floorDiv(pz, T);

        List<Thread> bandThreads = startBandThreads(dir, cyLo, cyHi, rpx, rpz, tileset, originCx, originCz, maxCells);
        joinAll(bandThreads);
    }

    private static int currentMaxCy() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null ? CreateChunkFile.getMaxYSection(level) : 0;
    }

    private static NativeImage loadTileset() throws IOException {
        Path tileset = FabricLoader.getInstance().getConfigDir().resolve("IsoMap/Tileset/IsoMap_TileSet.png");

        if (Files.exists(tileset)) {
            try (InputStream stream = Files.newInputStream(tileset)) {
                return NativeImage.read(stream);
            }
        }

        String fallback = "/textures/IsoMap_TileSet_Fallback.png";
        try (InputStream stream = MapScreen.class.getResourceAsStream(fallback)) {
            if (stream == null) {
                throw new FileNotFoundException("Missing resource: " + fallback);
            }
            return NativeImage.read(stream);
        }
    }

    private static Path sectionDirectory() {
        String worldName = getWorldIdentifier();
        String seed = IsomapClient.getSeedString();
        String dimension = CreateChunkFile.getWorldDimension();

        return FabricLoader.getInstance().getConfigDir()
                .resolve("IsoMap/" + worldName + "/" + seed + "/" + dimension);
    }



    private List<Thread> startBandThreads(Path dir, int cyLo, int cyHi, int px, int pz, NativeImage tileset, int originCx, int originCz, double maxCells) {
        int total = cyHi - cyLo + 1;
        int bands = Math.min(Math.max(1, IsoMapConfig.ySplit), total);
        int bandSize = (total + bands - 1) / bands;

        List<Thread> threads = new ArrayList<>(bands);
        for (int i = 0; i < bands; i++) {
            final int lo = cyLo + i * bandSize;
            final int hi = Math.min(cyHi, lo + bandSize - 1);
            if (lo > hi) break;

            Thread bandThread = new Thread(() ->
                    renderBand(dir, lo, hi, px, pz, tileset, originCx, originCz, maxCells));
            bandThread.setDaemon(true);
            threads.add(bandThread);
            bandThread.start();
        }
        return threads;
    }

    private static void joinAll(List<Thread> threads) {
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void renderBand(Path dir, int cyLo, int cyHi, int px, int pz, NativeImage tileset, int originCx, int originCz, double maxCells) {
        final long radiusRegions = (IsoMapConfig.maxRadius + 7) / 8;
        final long radiusSq = radiusRegions * radiusRegions;
        final int batchSize = Math.max(1, IsoMapConfig.spiralCellSize * IsoMapConfig.spiralCellSize);

        List<Map.Entry<Long, Long>> cols = new ArrayList<>(columns.entrySet());
        cols.sort(Comparator.comparingLong(e -> {
            long dx = (int) (e.getKey() >> 32) - px;
            long dz = e.getKey().intValue() - pz;
            return dx * dx + dz * dz;
        }));

        List<List<SectionFile>> buckets = newBuckets();
        int inBatch = 0;

        for (var e : cols) {
            if (!open) return;
            int x = (int) (e.getKey() >> 32);
            int z = e.getKey().intValue();

            if (IsoMapConfig.maxRadius > 0) {
                long ddx = x - px, ddz = z - pz;
                if (ddx * ddx + ddz * ddz > radiusSq) continue;
            }

            long bits = e.getValue();
            for (int cy = cyHi; cy >= cyLo; cy--) {
                if ((bits & (1L << cy)) == 0) continue;
                Path p = dir.resolve("r." + x + "." + cy + "." + z + ".fops");
                buckets.get(((x & 1) | ((z & 1) << 1)) % WORKERS).add(new SectionFile(p, x, z, cy));
            }

            if (++inBatch >= batchSize) {
                if (!processBuckets(buckets, tileset, originCx, originCz)) return;
                buckets = newBuckets();
                inBatch = 0;
            }
        }
        processBuckets(buckets, tileset, originCx, originCz);
    }

    private static int ringOf(int batchIndex) {
        return batchIndex == 0 ? 0 : (int) Math.ceil((Math.sqrt(batchIndex + 1) - 1) / 2);
    }

    //Spiral
    private int[] cellOrigin(int cellIndex, int px, int pz) {
        int gx = 0;
        int gz = 0;

        if (cellIndex > 0) {
            int k = (int) Math.ceil((Math.sqrt(cellIndex + 1) - 1) / 2);
            int m = cellIndex - (2 * k - 1) * (2 * k - 1);
            int side = m / (2 * k);
            int off = m % (2 * k);

            switch (side) {
                case 0 -> { gx = k;           gz = -k + 1 + off; }
                case 1 -> { gx = k - 1 - off; gz = k; }
                case 2 -> { gx = -k;          gz = k - 1 - off; }
                default -> { gx = -k + 1 + off; gz = -k; }
            }
        }

        int half = IsoMapConfig.spiralCellSize / 2;
        return new int[]{px - half + gx * IsoMapConfig.spiralCellSize, pz - half + gz * IsoMapConfig.spiralCellSize};
    }

    private boolean collectCellFiles(Path dir, int[] origin, int px, int pz, int cyLo, int cyHi, long radiusSq, List<List<SectionFile>> buckets) {
        boolean any = false;

        for (int dx = 0; dx < IsoMapConfig.spiralCellSize; dx++) {
            for (int dz = 0; dz < IsoMapConfig.spiralCellSize; dz++) {
                int x = origin[0] + dx;
                int z = origin[1] + dz;

                if (IsoMapConfig.maxRadius > 0) {
                    long ddx = x - px, ddz = z - pz;
                    if (ddx * ddx + ddz * ddz > radiusSq) continue;
                }

                long cols = columns.getOrDefault(colKey(x, z), 0L);
                if (cols == 0) continue;
                for (int cy = cyHi; cy >= cyLo; cy--) {
                    if ((cols & (1L << cy)) == 0) continue;
                    Path p = dir.resolve("r." + x + "." + cy + "." + z + ".fops");
                    buckets.get((x & 1) | ((z & 1) << 1)).add(new SectionFile(p, x, z, cy));
                    any = true;
                }
            }
        }
        return any;
    }

    private static long colKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static Map<Long, Long> indexSections(Path dir) {
        Map<Long, Long> cols = new HashMap<>();
        if (!Files.isDirectory(dir)) return cols;

        try (var stream = Files.newDirectoryStream(dir, "r.*.fops")) {
            for (Path p : stream) {
                String n = p.getFileName().toString();
                // r.<x>.<y>.<z>.fops -> ["r", x, y, z]
                String[] a = n.substring(0, n.length() - 5).split("\\.");
                if (a.length != 4) continue;
                try {
                    int x  = Integer.parseInt(a[1]);
                    int cy = Integer.parseInt(a[2]);
                    int z  = Integer.parseInt(a[3]);
                    if (cy < 0 || cy >= 64) continue;
                    cols.merge(colKey(x, z), 1L << cy, (o, v) -> o | v);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return cols;
    }

    private static List<List<SectionFile>> newBuckets() {
        List<List<SectionFile>> buckets = new ArrayList<>(WORKERS);
        for (int i = 0; i < WORKERS; i++) buckets.add(new ArrayList<>());
        return buckets;
    }

    private boolean processBuckets(List<List<SectionFile>> buckets, NativeImage tileset, int originCx, int originCz) {
        if (!open || batchPool.isShutdown()) return false;

        List<Callable<Void>> tasks = new ArrayList<>(WORKERS);
        for (List<SectionFile> bucket : buckets) {
            if (bucket.isEmpty()) continue;
            tasks.add(() -> {
                processSector(bucket, tileset, originCx, originCz, straight, direction);
                return null;
            });
        }

        try {
            for (Callable<Void> t : tasks) batchPool.submit(t);
            return open;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }



    //Process chunk sections
    private void processSector(List<SectionFile> sector, NativeImage tileset, int originCx, int originCz, boolean straight, int direction) {

        final int n = 128;
        final int w = straight ? 2 * n : 4 * n;
        final int h = 4 * n;
        final int rot = straight ? (4 - direction) & 3 : (direction + 3) & 3;

        for (SectionFile sf : sector) {
            if (!open) return;
            if (!waitForRoom()) return;
            try {
                CreateChunkFile.RegionParse[] region = CreateChunkFile.decodeRegion(sf.x(), sf.z(), sf.y());
                var combined = CreateChunkFile.combineRegion(region);
                int[][][] blocks = combined.blocks();
                int[][][] biomes = combined.biomes();

                short[] depth = new short[w * h];
                int[] argb = buildSectionImage(blocks, biomes, tileset, depth, straight, direction,
                        sf.x() * blocks.length, sf.y() * blocks.length, sf.z() * blocks.length);

                if (argb == null) { room.release(); continue; }

                int[] rel = rotatedChunkPos(rot, sf.x() * 8 - originCx, sf.z() * 8 - originCz, 8);
                pendingTiles.add(new TileResult(rel[0], rel[1], sf.y() * 8, w, argb, depth));
                pendingCount.incrementAndGet();
            } catch (Throwable ex) {
                room.release();
                ex.printStackTrace();
            }
        }
    }

    private boolean waitForRoom() {
        try {
            while (!room.tryAcquire(50, TimeUnit.MILLISECONDS)) {
                if (!open) return false;
            }
            return open;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int[] rotatedChunkPos(int rot, int dx, int dz, int size) {
        return switch (rot) {
            case 1  -> new int[]{-dz - size, dx};
            case 2  -> new int[]{-dx - size, -dz - size};
            case 3  -> new int[]{dz,         -dx - size};
            default -> new int[]{dx,         dz};
        };
    }

    //Create NativeImage
    private int[] buildSectionImage(int[][][] blocks, int[][][] biomes, NativeImage tileset, short[] depth, boolean straight, int direction, int originX, int originY, int originZ) {
        if (blocks == null) return null;
        boolean drawn = false;

        final int N = blocks.length;
        final int w = straight ? 2 * N : 4 * N;
        final int h = 4 * N;
        int[] image = new int[w * h];

        final int atlasCellsPerRow = 50;
        final int tileW = 4, tileH = 4;
        final int[] atlas = this.atlas;
        final boolean[] opq = this.opq;
        final int atlasW = this.atlasW, atlasH = this.atlasH;

        final int rot = straight ? (4 - direction) & 3 : (direction + 3) & 3;
        final float floor = 0.15f + 0.85f * (IsoMapConfig.brightness / 100);

        final int steps = straight ? N : 2 * N - 1;

        for (int sy = 0; sy < N; sy++) {
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
                    if (id == 0) continue;

                    if (sy < N - 1 && vz < N - 1 && (straight || vx < N - 1)
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
                        Biome[] bm = biomeById;
                        Biome biome = (bid >= 0 && bid < bm.length) ? bm[bid] : null;
                        if (biome != null) {
                            int wx = originX + sx;
                            int wz = originZ + sz;
                            int rgb = switch (tt) {
                                case 1  -> biome.getGrassColor(wx, wz);
                                case 2  -> biome.getFoliageColor();
                                case 4  -> biome.getDryFoliageColor();
                                default -> biome.getWaterColor();
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

                            int i = ty * w + tx;
                            image[i] = over(shaded(argb, shade), image[i]);
                            depth[i] = (short) d;
                            drawn = true;
                        }
                    }
                }
            }
        }
        return drawn ? image : null;
    }


    private volatile Biome[] biomeById = new Biome[0];

    private void loadBiomes(ClientLevel level) {
        try {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("IsoMap/biome_index.json");
            InputStream in = Files.exists(p) ? Files.newInputStream(p)
                    : MapScreen.class.getResourceAsStream("/biomes.json");
            System.out.println("[IsoMap] biomes.json found=" + (in != null) + " configPath=" + p + " exists=" + Files.exists(p));
            if (in == null) return;

            JsonObject json;
            try (InputStreamReader r = new InputStreamReader(in)) {
                json = JsonParser.parseReader(r).getAsJsonObject();
            }
            Registry<Biome> reg = level.registryAccess().lookupOrThrow(Registries.BIOME);

            int max = json.entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).max().orElse(0) + 1;
            Biome[] arr = new Biome[max];
            for (var e : json.entrySet()) {
                arr[e.getValue().getAsInt()] = reg.getValue(Identifier.parse(e.getKey()));
            }
            biomeById = arr;
            System.out.println("[IsoMap] biomes loaded: " + Arrays.stream(arr).filter(Objects::nonNull).count());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void buildTintTable(Map<Integer, String> idToName) {
        for (var e : idToName.entrySet()) {
            int id = e.getKey() & 0xFFF;
            String p = e.getValue().replace("minecraft:", "");
            byte t = 0;

            if (p.equals("grass_block") || p.equals("short_grass") || p.equals("tall_grass")
                    || p.equals("fern") || p.equals("large_fern") || p.equals("bush")
                    || p.equals("sugar_cane") || p.equals("potted_fern")
                    || p.equals("attached_melon_stem") || p.equals("attached_pumpkin_stem")
                    || p.equals("melon_stem") || p.equals("pumpkin_stem")) {
                t = 1;                                   // grass colormap
            } else if ((p.endsWith("_leaves")
                    && !p.equals("cherry_leaves") && !p.equals("pale_oak_leaves")
                    && !p.equals("azalea_leaves") && !p.equals("flowering_azalea_leaves"))
                    || p.equals("vine") || p.equals("lily_pad")) {
                t = 2;                                   // foliage colormap
            } else if (p.equals("water") || p.equals("bubble_column") || p.equals("water_cauldron")) {
                t = 3;                                   // water color
            } else if (p.equals("leaf_litter")) {
                t = 4;                                   // dry foliage colormap
            }

            tintType[id] = t;
            grassTop[id] = p.equals("grass_block");
        }
    }

    private void loadBlockTints() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("IsoMap/block_index.json");
        System.out.println("[IsoMap] loading block index from " + p + " exists=" + Files.exists(p));
        if (!Files.exists(p)) return;

        try (InputStream in = Files.newInputStream(p);
             InputStreamReader reader = new InputStreamReader(in)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            Map<Integer, String> idToName = new HashMap<>();
            for (var e : json.entrySet()) idToName.put(e.getValue().getAsInt(), e.getKey());
            buildTintTable(idToName);

            long n = 0;
            for (byte t : tintType) if (t != 0) n++;
            System.out.println("[IsoMap] tinted block ids: " + n);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
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

    private final byte[] tintType = new byte[4096];   // 0 none, 1 grass, 2 foliage, 3 water
    private final boolean[] grassTop = new boolean[4096];

    private static int multiply(int argb, int tint) {
        int r = (argb >> 16 & 255) * (tint >> 16 & 255) / 255;
        int g = (argb >> 8  & 255) * (tint >> 8  & 255) / 255;
        int b = (argb       & 255) * (tint       & 255) / 255;
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private static int biomeAt(int[][][] biomes, int N, int sx, int sy, int sz) {
        if (biomes == null) return -1;
        int bl = biomes.length;   // 32 if it's per 4x4x4 cell, 128 if per block
        return biomes[sx * bl / N][sy * bl / N][sz * bl / N];
    }



    private static int shaded(int argb, float shade) {
        int r = (int) ((argb >> 16 & 255) * shade);
        int g = (int) ((argb >> 8 & 255) * shade);
        int b = (int) ((argb & 255) * shade);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    static int over(int src, int dst) {
        int sa = src >>> 24;
        int da = dst >>> 24;
        if (sa == 255 || da == 0) return src;
        int outA = sa + da * (255 - sa) / 255;
        int r = ch(src >> 16 & 255, sa, dst >> 16 & 255, da, outA);
        int g = ch(src >> 8 & 255, sa, dst >> 8 & 255, da, outA);
        int b = ch(src & 255, sa, dst & 255, da, outA);
        return outA << 24 | r << 16 | g << 8 | b;
    }

    private static int ch(int s, int sa, int d, int da, int outA) {
        return (s * sa * 255 + d * da * (255 - sa)) / (outA * 255);
    }


    //Delete Blank Chunks
    private static void deleteBlankChunks() {

        Path dir = FabricLoader.getInstance().getConfigDir().resolve("IsoMap");

        List<Path> pngs;
        try (var files = Files.list(dir)) {
            pngs = files.filter(p -> p.getFileName().toString().endsWith(".fops")).toList();
        } catch (IOException ignored) {
            return;
        }

        List<Entry> entries = new ArrayList<>();
        Map<Long, Coverage> byPos = new HashMap<>();

        for (Path p : pngs) {
            try {
                String name = p.getFileName().toString();
                String[] parts = name.substring(0, name.lastIndexOf('.')).split("_");
                int cx = Integer.parseInt(parts[0]);
                int cz = Integer.parseInt(parts[1]);
                int idx = Integer.parseInt(parts[2]);

                Coverage cov;
                try (InputStream in = Files.newInputStream(p);
                     NativeImage img = NativeImage.read(in)) {
                    if (img.getWidth() != 64 || img.getHeight() != 64) continue;
                    cov = readCoverage(img);
                }

                if (isEmpty(cov.any())) {
                    Files.delete(p);
                    continue;
                }
                entries.add(new Entry(p, cx, cz, idx, cov));
                byPos.put(key(cx, cz, idx), cov);
            } catch (Exception ignored) {

            }
        }

        for (Entry e : entries) {
            try {
                if (isFullyCovered(e, byPos)) Files.delete(e.path());
            } catch (Exception ignored) {
            }
        }
    }

    private static Coverage readCoverage(NativeImage img) {
        long[] any = new long[64];
        long[] opaque = new long[64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int a = img.getPixel(x, y) >>> 24;
                if (a != 0) any[y] |= 1L << x;
                if (a == 255) opaque[y] |= 1L << x;
            }
        }
        if (Arrays.equals(any, opaque)) any = opaque;
        return new Coverage(any, opaque);
    }

    private static boolean isFullyCovered(Entry e, Map<Long, Coverage> byPos) {
        int maxCy = 0;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            maxCy = CreateChunkFile.getMaxYSection(level);
        }

        long[] remaining = e.cov().any().clone();

        for (int didx = -e.idx(); didx <= maxCy - e.idx(); didx++) {
            for (int s = 2 * didx - 3; s <= 2 * didx + 3; s++) {
                for (int d = -1; d <= 1; d++) {
                    if (((s + d) & 1) != 0) continue;
                    int dcx = (s + d) / 2, dcz = (s - d) / 2;
                    if (dcx == 0 && dcz == 0 && didx == 0) continue;

                    int ocx = e.cx() + dcx, ocz = e.cz() + dcz, oidx = e.idx() + didx;
                    Coverage other = byPos.get(key(ocx, ocz, oidx));
                    if (other == null) continue;
                    if (!drawnAfter(e.cx(), e.cz(), e.idx(), ocx, ocz, oidx)) continue;

                    subtractOpaque(remaining, other.opaque(), d * 32, s * 16 - didx * 32);
                }
            }
            if (isEmpty(remaining)) return true;
        }
        return isEmpty(remaining);
    }

    private static void subtractOpaque(long[] remaining, long[] covering, int dx, int dy) {
        for (int y = 0; y < 64; y++) {
            int by = y - dy;
            if (by < 0 || by >= 64) continue;
            long bits = covering[by];
            bits = dx >= 0 ? bits << dx : bits >>> -dx;
            remaining[y] &= ~bits;
        }
    }

    private static boolean drawnAfter(int acx, int acz, int aidx, int bcx, int bcz, int bidx) {
        int arx = Math.floorDiv(acx, R), arz = Math.floorDiv(acz, R);
        int brx = Math.floorDiv(bcx, R), brz = Math.floorDiv(bcz, R);
        if (arx + arz != brx + brz) return brx + brz > arx + arz;
        if (arx != brx) return brx > arx;
        int da = depthOf(acx, acz, aidx), db = depthOf(bcx, bcz, bidx);
        if (da != db) return db > da;
        return bidx > aidx;
    }

    private static int depthOf(int cx, int cz, int idx) {
        return (cx + cz) * 32 + 2 * 16 * idx;
    }

    private static long key(int cx, int cz, int idx) {
        return (((long) cx & 0xFFFFFFL) << 40) | (((long) cz & 0xFFFFFFL) << 16) | (idx & 0xFFFFL);
    }

    //Helper Methods
    private static boolean isEmpty(long[] rows) {
        for (long r : rows) if (r != 0) return false;
        return true;
    }

    private int[] atlas = new int[0];
    private int atlasW, atlasH;
    private boolean[] opq = new boolean[4096];

    private void prepareAtlas(NativeImage ts) {
        atlasW = ts.getWidth();
        atlasH = ts.getHeight();
        int[] a = new int[atlasW * atlasH];
        for (int y = 0; y < atlasH; y++)
            for (int x = 0; x < atlasW; x++)
                a[y * atlasW + x] = ts.getPixel(x, y);

        boolean[] o = new boolean[4096];
        for (int id = 1; id < 4096; id++) {
            int ax = (id % 50) * 4, ay = (id / 50) * 4;
            if (ax + 3 >= atlasW || ay + 3 >= atlasH) continue;
            boolean all = true;
            for (int dy = 0; dy < 4 && all; dy++)
                for (int dx = 0; dx < 4; dx++)
                    if ((a[(ay + dy) * atlasW + ax + dx] >>> 24) != 255) { all = false; break; }
            o[id] = all;
        }
        atlas = a;
        opq = o;
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
}