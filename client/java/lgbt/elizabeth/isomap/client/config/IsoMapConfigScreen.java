package lgbt.elizabeth.isomap.client.config;

import com.mojang.blaze3d.platform.NativeImage;
import lgbt.elizabeth.isomap.client.IsomapClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static java.lang.Math.random;

public class IsoMapConfigScreen extends Screen {

    private static final String VERSION = String.valueOf(IsomapClient.modVer);

    private static final String[] DIRS = {"North", "Northeast", "East", "Southeast",
            "South", "Southwest", "West", "Northwest"};

    private final Screen parent;

    private int pendingMaxRadius = IsoMapConfig.maxRadius;
    private int pendingMinY = IsoMapConfig.minSectionedYBlock;
    private int pendingMaxY = IsoMapConfig.maxSectionedYBlock;
    private int pendingDirection = IsoMapConfig.direction;
    private float pendingBrightness = IsoMapConfig.brightness;
    private int pendingWorkers = IsoMapConfig.workersPerCell;
    private int pendingWaterTint = IsoMapConfig.waterTint;

    //Background
    private static Identifier bgId;
    private static int bgW, bgH;

    //Layout
    private record Label(String text, int x, int y) {}
    private final List<Label> labels = new ArrayList<>();
    private final List<int[]> plates = new ArrayList<>();
    private int mainX, expX, headerY, iconSize;

    @Override
    public void removed() {
        if (bgId != null) {
            Minecraft.getInstance().getTextureManager().release(bgId);
            bgId = null;
        }
        super.removed();
    }

    //Background
    private static void loadBackground() {
        if (bgId != null) return;

        int ranNum = (int) (random() * 12);
        String ImgName = "config_background_" + ranNum;
        String backgroundPath = "/textures/backgrounds/Alpha1.1/" + ImgName + ".png";

        try (InputStream in = IsoMapConfigScreen.class.getResourceAsStream(backgroundPath)) {
            if (in == null) return;
            NativeImage img = NativeImage.read(in);
            bgW = img.getWidth();
            bgH = img.getHeight();
            bgId = Identifier.fromNamespaceAndPath("isomap", "dynamic/config_bg");
            Minecraft.getInstance().getTextureManager()
                    .register(bgId, new DynamicTexture(() -> "isomap_config_bg", img));
        } catch (IOException e) {
            bgId = null;
        }
    }


    //Screen Constructor
    public IsoMapConfigScreen(Screen parent) {
        super(Component.literal("IsoMap Settings"));
        this.parent = parent;
    }

    //Initialize Screen
    @Override
    protected void init() {
        loadBackground();
        labels.clear();
        plates.clear();

        //Layout
        int margin = this.width / 24;
        iconSize = this.height / 6;

        int colW = (this.width - margin * 3) / 2;
        mainX = margin;
        expX = margin + colW + margin;

        int baseW = Math.max(90, (int) (colW * 0.45));
        int h = Math.max(20, (int) (this.height * 0.05));

        int mainW = Math.max(baseW, checkboxWidth("Save outside Y range as air"));
        int expW = Math.max(baseW, checkboxWidth("Skip solid sections when rendering"));

        headerY = margin + iconSize + 12;
        int firstRowY = headerY + this.font.lineHeight + 10;

        int rows = 7;
        int available = this.height - margin - firstRowY;
        int step = Math.max(h + 2, Math.min(h * 2, available / rows));

        //MAIN SETTINGS
        addIntBox(mainX, firstRowY, mainW, h, "Max Radius", "Max Radius", 10,
                pendingMaxRadius, 0, v -> pendingMaxRadius = v, null);

        addIntBox(mainX, firstRowY + step, mainW, h, "Maximum Y", "Max Y", 6,
                pendingMaxY, Integer.MIN_VALUE, v -> pendingMaxY = v, null);

        addIntBox(mainX, firstRowY + step * 2, mainW, h, "Minimum Y", "Min Y", 6,
                pendingMinY, Integer.MIN_VALUE, v -> pendingMinY = v, null);

        addCheckbox(mainX, firstRowY + step * 3, mainW, "Save outside Y range as air",
                IsoMapConfig.saveSectionedY, v -> IsoMapConfig.saveSectionedY = v,
                "Saves parts outside the sectioned Y as air");

        this.addRenderableWidget(directionSlider(mainX, firstRowY + step * 4, mainW, h));

        this.addRenderableWidget(brightnessSlider(mainX, firstRowY + step * 5, mainW, h));

        addIntBox(mainX, firstRowY + step * 6, mainW, h, "Chunk Save Delay", "Seconds", 5,
                IsoMapConfig.saveDelay, 0, v -> IsoMapConfig.saveDelay = v, null);

        //EXPERIMENTAL
        addIntBox(expX, firstRowY, expW, h, "Render Threads", "Thread Count", 2,
                pendingWorkers, 1, v -> pendingWorkers = v,
                "Number of threads used to render map regions.");

        this.addRenderableWidget(waterSlider(expX, firstRowY + step, expW, h));
        addCheckbox(expX, firstRowY + step * 2, expW, "Skip solid sections when rendering",
                IsoMapConfig.repSolidSecAir, v -> IsoMapConfig.repSolidSecAir = v,
                "Replaces all solid sections not touching air when rendering with air.");
    }

    private int checkboxWidth(String text) {
        return this.font.lineHeight + 8 + 4 + this.font.width(text) + 4;
    }


    //Widget Helpers
    private void addIntBox(int x, int y, int w, int h, String label, String hint, int maxLen, int value, int min, IntConsumer setter, String tooltip) {
        EditBox box = new EditBox(this.font, x, y, w, h, Component.literal(label));
        box.setMaxLength(maxLen);
        box.setValue(String.valueOf(value));
        box.setHint(Component.literal(hint));
        if (tooltip != null) box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        box.setResponder(text -> {
            if (text.matches("-?\\d+")) {
                try {
                    setter.accept(Math.max(min, Integer.parseInt(text)));
                } catch (NumberFormatException ignored) {}
            }
        });
        addLabel(label, x, y, w, h);
        this.addRenderableWidget(box);
    }

    private void addCheckbox(int x, int y, int w, String text, boolean value, Consumer<Boolean> setter, String tooltip) {
        Checkbox box = Checkbox.builder(Component.literal(text), this.font)
                .pos(x, y)
                .selected(value)
                .onValueChange((_, v) -> setter.accept(v))
                .build();
        box.setWidth(w);
        box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        plates.add(new int[]{x, y, x + w, y + box.getHeight()});
        this.addRenderableWidget(box);
    }

    private AbstractSliderButton directionSlider(int x, int y, int w, int h) {
        return new AbstractSliderButton(x, y, w, h,
                Component.literal("Direction: " + DIRS[pendingDirection]),
                pendingDirection / 7.0) {
            @Override
            protected void updateMessage() {
                int i = (int) Math.round(this.value * 7);
                setMessage(Component.literal("Direction: " + DIRS[i]));
                setTooltip(Tooltip.create(Component.literal(DIRS[i])));
            }

            @Override
            protected void applyValue() {
                int i = (int) Math.round(this.value * 7);
                this.value = i / 7.0;
                pendingDirection = i;
                updateMessage();
            }
        };
    }

    private AbstractSliderButton brightnessSlider(int x, int y, int w, int h) {
        return new AbstractSliderButton(x, y, w, h,
                Component.literal("Brightness: " + (int) pendingBrightness + "%"),
                pendingBrightness / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Brightness: " + (int) Math.round(this.value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                pendingBrightness = (int) Math.round(this.value * 100);
            }
        };
    }

    private AbstractSliderButton waterSlider(int x, int y, int w, int h) {
        double start = 1.0 - pendingWaterTint / 255.0;
        AbstractSliderButton s = new AbstractSliderButton(x, y, w, h,
                Component.literal("Water Transparency: " + (int) Math.round(start * 100) + "%"),
                start) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Water Transparency: " + (int) Math.round(this.value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                int pct = (int) Math.round(this.value * 100);
                this.value = pct / 100.0;
                pendingWaterTint = (int) Math.round((100 - pct) * 255 / 100.0);
                updateMessage();
            }
        };
        s.setTooltip(Tooltip.create(Component.literal("How see-through water is. 0% is solid, 100% is invisible.")));
        return s;
    }

    private void addLabel(String text, int widgetX, int widgetY, int widgetW, int widgetH) {
        labels.add(new Label(text, widgetX + widgetW + 6, widgetY + (widgetH - this.font.lineHeight) / 2));
    }


    //Gay Colors :3
    private static final int RAINBOW_STEPS = 40;
    private static final float RAINBOW_SAT = 0.85f;
    private static final float RAINBOW_BRI = 0.60f;
    private static final int TEXT_ALPHA = 0xA0;
    private static final long PERIOD_MS = 6000;

    private static float phase() {
        long step = (System.currentTimeMillis() % PERIOD_MS) * RAINBOW_STEPS / PERIOD_MS;
        return step / (float) RAINBOW_STEPS;
    }

    private static int pixelRainbow(float hue) {
        hue -= (float) Math.floor(hue);
        float q = (float) Math.floor(hue * RAINBOW_STEPS) / RAINBOW_STEPS;
        return (IsoMapConfigScreen.TEXT_ALPHA << 24) | (java.awt.Color.HSBtoRGB(q, RAINBOW_SAT, RAINBOW_BRI) & 0xFFFFFF);
    }

    private static int outlineColor(int screenX, float shift) {
        return pixelRainbow((screenX % 96) / 96f + shift);
    }

    private void drawRainbowGlow(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        float shift = phase();
        int x0 = x - 1, y0 = y - 1, x1 = x + w + 1, y1 = y + h + 1;

        for (int px = x0; px < x1; px++) {
            int c = outlineColor(px, shift);
            g.fill(px, y0, px + 1, y0 + 1, c);
            g.fill(px, y1 - 1, px + 1, y1, c);
        }
        int cl = outlineColor(x0, shift), cr = outlineColor(x1 - 1, shift);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, cl);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, cr);
    }

    private void drawOutlined(GuiGraphicsExtractor graphics, String text, int x, int y) {
        drawOutlined(graphics, text, x, y, 0xFFFFFFFF);
    }

    private void drawOutlined(GuiGraphicsExtractor graphics, String text, int x, int y, int textColor) {
        float shift = phase();

        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            int cx = x + this.font.width(text.substring(0, i));
            int color = pixelRainbow((cx % 96) / 96f + shift);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        graphics.text(this.font, ch, cx + dx, y + dy, color, false);
                    }
                }
            }
        }
        graphics.text(this.font, text, x, y, textColor, true);
    }

    private void drawWidgetTexts(GuiGraphicsExtractor g) {
        int lh = this.font.lineHeight;
        for (var c : this.children()) {
            switch (c) {
                case EditBox box -> {
                    if (box.isFocused() || box.getValue().isEmpty()) continue;
                    drawOutlined(g, box.getValue(), box.getX() + 4, box.getY() + (box.getHeight() - lh) / 2, 0xFFE0E0E0);
                }
                case Checkbox cb -> {
                    int boxSize = lh + 8;

                    drawOutlined(g, cb.getMessage().getString(), cb.getX() + boxSize + 4,
                            cb.getY() + (cb.getHeight() - lh) / 2, 0xFFFFFFFF);
                }
                case AbstractSliderButton sl -> {
                    String s = sl.getMessage().getString();
                    drawOutlined(g, s, sl.getX() + (sl.getWidth() - this.font.width(s)) / 2,
                            sl.getY() + (sl.getHeight() - lh) / 2, 0xFFFFFFFF);
                }
                default -> {
                }
            }
        }
    }


    //Render Screen
    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (bgId == null) {
            super.extractBackground(graphics, mouseX, mouseY, delta);
            return;
        }

        graphics.blit(RenderPipelines.GUI_TEXTURED, bgId, 0, 0, 0f, 0f, this.width, this.height, bgW, bgH, bgW, bgH);

        int margin = this.width / 24;
        Identifier icon = Identifier.fromNamespaceAndPath("isomap", "textures/icon.png");
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon, margin, margin, 0, 0, iconSize, iconSize, 128, 128, 128, 128);

        for (int[] r : plates) {
            graphics.fill(r[0], r[1], r[2], r[3], 0x90000000);
        }

        for (var c : this.children()) {
            if (c instanceof Checkbox || !(c instanceof AbstractWidget wdg)) continue;
            drawRainbowGlow(graphics, wdg.getX(), wdg.getY(), wdg.getWidth(), wdg.getHeight());
        }
        for (int[] r : plates) {
            drawRainbowGlow(graphics, r[0], r[1], r[2] - r[0], r[3] - r[1]);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
        drawWidgetTexts(graphics);

        int textX = margin + iconSize + 10;
        int lineH = this.font.lineHeight + 2;
        int textBottom = margin + iconSize;
        drawOutlined(graphics, "IsoMap", textX, textBottom - lineH * 2);
        drawOutlined(graphics, "Version Alpha " + VERSION, textX, textBottom - lineH);

        drawOutlined(graphics, "Main Settings", mainX, headerY);
        drawOutlined(graphics, "Experimental", expX, headerY);

        for (Label l : labels) {
            drawOutlined(graphics, l.text(), l.x(), l.y());
        }
    }

    //Saves settings
    @Override
    public void onClose() {
        IsoMapConfig.maxRadius = pendingMaxRadius;
        IsoMapConfig.minSectionedYBlock = pendingMinY;
        IsoMapConfig.maxSectionedYBlock = Math.max(pendingMinY + 1, pendingMaxY);
        IsoMapConfig.direction = pendingDirection;
        IsoMapConfig.brightness = pendingBrightness;
        IsoMapConfig.workersPerCell = pendingWorkers;
        IsoMapConfig.waterTint = pendingWaterTint;
        IsoMapConfig.save();
        this.minecraft.setScreenAndShow(parent);
    }
}