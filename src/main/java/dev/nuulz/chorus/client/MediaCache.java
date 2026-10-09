package dev.nuulz.chorus.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nuulz.chorus.Chorus;
import dev.nuulz.chorus.Media;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Videos from the server, kept in .minecraft/chorus-cache and served to Chromium from 127.0.0.1.
 * Chunks are pulled in order, but whatever Chromium asks for (a seek, an mp4 index at the end) jumps
 * the queue, and its request waits until those bytes are here. So playback starts before the
 * download ends.
 */
final class MediaCache {
    private static final int IN_FLIGHT = 8;
    private static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("chorus-cache");
    private static final String TOKEN = HexFormat.of().formatHex(new SecureRandom().generateSeed(12));
    private static final Map<String, Entry> entries = new HashMap<>();
    private static HttpServer http;

    private static final class Entry {
        final String name;
        long size = -1;
        RandomAccessFile file;
        BitSet have = new BitSet();
        final BitSet asked = new BitSet();
        final ArrayDeque<Integer> urgent = new ArrayDeque<>();
        int next, inFlight;
        boolean missing;

        Entry(String name) { this.name = name; }
        int chunks() { return (int) ((size + Media.CHUNK - 1) / Media.CHUNK); }
        boolean complete() { return size >= 0 && have.cardinality() == chunks(); }
    }

    /** The address Chromium loads for a "chorus-media:" URL. */
    static synchronized String url(String name) {
        entry(name);
        return "http://127.0.0.1:" + server().getAddress().getPort() + "/" + TOKEN + "/" + name;
    }

    /** Fraction downloaded, or -1 if unknown. */
    static synchronized double progress(String name) {
        Entry e = entries.get(name);
        if (e == null || e.size < 0) return -1;
        return e.size == 0 ? 1 : (double) e.have.cardinality() / e.chunks();
    }

    /** Keeps requests going; called every client tick. */
    static synchronized void tick() {
        for (Entry e : entries.values()) {
            if (e.missing || e.complete()) continue;
            if (e.size < 0) { if (e.inFlight == 0) request(e, 0); continue; }
            while (e.inFlight < IN_FLIGHT) {
                Integer i = e.urgent.poll();
                if (i == null) {
                    while (e.next < e.chunks() && (e.have.get(e.next) || e.asked.get(e.next))) e.next++;
                    if (e.next >= e.chunks()) break;
                    i = e.next;
                }
                if (!e.have.get(i) && !e.asked.get(i)) request(e, i);
            }
        }
    }

    private static void request(Entry e, int index) {
        e.asked.set(index);
        e.inFlight++;
        ClientPlayNetworking.send(new Media.Fetch(e.name, index));
    }

    static synchronized void received(Media.Chunk c) {
        Entry e = entries.get(c.name());
        if (e == null) return;
        e.inFlight = Math.max(0, e.inFlight - 1);
        e.asked.clear(c.index());
        if (c.size() < 0) { e.missing = true; MediaCache.class.notifyAll(); return; }
        try {
            if (e.size < 0) open(e, c.size());
            e.file.seek((long) c.index() * Media.CHUNK);
            e.file.write(c.data());
            e.have.set(c.index());
            if (e.complete()) Files.writeString(DIR.resolve(e.name + ".done"), "");
        } catch (IOException ex) {
            Chorus.LOG.warn("chorus: cache write failed ({})", ex.toString());
        }
        MediaCache.class.notifyAll();
    }

    /** Forget downloads in progress (on disconnect); finished files stay cached. */
    static synchronized void reset() {
        for (Entry e : entries.values()) try { if (e.file != null) e.file.close(); } catch (IOException ignored) { }
        entries.clear();
        MediaCache.class.notifyAll();
    }

    private static Entry entry(String name) {
        return entries.computeIfAbsent(name, n -> {
            Entry e = new Entry(n);
            Path f = DIR.resolve(n);
            try {
                if (Files.exists(DIR.resolve(n + ".done")) && Files.isRegularFile(f)) {
                    open(e, Files.size(f));
                    e.have.set(0, e.chunks());
                }
            } catch (IOException ignored) { }
            return e;
        });
    }

    private static void open(Entry e, long size) throws IOException {
        Files.createDirectories(DIR);
        e.size = size;
        e.file = new RandomAccessFile(DIR.resolve(e.name).toFile(), "rw");
        if (e.file.length() != size) e.file.setLength(size);
    }

    /** Waits until [index] is here (asking for it first); false if it never comes. */
    private static synchronized boolean await(Entry e, int index) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        if (e.size >= 0 && !e.have.get(index) && !e.urgent.contains(index)) e.urgent.addFirst(index);
        while (entries.get(e.name) == e && !e.missing && (e.size < 0 || !e.have.get(index))) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return false;
            MediaCache.class.wait(left);
            if (e.size >= 0 && !e.have.get(index) && !e.urgent.contains(index) && !e.asked.get(index)) e.urgent.addFirst(index);
        }
        return entries.get(e.name) == e && !e.missing;
    }

    private static HttpServer server() {
        if (http != null) return http;
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            http.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "chorus-media"); t.setDaemon(true); return t; }));
            http.createContext("/" + TOKEN + "/", MediaCache::serve);
            http.start();
        } catch (IOException e) {
            throw new IllegalStateException("chorus: no local media server", e);
        }
        return http;
    }

    private static void serve(HttpExchange x) throws IOException {
        try (x) {
            String name = x.getRequestURI().getPath().substring(TOKEN.length() + 2);
            Entry e;
            synchronized (MediaCache.class) { e = Media.NAME.matcher(name).matches() ? entries.get(name) : null; }
            if (e == null || !await(e, 0)) { x.sendResponseHeaders(404, -1); return; }
            long size = e.size, from = 0, to = size - 1;
            String range = x.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                String[] r = range.substring(6).split("-", 2);
                if (!r[0].isEmpty()) from = Long.parseLong(r[0]);
                else to = size - 1;
                if (r.length > 1 && !r[1].isEmpty()) {
                    if (r[0].isEmpty()) from = Math.max(0, size - Long.parseLong(r[1]));
                    else to = Math.min(size - 1, Long.parseLong(r[1]));
                }
            }
            if (from > to || from >= size) {
                x.getResponseHeaders().set("Content-Range", "bytes */" + size);
                x.sendResponseHeaders(416, -1);
                return;
            }
            x.getResponseHeaders().set("Content-Type", type(name));
            x.getResponseHeaders().set("Accept-Ranges", "bytes");
            if (range != null) x.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + size);
            x.sendResponseHeaders(range != null ? 206 : 200, to - from + 1);
            OutputStream out = x.getResponseBody();
            byte[] buf = new byte[Media.CHUNK];
            for (long at = from; at <= to; ) {
                int index = (int) (at / Media.CHUNK);
                if (!await(e, index)) return;
                int n = (int) Math.min(to + 1 - at, (long) (index + 1) * Media.CHUNK - at);
                synchronized (MediaCache.class) {
                    if (e.file == null) return;
                    e.file.seek(at);
                    e.file.readFully(buf, 0, n);
                }
                out.write(buf, 0, n);
                at += n;
            }
        } catch (IOException | InterruptedException | NumberFormatException closedOrBad) {
            // Chromium dropped the connection (seeking does that) or sent a range we don't understand
        }
    }

    private static String type(String name) {
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        return "video/webm";
    }

    /** The JDK's HTTP server keeps a non-daemon thread, which would hold the game open on exit. */
    static synchronized void stop() {
        if (http != null) http.stop(0);
        http = null;
    }

    private MediaCache() {}
}
