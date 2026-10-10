package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Props;
import dev.nuulz.chorus.UrlPolicy;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** This player's limits: what their machine renders and which sites their Chromium will open. */
public final class ClientConfig {
    static int maxActive, width, height, radius, volume;
    static boolean anyLink;
    static UrlPolicy urls;
    private static Props p;

    static void load() {
        Properties d = new Properties();
        d.setProperty("max_active_screens", "2");
        d.setProperty("width", "854");
        d.setProperty("height", "480");
        d.setProperty("radius", "32");
        d.setProperty("volume_percent", "100");
        d.setProperty("allowed_hosts", UrlPolicy.DEFAULT_HOSTS);
        d.setProperty("allow_any_link", "false");
        d.setProperty("cast_restore_token", "");
        d.setProperty("cast_quality", "720");
        d.setProperty("cast_hear_projector", "false");
        d.setProperty("cast_audio_delay_ms", "0");
        p = new Props(FabricLoader.getInstance().getConfigDir().resolve("chorus-client.properties"), d);
        maxActive = p.num("max_active_screens", 0, 4);
        width = p.num("width", 256, 1280);
        height = p.num("height", 144, 720);
        radius = p.num("radius", 8, 64);
        volume = p.num("volume_percent", 0, 100);
        anyLink = p.flag("allow_any_link");
        urls = new UrlPolicy(p.str("allowed_hosts"), anyLink);
    }

    public static void set(String key, Object value) {
        p.set(key, value);
        load();
    }

    static String castToken() { return p.str("cast_restore_token"); }
    static String castQuality() { return p.str("cast_quality"); }
    static boolean hearProjector() { return p.flag("cast_hear_projector"); }
    static int castAudioDelay() { return Math.clamp(p.num("cast_audio_delay_ms", 0, 1000), 0, 1000); }

    private ClientConfig() {}
}
