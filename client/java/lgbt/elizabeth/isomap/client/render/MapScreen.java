package lgbt.elizabeth.isomap.client.render;

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
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import java.io.FileNotFoundException;
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

    private record TileResult(int rcx, int rcz, int idx, int w, int[] argb, byte[] z) {}
    record Tile(int cx, int cz, int idx, int w, int[] argb, byte[] z) {
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

        Thread t = new Thread(() -> CreateChunkFile.saveRenderDist(chunkX, chunkZ, IsomapClient.modVer));
        t.setDaemon(true);
        t.start();

        Thread h = new Thread(() -> {
            try {
                renderAllSections(chunkX, chunkZ, originCx, originCz, maxCells);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        h.setDaemon(true);
        h.start();
    }

    //Thread cleanup
    @Override
    public void removed() {
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
    private void renderAllSections(int px, int pz, int originCx, int originCz, double maxCells) throws IOException {
        int maxCy = currentMaxCy();
        NativeImage tileset = loadTileset();
        Path dir = sectionDirectory();

        int cyLo = Math.max(0, Math.floorDiv(IsoMapConfig.minSectionedYBlock, 16));
        int cyHi = Math.min(maxCy, Math.floorDiv(IsoMapConfig.maxSectionedYBlock, 16));
        if (cyHi < cyLo) return;

        List<Thread> bandThreads = startBandThreads(dir, cyLo, cyHi, px, pz, tileset, originCx, originCz, maxCells);
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
        final long radiusSq = (long) IsoMapConfig.maxRadius * IsoMapConfig.maxRadius;

        int currentRing = 0;
        boolean ringHadFiles = false;
        int emptyRings = 0;

        for (int batch = 0; batch < maxCells && open; batch++) {

            int ring = ringOf(batch);
            if (ring != currentRing) {
                emptyRings = ringHadFiles ? 0 : emptyRings + 1;
                if (emptyRings >= 3) return;
                currentRing = ring;
                ringHadFiles = false;
            }

            List<List<SectionFile>> buckets = newBuckets();
            boolean any = collectCellFiles(dir, cellOrigin(batch, px, pz), px, pz, cyLo, cyHi, radiusSq, buckets);

            if (!any) continue;
            ringHadFiles = true;

            if (!processBuckets(buckets, tileset, originCx, originCz)) return;
        }
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

    private static boolean collectCellFiles(Path dir, int[] origin, int px, int pz, int cyLo, int cyHi, long radiusSq, List<List<SectionFile>> buckets) {
        boolean any = false;

        for (int dx = 0; dx < IsoMapConfig.spiralCellSize; dx++) {
            for (int dz = 0; dz < IsoMapConfig.spiralCellSize; dz++) {
                int x = origin[0] + dx;
                int z = origin[1] + dz;

                if (IsoMapConfig.maxRadius > 0) {
                    long ddx = x - px, ddz = z - pz;
                    if (ddx * ddx + ddz * ddz > radiusSq) continue;
                }

                for (int cy = cyHi; cy >= cyLo; cy--) {
                    Path p = dir.resolve(x + "_" + z + "_" + cy + ".fops");
                    if (!Files.exists(p)) continue;
                    buckets.get((x & 1) | ((z & 1) << 1)).add(new SectionFile(p, x, z, cy));
                    any = true;
                }
            }
        }
        return any;
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
            batchPool.invokeAll(tasks);
            return open;
        } catch (RejectedExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }



    //Process chunk sections
    private void processSector(List<SectionFile> sector, NativeImage tileset, int originCx, int originCz, boolean straight, int direction) {
        final int w = straight ? 32 : 64;
        final int rot = straight ? (4 - direction) & 3 : (direction + 3) & 3;

        try {
            for (SectionFile sf : sector) {
                if (!open) return;
                if (!waitForRoom()) return;

                int[][][] blocks = CreateChunkFile.decodeSection(sf.x(), sf.z(), sf.y());
                byte[] depth = new byte[w * 64];
                int[][] sectionImage = buildSectionImage(blocks, tileset, depth, straight, direction);
                int[] argb = flatten(sectionImage, w);

                int[] rel = rotatedChunkPos(rot, sf.x() - originCx, sf.z() - originCz);

                pendingTiles.add(new TileResult(rel[0], rel[1], sf.y(), w, argb, depth));
                pendingCount.incrementAndGet();
            }
        } catch (Throwable t) {
            t.printStackTrace();
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

    private static int[] rotatedChunkPos(int rot, int dx, int dz) {
        return switch (rot) {
            case 1  -> new int[]{-dz - 1, dx};
            case 2  -> new int[]{-dx - 1, -dz - 1};
            case 3  -> new int[]{dz,      -dx - 1};
            default -> new int[]{dx,      dz};
        };
    }

    //Create NativeImage
    private int[][] buildSectionImage(int[][][] blocks, NativeImage tileset, byte[] depth, boolean straight, int direction) {
        final int w = straight ? 32 : 64;
        int[][] image = new int[w][64];
        if (blocks == null) return image;

        final int atlasCellsPerRow = 50;
        final int tileW = 4, tileH = 4;

        final int rot = straight ? (4 - direction) & 3 : (direction + 3) & 3;
        final int steps = straight ? 16 : 31;

        final float floor = 0.15f + 0.85f * (IsoMapConfig.brightness / 100);

        for (int sy = 0; sy < 16; sy++) {
            for (int step = 0; step < steps; step++) {
                for (int vx = 15; vx >= 0; vx--) {
                    int vz = straight ? step : step - vx;
                    if (vz < 0 || vz >= 16) continue;

                    int sx, sz;
                    switch (rot) {
                        case 1  -> { sx = vz;      sz = 15 - vx; }
                        case 2  -> { sx = 15 - vx; sz = 15 - vz; }
                        case 3  -> { sx = 15 - vz; sz = vx; }
                        default -> { sx = vx;      sz = vz; }
                    }

                    int id = blocks[sx][sy][sz] & 0xFFF;
                    if (id == 0) continue;

                    float shade = floor + (1f - floor) * (blockLight(blocks, sx, sy, sz) / 15f);

                    int ax = (id % atlasCellsPerRow) * tileW;
                    int ay = (id / atlasCellsPerRow) * tileH;

                    int px, py, d;
                    if (straight) {
                        px = 2 * vx;
                        py = 30 + 2 * vz - 2 * sy;
                        d  = vz + sy;
                    } else {
                        px = 30 + 2 * (vx - vz);
                        py = 30 + vx + vz - 2 * sy;
                        d  = vx + vz + sy;
                    }

                    for (int dx = 0; dx < tileW; dx++) {
                        for (int dy = 0; dy < tileH; dy++) {
                            int tx = px + dx, ty = py + dy;
                            if (tx < 0 || tx >= w || ty < 0 || ty >= 64) continue;

                            int argb = tileset.getPixel(ax + dx, ay + dy);
                            if ((argb >>> 24) == 0) continue;

                            image[tx][ty] = over(shaded(argb, shade), image[tx][ty]);
                            depth[ty * w + tx] = (byte) d;
                        }
                    }
                }
            }
        }
        return image;
    }

    private static int blockLight(int[][][] blocks, int sx, int sy, int sz) {
        int light = blocks[sx][sy][sz] >>> 12;
        if (light == 0 && sy < 15) {
            light = blocks[sx][sy + 1][sz] >>> 12;
        }
        return light;
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

    private static int[] flatten(int[][] image, int w) {
        int[] argb = new int[w * 64];
        for (int ty = 0; ty < 64; ty++)
            for (int tx = 0; tx < w; tx++)
                argb[ty * w + tx] = image[tx][ty];
        return argb;
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

}