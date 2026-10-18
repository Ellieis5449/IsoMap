package lgbt.elizabeth.isomap.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import lgbt.elizabeth.isomap.client.data.CreateChunkFile;
import lgbt.elizabeth.isomap.client.config.IsoMapConfig;
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

    static final int R = 16;

    int dirIndex = IsoMapConfig.direction;
    boolean straight = (dirIndex % 2 == 0);

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

    Region(int rx, int rz, int ry) {
        int maxCy = 0;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            maxCy = CreateChunkFile.getMaxYSection(level);
        }

        this.rx = rx; this.rz = rz; this.ry = ry;
        int cx0 = rx * R, cz0 = rz * R, cx1 = cx0 + R - 1, cz1 = cz0 + R - 1;
        int idx0 = ry * R, idx1 = Math.min(idx0 + R - 1, maxCy);
        x0 = (straight ? cx0 : cx0 - cz1) * 32;
        y0 = (straight ? cz0 * 2 : cx0 + cz0) * 16 - idx1 * 32;
        w  = (straight ? cx1 : cx1 - cz0) * 32 + (straight ? 32 : 64) - x0;
        h  = (straight ? cz1 * 2 : cx1 + cz1) * 16 - idx0 * 32 + 64 - y0;
    }

    int[] compositeBuffer(List<Tile> tilesSnapshot, int maxVisibleIdx) {
        tilesSnapshot.sort(Comparator.comparingInt(Tile::depth).thenComparingInt(Tile::idx));

        int[] buf = new int[w * h];
        int[] zbuf = new int[w * h];
        Arrays.fill(zbuf, Integer.MIN_VALUE);
        for (Tile t : tilesSnapshot) {
            if (t.idx() > maxVisibleIdx) continue;
            final int tw = t.w();
            int base = 16 * ((straight ? t.cz() : t.cx() + t.cz()) + t.idx());
            int ox = (straight ? t.cx() : t.cx() - t.cz()) * 32 - x0;
            int oy = (straight ? t.cz() * 2 : t.cx() + t.cz()) * 16 - t.idx() * 32 - y0;
            int[] src = t.argb();
            byte[] tz = t.z();
            for (int y = 0; y < 64; y++) {
                int row = (oy + y) * w + ox;
                for (int x = 0; x < tw; x++) {
                    int s = src[y * tw + x];
                    if ((s >>> 24) == 0) continue;
                    int i = row + x;
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
