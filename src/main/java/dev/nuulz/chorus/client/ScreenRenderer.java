package dev.nuulz.chorus.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.nuulz.chorus.ScreenBlock;
import dev.nuulz.chorus.ScreenBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Draws the page as a 16:9 panel standing on the block, fullbright, facing the way it was placed. */
final class ScreenRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenRenderer.State> {
    static final class State extends BlockEntityRenderState {
        Identifier texture;
        Direction facing = Direction.NORTH;
        float width = 3f;
    }

    @Override public State createRenderState() { return new State(); }

    @Override
    public void extractRenderState(ScreenBlockEntity be, State s, float partial, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(be, s, partial, camera, crumbling);
        s.facing = be.getBlockState().getValue(ScreenBlock.FACING);
        s.width = be.size;
        s.texture = null;
        Screens.pump();
        Screens.Live l = Screens.get(be);
        if (l == null || l.view == null) return;
        l.texture.update(l.view);
        if (l.texture.ready()) s.texture = l.texture.id;
    }

    /**
     * The picture floats in front of and above the projector, and a cone of light goes from the lens on
     * top of the block to its four corners. The cone stays behind the picture, so it never tints it.
     */
    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        if (s.texture == null) return;
        pose.pushPose();
        pose.translate(0.5f, 0f, 0.5f);
        pose.mulPose(Axis.YP.rotationDegrees(-s.facing.toYRot()));
        float w = s.width / 2, h = s.width * 9 / 16;
        float z = 0.6f + s.width * 0.12f, y0 = 1.25f + s.width * 0.08f, y1 = y0 + h;
        float lens = 0.17f, ly = 1.001f;
        int light = 0xF000F0;
        out.submitCustomGeometry(pose, RenderTypes.text(s.texture), (p, v) -> {
            v.addVertex(p, -w, y0, z).setColor(-1).setUv(0, 1).setLight(light);
            v.addVertex(p, w, y0, z).setColor(-1).setUv(1, 1).setLight(light);
            v.addVertex(p, w, y1, z).setColor(-1).setUv(1, 0).setLight(light);
            v.addVertex(p, -w, y1, z).setColor(-1).setUv(0, 0).setLight(light);
        });
        int near = 0x55_46DCFF, far = 0x0A_46DCFF, edge = 0x90_78F0FF;
        out.submitCustomGeometry(pose, RenderTypes.lightning(), (p, v) -> {
            // cone: lens corners → screen corners (both windings, so it shows from any side)
            float[][] lensC = {{-lens, lens}, {lens, lens}, {lens, -lens}, {-lens, -lens}};
            float[][] scr = {{-w, y0}, {w, y0}, {w, y1}, {-w, y1}};
            int[][] faces = {{0, 1, 1, 0}, {1, 2, 2, 1}, {2, 3, 3, 2}, {3, 0, 0, 3}};
            for (int[] f : faces) {
                float[] la = lensC[f[0]], lb = lensC[f[1]], sb = scr[f[2] == 1 ? 1 : f[2]], sa = scr[f[3]];
                quad(v, p, la[0], ly, la[1], lb[0], ly, lb[1], sb[0], sb[1], z, sa[0], sa[1], z, near, near, far, far);
            }
            // glowing frame
            float t = Math.max(0.03f, s.width * 0.012f), fz = z - 0.002f;
            quad(v, p, -w - t, y0 - t, fz, w + t, y0 - t, fz, w + t, y0, fz, -w - t, y0, fz, edge, edge, edge, edge);
            quad(v, p, -w - t, y1, fz, w + t, y1, fz, w + t, y1 + t, fz, -w - t, y1 + t, fz, edge, edge, edge, edge);
            quad(v, p, -w - t, y0, fz, -w, y0, fz, -w, y1, fz, -w - t, y1, fz, edge, edge, edge, edge);
            quad(v, p, w, y0, fz, w + t, y0, fz, w + t, y1, fz, w, y1, fz, edge, edge, edge, edge);
        });
        pose.popPose();
    }

    private static void quad(com.mojang.blaze3d.vertex.VertexConsumer v, PoseStack.Pose p,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             int ca, int cb, int cc, int cd) {
        v.addVertex(p, ax, ay, az).setColor(ca); v.addVertex(p, bx, by, bz).setColor(cb);
        v.addVertex(p, cx, cy, cz).setColor(cc); v.addVertex(p, dx, dy, dz).setColor(cd);
        v.addVertex(p, dx, dy, dz).setColor(cd); v.addVertex(p, cx, cy, cz).setColor(cc);
        v.addVertex(p, bx, by, bz).setColor(cb); v.addVertex(p, ax, ay, az).setColor(ca);
    }

    @Override public boolean shouldRenderOffScreen() { return true; }
    @Override public int getViewDistance() { return 192; }
}
