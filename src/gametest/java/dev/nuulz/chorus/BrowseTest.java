package dev.nuulz.chorus;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

// Browse an empty screen to Telegram Web, share it, and show the settings screen.
public class BrowseTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("chorus.browse") == null) return;
        Path out = Path.of(System.getProperty("chorus.out", "/tmp/chorus-shots"));
        try (var world = ctx.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var sv = world.getServer();
            sv.runCommand("time set 6000");
            sv.runCommand("setblock 0 -60 0 chorus:screen[facing=south]");
            sv.runCommand("tp @a 0.5 -60 6.5 180 -8");
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> mc.gui.setScreen(new dev.nuulz.chorus.client.BrowseScreen((ScreenBlockEntity) mc.level.getBlockEntity(ScreenTest.POS))));
            ctx.waitFor(mc -> dev.nuulz.pane.Pane.running(), 20 * 60);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> ((dev.nuulz.chorus.client.BrowseScreen) mc.gui.screen()).goForTest("web.telegram.org/k/"));
            ctx.waitTicks(20 * 10);
            shot(ctx, out, "browse_telegram");
            ctx.runOnClient(mc -> ((dev.nuulz.chorus.client.BrowseScreen) mc.gui.screen()).shareForTest());
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
            ctx.waitTicks(20 * 3);
            sv.runOnServer(s -> System.out.println("chorus-test shared url: " + ((ScreenBlockEntity) s.overworld().getBlockEntity(ScreenTest.POS)).url));
            shot(ctx, out, "browse_shared_world");
            ctx.runOnClient(mc -> mc.gui.setScreen(new dev.nuulz.chorus.client.SettingsScreen(null)));
            ctx.waitTicks(5);
            shot(ctx, out, "settings");
        }
    }

    private static void shot(ClientGameTestContext ctx, Path out, String name) {
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
    }
}
