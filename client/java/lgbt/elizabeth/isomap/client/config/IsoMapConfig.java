package lgbt.elizabeth.isomap.client.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class IsoMapConfig {

    //Square size of the spiral cells, goes from floor to ceiling or in the sectioned Y and split with the ySplit variable
    public static int spiralCellSize = 10;

    //Amount of divisions the spiral is, can help with draw speeds with tall structures.
    public static int ySplit = 2;

    //Maximum Draw Radius
    public static int maxRadius = 4096; //When 0 unlimited

    //Minimum and Maximum sectioned Y values
    public static int minSectionedYBlock = 0;
    public static int maxSectionedYBlock = 384;

    //Replace Solid Sections with Air
    public static boolean repSolidSecAir = false;

    //Saves parts outside the sectioned Y as air
    public static boolean saveSectionedY = false;

    //Eight directions
    public static int direction = 0;

    //Brightness
    public static float brightness = 50;

    //Chunk Save Delay
    public static int saveDelay = 5;

    //Workers Per Cell
    public static int workersPerCell = 4;

    //Water Tint
    public static int waterTint = 0xA0;

    //Grass Hue
    public static int grassHue = 0x5A753A;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("isomap.properties");

    //Parsing helpers: each key is parsed on its own so one bad value can't abort the rest
    private static int intOf(Properties p, String key, int def) {
        String s = p.getProperty(key);
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            System.err.println("[IsoMap] bad int for " + key + ": '" + s + "'");
            return def;
        }
    }

    private static float floatOf(Properties p, float def) {
        String s = p.getProperty("brightness");
        if (s == null) return def;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            System.err.println("[IsoMap] bad float for " + "brightness" + ": '" + s + "'");
            return def;
        }
    }

    private static boolean boolOf(Properties p, String key, boolean def) {
        String s = p.getProperty(key);
        return s == null ? def : Boolean.parseBoolean(s.trim());
    }

    private static int hexOf(Properties p, String key, int def) {
        String s = p.getProperty(key);
        if (s == null) return def;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        else if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        try {
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            System.err.println("[IsoMap] bad hex for " + key + ": '" + s + "'");
            return def;
        }
    }

    //Load config settings
    public static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("[IsoMap] failed to read config: " + e);
            return;
        }

        spiralCellSize = intOf(p, "spiralCellSize", spiralCellSize);
        ySplit = intOf(p, "ySplit", ySplit);
        maxRadius = intOf(p, "maxRadius", maxRadius);
        repSolidSecAir = boolOf(p, "repSolidSecAir", repSolidSecAir);
        minSectionedYBlock = intOf(p, "minSectionedYBlock", minSectionedYBlock);
        maxSectionedYBlock = intOf(p, "maxSectionedYBlock", maxSectionedYBlock);
        saveSectionedY = boolOf(p, "saveSectionedY", saveSectionedY);
        direction = intOf(p, "direction", direction);
        brightness = floatOf(p, brightness);
        saveDelay = intOf(p, "saveDelay", saveDelay);
        workersPerCell = intOf(p, "workersPerCell", workersPerCell);
        waterTint = hexOf(p, "waterTint", waterTint);
        grassHue = hexOf(p, "grassHue", grassHue);

        System.out.println("[IsoMap] loaded grassHue = " + String.format("%06X", grassHue));
    }

    //Save config settings
    public static void save() {
        Properties p = new Properties();
        p.setProperty("spiralCellSize", String.valueOf(spiralCellSize));
        p.setProperty("ySplit", String.valueOf(ySplit));
        p.setProperty("maxRadius", String.valueOf(maxRadius));
        p.setProperty("repSolidSecAir", String.valueOf(repSolidSecAir));
        p.setProperty("minSectionedYBlock", String.valueOf(minSectionedYBlock));
        p.setProperty("maxSectionedYBlock", String.valueOf(maxSectionedYBlock));
        p.setProperty("saveSectionedY", String.valueOf(saveSectionedY));
        p.setProperty("direction", String.valueOf(direction));
        p.setProperty("brightness", String.valueOf(brightness));
        p.setProperty("saveDelay", String.valueOf(saveDelay));
        p.setProperty("workersPerCell", String.valueOf(workersPerCell));
        p.setProperty("waterTint", String.format("%02X", waterTint));
        p.setProperty("grassHue", String.format("%06X", grassHue));
        try (OutputStream out = Files.newOutputStream(FILE)) {
            p.store(out, "IsoMap settings");
        } catch (IOException e) {
            System.err.println("[IsoMap] failed to save config: " + e);
        }
    }
}