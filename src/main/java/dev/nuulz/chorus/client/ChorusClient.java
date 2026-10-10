package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Chorus;
import dev.nuulz.chorus.Cast;
import dev.nuulz.chorus.Media;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import dev.nuulz.chorus.ScreenBlock;
import dev.nuulz.chorus.ScreenBlockEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

public final class ChorusClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        FFmpeg.install();
        BlockEntityRendererRegistry.register(Chorus.SCREEN_ENTITY, ctx -> new ScreenRenderer());
        ScreenBlockEntity.clientLoaded = Screens::loaded;
        ScreenBlockEntity.clientUnloaded = Screens::unloaded;
        ScreenBlock.openControls = be -> Minecraft.getInstance().gui.setScreen(new ControlScreen(be));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { Caster.stop(); MediaCache.stop(); });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof PauseScreen && Caster.running) {
                var widgets = net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen);
                int bottom = widgets.stream().mapToInt(x -> x.getY() + x.getHeight()).max().orElse(h / 4);
                widgets.add(Button.builder(Component.translatable("chorus.cast.stop"), b -> Caster.stop())
                    .bounds(w / 2 - 102, bottom + 4, 204, 20).build());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> { Screens.tick(); Uploader.tick(); MediaCache.tick(); Caster.tick(); });
        ClientPlayNetworking.registerGlobalReceiver(Media.Ack.TYPE, (p, ctx) -> Uploader.ack(p));
        ClientPlayNetworking.registerGlobalReceiver(Media.Chunk.TYPE, (p, ctx) -> MediaCache.received(p));
        ClientPlayNetworking.registerGlobalReceiver(Cast.Bytes.TYPE, (p, ctx) -> LiveCache.received(p));
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> mc.execute(() -> {
            Screens.closeAll();
            Uploader.reset();
            Caster.stop();
            LiveCache.reset();
            MediaCache.reset();
        }));
    }
}
