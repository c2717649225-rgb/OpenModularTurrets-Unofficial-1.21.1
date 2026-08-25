package omtteam.openmodularturrets.service;

import java.util.List;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.data.SpecialTurretRules;
import omtteam.openmodularturrets.data.TargetPriorityProfile;
import omtteam.openmodularturrets.data.TargetingRules;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretGeometryRules;
import omtteam.openmodularturrets.data.TurretTargetingWorldQueries;
import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.registration.ModTags;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
/**
 * Server-side target selection and line-of-sight policy for one turret head.
 *
 * <p>The service owns only its small LOS cache.  It never stores or calls a
 * Head BlockEntity; the caller supplies the head position, the frozen range
 * value, and explicit server-world queries.  This keeps the dependency one-way:
 * {@code Head -> TargetingService}, not {@code Head -> Service -> Head}.</p>
 */
public final class TurretTargetingService {
    private static final long TARGET_LOS_CACHE_TICKS = 2L;

    private int cachedLineOfSightTargetId = -1;
    private long cachedLineOfSightTick = Long.MIN_VALUE;
    private boolean cachedLineOfSight;

    @Nullable
    public LivingEntity validateCurrentTarget(ServerLevel level, BlockPos headPos,
            TurretTargetingWorldQueries worldQueries,
            int range,
            TurretDefinition definition, @Nullable LivingEntity entity,
            long gameTime, boolean forceLineOfSight) {
        if (!isTargetEligible(entity, worldQueries, range, definition, headPos)) {
            return null;
        }
        return isLegalLineOfSight(level, headPos, entity, gameTime, forceLineOfSight)
                ? entity : null;
    }

    @Nullable
    public LivingEntity findTarget(ServerLevel level, BlockPos headPos,
            TurretTargetingWorldQueries worldQueries,
            int range,
            TurretDefinition definition, TargetPriorityProfile priorities,
            long gameTime) {
        Vec3 origin = Vec3.atCenterOf(headPos);
        AABB search = new AABB(headPos).inflate(range);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                search, entity -> isTargetEligible(entity, worldQueries, range,
                        definition, headPos));
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (LivingEntity candidate : candidates) {
            if (worldQueries.isTargetClaimedBySibling(headPos, candidate)) {
                continue;
            }
            double distanceSquared = candidate.distanceToSqr(origin);
            double score = TargetingRules.score(priorities, candidate.getHealth(),
                    candidate.getMaxHealth(), Math.sqrt(distanceSquared), range,
                    candidate.getArmorValue(), candidate instanceof Player);
            boolean better = score > bestScore
                    || (Double.compare(score, bestScore) == 0
                            && (distanceSquared < bestDistance
                                    || (Double.compare(distanceSquared, bestDistance) == 0
                                            && (best == null
                                                    || candidate.getId() < best.getId()))));
            // Ray-cast only candidates that can beat the current visible winner.
            if (better && hasLineOfSight(level, headPos, candidate)) {
                best = candidate;
                bestScore = score;
                bestDistance = distanceSquared;
                cacheLineOfSight(candidate, gameTime, true);
            }
        }
        return best;
    }

    public void onTargetChanged(int targetEntityId) {
        if (cachedLineOfSightTargetId != targetEntityId) {
            clearLineOfSightCache();
        }
    }

    public void clearLineOfSightCache() {
        cachedLineOfSightTargetId = -1;
        cachedLineOfSightTick = Long.MIN_VALUE;
        cachedLineOfSight = false;
    }

    public static boolean shouldScan(long gameTime, BlockPos pos) {
        return Math.floorMod(gameTime + pos.asLong(),
                omtteam.openmodularturrets.config.ModServerConfig.targetSearchTicks()) == 0;
    }

    private boolean isLegalLineOfSight(ServerLevel level, BlockPos headPos,
            LivingEntity entity, long gameTime, boolean forceLineOfSight) {
        if (forceLineOfSight) {
            return cacheLineOfSight(entity, gameTime,
                    hasLineOfSight(level, headPos, entity));
        }
        if (cachedLineOfSightTargetId == entity.getId()
                && gameTime >= cachedLineOfSightTick
                && gameTime - cachedLineOfSightTick < TARGET_LOS_CACHE_TICKS) {
            return cachedLineOfSight;
        }
        return cacheLineOfSight(entity, gameTime,
                hasLineOfSight(level, headPos, entity));
    }

    private boolean isTargetEligible(@Nullable LivingEntity entity,
            TurretTargetingWorldQueries worldQueries,
            int range,
            TurretDefinition definition, BlockPos headPos) {
        if (entity == null || !entity.isAlive() || entity.isSpectator()
                || entity.getType().is(ModTags.EntityTypes.TARGET_BLACKLIST)) {
            return false;
        }
        if (entity.distanceToSqr(Vec3.atCenterOf(headPos))
                > (double) range * range) {
            return false;
        }
        if (!(entity instanceof Player) && !(entity instanceof Enemy)
                && !(entity instanceof Mob)) {
            return false;
        }
        return SpecialTurretRules.acceptsTarget(definition, entity)
                && worldQueries.mayDamage(entity);
    }

    private boolean hasLineOfSight(ServerLevel level, BlockPos headPos,
            LivingEntity entity) {
        Vec3 target = entity.getEyePosition();
        Vec3 origin = TurretGeometryRules.muzzleOrigin(headPos, target);
        return level.clip(new ClipContext(origin, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.MISS;
    }

    private boolean cacheLineOfSight(LivingEntity entity, long gameTime,
            boolean visible) {
        cachedLineOfSightTargetId = entity.getId();
        cachedLineOfSightTick = gameTime;
        cachedLineOfSight = visible;
        return visible;
    }
}
