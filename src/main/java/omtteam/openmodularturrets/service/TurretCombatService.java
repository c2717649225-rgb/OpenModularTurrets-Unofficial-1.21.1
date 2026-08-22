package omtteam.openmodularturrets.service;

import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.damage.TurretAttackContext;
import omtteam.openmodularturrets.damage.TurretDamageSource;
import omtteam.openmodularturrets.data.SpecialTurretRules;
import omtteam.openmodularturrets.data.TurretAddonRules;
import omtteam.openmodularturrets.data.TurretCombatContext;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretUpgradeRules;
import omtteam.openmodularturrets.data.TurretVisualRules;
import omtteam.openmodularturrets.data.TurretVolleyResourcesView;
import omtteam.openmodularturrets.entity.ProjectileKind;
import omtteam.openmodularturrets.entity.TurretProjectileEntity;
import omtteam.openmodularturrets.network.BeamEffectPayload;
import omtteam.openmodularturrets.registration.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative volley execution.  It receives positions and state
 * owners, never a Head BlockEntity, so combat effects cannot form a callback
 * cycle through the orchestration object.
 */
public final class TurretCombatService {
    private TurretCombatService() {
    }

    public static CombatResult executeVolley(ServerLevel level, BlockPos headPos,
            LivingEntity target, TurretDefinition definition,
            TurretVolleyResourcesView resources,
            TurretCombatContext combatContext) {
        boolean aliveBefore = target.isAlive();
        int executions = SpecialTurretRules.shotExecutions(
                definition, resources.projectileCount());
        for (int shot = 0; shot < executions; shot++) {
            switch (definition.shotKind()) {
                case TELEPORT -> {
                    Vec3 destination = Vec3.atBottomCenterOf(headPos.above());
                    target.teleportTo(destination.x, destination.y, destination.z);
                    level.sendParticles(ParticleTypes.PORTAL,
                            headPos.getX() + 0.5D, headPos.getY() + 0.5D,
                            headPos.getZ() + 0.5D,
                            TurretVisualRules.TELEPORT_BURST_PARTICLES,
                            1.0D, 1.0D, 1.0D, 0.1D);
                }
                case RELATIVISTIC -> {
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                            200, 3));
                    target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,
                            200, 3));
                }
                case BEAM -> shootBeam(level, headPos, target, definition, combatContext);
                case PROJECTILE, INCENDIARY, EXPLOSIVE, PLASMA -> spawnProjectile(
                        level, headPos, target, definition, resources.ammo(), combatContext);
            }
        }
        return new CombatResult(aliveBefore, target.isAlive(), executions);
    }

    private static void shootBeam(ServerLevel level, BlockPos headPos,
            LivingEntity intendedTarget, TurretDefinition definition,
            TurretCombatContext combatContext) {
        Vec3 intendedEnd = intendedTarget.getEyePosition();
        Vec3 start = muzzleOrigin(headPos, intendedEnd);
        Vec3 direct = intendedEnd.subtract(start);
        double distance = direct.length();
        double deviationModifier = 0.3D
                + distance * 0.2D / Math.max(1, definition.baseRange());
        double spread = distance * 0.003D * deviationModifier
                * TurretUpgradeRules.accuracyDeviation(definition,
                        combatContext.accuracyUpgradeLevel(),
                        combatContext.scatterShotUpgradeLevel());
        Vec3 end = intendedEnd.add(level.random.nextGaussian() * spread,
                level.random.nextGaussian() * spread,
                level.random.nextGaussian() * spread);

        HitResult blockHit = level.clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, intendedTarget));
        Vec3 endpoint = blockHit.getType() == HitResult.Type.BLOCK
                ? blockHit.getLocation() : end;
        double nearestDistance = start.distanceToSqr(endpoint);
        LivingEntity hitEntity = null;
        Vec3 entityHitPoint = null;
        AABB traceBounds = new AABB(start, endpoint).inflate(1.0D);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                traceBounds, entity -> entity.isAlive()
                        && combatContext.mayDamage(entity))) {
            java.util.Optional<Vec3> hit = candidate.getBoundingBox().inflate(0.3D)
                    .clip(start, endpoint);
            if (hit.isEmpty()) {
                continue;
            }
            double hitDistance = start.distanceToSqr(hit.get());
            if (hitDistance < nearestDistance) {
                nearestDistance = hitDistance;
                hitEntity = candidate;
                entityHitPoint = hit.get();
            }
        }
        if (hitEntity != null) {
            endpoint = entityHitPoint;
            boolean aliveBefore = hitEntity.isAlive();
            var holder = level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(SpecialTurretRules.beamDamageType(definition));
            float baseDamage = definition.damage()
                    * SpecialTurretRules.beamDamageMultiplier(definition,
                            hitEntity.getArmorValue());
            float damage = TurretAddonRules.amplifiedDamage(definition, baseDamage,
                    hitEntity.getHealth(), combatContext.damageAmpLevel());
            hitEntity.hurt(TurretDamageSource.create(level, holder, null,
                    combatContext.attackContext()), damage);
            hitEntity.invulnerableTime = 0;
            if (aliveBefore && !hitEntity.isAlive()) {
                combatContext.recordKill(hitEntity);
            }
            SoundEvent impact = ModSounds.rayImpactFor(definition);
            if (impact != null) {
                level.playSound(null, hitEntity.blockPosition(), impact,
                        SoundSource.AMBIENT, ModServerConfig.turretSoundVolume(),
                        0.5F + level.random.nextFloat());
            }
        } else if (definition == TurretDefinition.RAIL_GUN
                && ModServerConfig.railgunDestroysBlocks()
                && blockHit instanceof BlockHitResult hit) {
            BlockState hitState = level.getBlockState(hit.getBlockPos());
            float hardness = hitState.getDestroySpeed(level, hit.getBlockPos());
            if (SpecialTurretRules.railgunCanDestroyBlock(hardness,
                    ModServerConfig.railgunDestroysBlocks())) {
                level.destroyBlock(hit.getBlockPos(), false);
            }
        }
        PacketDistributor.sendToPlayersTrackingChunk(level,
                new net.minecraft.world.level.ChunkPos(headPos),
                new BeamEffectPayload(start, endpoint,
                        SpecialTurretRules.beamColor(definition),
                        TurretVisualRules.beamAlpha(definition),
                        TurretVisualRules.beamDurationTicks(definition)));
    }

    private static void spawnProjectile(ServerLevel level, BlockPos headPos,
            LivingEntity target, TurretDefinition definition, ItemStack consumedAmmo,
            TurretCombatContext combatContext) {
        ProjectileKind kind = switch (definition) {
            case DISPOSABLE -> ProjectileKind.DISPOSABLE;
            case POTATO -> ProjectileKind.POTATO;
            case MACHINE_GUN -> ProjectileKind.BULLET;
            case INCENDIARY -> ProjectileKind.BLAZING_CLAY;
            case GRENADE -> ProjectileKind.GRENADE;
            case ROCKET -> ProjectileKind.ROCKET;
            case PLASMA -> ProjectileKind.PLASMA;
            default -> throw new IllegalArgumentException(
                    "Turret does not use a projectile: " + definition.id());
        };
        TurretAttackContext attackContext = combatContext.attackContext();
        TurretProjectileEntity projectile = TurretProjectileEntity.create(level, kind,
                attackContext.sourceBasePos(), target, definition,
                combatContext.damageAmpLevel(), attackContext, consumedAmmo);
        Vec3 targetPosition = target.getEyePosition();
        Vec3 origin = muzzleOrigin(headPos, targetPosition);
        Vec3 delta = targetPosition.subtract(origin);
        projectile.setPos(origin.x, origin.y, origin.z);
        float speed = kind == ProjectileKind.ROCKET ? 0.24F
                : kind.gravity() > 0.0D ? 1.6F : 3.0F;
        projectile.shoot(delta.x, delta.y, delta.z, speed,
                combatContext.projectileInaccuracy(definition));
        level.addFreshEntity(projectile);
    }

    private static Vec3 muzzleOrigin(BlockPos headPos, Vec3 targetPosition) {
        Vec3 center = Vec3.atCenterOf(headPos);
        Vec3 direction = targetPosition.subtract(center);
        if (direction.lengthSqr() < 1.0E-7D) {
            return center;
        }
        return center.add(direction.normalize().scale(1.0D));
    }

    public record CombatResult(boolean targetAliveBefore, boolean targetAliveAfter,
            int executions) {
    }
}
