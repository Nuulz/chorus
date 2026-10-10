package dev.nuulz.chorus.client;

import com.sun.net.httpserver.HttpExchange;
import dev.nuulz.chorus.Cast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Casts this client is watching. Keeps the header and the clusters since the latest join point, and
 * serves them to Chromium as one endless WebM: header, then clusters as they arrive.
 */
final class LiveCache {
    private static final int KEEP = 24 * 1024 * 1024;

    private static final class Live {
        byte[] init;
        final List<byte[]> clusters = new ArrayList<>();
        long first; // sequence number of clusters.get(0)
        int bytes;
        boolean ended;
        final ByteArrayOutputStream building = new ByteArrayOutputStream();
    }

    private static final Map<UUID, Live> lives = new HashMap<>();

    static synchronized String url(UUID caster) {
        if (!lives.containsKey(caster)) {
            lives.put(caster, new Live());
            ClientPlayNetworking.send(new Cast.Watch(caster, true));
        }
        return MediaCache.local("live", LiveCache::serve) + caster;
    }

    static synchronized void stop(UUID caster) {
        Live l = lives.remove(caster);
        if (l == null) return;
        l.ended = true;
        ClientPlayNetworking.send(new Cast.Watch(caster, false));
        LiveCache.class.notifyAll();
    }

    static synchronized void reset() {
        for (Live l : lives.values()) l.ended = true;
        lives.clear();
        LiveCache.class.notifyAll();
    }

    static synchronized void received(Cast.Bytes b) {
        Live l = lives.get(b.caster());
        if (l == null) return;
        if (b.kind() < 0) { l.ended = true; LiveCache.class.notifyAll(); return; }
        l.building.writeBytes(b.data());
        if (!b.last()) return;
        byte[] element = l.building.toByteArray();
        l.building.reset();
        if (b.kind() == Cast.INIT) {
            l.init = element;
            l.first += l.clusters.size();
            l.clusters.clear();
            l.bytes = 0;
        } else {
            if (b.kind() == Cast.JOIN || l.bytes + element.length > KEEP) {
                l.first += l.clusters.size();
                l.clusters.clear();
                l.bytes = 0;
                if (b.kind() != Cast.JOIN) return;
            }
            l.clusters.add(element);
            l.bytes += element.length;
        }
        LiveCache.class.notifyAll();
    }

    private static void serve(HttpExchange x) throws IOException {
        try (x) {
            String id = x.getRequestURI().getPath();
            UUID caster = UUID.fromString(id.substring(id.lastIndexOf('/') + 1));
            Live l;
            byte[] init;
            long next;
            synchronized (LiveCache.class) {
                l = lives.get(caster);
                while (l != null && !l.ended && (l.init == null || l.clusters.isEmpty())) LiveCache.class.wait(30_000);
                if (l == null || l.ended || l.init == null) { x.sendResponseHeaders(404, -1); return; }
                init = l.init;
                next = l.first;
            }
            x.getResponseHeaders().set("Content-Type", "video/webm");
            x.getResponseHeaders().set("Cache-Control", "no-store");
            x.sendResponseHeaders(200, 0);
            OutputStream out = x.getResponseBody();
            out.write(init);
            while (true) {
                byte[] cluster;
                synchronized (LiveCache.class) {
                    while (!l.ended && next >= l.first + l.clusters.size()) LiveCache.class.wait(5_000);
                    if (l.ended) return;
                    if (next < l.first) next = l.first; // fell behind past a join point: skip ahead
                    cluster = l.clusters.get((int) (next - l.first));
                    next++;
                }
                out.write(cluster);
                out.flush();
            }
        } catch (IOException | InterruptedException | IllegalArgumentException closed) {
            // Chromium went away or reloaded
        }
    }

    private LiveCache() {}
}
