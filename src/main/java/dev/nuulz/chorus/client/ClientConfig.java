package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Props;
import dev.nuulz.chorus.UrlPolicy;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** This player's limits: what their machine renders and which sites their Chromium will open. */
final class ClientConfig {
    static int maxActive, width, height, radius, volume;
    static UrlPolicy urls;

    static void load() {
        Properties d = new Properties();
        d.setProperty("max_active_screens", "2");
        d.setProperty("width", "854");
        d.setProperty("height", "480");
        d.setProperty("radius", "32");
        d.setProperty("volume_percent", "100");
        d.setProperty("allowed_hosts", UrlPolicy.DEFAULT_HOSTS);
        Props p = new Props(FabricLoader.getInstance().getConfigDir().resolve("chorus-client.properties"), d);
        maxActive = p.num("max_active_screens", 0, 4);
        width = p.num("width", 256, 1280);
        height = p.num("height", 144, 720);
        radius = p.num("radius", 8, 64);
        volume = p.num("volume_percent", 0, 100);
        urls = new UrlPolicy(p.str("allowed_hosts"));
    }

    private ClientConfig() {}
}
