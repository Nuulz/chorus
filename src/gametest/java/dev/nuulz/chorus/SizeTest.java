package dev.nuulz.chorus;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

// A screen 6 blocks wide playing a video; the resolution stays the same as for 3 blocks.
public class SizeTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("chorus.size") == null) return;
        Path out = Path.of(System.getProperty("chorus.out", "/tmp/chorus-shots"));
        try (var world = ctx.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var sv = world.getServer();
            sv.runCommand("time set 13000");
            sv.runCommand("setblock 0 -60 0 chorus:screen[facing=south]");
            sv.runCommand("tp @a 2.5 -60 10.5 180 -6");
            ctx.waitTicks(20);
            sv.runOnServer(s -> {
                var be = (ScreenBlockEntity) s.overworld().getBlockEntity(ScreenTest.POS);
                be.setSize(6);
                be.load("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
            });
            ctx.waitFor(mc -> dev.nuulz.pane.Pane.running(), 20 * 60);
            ctx.waitTicks(20 * 40);
            ctx.takeScreenshot(TestScreenshotOptions.of("size_6").disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
        }
    }
}
