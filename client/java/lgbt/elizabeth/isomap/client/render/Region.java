package lgbt.elizabeth.isomap.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import lgbt.elizabeth.isomap.client.data.CreateChunkFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import lgbt.elizabeth.isomap.client.render.MapScreen.Tile;

public class Region {

    static final int R = MapScreen.R;          // region size in chunks

    // Must match processSector's `n`
    static final int N = 128;                  // blocks per tile side
    static final int T = N / 16;               // chunks per tile side (8)
    static final int ANCHOR = 2 * (N - 1);     // px offset of block (0,0,0) inside a tile image

    final boolean straight = MapScreen.straight;
    final int tileW = straight ? 2 * N : 4 * N;
    final int tileH = 4 * N;

    final int rx, rz, ry;
    final int x0, y0, w, h;
    final List<Tile> tiles = new ArrayList<>();
    boolean dirty = true;
    boolean queued = false;
    boolean building = false;
    boolean built = false;
    long lastBuildNs = 0;
    DynamicTexture tex;
    Identifier id;

    // World-space (pre-region-offset) top-left of a tile image
    private int tileLeft(int cx, int cz) {
        return straight ? cx * 32 : (cx - cz) * 32 - ANCHOR;
    }

    private int tileTop(int cx, int cz, int idx) {
        return (straight ? cz * 2 : cx + cz) * 16 - idx * 32 - ANCHOR;
    }

    Region(int rx, int rz, int ry) {
        int maxCy = 0;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            maxCy = CreateChunkFile.getMaxYSection(level);
        }

        this.rx = rx; this.rz = rz; this.ry = ry;

        // Range of tile ORIGINS that can land in this region
        int cx0 = rx * R, cx1 = cx0 + R - 1;
        int cz0 = rz * R, cz1 = cz0 + R - 1;
        int idx0 = ry * R;
        // tile idx values are multiples of T, so the highest origin is idx0 + R - T
        int idxMax = Math.max(idx0, Math.min(idx0 + R - T, maxCy));

        // The mapping is linear, so the corners give the extremes
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int cx : new int[]{cx0, cx1}) {
            for (int cz : new int[]{cz0, cz1}) {
                int left = tileLeft(cx, cz);
                minX = Math.min(minX, left);
                maxX = Math.max(maxX, left);
                minY = Math.min(minY, tileTop(cx, cz, idxMax)); // highest tile = smallest y
                maxY = Math.max(maxY, tileTop(cx, cz, idx0));   // lowest tile = largest y
            }
        }

        x0 = minX;
        y0 = minY;
        w = maxX - minX + tileW;
        h = maxY - minY + tileH;
    }

    int[] compositeBuffer(List<Tile> tilesSnapshot, int maxVisibleIdx) {
        tilesSnapshot.sort(Comparator.comparingInt(Tile::depth).thenComparingInt(Tile::idx));

        int[] buf = new int[w * h];
        int[] zbuf = new int[w * h];
        Arrays.fill(zbuf, Integer.MIN_VALUE);

        for (Tile t : tilesSnapshot) {
            if (t.idx() > maxVisibleIdx) continue;

            final int tw = t.w();
            final int[] src = t.argb();
            final short[] tz = t.z();
            final int th = src.length / tw;

            final int base = 16 * ((straight ? t.cz() : t.cx() + t.cz()) + t.idx());
            final int ox = tileLeft(t.cx(), t.cz()) - x0;
            final int oy = tileTop(t.cx(), t.cz(), t.idx()) - y0;

            for (int y = 0; y < th; y++) {
                int ty = oy + y;
                if (ty < 0 || ty >= h) continue;

                for (int x = 0; x < tw; x++) {
                    int tx = ox + x;
                    if (tx < 0 || tx >= w) continue;

                    int s = src[y * tw + x];
                    if ((s >>> 24) == 0) continue;

                    int i = ty * w + tx;
                    int d = base + tz[y * tw + x];
                    if (d >= zbuf[i]) {
                        buf[i] = MapScreen.over(s, buf[i]);
                        zbuf[i] = d;
                    } else if ((buf[i] >>> 24) != 255) {
                        buf[i] = MapScreen.over(buf[i], s);
                    }
                }
            }
        }
        return buf;
    }

    void applyBuffer(int[] buf) {
        if (tex == null) {
            tex = new DynamicTexture(() -> "isomap_region_" + rx + "_" + rz + "_" + ry, new NativeImage(w, h, true));
            id = Identifier.fromNamespaceAndPath("isomap", "dynamic/region_" + rx + "_" + rz + "_" + ry);
            Minecraft.getInstance().getTextureManager().register(id, tex);
        }
        NativeImage img = tex.getPixels();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setPixel(x, y, buf[y * w + x]);
        tex.upload();
    }
}