package dev.nuulz.chorus;

import dev.nuulz.chorus.client.Caster;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

// Live cast of a generated picture through the server and back into the screen.
public class CastTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("chorus.cast") == null) return;
        Path out = Path.of(System.getProperty("chorus.out", "/tmp/chorus-shots"));
        try (var world = ctx.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var sv = world.getServer();
            sv.runCommand("time set 13000");
            sv.runCommand("setblock 0 -60 0 chorus:screen[facing=south]");
            sv.runCommand("tp @a 0.5 -60 6.5 180 -8");
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> {
                dev.nuulz.chorus.client.ClientConfig.set("cast_quality", System.getProperty("chorus.quality", "720"));
                if (Boolean.getBoolean("chorus.gsttest")) Caster.startGstTest(ScreenTest.POS);
                else Caster.startTest(ScreenTest.POS);
            });
            ctx.waitTicks(20 * 15);
            ctx.runOnClient(mc -> System.out.println("chorus-test cast status: " + Caster.status));
            sv.runOnServer(s -> System.out.println("chorus-test cast url: " + ((ScreenBlockEntity) s.overworld().getBlockEntity(ScreenTest.POS)).url));
            ctx.takeScreenshot(TestScreenshotOptions.of("cast_1").disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
            ctx.waitTicks(20 * 4);
            ctx.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
            ctx.waitTicks(10);
            ctx.takeScreenshot(TestScreenshotOptions.of("cast_pause_menu").disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
            ctx.takeScreenshot(TestScreenshotOptions.of("cast_2_4s").disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
            ctx.runOnClient(mc -> dev.nuulz.chorus.client.Caster.stopForTest());
            ctx.waitTicks(20 * 2);
            sv.runOnServer(s -> System.out.println("chorus-test after stop url: [" + ((ScreenBlockEntity) s.overworld().getBlockEntity(ScreenTest.POS)).url + "]"));
        }
    }
}
