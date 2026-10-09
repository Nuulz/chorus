package dev.nuulz.chorus.client;

import dev.nuulz.chorus.ControlPayload;
import dev.nuulz.chorus.Media;
import dev.nuulz.chorus.ScreenBlockEntity;
import dev.nuulz.chorus.UrlPolicy;
import dev.nuulz.pane.PaneView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Using a screen's page by hand: click, scroll, type, back/forward (e.g. log in to Telegram Web and
 * open a video). It moves only your own copy of the page; "Share" sends its address to everyone.
 * While it's open, Chorus stops pinning the video over the page so the site stays usable.
 */
public final class BrowseScreen extends Screen {
    private final ScreenBlockEntity be;
    private EditBox address;
    private int px, py, pw, ph;
    private String shown = "";

    public BrowseScreen(ScreenBlockEntity be) {
        super(Component.translatable("chorus.browse"));
        this.be = be;
    }

    private Screens.Live live() { return Screens.get(be); }
    private PaneView page() { Screens.Live l = live(); return l == null ? null : l.view; }

    @Override
    protected void init() {
        Screens.browsingNow.add(be);
        Screens.tick();
        Screens.Live l = live();
        if (l != null) l.browsing = true;
        int bar = 4;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { if (page() != null) page().back(); }).bounds(4, bar, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { if (page() != null) page().forward(); }).bounds(26, bar, 20, 20).build());
        address = new EditBox(font, 50, bar, width - 50 - 150, 20, Component.literal("URL"));
        address.setMaxLength(UrlPolicy.MAX_LENGTH);
        addRenderableWidget(address);
        addRenderableWidget(Button.builder(Component.translatable("chorus.share"), b -> share()).bounds(width - 96, bar, 60, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width - 34, bar, 30, 20).build());
        int top = 28, avail = height - top - 4;
        pw = Math.min(width - 8, avail * 16 / 9);
        ph = pw * 9 / 16;
        px = (width - pw) / 2;
        py = top + (avail - ph) / 2;
    }

    private void share() {
        PaneView p = page();
        if (p == null) return;
        String url = p.url();
        if (!ClientConfig.urls.allows(url)) {
            minecraft.player.sendSystemMessage(Component.translatable("chorus.url_blocked"));
            return;
        }
        ClientPlayNetworking.send(new ControlPayload(be.getBlockPos(), ControlPayload.SET_URL, url, 0));
    }

    private void go(String typed) {
        PaneView p = page();
        if (p == null || typed.isBlank()) return;
        String url = typed.contains("://") ? typed : "https://" + typed;
        if (ClientConfig.urls.allows(url)) p.load(url);
        else minecraft.player.sendSystemMessage(Component.translatable("chorus.url_blocked"));
    }

    private boolean inPage(double x, double y) { return x >= px && x < px + pw && y >= py && y < py + ph; }
    private int pageX(double x) { PaneView p = page(); return (int) ((x - px) * p.width() / pw); }
    private int pageY(double y) { PaneView p = page(); return (int) ((y - py) * p.height() / ph); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        Screens.pump();
        Screens.Live l = live();
        if (l != null) l.browsing = true;
        PaneView p = page();
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFF000000);
        if (p == null) {
            g.centeredText(font, Component.translatable("chorus.browse_far"), width / 2, py + ph / 2, 0xFFAAAAAA);
            return;
        }
        if (!address.isFocused() && !shown.equals(p.url())) { shown = p.url(); address.setValue(Media.isMedia(be.url) ? "" : shown); }
        l.texture.update(p);
        if (l.texture.ready())
            g.blit(RenderPipelines.GUI_TEXTURED, l.texture.id, px, py, 0f, 0f, pw, ph, p.width(), p.height(), p.width(), p.height());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (super.mouseClicked(e, doubleClick)) return true;
        PaneView p = page();
        if (p == null || !inPage(e.x(), e.y())) return false;
        setFocused(null);
        address.setFocused(false);
        p.focus(true);
        p.mouseDown(pageX(e.x()), pageY(e.y()), e.button(), e.modifiers());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        PaneView p = page();
        if (p != null && inPage(e.x(), e.y())) p.mouseUp(pageX(e.x()), pageY(e.y()), e.button(), e.modifiers());
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        PaneView p = page();
        if (p != null && inPage(e.x(), e.y())) p.mouseMove(pageX(e.x()), pageY(e.y()));
        return true;
    }

    @Override
    public void mouseMoved(double x, double y) {
        PaneView p = page();
        if (p != null && inPage(x, y)) p.mouseMove(pageX(x), pageY(y));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        PaneView p = page();
        if (p != null && inPage(x, y)) p.wheel(pageX(x), pageY(y), v, 0);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(e);
        if (address.isFocused()) {
            if (e.key() == GLFW.GLFW_KEY_ENTER || e.key() == GLFW.GLFW_KEY_KP_ENTER) {
                go(address.getValue().trim());
                address.setFocused(false);
                return true;
            }
            return super.keyPressed(e);
        }
        PaneView p = page();
        if (p != null) p.keyDown(e.key(), e.scancode(), e.modifiers());
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent e) {
        PaneView p = page();
        if (p != null && !address.isFocused()) p.keyUp(e.key(), e.scancode(), e.modifiers());
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (address.isFocused()) return super.charTyped(e);
        PaneView p = page();
        if (p != null) for (char c : e.codepointAsString().toCharArray()) p.typed(c, 0);
        return true;
    }

    @Override
    public void removed() {
        Screens.browsingNow.remove(be);
        Screens.Live l = live();
        if (l != null) { l.browsing = false; l.revision = -1; } // resync and pin again
        PaneView p = page();
        if (p != null) { p.focus(false); p.mouseLeave(); }
    }

    @Override public boolean isPauseScreen() { return false; }

    public void goForTest(String url) { go(url); }
    public void shareForTest() { share(); }
}
