package omtteam.openmodularturrets.client.render;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side queue for the legacy 1.12 translucent ray beams (laser / rail
 * gun).  Beams are drawn as lit lines that persist for their full lifetime so
 * a fast volley leaves a visible streak, mirroring the OMLib Ray renderer
 * instead of the previous one-frame particle dots.
 */
public final class BeamRenderCache {
    private static final List<Beam> BEAMS = new ArrayList<>();

    private BeamRenderCache() {
    }

    public static void add(Vec3 start, Vec3 end, int color, float alpha,
            int durationTicks) {
        long gameTime = Minecraft.getInstance().level == null
                ? 0L : Minecraft.getInstance().level.getGameTime();
        BEAMS.add(new Beam(start, end, argb(color, alpha), gameTime + durationTicks));
    }

    public static void render(PoseStack poseStack) {
        if (BEAMS.isEmpty()) {
            return;
        }
        long gameTime = Minecraft.getInstance().level == null
                ? 0L : Minecraft.getInstance().level.getGameTime();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera()
                .getPosition();
        MultiBufferSource.BufferSource buffers =
                Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.LINES);
        var pose = poseStack.last();
        Iterator<Beam> iterator = BEAMS.iterator();
        while (iterator.hasNext()) {
            Beam beam = iterator.next();
            if (gameTime > beam.expiryTick) {
                iterator.remove();
                continue;
            }
            // RenderType.LINES uses POSITION_COLOR_NORMAL - every vertex needs
            // a normal or Sodium/Iris BufferBuilder throws "Missing elements".
            Vec3 normal = beam.end.subtract(beam.start).normalize();
            consumer.addVertex(pose.pose(),
                            (float) (beam.start.x - camera.x),
                            (float) (beam.start.y - camera.y),
                            (float) (beam.start.z - camera.z))
                    .setColor(beam.argb)
                    .setNormal(pose, (float) normal.x, (float) normal.y,
                            (float) normal.z);
            consumer.addVertex(pose.pose(),
                            (float) (beam.end.x - camera.x),
                            (float) (beam.end.y - camera.y),
                            (float) (beam.end.z - camera.z))
                    .setColor(beam.argb)
                    .setNormal(pose, (float) normal.x, (float) normal.y,
                            (float) normal.z);
        }
        buffers.endBatch(RenderType.LINES);
    }

    private static int argb(int rgb, float alpha) {
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private record Beam(Vec3 start, Vec3 end, int argb, long expiryTick) {
    }
}
