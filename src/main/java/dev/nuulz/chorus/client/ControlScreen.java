package dev.nuulz.chorus.client;

import dev.nuulz.chorus.ControlPayload;
import dev.nuulz.chorus.ScreenBlockEntity;
import dev.nuulz.chorus.UrlPolicy;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The remote: a URL and the transport buttons. It only asks; the server decides and everyone follows. */
final class ControlScreen extends Screen {
    private final ScreenBlockEntity be;
    private EditBox url;

    ControlScreen(ScreenBlockEntity be) {
        super(Component.translatable("block.chorus.screen"));
        this.be = be;
    }

    @Override
    protected void init() {
        int w = 300, x = (width - w) / 2, y = height / 2 - 40;
        url = new EditBox(font, x, y, w - 64, 20, Component.literal("URL"));
        url.setMaxLength(UrlPolicy.MAX_LENGTH);
        url.setValue(be.url);
        addRenderableWidget(url);
        setInitialFocus(url);
        addRenderableWidget(Button.builder(Component.translatable("chorus.load"),
            b -> send(ControlPayload.SET_URL, url.getValue().trim(), 0)).bounds(x + w - 60, y, 60, 20).build());

        int by = y + 28, bw = 56;
        addRenderableWidget(Button.builder(Component.literal("-10s"), b -> seek(-10_000)).bounds(x, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("chorus.play"), b -> send(ControlPayload.PLAY, "", 0)).bounds(x + 61, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("chorus.pause"), b -> send(ControlPayload.PAUSE, "", 0)).bounds(x + 122, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+10s"), b -> seek(10_000)).bounds(x + 183, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("chorus.stop"), b -> send(ControlPayload.STOP, "", 0)).bounds(x + 244, by, bw, 20).build());
    }

    private void seek(long delta) {
        send(ControlPayload.SEEK, "", be.positionMs(minecraft.level.getGameTime()) + delta);
    }

    private void send(int action, String u, long ms) {
        if (action == ControlPayload.SET_URL && !ClientConfig.urls.allows(u)) {
            minecraft.player.sendSystemMessage(Component.translatable("chorus.url_blocked"));
            return;
        }
        ClientPlayNetworking.send(new ControlPayload(be.getBlockPos(), action, u, ms));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        int cx = width / 2, y = height / 2 - 66;
        g.centeredText(font, title, cx, y, 0xFFFFFFFF);
        long s = be.positionMs(minecraft.level.getGameTime()) / 1000;
        String status = be.url.isEmpty() ? Component.translatable("chorus.idle").getString()
            : (be.playing ? "▶ " : "⏸ ") + String.format("%d:%02d", s / 60, s % 60);
        if (!Browser.ready() && !Browser.failed() && !be.url.isEmpty()) status += "  ·  " + Browser.step;
        if (Browser.failed()) status = Component.translatable("chorus.browser_failed").getString();
        g.centeredText(font, Component.literal(status), cx, height / 2 + 16, 0xFFAAAAAA);
    }

    @Override public boolean isPauseScreen() { return false; }
}
