package dev.nuulz.chorus.client;

import dev.nuulz.pane.PaneView;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/** A page's BGRA pixels as a game texture, re-uploaded only when Chromium painted something new. */
final class ScreenTexture {
    private static int serial;
    final Identifier id = Identifier.fromNamespaceAndPath("chorus", "screen/" + serial++);
    private DynamicTexture texture;
    private int width, height, version = -1;

    boolean ready() { return texture != null && version != -1; }

    void update(PaneView page) {
        ByteBuffer src = page.pixels();
        if (src == null) return;
        int w = page.width(), h = page.height();
        if (texture == null || w != width || h != height) {
            release();
            width = w; height = h;
            texture = new DynamicTexture(id::toString, w, h, false);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            version = -1;
        }
        if (page.version() == version) return;
        var image = texture.getPixels();
        if (image == null) return;
        version = page.version();
        long s = MemoryUtil.memAddress(src), d = image.getPointer();
        long n = Math.min((long) w * h, src.capacity() / 4);
        for (long i = 0; i < n; i++) {
            int v = MemoryUtil.memGetInt(s + i * 4);
            MemoryUtil.memPutInt(d + i * 4, (v & 0x0000FF00) | ((v >>> 16) & 0xFF) | ((v & 0xFF) << 16) | 0xFF000000);
        }
        texture.upload();
    }

    void release() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(id);
        texture = null;
        version = -1;
    }
}
