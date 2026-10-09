package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Chorus;
import dev.nuulz.chorus.Media;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Sending one of your own videos to a screen: pick → check codecs → convert if Chromium can't play
 * it (our Chromium has no H.264/AAC) → hash → upload in pieces, a few acks ahead of the server.
 */
public final class Uploader {
    private static final Set<String> VIDEO_OK = Set.of("vp8", "vp9", "av1");
    private static final Set<String> AUDIO_OK = Set.of("opus", "vorbis", "flac", "mp3");
    private static final Path WORK = FabricLoader.getInstance().getGameDir().resolve("chorus-cache/converted");

    /** What the control panel shows. */
    public static volatile String status = "";
    static volatile double fraction = -1;
    static volatile boolean busy;

    private static BlockPos pos;
    private static Path file;
    private static String name;
    private static long size, sent, acked;
    private static InputStream in;
    private static volatile boolean ready;

    private static Path preset;

    /** Skips the file dialog (tests). */
    public static void send(BlockPos target, Path video) {
        preset = video;
        pick(target);
    }

    static void pick(BlockPos target) {
        if (busy) return;
        busy = true;
        pos = target;
        status = "…";
        fraction = -1;
        Thread t = new Thread(Uploader::prepare, "chorus-upload");
        t.setDaemon(true);
        t.start();
    }

    private static void prepare() {
        try {
            Path chosen = preset != null ? preset : choose();
            preset = null;
            if (chosen == null) { finish(""); return; }
            List<String> codecs = probe(chosen);
            String ext = chosen.getFileName().toString().toLowerCase(Locale.ROOT);
            ext = ext.contains(".") ? ext.substring(ext.lastIndexOf('.') + 1) : "";
            boolean playable = codecs != null && Set.of("webm", "mkv", "mp4").contains(ext)
                && codecs.stream().allMatch(c -> VIDEO_OK.contains(c) || AUDIO_OK.contains(c) || c.startsWith("-"));
            Path upload = chosen;
            if (!playable) {
                if (codecs == null) { finish(tr("needs_ffmpeg")); return; }
                upload = convert(chosen);
                ext = "webm";
                if (upload == null) return;
            }
            status = tr("hashing");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream s = Files.newInputStream(upload)) {
                byte[] buf = new byte[1 << 20];
                for (int n; (n = s.read(buf)) > 0; ) sha.update(buf, 0, n);
            }
            file = upload;
            size = Files.size(upload);
            name = HexFormat.of().formatHex(sha.digest()) + "." + ext;
            sent = acked = 0;
            in = Files.newInputStream(upload);
            status = tr("uploading");
            fraction = 0;
            ready = true;
        } catch (Exception e) {
            Chorus.LOG.warn("chorus: upload failed", e);
            finish(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static Path choose() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(6);
            for (String f : new String[]{"*.mp4", "*.webm", "*.mkv", "*.mov", "*.avi", "*.m4v"}) filters.put(stack.UTF8(f));
            filters.flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog(tr("pick"), null, filters, "Video", false);
            return path == null ? null : Path.of(path);
        }
    }

    /** Codec names of every stream ("-" for streams that aren't audio/video), or null without ffprobe. */
    private static List<String> probe(Path f) {
        try {
            Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name",
                "-of", "csv=p=0", f.toString()).redirectErrorStream(true).start();
            List<String> out = new BufferedReader(new InputStreamReader(p.getInputStream())).lines()
                .map(l -> l.split(",")).map(c -> c.length == 2 && (c[1].equals("video") || c[1].equals("audio")) ? c[0] : "-" + String.join(",", c))
                .toList();
            return p.waitFor() == 0 ? out : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static Path convert(Path src) throws Exception {
        Files.createDirectories(WORK);
        Path out = WORK.resolve("converting.webm");
        double duration = duration(src);
        boolean nvenc = encoders().contains("av1_nvenc");
        List<String> cmd = new java.util.ArrayList<>(List.of("ffmpeg", "-y", "-v", "error", "-progress", "pipe:1", "-i", src.toString(),
            "-map", "0:v:0", "-map", "0:a:0?", "-vf", "scale='min(1280,iw)':-2"));
        if (nvenc) cmd.addAll(List.of("-c:v", "av1_nvenc", "-cq", "32"));
        else cmd.addAll(List.of("-c:v", "libsvtav1", "-preset", "10", "-crf", "35"));
        cmd.addAll(List.of("-c:a", "libopus", "-b:a", "128k", out.toString()));
        status = tr("converting");
        fraction = 0;
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (l.startsWith("out_time_us=") && duration > 0) {
                    try { fraction = Math.min(1, Long.parseLong(l.substring(12)) / 1e6 / duration); } catch (NumberFormatException ignored) { }
                } else if (!l.contains("=")) Chorus.LOG.info("chorus ffmpeg: {}", l);
            }
        }
        if (p.waitFor() != 0) { finish(tr("convert_failed")); return null; }
        return out;
    }

    private static double duration(Path f) {
        try {
            Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f.toString()).start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Double.parseDouble(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String encoders() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders").start();
            String s = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            // listed isn't enough: nvenc needs an NVIDIA GPU, so try one frame
            if (s.contains("av1_nvenc")) {
                Process t = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=s=256x256:d=0.1",
                    "-frames:v", "1", "-c:v", "av1_nvenc", "-f", "null", "-").start();
                t.getInputStream().readAllBytes();
                if (t.waitFor() != 0) s = s.replace("av1_nvenc", "");
            }
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    /** Sends while fewer than a few acks are outstanding; called every client tick. */
    static void tick() {
        if (!ready) return;
        try {
            if (sent == 0 && acked == 0 && in != null && !begun) {
                begun = true;
                ClientPlayNetworking.send(new Media.Begin(pos, name, size));
                return;
            }
            long window = (long) Media.UPLOAD_PIECE * 32 * 4;
            byte[] buf = new byte[Media.UPLOAD_PIECE];
            while (started && sent < size && sent - acked < window) {
                int n = in.readNBytes(buf, 0, (int) Math.min(buf.length, size - sent));
                ClientPlayNetworking.send(new Media.Piece(n == buf.length ? buf.clone() : java.util.Arrays.copyOf(buf, n)));
                sent += n;
            }
        } catch (IOException e) {
            finish(e.toString());
        }
    }

    private static boolean begun, started;

    static void ack(Media.Ack a) {
        if (!ready) return;
        switch (a.state()) {
            case Media.Ack.PROGRESS -> { started = true; acked = a.received(); fraction = (double) acked / size; }
            case Media.Ack.DONE -> finish(tr("done"));
            default -> finish(tr("failed") + " " + a.message());
        }
    }

    private static void finish(String message) {
        ready = false;
        begun = started = false;
        try { if (in != null) in.close(); } catch (IOException ignored) { }
        in = null;
        if (file != null && file.startsWith(WORK)) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        file = null;
        status = message;
        fraction = -1;
        busy = false;
    }

    static void reset() { if (busy) finish(""); }

    private static String tr(String key) {
        return net.minecraft.network.chat.Component.translatable("chorus.upload." + key).getString();
    }

    private Uploader() {}
}
