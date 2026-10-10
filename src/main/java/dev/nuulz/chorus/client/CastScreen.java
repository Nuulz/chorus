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

/** Start or stop sharing your screen on this Chorus screen: what to show, its quality, and the sound. */
public final class CastScreen extends Screen {
    private static final String DESKTOP = "desktop", WINDOW = "window:";
    private final ScreenBlockEntity be;
    private String app = AudioRoute.NONE;
    private String source = DESKTOP;

    public CastScreen(ScreenBlockEntity be) {
        super(Component.translatable("chorus.cast"));
        this.be = be;
    }

    @Override
    protected void init() {
        int w = 280, x = (width - w) / 2, y = height / 2 - 40;
        if (!Caster.supported()) return;
        boolean idle = !Caster.running;
        if (Caster.windows()) {
            WindowList.refresh();
            List<String> sources = new ArrayList<>(List.of(DESKTOP));
            for (String t : WindowList.titles()) sources.add(WINDOW + t);
            if (!sources.contains(source)) source = DESKTOP;
            if (idle) addRenderableWidget(CycleButton.<String>builder(s -> Component.literal(
                    s.equals(DESKTOP) ? Caster.tr("source_desktop") : s.substring(WINDOW.length())), source)
                .withValues(sources)
                .create(x, y - 28, w, 20, Component.translatable("chorus.cast.source"), (b, v) -> source = v));
            addRenderableWidget(Button.builder(Component.translatable("chorus.cast.refresh"), b -> minecraft.gui.setScreen(new CastScreen(be)))
                .bounds(x, y - 52, w, 20).build());
        }
        List<String> audio = new ArrayList<>(List.of(AudioRoute.NONE, AudioRoute.ALL));
        Map<String, String> apps = Caster.windows() ? Map.of() : AudioRoute.apps();
        audio.addAll(apps.keySet());
        if (idle) {
            if (!apps.containsKey(app)) app = apps.isEmpty() ? AudioRoute.NONE : apps.keySet().iterator().next();
            addRenderableWidget(CycleButton.<String>builder(id -> Component.literal(
                    id.equals(AudioRoute.NONE) ? Caster.tr("audio_none") : id.equals(AudioRoute.ALL) ? Caster.tr("audio_all") : apps.get(id)), app)
                .withValues(audio)
                .create(x, y, w, 20, Component.translatable("chorus.cast.audio"), (b, v) -> app = v));
            List<Integer> delays = new ArrayList<>();
            for (int ms = -500; ms <= 1000; ms += 50) delays.add(ms);
            int current = ClientConfig.castAudioDelay();
            if (!delays.contains(current)) current = 0;
            addRenderableWidget(CycleButton.<Integer>builder(ms -> Component.translatable("chorus.cast.delay_value", (ms > 0 ? "+" : "") + ms), current)
                .withValues(delays)
                .create(x, y + 84, w, 20, Component.translatable("chorus.cast.delay"), (b, ms) -> ClientConfig.set("cast_audio_delay_ms", ms)));
            if (!Caster.windows()) addRenderableWidget(CycleButton.onOffBuilder(ClientConfig.hearProjector())
                .create(x, y + 56, w, 20, Component.translatable("chorus.cast.hear_projector"), (b, on) -> ClientConfig.set("cast_hear_projector", on)));
            addRenderableWidget(CycleButton.<String>builder(id -> Component.literal(qualityLabel(id)), ClientConfig.castQuality())
                .withValues(java.util.Arrays.stream(Caster.QUALITIES).map(Caster.Quality::id).toList())
                .create(x, y + 28, w, 20, Component.translatable("chorus.cast.quality"), (b, v) -> ClientConfig.set("cast_quality", v)));
        }
        addRenderableWidget(Button.builder(Component.translatable(Caster.running ? "chorus.cast.stop" : "chorus.cast.start"), b -> {
            if (Caster.running) Caster.stop();
            else Caster.start(be.getBlockPos(), app, source);
            minecraft.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
        }).bounds(x, y + (Caster.running ? 0 : 112), w, 20).build());
    }

    static String qualityLabel(String id) {
        for (Caster.Quality q : Caster.QUALITIES)
            if (q.id().equals(id)) return q.height() + "p" + (q.fps() == 60 ? " 60 fps" : "") + " · " + (q.kbps() / 1000.0) + " Mbps";
        return id;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        int cx = width / 2, y = height / 2 - 70;
        if (!Caster.supported()) {
            g.centeredText(font, title, cx, y, 0xFFFFFFFF);
            g.centeredText(font, Component.translatable("chorus.cast.unsupported"), cx, y + 30, 0xFFFFAA55);
            return;
        }
        g.centeredText(font, title, cx, y - 14, 0xFFFFFFFF);
        if (!Caster.running) {
            boolean projector = ClientConfig.hearProjector() && !Caster.windows();
            boolean oneApp = !app.equals(AudioRoute.NONE) && !app.equals(AudioRoute.ALL);
            String hint = !projector ? "chorus.cast.hint" : oneApp ? "chorus.cast.hint_projector" : "chorus.cast.hint_projector_app";
            g.centeredText(font, Component.translatable(hint), cx, height / 2 + 104, projector && !oneApp ? 0xFFFFAA55 : 0xFFAAAAAA);
        }
        g.centeredText(font, Component.literal(Caster.status), cx, height / 2 + 118, 0xFF88CCFF);
    }

    @Override public boolean isPauseScreen() { return false; }
}
