package lgbt.elizabeth.isomap.client;

import com.mojang.blaze3d.platform.InputConstants;
import lgbt.elizabeth.isomap.client.config.IsoMapConfig;
import lgbt.elizabeth.isomap.client.data.CreateChunkFile;
import lgbt.elizabeth.isomap.client.data.CreateTileImage;
import lgbt.elizabeth.isomap.client.data.OrganizeData;
import lgbt.elizabeth.isomap.client.render.MapScreen;
import lgbt.elizabeth.isomap.client.render.MapSession;
import lgbt.elizabeth.isomap.mixin.client.BiomeManagerAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class IsomapClient implements ClientModInitializer {

    private int tickCounter = 0;

    private static final AtomicBoolean rendering = new AtomicBoolean(false);
    public static final java.util.concurrent.atomic.AtomicInteger saveGen = new java.util.concurrent.atomic.AtomicInteger();

    private static volatile int wantCx, wantCz;
    private static final AtomicBoolean wanted = new AtomicBoolean(false);
    private int lastSaveCx = 0, lastSaveCz = 0;
    private boolean hasSaved = false;

    public static boolean isSaving() { return rendering.get(); }

    public static void requestSave(int chunkX, int chunkZ) {
        wantCx = chunkX;
        wantCz = chunkZ;
        wanted.set(true);
        if (!rendering.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                while (wanted.getAndSet(false)) {
                    try {
                        CreateChunkFile.saveRenderDist(wantCx, wantCz, modVer);
                    } catch (Throwable e) {
                        e.printStackTrace();
                    } finally {
                        saveGen.incrementAndGet();
                    }
                }
            } finally {
                rendering.set(false);
                if (wanted.get()) requestSave(wantCx, wantCz);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public static final float modVer = parseVersion(
            FabricLoader.getInstance()
                    .getModContainer("isomap")
                    .orElseThrow()
                    .getMetadata()
                    .getVersion()
                    .getFriendlyString());

    private static float parseVersion(String version) {
        String[] parts = version.split("[.-]");
        String major = parts[0];
        String minor = parts.length > 1 ? parts[1] : "0";
        return Float.parseFloat(major + "." + minor);
    }

    @Override
    public void onInitializeClient() {

        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Path blockIndexFile = configDirectory.resolve("IsoMap").resolve("block_index.json");
        Path biomeIndexFile = configDirectory.resolve("IsoMap").resolve("biome_index.json");

        try {
            OrganizeData.loadBlockIds(blockIndexFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath("isomap", "tileset"),
                (ResourceManagerReloadListener) rm -> {
                    try {
                        CreateTileImage.createIndex(rm);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

        try {
            makeDir();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        IsoMapConfig.load();

        KeyMapping.Category CATEGORY = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("isomap", "custom_category")
        );

        KeyMapping openScreenKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.example-mod.send_to_chat",
                        InputConstants.Type.KEYSYM,
                        InputConstants.KEY_SEMICOLON,
                        CATEGORY
                ));

        ClientPlayConnectionEvents.JOIN.register((_, _, client) -> {
            tickCounter = 0;
            hasSaved = false;

            ClientLevel level = client.level;
            if (level != null) {
                CreateChunkFile.getMaxYSection(level);

                try {
                    OrganizeData.loadBiomeIds(biomeIndexFile, level.registryAccess().lookupOrThrow(Registries.BIOME));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            while (openScreenKey.consumeClick()) {
                client.setScreenAndShow(new MapScreen(Component.literal("IsoMap")));
            }

            MapSession.clientTick(client);

            if (client.level == null) return;

            LocalPlayer player = client.player;
            if (player == null) return;
            BlockPos pos = player.blockPosition();
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;

            tickCounter++;
            boolean moved = !hasSaved || Math.max(Math.abs(cx - lastSaveCx), Math.abs(cz - lastSaveCz)) >= 2;
            if (moved || tickCounter >= (IsoMapConfig.saveDelay * 20)) {
                tickCounter = 0;
                hasSaved = true;
                lastSaveCx = cx;
                lastSaveCz = cz;
                requestSave(cx, cz);
            }
        });
    }

    public static String getSeedString() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        long seed = ((BiomeManagerAccessor) level.getBiomeManager()).isomap$getSeed();
        return Long.toString(seed);
    }

    private static void makeDir() throws IOException {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Path isoMapFolder = configDirectory.resolve("IsoMap");

        if (!Files.exists(isoMapFolder)) {
            Files.createDirectories(isoMapFolder);
        }
    }
}