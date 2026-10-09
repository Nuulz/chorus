package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Chorus;
import dev.nuulz.chorus.Media;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import dev.nuulz.chorus.ScreenBlock;
import dev.nuulz.chorus.ScreenBlockEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.Minecraft;

public final class ChorusClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        FFmpeg.install();
        BlockEntityRendererRegistry.register(Chorus.SCREEN_ENTITY, ctx -> new ScreenRenderer());
        ScreenBlockEntity.clientLoaded = Screens::loaded;
        ScreenBlockEntity.clientUnloaded = Screens::unloaded;
        ScreenBlock.openControls = be -> Minecraft.getInstance().gui.setScreen(new ControlScreen(be));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MediaCache.stop());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> { Screens.tick(); Uploader.tick(); MediaCache.tick(); });
        ClientPlayNetworking.registerGlobalReceiver(Media.Ack.TYPE, (p, ctx) -> Uploader.ack(p));
        ClientPlayNetworking.registerGlobalReceiver(Media.Chunk.TYPE, (p, ctx) -> MediaCache.received(p));
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> mc.execute(() -> {
            Screens.closeAll();
            Uploader.reset();
            MediaCache.reset();
        }));
    }
}
