package dev.nuulz.chorus;

import dev.nuulz.chorus.client.ClientConfig;
import dev.nuulz.chorus.client.Stats;
import dev.nuulz.chorus.client.Uploader;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

// FPS and time spent in screens with 0, 1, 2 and 4 live screens playing the same video.
public class BenchTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        String video = System.getProperty("chorus.bench");
        if (video == null) return;
        try (var world = ctx.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var sv = world.getServer();
            sv.runCommand("gamemode creative @a");
            sv.runCommand("time set 6000");
            sv.runCommand("tp @a 3 -60 12.5 180 -5");
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> {
                mc.options.framerateLimit().set(120);
                mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            });
            ctx.runOnClient(mc -> ClientConfig.set("max_active_screens", 4));
            sv.runCommand("setblock 0 -60 0 chorus:screen[facing=south]");
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> Uploader.send(new BlockPos(0, -60, 0), Path.of(video)));
            ctx.waitFor(mc -> Uploader.status.contains("enviado") || Uploader.status.contains("sent") || Uploader.status.contains("Couldn"), 20 * 180);
            ctx.waitTicks(20 * 3);
            ctx.runOnClient(mc -> System.out.println("bench-upload status=" + Uploader.status + " name=" + Uploader.lastName()));
            String media = "chorus-media:" + Uploader.lastName();
            for (int count : new int[]{0, 1, 2, 4}) {
                sv.runOnServer(s -> {
                    for (int i = 0; i < 4; i++) {
                        BlockPos p = new BlockPos(i * 2, -60, 0);
                        s.overworld().setBlock(p, i < count ? Chorus.SCREEN.defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH)
                            : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    }
                });
                ctx.waitTicks(20);
                for (int i = 0; i < count; i++) {
                    BlockPos p = new BlockPos(i * 2, -60, 0);
                    sv.runOnServer(s -> ((ScreenBlockEntity) s.overworld().getBlockEntity(p)).load(media));
                }
                ctx.waitTicks(20 * 8);
                long c0 = Stats.converts(), t0 = Stats.convertNs(), s0 = Stats.syncs(), y0 = Stats.syncNs(), u0 = Stats.uploadNs();
                int[] fps = new int[10];
                for (int k = 0; k < fps.length; k++) {
                    ctx.waitTicks(20);
                    final int idx = k;
                    ctx.runOnClient(mc -> fps[idx] = mc.getFps());
                }
                ctx.runOnClient(mc -> System.out.println("bench-debug " + dev.nuulz.chorus.client.Screens.debug() + " fpsLimit=" + mc.getFramerateLimitTracker().getFramerateLimit()));
                double avg = 0;
                for (int v : fps) avg += v;
                avg /= fps.length;
                long conv = Stats.converts() - c0, convMs = (Stats.convertNs() - t0) / 1_000_000, syncs = Stats.syncs() - s0;
                long syncMs = (Stats.syncNs() - y0) / 1_000_000;
                long upMs = (Stats.uploadNs() - u0) / 1_000_000;
                System.out.printf("bench screens=%d fps=%.1f uploads_per_s=%.1f convert_ms_per_s=%.1f upload_ms_per_s=%.1f sync_ms_per_s=%.1f%n",
                    count, avg, conv / 10.0, convMs / 10.0, upMs / 10.0, syncMs / 10.0);
            }
        }
    }
}
