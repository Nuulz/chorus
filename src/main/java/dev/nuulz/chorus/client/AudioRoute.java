package dev.nuulz.chorus.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nuulz.chorus.Chorus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which sound goes into a cast (Linux, PulseAudio/PipeWire through pactl). For one app: a virtual
 * "Chorus" output gets that app's streams (new ones too, checked every second) and a loopback keeps
 * them audible on your speakers; the cast records the virtual output, so your game, voice chat and
 * notifications stay out of it.
 */
final class AudioRoute implements AutoCloseable {
    static final String NONE = "", ALL = "*";
    private static final String SINK = "chorus_cast";

    private final String app;
    private final List<String> modules = new ArrayList<>();
    final String source;

    /** Apps playing sound right now: binary name → what to show. */
    static Map<String, String> apps() {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonObject in : sinkInputs()) {
            JsonObject p = in.getAsJsonObject("properties");
            String id = key(p), name = str(p, "application.name");
            if (id.isEmpty() || id.equals("java") || str(p, "application.process.id").equals(String.valueOf(ProcessHandle.current().pid())))
                continue; // Minecraft itself
            out.putIfAbsent(id, name.isEmpty() ? id : name);
        }
        return out;
    }

    AudioRoute(String app) throws Exception {
        this.app = app;
        if (app.equals(NONE)) { source = null; return; }
        String speakers = run("pactl", "get-default-sink").trim();
        if (app.equals(ALL)) { source = speakers + ".monitor"; return; }
        modules.add(run("pactl", "load-module", "module-null-sink", "sink_name=" + SINK,
            "sink_properties=device.description=Chorus").trim());
        modules.add(run("pactl", "load-module", "module-loopback", "source=" + SINK + ".monitor",
            "sink=" + speakers, "latency_msec=40").trim());
        source = SINK + ".monitor";
        follow();
    }

    /** Moves the chosen app's streams into the virtual output; call it now and then. */
    void follow() {
        if (modules.isEmpty()) return;
        for (JsonObject in : sinkInputs()) {
            JsonObject p = in.getAsJsonObject("properties");
            if (!app.equals(key(p))) continue;
            if (str(in, "sink").equals(sinkIndex())) continue;
            try { run("pactl", "move-sink-input", in.get("index").getAsString(), SINK); } catch (Exception ignored) { }
        }
    }

    private String sinkIndex;

    private String sinkIndex() {
        if (sinkIndex != null) return sinkIndex;
        try {
            for (JsonElement e : JsonParser.parseString(run("pactl", "-f", "json", "list", "sinks")).getAsJsonArray())
                if (str(e.getAsJsonObject(), "name").equals(SINK)) return sinkIndex = str(e.getAsJsonObject(), "index");
        } catch (Exception ignored) { }
        return "";
    }

    @Override
    public void close() {
        for (int i = modules.size() - 1; i >= 0; i--) {
            try { run("pactl", "unload-module", modules.get(i)); } catch (Exception e) { Chorus.LOG.warn("chorus: {}", e.toString()); }
        }
        modules.clear();
    }

    private static List<JsonObject> sinkInputs() {
        List<JsonObject> out = new ArrayList<>();
        try {
            JsonArray a = JsonParser.parseString(run("pactl", "-f", "json", "list", "sink-inputs")).getAsJsonArray();
            for (JsonElement e : a) out.add(e.getAsJsonObject());
        } catch (Exception ignored) { }
        return out;
    }

    /** An app's identity: its executable, or its name when the stream doesn't say. */
    private static String key(JsonObject p) {
        String bin = str(p, "application.process.binary");
        return bin.isEmpty() ? str(p, "application.name") : bin;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new Exception(String.join(" ", cmd) + ": " + out.trim());
        return out;
    }
}
