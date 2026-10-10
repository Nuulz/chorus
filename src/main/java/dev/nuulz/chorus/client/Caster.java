package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Cast;
import dev.nuulz.chorus.Chorus;
import dev.nuulz.chorus.Media;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Sharing your screen: the portal's picker → GStreamer reads the window from PipeWire as raw frames →
 * ffmpeg encodes live AV1 (+ Opus from {@link AudioRoute}) into WebM → cut into header and clusters
 * here → sent through the Minecraft connection. One cast per player.
 */
public final class Caster {
    /** Picture size, frame rate and bitrate of a cast; set from the screen's quality setting. */
    record Quality(String id, int height, int fps, int kbps) {
        int width() { return height * 16 / 9; }
    }

    static final Quality[] QUALITIES = {
        new Quality("480", 480, 30, 1200),
        new Quality("720", 720, 30, 2500),
        new Quality("1080", 1080, 30, 5000),
        new Quality("1080x60", 1080, 60, 8000),
    };

    static Quality quality() {
        String id = ClientConfig.castQuality();
        for (Quality q : QUALITIES) if (q.id().equals(id)) return q;
        return QUALITIES[1];
    }

    public static volatile String status = "";
    static volatile boolean running;

    private static volatile boolean stopping;
    private static Portal portal;
    private static Path progress;
    private static long startedAt, sentBytes;
    private static long frames = -1;
    private static AudioRoute audio;
    private static final List<Process> processes = new ArrayList<>();
    private static final ConcurrentLinkedQueue<Element> out = new ConcurrentLinkedQueue<>();
    private static BlockPos pos;
    private static boolean begun;
    private static int ticks;

    private record Element(int kind, byte[] data) {}

    /** Your own projector plays your cast only when the app's sound goes nowhere else (no echo, no loop). */
    static boolean hearOwnProjector() {
        AudioRoute a = audio;
        return running && a != null && a.onlyInCast();
    }

    static boolean windows() { return System.getProperty("os.name").toLowerCase().contains("windows"); }

    static boolean supported() {
        return windows() || System.getProperty("os.name").toLowerCase().contains("linux");
    }

    /** [audio]: an app's name (Linux), {@link AudioRoute#ALL} or {@link AudioRoute#NONE}. [source]: Windows only, "desktop" or "window:<title>". */
    static synchronized void start(BlockPos target, String app, String source) {
        if (running) return;
        running = true;
        stopping = false;
        begun = false;
        pos = target;
        status = tr("starting");
        Thread t = new Thread(() -> run(app, source), "chorus-cast");
        t.setDaemon(true);
        t.start();
    }

    /** For tests: a generated picture and tone instead of a window. */
    public static void startTest(BlockPos target) { start(target, "test", "test"); }

    /** Raw I420 pictures of the given size from a GStreamer source, as fast as they come (no fixed rate). */
    private static ProcessBuilder gstreamer(List<String> source, int w, int h) {
        List<String> cmd = new ArrayList<>(List.of("gst-launch-1.0", "-q"));
        cmd.addAll(source);
        cmd.addAll(List.of("!", "queue", "leaky=downstream", "max-size-buffers=2", "!", "videoconvert", "!",
            "videoscale", "add-borders=true", "!", "video/x-raw,format=I420,width=" + w + ",height=" + h, "!", "fdsink", "fd=1"));
        return new ProcessBuilder(cmd);
    }

    /**
     * Screen capture only sends a picture when something changed, and a slow window sends few. ffmpeg
     * needs a steady rate, so one thread keeps the newest picture and another hands it to ffmpeg [fps]
     * times a second, repeating it while nothing new comes.
     */
    private static void pump(InputStream from, java.io.OutputStream to, int frameBytes, int fps) {
        byte[][] latest = {null};
        Thread reader = new Thread(() -> {
            try (DataInputStream in = new DataInputStream(from)) {
                while (true) {
                    byte[] f = new byte[frameBytes];
                    in.readFully(f);
                    synchronized (latest) { latest[0] = f; }
                    grabbed++;
                }
            } catch (IOException end) { }
        }, "chorus-cast-read");
        Thread writer = new Thread(() -> {
            long period = 1_000_000_000L / fps, next = System.nanoTime();
            try (to) {
                while (running && !stopping) {
                    byte[] f;
                    synchronized (latest) { f = latest[0]; }
                    if (f != null) to.write(f);
                    next += period;
                    long wait = next - System.nanoTime();
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    else next = System.nanoTime();
                }
            } catch (Exception end) { }
        }, "chorus-cast-write");
        reader.setDaemon(true); writer.setDaemon(true);
        reader.start(); writer.start();
    }

    private static volatile long grabbed;

    private static boolean gstreamerHasPipeWire() {
        try {
            return new ProcessBuilder("gst-inspect-1.0", "pipewiresrc").redirectErrorStream(true).start()
                .waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String lastLine(Path log) {
        try {
            List<String> lines = Files.readAllLines(log);
            for (int i = lines.size() - 1; i >= 0; i--) if (!lines.get(i).isBlank()) return lines.get(i).trim();
        } catch (IOException ignored) { }
        return "";
    }

    public static void startGstTest(BlockPos target) { start(target, "gsttest", "test"); }

    private static void run(String app, String source) {
        grabbed = 0;
        try {
            Encoders.Encoder enc = Encoders.best();
            Quality q = quality();
            int W = q.width(), H = q.height(), FPS = q.fps();
            List<String> ff = new ArrayList<>(List.of(FFmpeg.ffmpeg(), "-v", "error"));
            ff.addAll(enc.before());
            ProcessBuilder video = null;
            boolean test = app.equals("test");
            if (app.equals("gsttest")) {
                // a slow, damage-like source (2 pictures a second) through the same pump as a real window
                video = gstreamer(List.of("videotestsrc", "is-live=true", "pattern=ball", "!", "video/x-raw,framerate=2/1"), W, H);
                ff.addAll(List.of("-f", "rawvideo", "-pix_fmt", "yuv420p", "-video_size", W + "x" + H, "-framerate", "" + FPS,
                    "-thread_queue_size", "64", "-i", "pipe:0"));
                app = AudioRoute.NONE;
                test = false;
            }
            boolean sound;
            if (test) {
                ff.addAll(List.of("-re", "-f", "lavfi", "-i", "testsrc2=s=" + W + "x" + H + ":r=" + FPS,
                    "-re", "-f", "lavfi", "-i", "sine=frequency=330"));
                sound = true;
            } else if (video != null) {
                sound = false;
            } else if (windows()) {
                ff.addAll(windowsVideo(source, FPS));
                sound = !app.equals(AudioRoute.NONE);
                if (sound) ff.addAll(List.of("-f", "wasapi", "-thread_queue_size", "1024", "-i", "default"));
            } else {
                if (!gstreamerHasPipeWire()) { finish(tr("needs_gst")); return; }
                portal = new Portal();
                status = tr("pick");
                if (!portal.pick(ClientConfig.castToken())) { finish(tr("cancelled")); return; }
                if (portal.restoreToken != null) ClientConfig.set("cast_restore_token", portal.restoreToken);
                video = gstreamer(List.of("pipewiresrc", "path=" + portal.node, "always-copy=true"), W, H);
                ff.addAll(List.of("-f", "rawvideo", "-pix_fmt", "yuv420p", "-video_size", W + "x" + H, "-framerate", "" + FPS,
                    "-thread_queue_size", "64", "-i", "pipe:0"));
                audio = new AudioRoute(app, !ClientConfig.hearProjector());
                sound = audio.source != null;
                if (sound) ff.addAll(List.of("-f", "pulse", "-thread_queue_size", "1024", "-i", audio.source));
            }
            ff.addAll(List.of("-map", "0:v"));
            if (sound) ff.addAll(List.of("-map", "1:a"));
            if (windows()) ff.addAll(List.of("-vf", "scale=-2:" + H + ",fps=" + FPS + ",format=" + (enc.hw().isEmpty() ? "yuv420p" : "nv12")));
            else if (!enc.hw().isEmpty()) ff.addAll(List.of("-vf", enc.hw()));
            ff.addAll(enc.live().apply(q.kbps()));
            ff.addAll(List.of("-g", "" + FPS * 2));
            if (enc.hw().isEmpty()) ff.addAll(List.of("-pix_fmt", "yuv420p"));
            if (sound) ff.addAll(List.of("-c:a", "libopus", "-b:a", "128k"));
            progress = FabricLoader.getInstance().getGameDir().resolve("logs/chorus-cast-progress.txt");
            ff.addAll(List.of("-progress", progress.toString(), "-stats_period", "2"));
            ff.addAll(List.of("-f", "webm", "-live", "1", "-cluster_time_limit", "1000", "pipe:1"));

            Path log = FabricLoader.getInstance().getGameDir().resolve("logs/chorus-cast.log");
            Chorus.LOG.info("chorus: cast command {}", String.join(" ", ff));
            ProcessBuilder encode = new ProcessBuilder(ff).redirectError(ProcessBuilder.Redirect.to(log.toFile()));
            List<Process> ps;
            if (video != null) {
                video.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
                Process grab = video.start(), enc2 = encode.start();
                ps = List.of(grab, enc2);
                pump(grab.getInputStream(), enc2.getOutputStream(), W * H * 3 / 2, FPS);
            } else {
                ps = List.of(encode.start());
            }
            synchronized (Caster.class) { processes.addAll(ps); }
            startedAt = System.currentTimeMillis();
            if (video != null) {
                Thread.sleep(1500);
                if (!ps.get(0).isAlive()) {
                    finish(tr("failed") + " " + lastLine(log));
                    return;
                }
            }
            status = tr("live") + " (" + enc.label() + ", " + q.height() + "p" + q.fps() + ")";
            Chorus.LOG.info("chorus: casting with {}", enc.label());
            split(ps.get(ps.size() - 1).getInputStream());
            if (!stopping) finish(tr("ended"));
        } catch (Exception e) {
            if (!stopping) Chorus.LOG.warn("chorus: cast failed", e);
            if (!stopping) finish(tr("failed") + " " + (e.getMessage() == null ? e : e.getMessage()));
        }
    }

    /** Windows screen or window through GDI (works on any GPU, no admin rights); scaled to the quality. */
    private static List<String> windowsVideo(String source, int fps) {
        List<String> in = new ArrayList<>();
        in.addAll(List.of("-f", "gdigrab", "-framerate", "" + fps, "-draw_mouse", "1"));
        in.addAll(List.of("-thread_queue_size", "64", "-i", source.startsWith("window:") ? "title=" + source.substring(7) : "desktop"));
        return in;
    }

    // ---- WebM splitting ----------------------------------------------------------------------------

    private static final long EBML = 0x1A45DFA3L, SEGMENT = 0x18538067L, CLUSTER = 0x1F43B675L,
        SIMPLE_BLOCK = 0xA3L, BLOCK_GROUP = 0xA0L;

    /** Header (EBML + Segment start + everything before the first cluster), then one element per cluster. */
    private static void split(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        ByteArrayOutputStream init = new ByteArrayOutputStream();
        boolean sentInit = false;
        try {
            while (true) {
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                long id = readId(in, head);
                long size = readSize(in, head);
                if (id == SEGMENT) { init.writeBytes(head.toByteArray()); continue; } // its children follow
                if (size < 0 || size > 64L * 1024 * 1024) throw new IOException("unexpected WebM element " + Long.toHexString(id));
                byte[] body = in.readNBytes((int) size);
                if (body.length < size) throw new EOFException();
                if (id != CLUSTER) {
                    if (!sentInit) { init.writeBytes(head.toByteArray()); init.writeBytes(body); }
                    continue;
                }
                if (!sentInit) { out.add(new Element(Cast.INIT, init.toByteArray())); sentInit = true; }
                ByteArrayOutputStream c = new ByteArrayOutputStream(head.size() + body.length);
                c.writeBytes(head.toByteArray());
                c.writeBytes(body);
                out.add(new Element(startsWithKeyframe(body) ? Cast.JOIN : Cast.CLUSTER, c.toByteArray()));
            }
        } catch (EOFException end) {
            // ffmpeg stopped
        }
    }

    /** Whether the cluster's first video frame (track 1) is a keyframe. */
    private static boolean startsWithKeyframe(byte[] b) {
        int i = 0;
        while (i < b.length) {
            int[] id = vint(b, i, true);
            int[] size = vint(b, i + id[1], false);
            int body = i + id[1] + size[1];
            if (id[0] == SIMPLE_BLOCK) {
                int[] track = vint(b, body, false);
                if (track[0] == 1) return (b[body + track[1] + 2] & 0x80) != 0;
            }
            if (id[0] == BLOCK_GROUP) return false; // ffmpeg's live WebM uses SimpleBlocks for frames it can start on
            if (size[0] < 0) return false;
            i = body + size[0];
        }
        return false;
    }

    /** {value, length} of an EBML variable-size integer at [at]; ids keep their marker bit. */
    private static int[] vint(byte[] b, int at, boolean keepMarker) {
        int first = b[at] & 0xFF, len = Integer.numberOfLeadingZeros(first) - 23;
        if (len < 1 || len > 8) return new int[]{-1, 1};
        long v = keepMarker ? first : first & (0xFF >> len);
        for (int k = 1; k < len; k++) v = (v << 8) | (b[at + k] & 0xFF);
        return new int[]{(int) v, len};
    }

    private static long readId(DataInputStream in, ByteArrayOutputStream head) throws IOException {
        int first = in.readUnsignedByte();
        head.write(first);
        int len = Integer.numberOfLeadingZeros(first) - 23;
        if (len < 1 || len > 4) throw new IOException("bad EBML id");
        long v = first;
        for (int k = 1; k < len; k++) { int x = in.readUnsignedByte(); head.write(x); v = (v << 8) | x; }
        return v;
    }

    /** -1 for "unknown size". */
    private static long readSize(DataInputStream in, ByteArrayOutputStream head) throws IOException {
        int first = in.readUnsignedByte();
        head.write(first);
        int len = Integer.numberOfLeadingZeros(first) - 23;
        if (len < 1 || len > 8) throw new IOException("bad EBML size");
        long v = first & (0xFF >> len);
        boolean allOnes = v == (0xFF >> len);
        for (int k = 1; k < len; k++) { int x = in.readUnsignedByte(); head.write(x); v = (v << 8) | x; allOnes &= x == 0xFF; }
        return allOnes ? -1 : v;
    }

    // ---- sending -----------------------------------------------------------------------------------

    /** Every client tick: hand finished elements to the server, and keep the audio route following. */
    static void tick() {
        if (!running) return;
        watchFrames();
        if (audio != null && ++ticks % 20 == 0) audio.follow();
        for (Element e; (e = out.poll()) != null; ) {
            if (!begun && e.kind == Cast.INIT) { ClientPlayNetworking.send(new Cast.Begin(pos)); begun = true; }
            for (int at = 0; at < e.data.length; at += Media.UPLOAD_PIECE) {
                int n = Math.min(Media.UPLOAD_PIECE, e.data.length - at);
                ClientPlayNetworking.send(new Cast.Piece(e.kind, at + n >= e.data.length, java.util.Arrays.copyOfRange(e.data, at, at + n)));
                sentBytes += n;
            }
        }
    }

    public static void stopForTest() { stop(); }

    /** Reads ffmpeg's progress file (every 2 s) and says so when no picture is arriving. */
    private static void watchFrames() {
        if (progress == null || System.currentTimeMillis() % 2000 > 60) return;
        long now = 0;
        try {
            for (String l : Files.readAllLines(progress)) if (l.startsWith("frame=")) now = Long.parseLong(l.substring(6).trim());
        } catch (Exception ignored) { }
        frames = now;
        if (frames == 0 && System.currentTimeMillis() - startedAt > 4000) status = tr("no_frames");
        else if (frames > 0) status = tr("live") + " · " + frames + " " + tr("frames") + " · " + (sentBytes / 1024) + " KB";
    }

    static void stop() {
        if (!running) return;
        stopping = true;
        finish(tr("stopped"));
    }

    private static synchronized void finish(String message) {
        for (Process p : processes) p.descendants().forEach(ProcessHandle::destroy);
        for (Process p : processes) p.destroy();
        processes.clear();
        if (audio != null) { audio.close(); audio = null; }
        if (portal != null) { portal.close(); portal = null; }
        out.clear();
        if (begun) { try { ClientPlayNetworking.send(new Cast.End()); } catch (Exception ignored) { } }
        begun = false;
        running = false;
        status = message;
    }

    static String tr(String key) { return Component.translatable("chorus.cast." + key).getString(); }

    private Caster() {}
}
