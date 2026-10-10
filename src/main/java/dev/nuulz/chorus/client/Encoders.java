package dev.nuulz.chorus.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/** AV1 encoders, hardware first; each is tried on a test frame before it's trusted. */
final class Encoders {
    /** [before]: ffmpeg options ahead of the input; [hw]: filter appended after scaling. [live]: for a given kbit/s. */
    record Encoder(String label, List<String> before, String hw, List<String> file, IntFunction<List<String>> live) {
        String filter(String scale) { return hw.isEmpty() ? scale : scale + "," + hw; }
    }

    static final Encoder CPU = new Encoder("CPU", List.of(), "",
        List.of("-c:v", "libsvtav1", "-preset", "10", "-crf", "35"),
        k -> List.of("-c:v", "libsvtav1", "-preset", "11", "-b:v", k + "k"));
    private static final List<Encoder> GPUS = List.of(
        new Encoder("NVIDIA", List.of(), "",
            List.of("-c:v", "av1_nvenc", "-preset", "p5", "-cq", "32"),
            k -> List.of("-c:v", "av1_nvenc", "-preset", "p4", "-tune", "ll", "-rc", "cbr", "-b:v", k + "k")),
        new Encoder("Intel", List.of(), "",
            List.of("-c:v", "av1_qsv", "-global_quality", "30"),
            k -> List.of("-c:v", "av1_qsv", "-b:v", k + "k")),
        new Encoder("AMD", List.of(), "",
            List.of("-c:v", "av1_amf", "-quality", "balanced", "-rc", "cqp", "-qp_i", "30", "-qp_p", "32"),
            k -> List.of("-c:v", "av1_amf", "-usage", "lowlatency", "-rc", "cbr", "-b:v", k + "k")),
        new Encoder("VAAPI", List.of("-vaapi_device", "/dev/dri/renderD128"), "format=nv12,hwupload",
            List.of("-c:v", "av1_vaapi", "-b:v", "4M"),
            k -> List.of("-c:v", "av1_vaapi", "-b:v", k + "k")));

    private static final Set<String> broken = new HashSet<>();
    private static Encoder chosen;

    static synchronized void markBroken(Encoder e) { broken.add(e.label()); chosen = null; }

    static synchronized Encoder best() {
        if (chosen != null) return chosen;
        chosen = CPU;
        for (Encoder e : GPUS) {
            if (broken.contains(e.label())) continue;
            List<String> cmd = new ArrayList<>(List.of(FFmpeg.ffmpeg(), "-v", "error"));
            cmd.addAll(e.before());
            cmd.addAll(List.of("-f", "lavfi", "-i", "testsrc2=s=640x360:d=0.2", "-vf", e.filter("scale=640:360")));
            cmd.addAll(e.file());
            cmd.addAll(List.of("-f", "null", "-"));
            try {
                Process t = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                t.getInputStream().readAllBytes();
                if (t.waitFor() == 0) { chosen = e; break; }
            } catch (Exception ignored) { }
        }
        return chosen;
    }

    private Encoders() {}
}
