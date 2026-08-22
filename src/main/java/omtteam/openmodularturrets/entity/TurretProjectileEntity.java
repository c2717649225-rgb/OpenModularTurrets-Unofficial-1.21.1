package omtteam.openmodularturrets.entity;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.damage.ModDamageTypes;
import omtteam.openmodularturrets.damage.TurretAttackContext;
import omtteam.openmodularturrets.damage.TurretDamageSource;
import omtteam.openmodularturrets.data.TurretAddonRules;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.SpecialTurretRules;
import omtteam.openmodularturrets.data.TurretVisualRules;
import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.registration.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public final class TurretProjectileEntity extends ThrowableItemProjectile {
    private static final int DATA_VERSION = 3;

    private ProjectileKind projectileKind;
    private float damage;
    private int damageAmpLevel;
    private int fakeDropsLevel = -1;
    private boolean suppressLoot;
    @Nullable
    private BlockPos sourceBasePos;
    @Nullable
    private UUID targetUuid;
    private boolean resolved;
    private boolean grenadeHit;

    public TurretProjectileEntity(EntityType<? extends TurretProjectileEntity> type, Level level) {
        super(type, level);
        projectileKind = ProjectileKind.forType(type);
    }

    public static TurretProjectileEntity create(ServerLevel level, ProjectileKind kind,
            BlockPos sourceBasePos, LivingEntity target, TurretDefinition definition,
            int damageAmpLevel, TurretAttackContext context,
            @Nullable ItemStack visualStack) {
        TurretProjectileEntity projectile = kind.entityType().create(level);
        if (projectile == null) {
            throw new IllegalStateException("Unable to create projectile " + kind.id());
        }
        projectile.projectileKind = kind;
        projectile.sourceBasePos = sourceBasePos.immutable();
        projectile.targetUuid = target.getUUID();
        projectile.damage = Math.clamp(definition.damage(), 0.0F,
                (float) ModServerConfig.MAX_TURRET_DAMAGE);
        projectile.damageAmpLevel = Math.max(0, damageAmpLevel);
        projectile.fakeDropsLevel = context.fakeDropsLevel();
        projectile.suppressLoot = context.suppressLoot();
        projectile.setItem(visualStack == null || visualStack.isEmpty()
                ? new ItemStack(kind.displayItem()) : visualStack);
        return projectile;
    }

    @Override
    protected Item getDefaultItem() {
        return projectileKind == null
                ? ProjectileKind.DISPOSABLE.displayItem()
                : projectileKind.displayItem();
    }

    @Override
    public void tick() {
        if (projectileKind.fuseExpired(tickCount)) {
            if (!level().isClientSide) {
                explode(3.0D, 0.9F, 0.1F);
            }
            discard();
            return;
        }
        if (projectileKind.shouldExpire(tickCount)) {
            discard();
            return;
        }
        updateRocketHoming();
        if (isRemoved()) {
            return;
        }
        super.tick();
    }

    private void updateRocketHoming() {
        if (level().isClientSide || projectileKind != ProjectileKind.ROCKET
                || !ModServerConfig.rocketsHome()) {
            return;
        }
        if (!(level() instanceof ServerLevel serverLevel) || targetUuid == null
                || !(serverLevel.getEntity(targetUuid) instanceof LivingEntity target)
                || !target.isAlive()) {
            discard();
            return;
        }
        Vec3 velocity = SpecialTurretRules.rocketHomingVelocity(
                position(), target.getEyePosition());
        if (velocity.lengthSqr() > 0.0D) {
            setDeltaMovement(velocity);
            hasImpulse = true;
        }
    }

    @Override
    protected double getDefaultGravity() {
        return projectileKind.gravity();
    }

    @Override
    protected void onHit(HitResult result) {
        if (result instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
            BlockState hitState = level().getBlockState(blockHit.getBlockPos());
            if (ignoresBlockCollision(hitState)) {
                return;
            }
            if (projectileKind == ProjectileKind.PLASMA
                    && blockHit.getBlockPos().distToCenterSqr(
                            getX(), getY(), getZ()) > 0.5D) {
                return;
            }
        }
        if (level().isClientSide || resolved) {
            return;
        }
        SoundEvent impactSound = ModSounds.impactFor(projectileKind);
        if (impactSound != null) {
            level().playSound(null, blockPosition(), impactSound,
                    SoundSource.AMBIENT, ModServerConfig.turretSoundVolume(),
                    0.5F + random.nextFloat());
        }
        switch (projectileKind) {
            case GRENADE -> {
                if (result.getType() == HitResult.Type.ENTITY) {
                    grenadeHit = true;
                    setDeltaMovement(getDeltaMovement().multiply(0.2D, 1.2D, 0.2D));
                    tickCount = 30;
                } else {
                    setDeltaMovement(Vec3.ZERO);
                }
            }
            case ROCKET -> {
                explode(5.0D, 1.0F, 0.0F);
                discard();
            }
            case PLASMA -> {
                Vec3 motion = getDeltaMovement();
                setPos(getX() + motion.x * 0.8D,
                        getY() + motion.y * 0.8D,
                        getZ() + motion.z * 0.8D);
                setDeltaMovement(Vec3.ZERO);
                explode(2.0D, 0.5F, 0.5F);
                discard();
            }
            case BLAZING_CLAY -> {
                damageArea(5.0D, 1.0F, 0.0F, true);
                discard();
            }
            case DISPOSABLE, POTATO, BULLET -> {
                if (result instanceof net.minecraft.world.phys.EntityHitResult entityHit
                        && entityHit.getEntity() instanceof LivingEntity living) {
                    damage(living, ModDamageTypes.TURRET_PROJECTILE,
                            amplifiedDamage(living));
                    living.invulnerableTime = 0;
                } else if (result instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
                    damageNonLiving(entityHit.getEntity());
                }
                discard();
            }
        }
    }

    private void explode(double radius, float normalShare, float armorPiercingShare) {
        if (resolved) {
            return;
        }
        resolved = true;
        if (level() instanceof ServerLevel serverLevel) {
            if (projectileKind == ProjectileKind.PLASMA) {
                spawnPlasmaImpact(serverLevel);
            } else {
                boolean terrainDamageEnabled = projectileKind == ProjectileKind.ROCKET
                        ? ModServerConfig.rocketsDestroyBlocks()
                        : projectileKind == ProjectileKind.GRENADE
                                && ModServerConfig.grenadesDestroyBlocks();
                float terrainStrength = projectileKind
                        .terrainExplosionStrength(terrainDamageEnabled);
                serverLevel.explode(null, getX(), getY(), getZ(), terrainStrength, true,
                        Level.ExplosionInteraction.BLOCK);
            }
        }
        damageArea(radius, normalShare, armorPiercingShare, false);
    }

    private void spawnPlasmaImpact(ServerLevel level) {
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME,
                getX(), getY(), getZ(),
                TurretVisualRules.PLASMA_IMPACT_PARTICLES_PER_TYPE,
                1.0D, 0.5D, 1.0D, 0.1D);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                getX(), getY(), getZ(),
                TurretVisualRules.PLASMA_IMPACT_PARTICLES_PER_TYPE,
                1.0D, 0.5D, 1.0D, 0.1D);
    }

    private void damageArea(double radius, float normalShare, float armorPiercingShare,
            boolean ignite) {
        if (!(level() instanceof ServerLevel)) {
            return;
        }
        AABB area = new AABB(getX() - radius, getY() - radius, getZ() - radius,
                getX() + radius, getY() + radius, getZ() + radius);
        List<LivingEntity> targets = level().getEntitiesOfClass(LivingEntity.class, area,
                this::mayDamage);
        for (LivingEntity target : targets) {
            float totalDamage = amplifiedDamage(target);
            if (normalShare > 0.0F) {
                damage(target, ignite ? ModDamageTypes.TURRET_FIRE
                        : ModDamageTypes.TURRET_EXPLOSION, totalDamage * normalShare);
            }
            if (armorPiercingShare > 0.0F && target.isAlive()) {
                damage(target, ModDamageTypes.TURRET_ARMOR_PIERCING,
                        totalDamage * armorPiercingShare);
            }
            if (ignite) {
                target.igniteForSeconds(5.0F);
            }
            target.invulnerableTime = 0;
        }
    }

    private void damageNonLiving(net.minecraft.world.entity.Entity target) {
        if (!(level() instanceof ServerLevel serverLevel) || damage <= 0.0F) {
            return;
        }
        var holder = serverLevel.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ModDamageTypes.TURRET_PROJECTILE);
        target.hurt(TurretDamageSource.create(serverLevel, holder, this,
                new TurretAttackContext(sourceBasePos, fakeDropsLevel, suppressLoot)), damage);
    }

    private void damage(LivingEntity target,
            net.minecraft.resources.ResourceKey<DamageType> damageType, float amount) {
        if (amount <= 0.0F || !mayDamage(target)) {
            return;
        }
        boolean aliveBefore = target.isAlive();
        var holder = level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(damageType);
        TurretDamageSource source = TurretDamageSource.create((ServerLevel) level(), holder, null,
                new TurretAttackContext(sourceBasePos, fakeDropsLevel, suppressLoot));
        if (projectileKind == ProjectileKind.ROCKET && target instanceof EnderDragon
                && ModServerConfig.rocketsHurtDragon()) {
            target.setHealth(target.getHealth() - amount);
            target.invulnerableTime = 0;
        } else {
            target.hurt(source, amount);
            target.invulnerableTime = 0;
        }
        if (aliveBefore && !target.isAlive()) {
            sourceBase().ifPresent(base -> base.recordKill(target));
        }
    }

    private boolean mayDamage(LivingEntity target) {
        if (!target.isAlive()) {
            return false;
        }
        if (projectileKind == ProjectileKind.ROCKET && target instanceof EnderDragon) {
            return sourceBase().isPresent();
        }
        return sourceBase()
                .map(base -> base.mayDamage(target))
                .orElse(false);
    }

    public boolean mayCollideWith(net.minecraft.world.entity.Entity target) {
        if (target instanceof TurretProjectileEntity || sourceBase().isEmpty()) {
            return false;
        }
        return !(target instanceof LivingEntity living) || mayDamage(living);
    }

    public boolean ignoresBlockCollision(BlockState state) {
        return state.getBlock() instanceof TurretHeadBlock;
    }

    @Override
    protected boolean canHitEntity(net.minecraft.world.entity.Entity target) {
        return mayCollideWith(target) && super.canHitEntity(target)
                && !(projectileKind == ProjectileKind.GRENADE && grenadeHit);
    }

    private java.util.Optional<TurretBaseBlockEntity> sourceBase() {
        if (sourceBasePos != null
                && level().getBlockEntity(sourceBasePos) instanceof TurretBaseBlockEntity base) {
            return java.util.Optional.of(base);
        }
        return java.util.Optional.empty();
    }

    public ProjectileKind projectileKind() {
        return projectileKind;
    }

    /**
     * Returns the immutable base position that authorized this projectile.
     * This is also useful for diagnostics that run alongside multiple
     * GameTest fixtures in the same server world.
     */
    @Nullable
    public BlockPos sourceBasePos() {
        return sourceBasePos;
    }

    private float amplifiedDamage(LivingEntity target) {
        return TurretAddonRules.amplifiedDamage(projectileKind.turretDefinition(),
                damage, target.getHealth(), damageAmpLevel);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("data_version", DATA_VERSION);
        tag.putString("projectile_kind", projectileKind.id());
        tag.putFloat("damage", damage);
        tag.putInt("damage_amp_level", damageAmpLevel);
        tag.putInt("fake_drops_level", fakeDropsLevel);
        tag.putBoolean("suppress_loot", suppressLoot);
        tag.putBoolean("grenade_hit", grenadeHit);
        if (sourceBasePos != null) {
            tag.putLong("source_base_pos", sourceBasePos.asLong());
        }
        if (targetUuid != null) {
            tag.putUUID("target_uuid", targetUuid);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        projectileKind = ProjectileKind.byId(tag.getString("projectile_kind"));
        damage = Math.clamp(tag.getFloat("damage"), 0.0F,
                (float) ModServerConfig.MAX_TURRET_DAMAGE);
        damageAmpLevel = Math.max(0, tag.getInt("damage_amp_level"));
        fakeDropsLevel = tag.contains("fake_drops_level")
                ? Math.clamp(tag.getInt("fake_drops_level"), -1, 3) : -1;
        suppressLoot = tag.getBoolean("suppress_loot");
        grenadeHit = tag.getBoolean("grenade_hit");
        sourceBasePos = tag.contains("source_base_pos")
                ? BlockPos.of(tag.getLong("source_base_pos")) : null;
        targetUuid = tag.hasUUID("target_uuid") ? tag.getUUID("target_uuid") : null;
    }
}
