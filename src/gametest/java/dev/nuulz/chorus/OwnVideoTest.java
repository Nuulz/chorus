package dev.nuulz.chorus;

import dev.nuulz.chorus.client.Uploader;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

// Sends a local H.264 file (so it gets converted), then plays and seeks it.
public class OwnVideoTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        String video = System.getProperty("chorus.video");
        if (video == null) return;
        Path out = Path.of(System.getProperty("chorus.out", "/tmp/chorus-shots"));
        try (var world = ctx.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var sv = world.getServer();
            sv.runCommand("time set 13000");
            sv.runCommand("setblock 0 -60 0 chorus:screen[facing=south]");
            sv.runCommand("tp @a 0.5 -60 6.5 180 -8");
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> Uploader.send(ScreenTest.POS, Path.of(video)));
            ctx.waitFor(mc -> Uploader.status.contains("enviado") || Uploader.status.contains("sent")
                || Uploader.status.contains("No se") || Uploader.status.contains("Couldn"), 20 * 120);
            ctx.runOnClient(mc -> System.out.println("chorus-test upload: " + Uploader.status));
            ctx.waitTicks(20 * 8);
            ctx.takeScreenshot(TestScreenshotOptions.of("own_1").disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
            sv.runOnServer(s -> ((ScreenBlockEntity) s.overworld().getBlockEntity(ScreenTest.POS)).seek(30_000));
            ctx.waitTicks(20 * 4);
            ctx.takeScreenshot(TestScreenshotOptions.of("own_2_seek30").disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
            // stopped: with -Dchorus.keepMs/sweepTicks small, both copies should go
            sv.runOnServer(s -> ((ScreenBlockEntity) s.overworld().getBlockEntity(ScreenTest.POS)).load(""));
            ctx.waitTicks(20 * 15);
            ctx.runOnClient(mc -> {
                var root = mc.gameDirectory.toPath();
                try (var a = java.nio.file.Files.list(root.resolve("chorus-cache"))) {
                    System.out.println("chorus-test cache after stop: " + a.filter(f -> !java.nio.file.Files.isDirectory(f)).count());
                } catch (Exception e) { System.out.println("chorus-test cache: " + e); }
            });
            sv.runOnServer(s -> {
                try (var a = java.nio.file.Files.list(MediaStore.dir(s))) {
                    System.out.println("chorus-test media after stop: " + a.count());
                } catch (Exception e) { System.out.println("chorus-test media: " + e); }
            });
        }
    }
}
