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
    private int[] pixels = new int[0];

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
        long t0 = System.nanoTime();
        int n = w * h;
        if (pixels.length < n) pixels = new int[n];
        src.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer().get(0, pixels, 0, Math.min(n, src.capacity() / 4));
        for (int i = 0; i < n; i++) {
            int v = pixels[i];
            pixels[i] = (v & 0xFF00FF00) | ((v >>> 16) & 0xFF) | ((v & 0xFF) << 16) | 0xFF000000;
        }
        long t1 = System.nanoTime();
        MemoryUtil.memIntBuffer(image.getPointer(), n).put(pixels, 0, n);
        long t2 = System.nanoTime();
        texture.upload();
        Stats.convertNs.addAndGet(t1 - t0);
        Stats.uploadNs.addAndGet(System.nanoTime() - t2);
        Stats.converts.incrementAndGet();
    }

    void release() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(id);
        texture = null;
        version = -1;
    }
}
