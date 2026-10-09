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
    static final float WIDTH = 3f, HEIGHT = WIDTH * 9 / 16;

    static final class State extends BlockEntityRenderState {
        Identifier texture;
        Direction facing = Direction.NORTH;
    }

    @Override public State createRenderState() { return new State(); }

    @Override
    public void extractRenderState(ScreenBlockEntity be, State s, float partial, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(be, s, partial, camera, crumbling);
        s.facing = be.getBlockState().getValue(ScreenBlock.FACING);
        s.texture = null;
        Screens.pump();
        Screens.Live l = Screens.get(be);
        if (l == null || l.view == null) return;
        l.texture.update(l.view);
        if (l.texture.ready()) s.texture = l.texture.id;
    }

    @Override
    public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        if (s.texture == null) return;
        pose.pushPose();
        pose.translate(0.5f, 0f, 0.5f);
        pose.mulPose(Axis.YP.rotationDegrees(-s.facing.toYRot()));
        float x = WIDTH / 2, y0 = 1.02f, y1 = y0 + HEIGHT;
        out.submitCustomGeometry(pose, RenderTypes.text(s.texture), (p, v) -> {
            int light = 0xF000F0;
            v.addVertex(p, -x, y0, 0).setColor(-1).setUv(0, 1).setLight(light);
            v.addVertex(p, x, y0, 0).setColor(-1).setUv(1, 1).setLight(light);
            v.addVertex(p, x, y1, 0).setColor(-1).setUv(1, 0).setLight(light);
            v.addVertex(p, -x, y1, 0).setColor(-1).setUv(0, 0).setLight(light);
        });
        pose.popPose();
    }

    @Override public boolean shouldRenderOffScreen() { return true; }
    @Override public int getViewDistance() { return 96; }
}
