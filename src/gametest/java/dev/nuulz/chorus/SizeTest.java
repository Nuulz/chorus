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
            sv.runCommand("tp @a 0.5 -60 13.5 180 -12");
            ctx.waitTicks(20);
            sv.runOnServer(s -> {
                var be = (ScreenBlockEntity) s.overworld().getBlockEntity(ScreenTest.POS);
                be.setSize(6);
                be.load("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
            });
            ctx.waitFor(mc -> dev.nuulz.pane.Pane.running(), 20 * 60);
            ctx.waitTicks(20 * 25);
            ctx.runOnClient(mc -> {
                System.out.println("chorus-test apps: " + dev.nuulz.chorus.client.AudioRoute.apps());
                try {
                    Process p = new ProcessBuilder("pactl", "-f", "json", "list", "sink-inputs").start();
                    String all = new String(p.getInputStream().readAllBytes());
                    for (String key : new String[]{"application.name", "application.process.binary", "application.process.id"})
                        for (var m = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(key) + "\":\"([^\"]*)\"").matcher(all); m.find(); )
                            System.out.println("chorus-test stream " + key + "=" + m.group(1));
                } catch (Exception e) { System.out.println("chorus-test pactl " + e); }
                System.out.println("chorus-test my pid " + ProcessHandle.current().pid());
            });
            ctx.takeScreenshot(TestScreenshotOptions.of("size_6").disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
            sv.runCommand("tp @a 8.5 -59 8.5 135 5");
            ctx.waitTicks(20);
            ctx.takeScreenshot(TestScreenshotOptions.of("size_6_side").disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
            sv.runCommand("tp @a 0.5 -59 -2.5 0 35");
            ctx.waitTicks(20);
            ctx.takeScreenshot(TestScreenshotOptions.of("block_close").disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
        }
    }
}
