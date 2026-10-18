package lgbt.elizabeth.isomap.client;

import com.mojang.blaze3d.platform.InputConstants;
import lgbt.elizabeth.isomap.client.config.IsoMapConfig;
import lgbt.elizabeth.isomap.client.data.CreateChunkFile;
import lgbt.elizabeth.isomap.client.data.CreateTileImage;
import lgbt.elizabeth.isomap.client.render.MapScreen;
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

    private final AtomicBoolean rendering = new AtomicBoolean(false);

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

            if (client.level != null) {
                CreateChunkFile.getMaxYSection(client.level);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            while (openScreenKey.consumeClick()) {
                client.setScreenAndShow(new MapScreen(Component.literal("IsoMap")));
            }

            if (client.level == null) return;

            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) return;
            BlockPos pos = player.blockPosition();

            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;

            tickCounter++;
            if (tickCounter >= (IsoMapConfig.saveDelay*20)) {
                tickCounter = 0;

                if (rendering.compareAndSet(false, true)) {
                    Thread t = new Thread(() -> {
                        try {
                            CreateChunkFile.saveRenderDist(chunkX, chunkZ, modVer);
                        } finally {
                            rendering.set(false);
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                }
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
        Path myModFolder = configDirectory.resolve("IsoMap");

        if (!Files.exists(myModFolder)) {
            Files.createDirectories(myModFolder);
        }
    }
}