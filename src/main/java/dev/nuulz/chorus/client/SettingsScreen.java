package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Chorus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Chorus's settings (Mod Menu's Config button). */
public final class SettingsScreen extends Screen {
    private final Screen parent;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("chorus.settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = 260, x = (width - w) / 2, y = height / 4;
        addRenderableWidget(CycleButton.onOffBuilder(ClientConfig.anyLink)
            .create(x, y, w, 20, Component.translatable("chorus.settings.any_link"), (b, on) -> {
                ClientConfig.set("allow_any_link", on);
                Chorus.allowAnyLink(on); // a world you host follows this machine's choice
            }));
        addRenderableWidget(slider(x, y + 48, w, "chorus.settings.volume", 0, 100, ClientConfig.volume, "%",
            v -> ClientConfig.set("volume_percent", v)));
        addRenderableWidget(slider(x, y + 72, w, "chorus.settings.screens", 0, 4, ClientConfig.maxActive, "",
            v -> ClientConfig.set("max_active_screens", v)));
        addRenderableWidget(slider(x, y + 96, w, "chorus.settings.radius", 8, 64, ClientConfig.radius, "",
            v -> ClientConfig.set("radius", v)));
        addRenderableWidget(CycleButton.<Integer>builder(h -> Component.literal(h + "p"), ClientConfig.height >= 720 ? 720 : 480)
            .withValues(480, 720)
            .create(x, y + 120, w, 20, Component.translatable("chorus.settings.quality"), (b, h) -> {
                ClientConfig.set("height", h);
                ClientConfig.set("width", h == 720 ? 1280 : 854);
            }));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x, y + 156, w, 20).build());
    }

    private AbstractSliderButton slider(int x, int y, int w, String key, int min, int max, int value, String unit,
                                        java.util.function.IntConsumer save) {
        return new AbstractSliderButton(x, y, w, 20, Component.empty(), (double) (value - min) / (max - min)) {
            { updateMessage(); }
            private int current() { return (int) Math.round(min + this.value * (max - min)); }
            @Override protected void updateMessage() {
                setMessage(Component.translatable(key).append(": " + current() + unit));
            }
            @Override protected void applyValue() { save.accept(current()); }
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        g.centeredText(font, title, width / 2, height / 4 - 20, 0xFFFFFFFF);
        g.centeredText(font, Component.translatable("chorus.settings.any_link_warning"), width / 2, height / 4 + 26, 0xFFFFAA55);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
