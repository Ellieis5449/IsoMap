package lgbt.elizabeth.isomap.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import lgbt.elizabeth.isomap.client.data.CreateChunkFile;
import lgbt.elizabeth.isomap.client.render.MapScreen.Tile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class Region {

    static final int R = MapScreen.R;

    static final int N = 128;
    static final int T = N / 16;
    static final int ANCHOR = 2 * (N - 1);

    static final int[] MIP = {8, 32};

    record Composite(int[] full, int[][] mips) {}

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

    final int[] mipW = new int[MIP.length], mipH = new int[MIP.length];
    final DynamicTexture[] mipTex = new DynamicTexture[MIP.length];
    final Identifier[] mipId = new Identifier[MIP.length];

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

        int cx0 = rx * R, cx1 = cx0 + R - 1;
        int cz0 = rz * R, cz1 = cz0 + R - 1;
        int idx0 = ry * R;

        int idxMax = Math.max(idx0, Math.min(idx0 + R - T, maxCy));

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int cx : new int[]{cx0, cx1}) {
            for (int cz : new int[]{cz0, cz1}) {
                int left = tileLeft(cx, cz);
                minX = Math.min(minX, left);
                maxX = Math.max(maxX, left);
                minY = Math.min(minY, tileTop(cx, cz, idxMax));
                maxY = Math.max(maxY, tileTop(cx, cz, idx0));
            }
        }

        x0 = minX;
        y0 = minY;
        w = maxX - minX + tileW;
        h = maxY - minY + tileH;

        for (int i = 0; i < MIP.length; i++) {
            mipW[i] = (w + MIP[i] - 1) / MIP[i];
            mipH[i] = (h + MIP[i] - 1) / MIP[i];
        }
    }

    Composite compositeBuffer(List<Tile> tilesSnapshot, int maxVisibleIdx) {
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

        int[][] mips = new int[MIP.length][];
        for (int i = 0; i < MIP.length; i++) {
            mips[i] = downsample(buf, w, h, MIP[i], mipW[i], mipH[i]);
            toAbgr(mips[i]);
        }
        toAbgr(buf);
        return new Composite(buf, mips);
    }

    private static int[] downsample(int[] src, int sw, int sh, int f, int dw, int dh) {
        int[] out = new int[dw * dh];
        for (int dy = 0; dy < dh; dy++) {
            int ys = dy * f, ye = Math.min(sh, ys + f);
            for (int dx = 0; dx < dw; dx++) {
                int xs = dx * f, xe = Math.min(sw, xs + f);
                int n = (ye - ys) * (xe - xs);
                int sa = 0, sr = 0, sg = 0, sb = 0;
                for (int y = ys; y < ye; y++) {
                    int row = y * sw;
                    for (int x = xs; x < xe; x++) {
                        int c = src[row + x];
                        int a = c >>> 24;
                        if (a == 0) continue;
                        sa += a;
                        sr += (c >> 16 & 255) * a;
                        sg += (c >> 8 & 255) * a;
                        sb += (c & 255) * a;
                    }
                }
                if (sa == 0) continue;
                out[dy * dw + dx] = (sa / n) << 24 | (sr / sa) << 16 | (sg / sa) << 8 | (sb / sa);
            }
        }
        return out;
    }

    private static void toAbgr(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (c & 0xFF00FF00) | (c >> 16 & 0xFF) | (c & 0xFF) << 16;
        }
    }

    void applyBuffer(Composite c) {
        if (tex == null) {
            tex = new DynamicTexture(() -> "isomap_region_" + rx + "_" + rz + "_" + ry, new NativeImage(w, h, true));
            id = Identifier.fromNamespaceAndPath("isomap", "dynamic/region_" + rx + "_" + rz + "_" + ry);
            Minecraft.getInstance().getTextureManager().register(id, tex);
        }
        copy(tex, c.full());

        for (int i = 0; i < MIP.length; i++) {
            if (mipTex[i] == null) {
                final int lvl = i;
                mipTex[i] = new DynamicTexture(() -> "isomap_region_" + rx + "_" + rz + "_" + ry + "_m" + lvl,
                        new NativeImage(mipW[i], mipH[i], true));
                mipId[i] = Identifier.fromNamespaceAndPath("isomap", "dynamic/region_" + rx + "_" + rz + "_" + ry + "_m" + i);
                Minecraft.getInstance().getTextureManager().register(mipId[i], mipTex[i]);
            }
            copy(mipTex[i], c.mips()[i]);
        }
    }

    private static void copy(DynamicTexture t, int[] abgr) {
        NativeImage img = t.getPixels();
        MemoryUtil.memIntBuffer(img.getPointer(), abgr.length).put(abgr);
        t.upload();
    }

    void release() {
        var tm = Minecraft.getInstance().getTextureManager();
        if (id != null) tm.release(id);
        for (Identifier m : mipId) if (m != null) tm.release(m);
    }
}