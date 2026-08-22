package omtteam.openmodularturrets.client.render;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Deque;

import omtteam.openmodularturrets.data.TurretVisualRules;

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
    private static final Deque<Beam> BEAMS = new ArrayDeque<>();

    private BeamRenderCache() {
    }

    public static void add(Vec3 start, Vec3 end, int color, float alpha,
            int durationTicks) {
        long gameTime = Minecraft.getInstance().level == null
                ? 0L : Minecraft.getInstance().level.getGameTime();
        while (BEAMS.size() >= TurretVisualRules.MAX_ACTIVE_BEAMS) {
            BEAMS.removeFirst();
        }
        Vec3 delta = end.subtract(start);
        Vec3 normal = delta.lengthSqr() < 1.0E-12D ? Vec3.ZERO : delta.normalize();
        BEAMS.addLast(new Beam(start, end, normal, argb(color, alpha),
                gameTime + durationTicks));
    }

    public static void clear() {
        BEAMS.clear();
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
            consumer.addVertex(pose.pose(),
                            (float) (beam.start.x - camera.x),
                            (float) (beam.start.y - camera.y),
                            (float) (beam.start.z - camera.z))
                    .setColor(beam.argb)
                    .setNormal(pose, (float) beam.normal.x, (float) beam.normal.y,
                            (float) beam.normal.z);
            consumer.addVertex(pose.pose(),
                            (float) (beam.end.x - camera.x),
                            (float) (beam.end.y - camera.y),
                            (float) (beam.end.z - camera.z))
                    .setColor(beam.argb)
                    .setNormal(pose, (float) beam.normal.x, (float) beam.normal.y,
                            (float) beam.normal.z);
        }
        buffers.endBatch(RenderType.LINES);
    }

    private static int argb(int rgb, float alpha) {
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private record Beam(Vec3 start, Vec3 end, Vec3 normal, int argb, long expiryTick) {
    }
}
