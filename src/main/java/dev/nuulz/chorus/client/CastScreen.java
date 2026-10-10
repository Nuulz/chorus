package dev.nuulz.chorus.client;

import dev.nuulz.chorus.ScreenBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Start or stop sharing your screen on this Chorus screen, choosing which sound goes with it. */
final class CastScreen extends Screen {
    private final ScreenBlockEntity be;
    private String app = AudioRoute.NONE;

    CastScreen(ScreenBlockEntity be) {
        super(Component.translatable("chorus.cast"));
        this.be = be;
    }

    @Override
    protected void init() {
        int w = 280, x = (width - w) / 2, y = height / 2 - 40;
        if (!Caster.supported()) return;
        List<String> ids = new ArrayList<>(List.of(AudioRoute.NONE, AudioRoute.ALL));
        Map<String, String> apps = AudioRoute.apps();
        ids.addAll(apps.keySet());
        if (!Caster.running) {
            app = apps.isEmpty() ? AudioRoute.NONE : apps.keySet().iterator().next();
            addRenderableWidget(CycleButton.<String>builder(id -> Component.literal(
                    id.equals(AudioRoute.NONE) ? Caster.tr("audio_none") : id.equals(AudioRoute.ALL) ? Caster.tr("audio_all") : apps.get(id)), app)
                .withValues(ids)
                .create(x, y, w, 20, Component.translatable("chorus.cast.audio"), (b, v) -> app = v));
        }
        addRenderableWidget(Button.builder(Component.translatable(Caster.running ? "chorus.cast.stop" : "chorus.cast.start"), b -> {
            if (Caster.running) Caster.stop();
            else Caster.start(be.getBlockPos(), app);
            onClose();
        }).bounds(x, y + 28, w, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        int cx = width / 2, y = height / 2 - 70;
        g.centeredText(font, title, cx, y, 0xFFFFFFFF);
        if (!Caster.supported()) {
            g.centeredText(font, Component.translatable("chorus.cast.linux_only"), cx, y + 30, 0xFFFFAA55);
            return;
        }
        g.centeredText(font, Component.translatable("chorus.cast.hint"), cx, y + 14, 0xFFAAAAAA);
        g.centeredText(font, Component.literal(Caster.status), cx, height / 2 + 20, 0xFF88CCFF);
    }

    @Override public boolean isPauseScreen() { return false; }
}
