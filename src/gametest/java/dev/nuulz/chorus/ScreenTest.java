package dev.nuulz.chorus;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;

// Places a screen, plays a video, pauses and seeks; screenshots each step to check by eye.
public class ScreenTest implements FabricClientGameTest {
    static final BlockPos POS = new BlockPos(0, -60, 0);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Path out = Path.of(System.getProperty("chorus.out", "/tmp/chorus-shots"));
        String url = System.getProperty("chorus.url", "https://www.youtube.com/watch?v=aqz-KE-bpKQ");
        try (var world = ctx.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var sv = world.getServer();
            sv.runCommand("gamemode creative @a");
            sv.runCommand("time set 13000");
            sv.runCommand("setblock 0 -60 0 chorus:screen[facing=south]");
            sv.runCommand("tp @a 0.5 -60 6.5 180 -8");
            ctx.waitTicks(20);
            server(ctx, sv, s -> s.load(url));
            ctx.waitFor(mc -> dev.nuulz.pane.Pane.running(), 20 * 60);
            ctx.waitTicks(20 * 12);
            shot(ctx, out, "1_playing");
            ctx.waitTicks(20 * 5);
            shot(ctx, out, "2_playing_5s_later");
            server(ctx, sv, s -> s.play(false));
            ctx.waitTicks(20 * 3);
            shot(ctx, out, "3_paused");
            ctx.waitTicks(20 * 3);
            shot(ctx, out, "4_paused_still");
            server(ctx, sv, s -> s.seek(300_000));
            server(ctx, sv, s -> s.play(true));
            ctx.waitTicks(20 * 6);
            shot(ctx, out, "5_seek_5min");
            ctx.runOnClient(mc -> ScreenBlock.openControls.accept((ScreenBlockEntity) mc.level.getBlockEntity(POS)));
            ctx.waitTicks(10);
            shot(ctx, out, "6_controls");
        }
    }

    private static void server(ClientGameTestContext ctx, net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext sv,
                               java.util.function.Consumer<ScreenBlockEntity> f) {
        sv.runOnServer(s -> f.accept((ScreenBlockEntity) s.overworld().getBlockEntity(POS)));
        ctx.waitTick();
    }

    private static void shot(ClientGameTestContext ctx, Path out, String name) {
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
    }
}
