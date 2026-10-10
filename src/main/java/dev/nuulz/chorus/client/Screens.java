package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Cast;
import dev.nuulz.chorus.Media;
import dev.nuulz.chorus.ScreenBlockEntity;
import dev.nuulz.pane.Pane;
import dev.nuulz.pane.PaneView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Which screens get a live browser on this machine, and keeping each one on the server's state.
 * Only the nearest {@link ClientConfig#maxActive} within the radius run Chromium; the rest are closed.
 * Textures upload only while the renderer draws them, so a screen nobody looks at costs no uploads.
 */
public final class Screens {
    static final class Live {
        PaneView view;
        final ScreenTexture texture = new ScreenTexture();
        String url = "";
        int revision = -1;
        /** Someone is using the page by hand (BrowseScreen): don't move or cover it. */
        boolean browsing;
        int fps = -1;
        private boolean unpinned;
        long nextSync;
    }

    /** Steady-state resync interval; a change in the screen's state resyncs at once. */
    static final int SYNC_TICKS = 40;

    private static final Map<ScreenBlockEntity, Live> live = new HashMap<>();
    private static final List<ScreenBlockEntity> loaded = new ArrayList<>();
    /** Screens open in BrowseScreen get a page even with nothing playing. */
    static final java.util.Set<ScreenBlockEntity> browsingNow = new java.util.HashSet<>();
    private static long lastPump;

    static void loaded(ScreenBlockEntity be) { if (!loaded.contains(be)) loaded.add(be); }

    static void unloaded(ScreenBlockEntity be) {
        loaded.remove(be);
        close(be);
    }

    static Live get(ScreenBlockEntity be) { return live.get(be); }

    public static String debug() {
        return "loaded=" + loaded.size() + " live=" + live.size() + " browserReady=" + Browser.ready()
            + " views=" + live.values().stream().filter(l -> l.view != null).count()
            + " ready=" + live.values().stream().filter(l -> l.texture.ready()).count()
            + " urls=" + loaded.stream().map(b -> "[" + b.url + "]").toList();
    }

    /** Chromium's work; called every frame from the renderer and every tick as a fallback. */
    static void pump() {
        long now = System.nanoTime();
        if (now - lastPump < 4_000_000L) return;
        lastPump = now;
        Pane.pump();
    }

    static void tick() {
        long t0 = System.nanoTime();
        try { tickScreens(); } finally { Stats.syncNs.addAndGet(System.nanoTime() - t0); Stats.syncs.incrementAndGet(); }
    }

    private static void tickScreens() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { closeAll(); return; }
        pump();
        loaded.removeIf(ScreenBlockEntity::isRemoved);
        Vec3 eye = mc.gameRenderer.mainCamera().position();
        double radius = ClientConfig.radius;

        List<ScreenBlockEntity> wanted = loaded.stream()
            .filter(be -> browsingNow.contains(be) || !be.url.isEmpty() && (Media.isMedia(be.url) || Cast.isCast(be.url) || ClientConfig.urls.allows(be.url)))
            .filter(be -> Vec3.atCenterOf(be.getBlockPos()).distanceTo(eye) < radius + be.size * 2 + (live.containsKey(be) ? 8 : 0))
            .sorted(Comparator.comparingDouble(be -> browsingNow.contains(be) ? -1 : Vec3.atCenterOf(be.getBlockPos()).distanceTo(eye)))
            .limit(ClientConfig.maxActive).toList();

        for (ScreenBlockEntity be : List.copyOf(live.keySet())) if (!wanted.contains(be)) close(be);
        Tuner.tick(mc);
        if (wanted.isEmpty()) return;
        if (!Browser.ready()) { Browser.prepare(); return; }

        long time = mc.level.getGameTime();
        for (ScreenBlockEntity be : wanted) {
            Live l = live.computeIfAbsent(be, k -> new Live());
            String target = be.url.isEmpty() ? "about:blank"
                : Media.isMedia(be.url) ? MediaCache.url(Media.name(be.url))
                : Cast.isCast(be.url) ? LiveCache.url(Cast.caster(be.url))
                : be.url;
            if (l.view == null) {
                l.view = Pane.open(target);
                l.view.resize(ClientConfig.width, ClientConfig.height);
                l.url = be.url;
            } else if (!l.url.equals(be.url)) {
                String old = l.url;
                l.view.load(target);
                l.url = be.url;
                if (Cast.isCast(old) && live.values().stream().noneMatch(o -> o.url.equals(old))) LiveCache.stop(Cast.caster(old));
            }
            if (l.view.width() != ClientConfig.width || l.view.height() != ClientConfig.height)
                l.view.resize(ClientConfig.width, ClientConfig.height);
            int fps = browsingNow.contains(be) ? 30 : Math.clamp(Tuner.budget() / Math.max(1, wanted.size()), 5, 30);
            if (fps != l.fps) { l.view.setWindowlessFrameRate(fps); l.fps = fps; }
            if (l.browsing) {
                if (!l.unpinned) { l.view.executeJavaScript(UNPIN, "", 0); l.unpinned = true; }
                continue;
            }
            l.unpinned = false;
            boolean fresh = l.revision != be.revision;
            if (!fresh && time < l.nextSync) continue;
            l.revision = be.revision;
            l.nextSync = time + SYNC_TICKS;
            double d = Vec3.atCenterOf(be.getBlockPos()).distanceTo(eye);
            double fall = Math.max(0, 1 - d / (radius + be.size * 2));
            double vol = fall * fall * ClientConfig.volume / 100.0;
            if (Cast.isCast(be.url) && Cast.caster(be.url).equals(mc.player.getUUID()) && !Caster.hearOwnProjector())
                vol = 0; // you hear the app itself
            l.view.executeJavaScript(sync(be.positionMs(time) / 1000.0, be.playing, vol, fresh ? 0.4 : 1.5), "", 0);
        }
    }

    /**
     * Puts the page's main video where the server says: seek if it drifted past [tolerance], play or
     * pause, volume by distance. Live streams (infinite duration) are never seeked. The video is also
     * pinned over the whole page so the screen shows the picture, not the site around it.
     */
    private static String sync(double seconds, boolean playing, double volume, double tolerance) {
        return String.format(Locale.ROOT, """
            (() => {
              const v = [...document.querySelectorAll('video')].sort((a, b) => b.clientWidth - a.clientWidth)[0];
              if (!v) return;
              if (!document.getElementById('__chorus')) {
                const s = document.createElement('style'); s.id = '__chorus';
                s.textContent = 'html,body{overflow:hidden!important;background:#000!important}body *{visibility:hidden!important}video.__chorus{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important}';
                document.head.appendChild(s);
              }
              v.classList.add('__chorus');
              v.volume = %f; v.muted = %f <= 0;
              if (isFinite(v.duration) && Math.abs(v.currentTime - %f) > %f) v.currentTime = %f;
              if (%b && v.paused) v.play().catch(() => {});
              if (!%b && !v.paused) v.pause();
            })();""", volume, volume, seconds, tolerance, seconds, playing, playing);
    }

    private static final String UNPIN = """
        (() => {
          const s = document.getElementById('__chorus'); if (s) s.remove();
          document.querySelectorAll('video.__chorus').forEach(v => v.classList.remove('__chorus'));
        })();""";

    private static void close(ScreenBlockEntity be) {
        Live l = live.remove(be);
        if (l == null) return;
        if (Cast.isCast(l.url) && live.values().stream().noneMatch(o -> o.url.equals(l.url))) LiveCache.stop(Cast.caster(l.url));
        if (l.view != null) l.view.close();
        l.texture.release();
    }

    static void closeAll() {
        for (ScreenBlockEntity be : List.copyOf(live.keySet())) close(be);
        loaded.clear();
    }

    private Screens() {}
}
