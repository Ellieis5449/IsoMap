package lgbt.elizabeth.isomap.client.render;

import lgbt.elizabeth.isomap.client.IsomapClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

public class MapScreen extends Screen {

    static final int R = 16;
    static boolean straight;

    record Tile(int cx, int cz, int idx, int w, int[] argb, short[] z) {
        int depth() { return ((straight ? cz : cx + cz) * 32) + 2 * 16 * idx; }
    }

    private float panX = 0;
    private float panY = 0;
    float scale = 1;
    private boolean centered = false;

    private static final float MIN_SCALE = 0.00001f;
    private static final float MAX_SCALE = 10.0f;
    private static final float ZOOM_FACTOR = 1.15f;

    public MapScreen(Component title) {
        super(title);
    }

    @Override
    protected void init() {
        tryCenter();
    }

    private void tryCenter() {
        if (centered) return;
        MapSession s = MapSession.get();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (s == null || player == null || mc.level == null) return;
        centered = true;

        float[] pan = s.panTo(player.blockPosition());
        panX = pan[0];
        panY = pan[1];

        BlockPos pos = player.blockPosition();
        IsomapClient.requestSave(pos.getX() >> 4, pos.getZ() >> 4);
    }

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

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        tryCenter();
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        String label = "IsoMap Version Alpha " + IsomapClient.modVer;
        int x = this.width - this.font.width(label);
        int y = this.height - this.font.lineHeight;
        graphics.text(this.font, label, x, y, 0xFFFFFFFF, true);

        MapSession s = MapSession.get();
        if (s != null) drawRegions(graphics, s);
    }

    private void drawRegions(GuiGraphicsExtractor graphics, MapSession s) {
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

        for (Region r : s.regionOrder) {
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
}