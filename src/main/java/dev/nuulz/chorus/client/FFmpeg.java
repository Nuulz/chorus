package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Chorus;
import dev.nuulz.pane.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * ffmpeg/ffprobe for converting videos, downloaded once in the background when the game starts,
 * from our copy of unmodified static builds. A system ffmpeg is the fallback while that isn't ready.
 */
final class FFmpeg {
    private static final String MIRROR = System.getProperty("chorus.ffmpeg.mirror", "https://chromium.nuulm.com/ffmpeg/8.1");
    private static final Path HOME = FabricLoader.getInstance().getGameDir().resolve("chorus-ffmpeg");

    static volatile double fraction = -1;
    private static volatile Path bin;
    private static volatile boolean started;

    static void install() {
        if (started) return;
        started = true;
        Thread t = new Thread(FFmpeg::run, "chorus-ffmpeg-install");
        t.setDaemon(true);
        t.start();
    }

    /** Still downloading (so the uploader can say so instead of failing). */
    static boolean installing() { return started && bin == null && fraction >= 0; }

    static String ffmpeg() { return tool("ffmpeg"); }
    static String ffprobe() { return tool("ffprobe"); }

    private static String tool(String name) {
        Path b = bin;
        if (b == null) return name; // whatever is on PATH
        return b.resolve(Platform.current().isWindows() ? name + ".exe" : name).toString();
    }

    private static void run() {
        Platform p = Platform.current();
        if (p == null) return;
        Path dir = HOME.resolve(p.id());
        Path stamp = HOME.resolve(p.id() + ".sha256");
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
            String url = MIRROR + "/" + p.id() + ".zip";
            String expected = http.send(HttpRequest.newBuilder(URI.create(url + ".sha256")).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString()).body().trim().split("\\s+")[0];
            if (!expected.matches("[0-9a-f]{64}")) throw new IOException("no checksum at " + url);
            if (Files.isDirectory(dir) && Files.exists(stamp) && Files.readString(stamp).trim().equals(expected)) {
                bin = dir;
                return;
            }
            fraction = 0;
            Files.createDirectories(HOME);
            Path zip = HOME.resolve(p.id() + ".zip.part");
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
            long total = r.headers().firstValueAsLong("content-length").orElse(-1);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = r.body(); var out = Files.newOutputStream(zip)) {
                byte[] buf = new byte[1 << 16];
                long got = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    got += n;
                    if (total > 0) fraction = (double) got / total;
                }
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equals(expected)) throw new IOException("ffmpeg download is corrupt");
            unzip(zip, dir);
            Files.delete(zip);
            Files.writeString(stamp, expected);
            bin = dir;
            Chorus.LOG.info("chorus: ffmpeg ready in {}", dir);
        } catch (Exception e) {
            Chorus.LOG.warn("chorus: couldn't get ffmpeg ({}); using the system one if there is one", e.toString());
        } finally {
            fraction = -1;
        }
    }

    private static void unzip(Path zip, Path dir) throws IOException {
        Path tmp = dir.resolveSibling(dir.getFileName() + ".new");
        deleteTree(tmp);
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String name = e.getName();
                int slash = name.indexOf('/');
                if (e.isDirectory() || slash < 0) continue;
                Path out = tmp.resolve(name.substring(slash + 1)).normalize();
                if (!out.startsWith(tmp)) throw new IOException("bad zip entry " + name);
                Files.createDirectories(out.getParent());
                Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                out.toFile().setExecutable(true);
            }
        }
        deleteTree(dir);
        Files.move(tmp, dir);
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        List<Path> all = new ArrayList<>();
        try (var s = Files.walk(p)) { s.forEach(all::add); }
        for (int i = all.size() - 1; i >= 0; i--) Files.delete(all.get(i));
    }

    private FFmpeg() {}
}
