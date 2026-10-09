package dev.nuulz.chorus.client;

import dev.nuulz.chorus.Chorus;
import dev.nuulz.pane.Pane;
import dev.nuulz.pane.PaneConfig;
import net.minecraft.client.Minecraft;

/**
 * Chromium through pane. pane is one per game, so if another mod (jukz) configured it first, Chorus
 * just uses that one instead of downloading a second copy.
 */
final class Browser {
    private static volatile boolean preparing;
    static volatile String step = "";

    static boolean ready() { return Pane.running(); }
    static boolean failed() { return Pane.state() == Pane.State.FAILED; }

    static void prepare() {
        if (preparing || Pane.state() != Pane.State.NEW) return;
        preparing = true;
        Minecraft mc = Minecraft.getInstance();
        try {
            Pane.configure(PaneConfig.at(mc.gameDirectory.toPath().resolve("chorus-browser"))
                .userAgentProduct("chorus")
                .glfwWindow(() -> mc.getWindow().handle()));
        } catch (IllegalStateException alreadyConfigured) {
            // someone else set pane up; their install/start will bring it up
        }
        Thread t = new Thread(() -> {
            if (!Pane.install((s, f) -> step = s)) {
                Chorus.LOG.warn("chorus: browser unavailable ({})", Pane.failure());
                return;
            }
            mc.execute(() -> { if (!Pane.start()) Chorus.LOG.warn("chorus: browser didn't start ({})", Pane.failure()); });
        }, "chorus-browser-install");
        t.setDaemon(true);
        t.start();
    }

    private Browser() {}
}
