package dev.nuulz.chorus.client;

import dev.nuulz.chorus.ControlPayload;
import dev.nuulz.chorus.Media;
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
    private static final int[] SIZES = {1, 2, 3, 4, 6, 8, 12, 16, 24};

    ControlScreen(ScreenBlockEntity be) {
        super(Component.translatable("block.chorus.screen"));
        this.be = be;
    }

    @Override
    protected void init() {
        int w = 300, x = (width - w) / 2, y = height / 2 - 40;
        url = new EditBox(font, x, y, w - 64, 20, Component.literal("URL"));
        url.setMaxLength(UrlPolicy.MAX_LENGTH);
        url.setValue(Media.isMedia(be.url) ? "" : be.url);
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
        addRenderableWidget(Button.builder(Component.translatable("chorus.own_video"), b -> Uploader.pick(be.getBlockPos()))
            .bounds(x, by + 28, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("chorus.browse"), b -> minecraft.gui.setScreen(new BrowseScreen(be)))
            .bounds(x + w / 2 + 2, by + 28, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("chorus.cast"), b -> minecraft.gui.setScreen(new CastScreen(be)))
            .bounds(x, by + 52, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("chorus.size", be.size), b -> {
                int next = SIZES[(java.util.Arrays.binarySearch(SIZES, be.size) + 1) % SIZES.length];
                ClientPlayNetworking.send(new ControlPayload(be.getBlockPos(), ControlPayload.SIZE, "", next));
                be.size = next;
                b.setMessage(Component.translatable("chorus.size", next));
            }).bounds(x, by + 76, w, 20).build());
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
        if (Media.isMedia(be.url)) {
            double got = MediaCache.progress(Media.name(be.url));
            if (got >= 0 && got < 1) status += "  ·  " + Component.translatable("chorus.downloading", (int) (got * 100)).getString();
        }
        if (Browser.failed()) status = Component.translatable("chorus.browser_failed").getString();
        g.centeredText(font, Component.literal(status), cx, height / 2 + 92, 0xFFAAAAAA);
        String up = Uploader.status;
        if (Uploader.fraction >= 0) up += " " + (int) (Uploader.fraction * 100) + "%";
        if (!up.isEmpty()) g.centeredText(font, Component.literal(up), cx, height / 2 + 106, 0xFF88CCFF);
    }

    @Override public boolean isPauseScreen() { return false; }
}
