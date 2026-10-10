package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Chorus;
import dev.nuulz.chorus.Props;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Keeps the game's frame rate where this computer can hold it. It sets a budget of Chromium paints
 * per second for all live screens; every few seconds it looks at the real FPS and the time spent
 * converting pictures, lowers the budget when the game falls short and raises it when there's room.
 * The budget that held is saved per machine, so the next launch starts from what this PC proved.
 */
public final class Tuner {
    static final int MIN = 12, MAX = 120, STEP = 6, PERIOD = 40;
    private static final String KEY = "budget." + Integer.toHexString(machine().hashCode());
    private static final Props store = new Props(FabricLoader.getInstance().getConfigDir().resolve("chorus-tuning.properties"), new Properties());
    private static int budget = -1;
    private static int ticks;
    private static long lastConvertNs;
    private static long lastNs = System.nanoTime();

    /** Paints per second to spend on all live screens. */
    public static int budget() {
        if (budget < 0) budget = Math.clamp(store.num(KEY, 0, MAX) == 0 ? 60 : store.num(KEY, MIN, MAX), MIN, MAX);
        return budget;
    }

    static void tick(Minecraft mc) {
        if (++ticks % PERIOD != 0) return;
        long now = System.nanoTime();
        double seconds = (now - lastNs) / 1e9;
        lastNs = now;
        long convertNs = Stats.convertNs();
        double convertShare = (convertNs - lastConvertNs) / 1e9 / Math.max(seconds, 1e-3);
        lastConvertNs = convertNs;
        int fps = mc.getFps();
        if (fps <= 0) return; // no reading yet (loading a world)
        int target = Math.min(mc.getFramerateLimitTracker().getFramerateLimit(), 60) * 9 / 10;
        int before = budget();
        if (fps < target || convertShare > 0.35) budget = Math.max(MIN, budget * 4 / 5);
        else if (convertShare < 0.2) budget = Math.min(MAX, budget + STEP);
        if (budget != before) {
            Chorus.LOG.info("chorus: paint budget {} → {} (fps {}, target {}, pictures {}% of a second)",
                before, budget, fps, target, Math.round(convertShare * 100));
            store.set(KEY, budget);
        }
    }

    private static String machine() {
        return System.getProperty("os.arch") + "|" + Runtime.getRuntime().availableProcessors() + "|"
            + Runtime.getRuntime().maxMemory() / (1 << 28) + "|" + System.getProperty("os.name");
    }

    private Tuner() {}
}
