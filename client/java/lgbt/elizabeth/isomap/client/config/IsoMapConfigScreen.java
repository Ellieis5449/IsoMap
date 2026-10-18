package lgbt.elizabeth.isomap.client.config;

import com.mojang.blaze3d.platform.NativeImage;
import lgbt.elizabeth.isomap.client.IsomapClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
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

        int ranNum = (int) (random() * 8);
        String ImgName = "config_background_" + ranNum;
        String backgroundPath = "/textures/backgrounds/" + ImgName + ".png";

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

        int w = Math.max(90, (int) (colW * 0.45));
        int h = Math.max(20, (int) (this.height * 0.05));

        headerY = margin + iconSize + 12;
        int firstRowY = headerY + this.font.lineHeight + 10;

        int rows = 7;
        int available = this.height - margin - firstRowY;
        int step = Math.max(h + 2, Math.min(h * 2, available / rows));

        //MAIN SETTINGS
        addIntBox(mainX, firstRowY, w, h, "Max Radius", "Max Radius", 10,
                IsoMapConfig.maxRadius, 0, v -> IsoMapConfig.maxRadius = v, null);
        addIntBox(mainX, firstRowY + step, w, h, "Maximum Y", "Max Y", 5,
                IsoMapConfig.maxSectionedYBlock, 0,
                v -> IsoMapConfig.maxSectionedYBlock = Math.max(IsoMapConfig.minSectionedYBlock + 1, v), null);
        addIntBox(mainX, firstRowY + step * 2, w, h, "Minimum Y", "Min Y", 5,
                IsoMapConfig.minSectionedYBlock, 0, v -> IsoMapConfig.minSectionedYBlock = v, null);
        addCheckbox(mainX, firstRowY + step * 3, "Save outside Y range as air",
                IsoMapConfig.saveSectionedY, v -> IsoMapConfig.saveSectionedY = v,
                "Saves parts outside the sectioned Y as air");
        this.addRenderableWidget(directionSlider(mainX, firstRowY + step * 4, w, h));
        this.addRenderableWidget(brightnessSlider(mainX, firstRowY + step * 5, w, h));
        addIntBox(mainX, firstRowY + step * 6, w, h, "Chunk Save Delay", "Seconds", 5,
                IsoMapConfig.saveDelay, 0, v -> IsoMapConfig.saveDelay = v, null);

        //EXPERIMENTAL
        addIntBox(expX, firstRowY, w, h, "Spiral Cell Size", "Spiral Cell Size", 3,
                IsoMapConfig.spiralCellSize, 1, v -> IsoMapConfig.spiralCellSize = v,
                "Square size of the spiral cells, goes from floor to ceiling or in the sectioned Y and split with the Y split input");
        addIntBox(expX, firstRowY + step, w, h, "Y Split", "Y Split", 1,
                IsoMapConfig.ySplit, 1, v -> IsoMapConfig.ySplit = v,
                "Amount of divisions the spiral is, can help with draw time with tall structures.");
        addIntBox(expX, firstRowY + step * 2, w, h, "Workers Per Cell", "Worker Count", 1,
                IsoMapConfig.workersPerCell, 1, v -> IsoMapConfig.workersPerCell = v,
                "Amount of worker threads per cell in the spiral.");
        addHexBox(expX, firstRowY + step * 3, w, h, "Water Transparency", "Alpha (hex 00-FF)",
                2, IsoMapConfig.waterTint, v -> IsoMapConfig.waterTint = v,
                "Transparency of water, as a 2-digit hex alpha value (00-FF).");
        addHexBox(expX, firstRowY + step * 4, w, h, "Grass Hue", "Grass Hue",
                6, IsoMapConfig.grassHue, v -> IsoMapConfig.grassHue = v, "Hue of grass (hex RRGGBB).");
        addCheckbox(expX, firstRowY + step * 5, "Skip solid sections when rendering",
                IsoMapConfig.repSolidSecAir, v -> IsoMapConfig.repSolidSecAir = v,
                "Replaces all solid sections not touching air when rendering with air.");
    }


    //Widget Helpers
    private void addIntBox(int x, int y, int w, int h, String label, String hint,
                           int maxLen, int value, int min, IntConsumer setter, String tooltip) {
        EditBox box = new EditBox(this.font, x, y, w, h, Component.literal(label));
        box.setMaxLength(maxLen);
        box.setValue(String.valueOf(value));
        box.setHint(Component.literal(hint));
        if (tooltip != null) box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        box.setResponder(text -> {
            if (text.matches("\\d+")) {
                try {
                    setter.accept(Math.max(min, Integer.parseInt(text)));
                } catch (NumberFormatException ignored) {}
            }
        });
        addLabel(label, x, y, w, h);
        this.addRenderableWidget(box);
    }

    private void addHexBox(int x, int y, int w, int h, String label, String hint,
                           int digits, int value, IntConsumer setter, String tooltip) {
        EditBox box = new EditBox(this.font, x, y, w, h, Component.literal(label));
        box.setMaxLength(digits);
        box.setValue(String.format("%0" + digits + "X", value));
        box.setHint(Component.literal(hint));
        if (tooltip != null) box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        box.setResponder(text -> {
            boolean valid = text.matches("[0-9a-fA-F]{" + digits + "}");
            if (valid) setter.accept(Integer.parseInt(text, 16));
            box.setTextColor(valid ? 0xFFE0E0E0 : 0xFFFF5555);
        });
        addLabel(label, x, y, w, h);
        this.addRenderableWidget(box);
    }

    private void addCheckbox(int x, int y, String text, boolean value,
                             Consumer<Boolean> setter, String tooltip) {
        Checkbox box = Checkbox.builder(Component.literal(text), this.font)
                .pos(x, y)
                .selected(value)
                .onValueChange((_, v) -> setter.accept(v))
                .build();
        box.setTooltip(Tooltip.create(Component.literal(tooltip)));
        plates.add(new int[]{x - 2, y - 2, x + box.getWidth() + 2, y + box.getHeight() + 2});
        this.addRenderableWidget(box);
    }

    private AbstractSliderButton directionSlider(int x, int y, int w, int h) {
        return new AbstractSliderButton(x, y, w, h,
                Component.literal("Direction: " + DIRS[IsoMapConfig.direction]),
                IsoMapConfig.direction / 7.0) {
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
                IsoMapConfig.direction = i;
                updateMessage();
            }
        };
    }

    private AbstractSliderButton brightnessSlider(int x, int y, int w, int h) {
        return new AbstractSliderButton(x, y, w, h,
                Component.literal("Brightness: " + IsoMapConfig.brightness + "%"),
                IsoMapConfig.brightness / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Brightness: " + (int) Math.round(this.value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                IsoMapConfig.brightness = (int) Math.round(this.value * 100);
            }
        };
    }

    private void drawOutlined(GuiGraphicsExtractor graphics, String text, int x, int y) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) {
                    graphics.text(this.font, text, x + dx, y + dy, 0xFF000000, false);
                }
            }
        }
        graphics.text(this.font, text, x, y, 0xFFFFFFFF, true);
    }

    private void addLabel(String text, int widgetX, int widgetY, int widgetW, int widgetH) {
        labels.add(new Label(text, widgetX + widgetW + 6, widgetY + (widgetH - this.font.lineHeight) / 2));
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

        super.extractRenderState(graphics, mouseX, mouseY, delta);

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
        IsoMapConfig.save();
        this.minecraft.setScreenAndShow(parent);
    }
}