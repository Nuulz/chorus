package dev.nuulz.chorus;

import dev.nuulz.chorus.client.CastScreen;
import dev.nuulz.chorus.client.Caster;
import dev.nuulz.chorus.client.ClientConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

// Windows only: ffmpeg.exe, the Windows cast panel, and real casts through gdigrab (whole screen and one
// window) with and without WASAPI sound, each one checked to reach the screen block and to stop cleanly.
// Fails with a message instead of only printing, so a broken step stops the run.
public class WindowsTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) return;
        Path out = Path.of(System.getProperty("chorus.out", Path.of(System.getProperty("java.io.tmpdir"), "chorus-shots").toString()));
        try (var world = ctx.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var sv = world.getServer();
            sv.runCommand("time set 13000");
            sv.runCommand("setblock 0 -60 0 chorus:screen[facing=south]");
            sv.runCommand("tp @a 0.5 -60 6.5 180 -8");
            ctx.waitTicks(20);

            // ffmpeg is downloaded on first launch; on Windows it must be the .exe
            ctx.waitFor(mc -> hasFfmpegExe(mc.gameDirectory.toPath().resolve("chorus-ffmpeg")), 20 * 300);

            // the panel: source picker and refresh button, no "hear from projector" (Linux only)
            ctx.runOnClient(mc -> mc.gui.setScreen(new CastScreen((ScreenBlockEntity) mc.level.getBlockEntity(ScreenTest.POS))));
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> {
                var labels = mc.gui.screen().children().stream()
                    .filter(w -> w instanceof net.minecraft.client.gui.components.AbstractWidget)
                    .map(w -> ((net.minecraft.client.gui.components.AbstractWidget) w).getMessage().getString()).toList();
                System.out.println("chorus-test windows panel: " + labels);
                check(labels.stream().anyMatch(l -> l.contains(Caster.tr("source_desktop"))), "no source picker in " + labels);
                check(labels.stream().noneMatch(l -> l.contains(net.minecraft.network.chat.Component.translatable("chorus.cast.hear_projector").getString())),
                    "'hear from projector' shown on Windows");
            });
            shot(ctx, out, "win_panel");
            ctx.runOnClient(mc -> mc.gui.setScreen(null));

            cast(ctx, sv, out, "win_desktop_silent", "", "desktop");
            cast(ctx, sv, out, "win_desktop_sound", "*", "desktop");

            // one window by title: the game's own window is always there
            List<String> titles = ctx.computeOnClient(mc -> {
                try { return Caster.windowTitlesForTest(); } catch (InterruptedException e) { return List.of(); }
            });
            System.out.println("chorus-test windows titles: " + titles);
            String game = titles.stream().filter(t -> t.contains("Minecraft")).findFirst().orElse(null);
            check(game != null, "the game's window isn't in the window list " + titles);
            cast(ctx, sv, out, "win_window", "", "window:" + game);
        }
    }

    private static void cast(ClientGameTestContext ctx, TestServerContext sv, Path out, String name, String app, String source) {
        ctx.runOnClient(mc -> {
            ClientConfig.set("cast_quality", System.getProperty("chorus.quality", "720"));
            Caster.startForTest(ScreenTest.POS, app, source);
        });
        ctx.waitTicks(20 * 12);
        String status = ctx.computeOnClient(mc -> Caster.status);
        String url = sv.computeOnServer(s -> ((ScreenBlockEntity) s.overworld().getBlockEntity(ScreenTest.POS)).url);
        System.out.println("chorus-test " + name + " status: " + status + " url: " + url);
        shot(ctx, out, name);
        check(ctx.computeOnClient(mc -> Caster.runningForTest()), name + ": cast stopped: " + status);
        check(!status.contains(Caster.tr("no_frames")), name + ": no frames: " + status);
        check(url != null && !url.isEmpty(), name + ": the screen didn't get the cast");
        ctx.runOnClient(mc -> Caster.stopForTest());
        ctx.waitTicks(20 * 2);
        check(!ctx.computeOnClient(mc -> Caster.runningForTest()), name + ": didn't stop");
        String after = sv.computeOnServer(s -> ((ScreenBlockEntity) s.overworld().getBlockEntity(ScreenTest.POS)).url);
        check(after == null || after.isEmpty(), name + ": the screen kept the cast after stopping: " + after);
    }

    private static boolean hasFfmpegExe(Path dir) {
        try (var all = Files.walk(dir)) {
            return all.anyMatch(f -> f.getFileName().toString().equals("ffmpeg.exe"));
        } catch (Exception e) {
            return false;
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("chorus-test windows: " + what);
    }

    private static void shot(ClientGameTestContext ctx, Path out, String name) {
        ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withSize(1280, 720).withDestinationDir(out));
    }
}
