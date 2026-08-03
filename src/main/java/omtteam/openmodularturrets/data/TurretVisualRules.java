package omtteam.openmodularturrets.data;

import net.minecraft.core.Direction;

public final class TurretVisualRules {
    public static final int MAX_BEAM_SEGMENTS = 96;
    public static final int ROCKET_TRAIL_PARTICLES = 21;
    public static final int PLASMA_IMPACT_PARTICLES_PER_TYPE = 16;
    public static final int IDLE_DUST_PARTICLES = 6;
    public static final int TELEPORT_BURST_PARTICLES = 26;

    private TurretVisualRules() {
    }

    public static String texturePath(TurretDefinition definition) {
        return switch (definition) {
            case DISPOSABLE -> "textures/block/dispose_item_turret.png";
            case PLASMA -> "textures/block/grenade_turret.png";
            default -> "textures/block/" + definition.id() + ".png";
        };
    }

    public static MountRotation mountRotation(Direction directionToBase) {
        return switch (directionToBase) {
            case DOWN -> new MountRotation(0.0F, 0.0F);
            case UP -> new MountRotation(3.145F, 0.0F);
            case NORTH -> new MountRotation(1.56F, 0.0F);
            case SOUTH -> new MountRotation(1.56F, 3.145F);
            case WEST -> new MountRotation(1.56F, 4.705F);
            case EAST -> new MountRotation(1.56F, 1.565F);
        };
    }

    public static int addonMask(boolean damageAmp, boolean solar, boolean reactor) {
        return (damageAmp ? 1 : 0) | (solar ? 2 : 0) | (reactor ? 4 : 0);
    }

    public static int beamSegments(double length) {
        return Math.clamp((int) (Math.max(0.0D, length) * 2.0D),
                1, MAX_BEAM_SEGMENTS);
    }

    /**
     * Legacy 1.12 ray opacity: the laser beam is a translucent orange-red
     * (alpha 0.38), the rail gun a faint orange (alpha 0.2).
     */
    public static float beamAlpha(omtteam.openmodularturrets.data.TurretDefinition definition) {
        return definition == omtteam.openmodularturrets.data.TurretDefinition.RAIL_GUN
                ? 0.2F : 0.38F;
    }

    /**
     * Legacy 1.12 ray lifetime in ticks (laser 5, rail gun 3).
     */
    public static int beamDurationTicks(omtteam.openmodularturrets.data.TurretDefinition definition) {
        return definition == omtteam.openmodularturrets.data.TurretDefinition.RAIL_GUN
                ? 3 : 5;
    }

    /**
     * Combines two vanilla packed-light values without forcing emissive rendering.
     * Large legacy turret models extend outside their invisible host block, so the
     * renderer may legitimately need the brightest neighboring sample.
     */
    public static int mergePackedLight(int first, int second) {
        int block = Math.max((first >> 4) & 0xF, (second >> 4) & 0xF);
        int sky = Math.max((first >> 20) & 0xF, (second >> 20) & 0xF);
        return (block << 4) | (sky << 20);
    }

    /** Legacy base-fit X/Y rotation in radians, applied only to support pieces. */
    public record MountRotation(float xRadians, float yRadians) {
    }
}
