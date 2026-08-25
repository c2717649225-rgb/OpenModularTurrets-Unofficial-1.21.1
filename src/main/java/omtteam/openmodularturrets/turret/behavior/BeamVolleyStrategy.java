package omtteam.openmodularturrets.turret.behavior;

import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.damage.TurretDamageSource;
import omtteam.openmodularturrets.data.SpecialTurretRules;
import omtteam.openmodularturrets.data.TurretAddonRules;
import omtteam.openmodularturrets.data.TurretCombatContext;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretUpgradeRules;
import omtteam.openmodularturrets.data.TurretVisualRules;
import omtteam.openmodularturrets.network.BeamEffectPayload;
import omtteam.openmodularturrets.registration.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageType;
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
 * Parameterized strategy for instantaneous hitscan ray/beam turrets (laser, railgun).
 * Holds only immutable metadata (ResourceKey, block breaking capabilities).
 * Dynamic damage values and configs are evaluated at runtime.
 */
public final class BeamVolleyStrategy implements VolleyStrategy {
    private final ResourceKey<DamageType> damageTypeKey;
    private final boolean ignoresArmor;
    private final boolean canDestroyBlocks;

    public BeamVolleyStrategy(ResourceKey<DamageType> damageTypeKey, boolean ignoresArmor, boolean canDestroyBlocks) {
        this.damageTypeKey = damageTypeKey;
        this.ignoresArmor = ignoresArmor;
        this.canDestroyBlocks = canDestroyBlocks;
    }

    public ResourceKey<DamageType> damageTypeKey() {
        return damageTypeKey;
    }

    public boolean ignoresArmor() {
        return ignoresArmor;
    }

    public boolean canDestroyBlocks() {
        return canDestroyBlocks;
    }

    @Override
    public void execute(ServerLevel level, BlockPos headPos, LivingEntity target,
                        TurretDefinition definition, ItemStack consumedAmmo,
                        TurretCombatContext combatContext) {
        Vec3 intendedEnd = target.getEyePosition();
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
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target));
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
            var holder = level.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(damageTypeKey);
            float baseDamage = definition.damage()
                    * SpecialTurretRules.beamDamageMultiplier(definition,
                            hitEntity.getArmorValue());
            float damage = TurretAddonRules.amplifiedDamage(definition, baseDamage,
                    hitEntity.getHealth(), combatContext.damageAmpLevel());
            hitEntity.hurt(TurretDamageSource.create(level, holder, null,
                    combatContext.attackContext()), damage);
            hitEntity.invulnerableTime = 0;
            SoundEvent impact = ModSounds.rayImpactFor(definition);
            if (impact != null) {
                level.playSound(null, hitEntity.blockPosition(), impact,
                        SoundSource.AMBIENT, ModServerConfig.turretSoundVolume(),
                        0.5F + level.random.nextFloat());
            }
        } else if (canDestroyBlocks
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

    private static Vec3 muzzleOrigin(BlockPos headPos, Vec3 targetPosition) {
        Vec3 center = Vec3.atCenterOf(headPos);
        Vec3 direction = targetPosition.subtract(center);
        if (direction.lengthSqr() < 1.0E-7D) {
            return center;
        }
        return center.add(direction.normalize().scale(1.0D));
    }
}
