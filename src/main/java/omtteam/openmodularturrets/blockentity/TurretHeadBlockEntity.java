package omtteam.openmodularturrets.blockentity;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretAddonRules;
import omtteam.openmodularturrets.data.TurretVisualRules;
import omtteam.openmodularturrets.data.TargetPriorityProfile;
import omtteam.openmodularturrets.data.TargetingRules;
import omtteam.openmodularturrets.data.SpecialTurretRules;
import omtteam.openmodularturrets.data.TurretUpgradeRules;
import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.damage.TurretDamageSource;
import omtteam.openmodularturrets.registration.ModBlockEntities;
import omtteam.openmodularturrets.registration.ModItems;
import omtteam.openmodularturrets.network.BeamEffectPayload;
import omtteam.openmodularturrets.network.TurretAimPayload;
import omtteam.openmodularturrets.entity.ProjectileKind;
import omtteam.openmodularturrets.entity.TurretProjectileEntity;
import omtteam.openmodularturrets.registration.ModSounds;
import omtteam.openmodularturrets.registration.ModTags;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

public final class TurretHeadBlockEntity extends BlockEntity {
    private static final double MUZZLE_CLEARANCE = 1.0D;

    private int cooldown;
    private int targetEntityId = -1;
    private float aimYaw;
    private float aimPitch;
    private int idleTicks;
    private TargetPriorityProfile priorityProfile;

    public TurretHeadBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TURRET_HEAD.value(), pos, state);
        priorityProfile = TargetPriorityProfile.defaults(definition());
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
            TurretHeadBlockEntity turret) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (turret.cooldown > 0) {
            turret.cooldown--;
        }

        Optional<TurretBaseBlockEntity> baseResult = turret.findBase();
        if (baseResult.isEmpty()) {
            turret.setTarget(null);
            turret.updateConcealment(false, false);
            return;
        }
        TurretBaseBlockEntity base = baseResult.get();
        TurretDefinition definition = turret.definition();
        if (!base.active() || base.tier().level() < definition.requiredBaseTier()
                || !ModServerConfig.turret(definition).enabled()) {
            turret.setTarget(null);
            turret.updateConcealment(false, false);
            return;
        }

        LivingEntity target = turret.currentTarget();
        boolean staggeredScan = shouldScan(level.getGameTime(), pos);
        if (!turret.isLegalTarget(target, base, definition)) {
            target = null;
            turret.setTarget(null);
            if (staggeredScan) {
                target = turret.findTarget(base, definition);
                turret.setTarget(target);
            }
        }
        turret.updateConcealment(base.hasConcealer()
                || ModServerConfig.concealWithoutAddon(), target != null);
        if (target == null) {
            return;
        }

        turret.updateAim(target);
        if (turret.cooldown <= 0) {
            Optional<TurretBaseBlockEntity.VolleyResources> consumed =
                    base.consumeResourcesForVolley(definition);
            if (consumed.isEmpty()) {
                return;
            }
            boolean aliveBefore = target.isAlive();
            turret.fire(serverLevel, target, definition, consumed.get(), base);
            if (base.damageAmpLevel() > 0 && definition.damageAmpFraction() > 0.0F) {
                serverLevel.playSound(null, pos, ModSounds.AMPED.value(),
                    SoundSource.BLOCKS, ModServerConfig.turretSoundVolume(),
                    0.5F + serverLevel.random.nextFloat());
            }
            boolean fixedSpecialSound = definition == TurretDefinition.RELATIVISTIC
                    || definition == TurretDefinition.TELEPORTER;
            serverLevel.playSound(null, pos, ModSounds.launchFor(definition),
                    SoundSource.BLOCKS,
                    fixedSpecialSound ? 0.6F : ModServerConfig.turretSoundVolume(),
                    fixedSpecialSound ? 1.0F : 0.5F + serverLevel.random.nextFloat());
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(pos),
                    new TurretAimPayload(pos, turret.aimYaw, turret.aimPitch, target.getId()));
            if (definition.shotKind() != TurretDefinition.ShotKind.BEAM
                    && aliveBefore && !target.isAlive()) {
                base.recordKill(target);
            }
            turret.cooldown = base.adjustedFireInterval(definition);
            turret.sync();
        }
    }

    private Optional<TurretBaseBlockEntity> findBase() {
        if (level == null) {
            return Optional.empty();
        }
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(direction))
                    instanceof TurretBaseBlockEntity base
                    && base.canSupportTurret(worldPosition, definition())) {
                return Optional.of(base);
            }
        }
        return Optional.empty();
    }

    @Nullable
    private LivingEntity currentTarget() {
        if (level == null || targetEntityId < 0) {
            return null;
        }
        return level.getEntity(targetEntityId) instanceof LivingEntity living ? living : null;
    }

    @Nullable
    private LivingEntity findTarget(TurretBaseBlockEntity base, TurretDefinition definition) {
        int range = base.range();
        AABB search = new AABB(worldPosition).inflate(range);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, search,
                entity -> isLegalTarget(entity, base, definition));
        TargetPriorityProfile priorities = priorityProfile;
        Vec3 origin = Vec3.atCenterOf(worldPosition);
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (LivingEntity candidate : candidates) {
            if (base.isTargetClaimedBySibling(worldPosition, candidate)) {
                continue;
            }
            double distanceSquared = candidate.distanceToSqr(origin);
            double score = TargetingRules.score(priorities, candidate.getHealth(),
                    candidate.getMaxHealth(), Math.sqrt(distanceSquared), range,
                    candidate.getArmorValue(), candidate instanceof Player);
            if (score > bestScore
                    || (Double.compare(score, bestScore) == 0
                            && (distanceSquared < bestDistance
                                    || (Double.compare(distanceSquared, bestDistance) == 0
                                            && (best == null || candidate.getId() < best.getId()))))) {
                best = candidate;
                bestScore = score;
                bestDistance = distanceSquared;
            }
        }
        return best;
    }

    private boolean isLegalTarget(@Nullable LivingEntity entity, TurretBaseBlockEntity base,
            TurretDefinition definition) {
        if (entity == null || !entity.isAlive() || entity.isSpectator()
                || entity.getType().is(ModTags.EntityTypes.TARGET_BLACKLIST)) {
            return false;
        }
        int range = base.range();
        if (entity.distanceToSqr(Vec3.atCenterOf(worldPosition)) > range * range) {
            return false;
        }
        if (!(entity instanceof Player) && !(entity instanceof Enemy)
                && !(entity instanceof Mob)) {
            return false;
        }
        if (!SpecialTurretRules.acceptsTarget(definition, entity)) {
            return false;
        }
        return base.mayDamage(entity) && hasLineOfSight(entity);
    }

    private boolean hasLineOfSight(LivingEntity entity) {
        Vec3 target = entity.getEyePosition();
        Vec3 origin = muzzleOrigin(target);
        return level.clip(new net.minecraft.world.level.ClipContext(
                origin, target,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, entity)).getType()
                == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private void fire(ServerLevel level, LivingEntity target, TurretDefinition definition,
            TurretBaseBlockEntity.VolleyResources resources, TurretBaseBlockEntity base) {
        int executions = SpecialTurretRules.shotExecutions(
                definition, resources.projectileCount());
        for (int shot = 0; shot < executions; shot++) {
            switch (definition.shotKind()) {
                case TELEPORT -> {
                    // Legacy 1.12 behaviour: yank the target to the cell directly
                    // above the head, unconditionally.  No safety preflight and no
                    // failure branch - an unsafe landing is forced exactly like the
                    // original setPositionAndUpdate call.
                    Vec3 destination = Vec3.atBottomCenterOf(worldPosition.above());
                    target.teleportTo(destination.x, destination.y, destination.z);
                    level.sendParticles(
                            net.minecraft.core.particles.ParticleTypes.PORTAL,
                            worldPosition.getX() + 0.5D,
                            worldPosition.getY() + 0.5D,
                            worldPosition.getZ() + 0.5D,
                            TurretVisualRules.TELEPORT_BURST_PARTICLES,
                            1.0D, 1.0D, 1.0D, 0.1D);
                }
                case RELATIVISTIC -> {
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                            200, 3));
                    target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200, 3));
                }
                case BEAM -> {
                    shootBeam(level, target, definition, base);
                }
                case PROJECTILE, INCENDIARY, EXPLOSIVE, PLASMA ->
                        spawnProjectile(level, target, definition, resources.ammo(), base);
            }
        }
    }

    private void shootBeam(ServerLevel level, LivingEntity intendedTarget,
            TurretDefinition definition, TurretBaseBlockEntity base) {
        Vec3 intendedEnd = intendedTarget.getEyePosition();
        Vec3 start = muzzleOrigin(intendedEnd);
        Vec3 direct = intendedEnd.subtract(start);
        double distance = direct.length();
        double deviationModifier = 0.3D
                + distance * 0.2D / Math.max(1, definition.baseRange());
        // Legacy 1.12 ray scatter uses the full accuracy deviation (30 for the
        // laser), not the /20 projectile inaccuracy - the beam fans out
        // noticeably per scatter upgrade.
        double spread = distance * 0.003D * deviationModifier
                * TurretUpgradeRules.accuracyDeviation(definition,
                        base.upgradeLevel(ModItems.UPGRADE_ACCURACY.value()),
                        base.upgradeLevel(ModItems.UPGRADE_SCATTER_SHOT.value()));
        Vec3 end = intendedEnd.add(
                level.random.nextGaussian() * spread,
                level.random.nextGaussian() * spread,
                level.random.nextGaussian() * spread);

        HitResult blockHit = level.clip(new net.minecraft.world.level.ClipContext(
                start, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, intendedTarget));
        Vec3 endpoint = blockHit.getType() == HitResult.Type.BLOCK
                ? blockHit.getLocation() : end;
        double nearestDistance = start.distanceToSqr(endpoint);
        LivingEntity hitEntity = null;
        Vec3 entityHitPoint = null;
        AABB traceBounds = new AABB(start, endpoint).inflate(1.0D);
        for (LivingEntity candidate : level.getEntitiesOfClass(
                LivingEntity.class, traceBounds,
                entity -> entity.isAlive() && base.mayDamage(entity))) {
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
                    * SpecialTurretRules.beamDamageMultiplier(
                            definition, hitEntity.getArmorValue());
            float damage = TurretAddonRules.amplifiedDamage(definition,
                    baseDamage, hitEntity.getHealth(), base.damageAmpLevel());
            hitEntity.hurt(TurretDamageSource.create(level, holder, null,
                    base.attackContext()), damage);
            hitEntity.invulnerableTime = 0;
            if (aliveBefore && !hitEntity.isAlive()) {
                base.recordKill(hitEntity);
            }
            SoundEvent impact = ModSounds.rayImpactFor(definition);
            if (impact != null) {
                level.playSound(null, hitEntity.blockPosition(), impact,
                        SoundSource.AMBIENT, ModServerConfig.turretSoundVolume(),
                        0.5F + level.random.nextFloat());
            }
        } else if (definition == TurretDefinition.RAIL_GUN
                && ModServerConfig.railgunDestroysBlocks()
                && blockHit instanceof net.minecraft.world.phys.BlockHitResult hit) {
            BlockState hitState = level.getBlockState(hit.getBlockPos());
            float hardness = hitState.getDestroySpeed(level, hit.getBlockPos());
            if (SpecialTurretRules.railgunCanDestroyBlock(
                    hardness, ModServerConfig.railgunDestroysBlocks())) {
                level.destroyBlock(hit.getBlockPos(), false);
            }
        }
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(worldPosition),
                new BeamEffectPayload(start, endpoint,
                        SpecialTurretRules.beamColor(definition),
                        TurretVisualRules.beamAlpha(definition),
                        TurretVisualRules.beamDurationTicks(definition)));
    }

    private void spawnProjectile(ServerLevel level, LivingEntity target,
            TurretDefinition definition, ItemStack consumedAmmo,
            TurretBaseBlockEntity base) {
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
        TurretProjectileEntity projectile = TurretProjectileEntity.create(level, kind,
                base.getBlockPos(), target, definition, base.damageAmpLevel(),
                base.attackContext(), consumedAmmo);
        Vec3 targetPosition = target.getEyePosition();
        Vec3 origin = muzzleOrigin(targetPosition);
        Vec3 delta = targetPosition.subtract(origin);
        projectile.setPos(origin.x, origin.y, origin.z);
        float speed = kind == ProjectileKind.ROCKET ? 0.24F
                : kind.gravity() > 0.0D ? 1.6F : 3.0F;
        float inaccuracy = base.projectileInaccuracy(definition);
        projectile.shoot(delta.x, delta.y, delta.z, speed, inaccuracy);
        level.addFreshEntity(projectile);
    }

    private Vec3 muzzleOrigin(Vec3 targetPosition) {
        Vec3 center = Vec3.atCenterOf(worldPosition);
        Vec3 direction = targetPosition.subtract(center);
        if (direction.lengthSqr() < 1.0E-7D) {
            return center;
        }
        return center.add(direction.normalize().scale(MUZZLE_CLEARANCE));
    }

    private void updateConcealment(boolean enabled, boolean hasTarget) {
        if (!enabled || hasTarget) {
            idleTicks = 0;
            setConcealed(false);
            return;
        }
        idleTicks = Math.min(TurretAddonRules.CONCEAL_DELAY, idleTicks + 1);
        if (idleTicks >= TurretAddonRules.CONCEAL_DELAY) {
            setConcealed(true);
        }
    }

    private void setConcealed(boolean concealed) {
        if (level == null || !(getBlockState().getBlock() instanceof TurretHeadBlock)
                || getBlockState().getValue(TurretHeadBlock.CONCEALED) == concealed) {
            return;
        }
        level.setBlock(worldPosition,
                getBlockState().setValue(TurretHeadBlock.CONCEALED, concealed), 3);
        if (!level.isClientSide) {
            level.playSound(null, worldPosition,
                    concealed ? ModSounds.TURRET_RETRACT.value()
                            : ModSounds.TURRET_DEPLOY.value(),
                    SoundSource.BLOCKS, ModServerConfig.turretSoundVolume(),
                    0.5F + level.random.nextFloat());
        }
    }

    public boolean concealed() {
        return getBlockState().getBlock() instanceof TurretHeadBlock
                && getBlockState().getValue(TurretHeadBlock.CONCEALED);
    }

    private void updateAim(LivingEntity target) {
        Vec3 delta = target.getEyePosition().subtract(Vec3.atCenterOf(worldPosition));
        aimYaw = (float) (Math.atan2(delta.z, delta.x) * (180.0D / Math.PI)) - 90.0F;
        aimPitch = (float) -(Math.atan2(delta.y,
                Math.sqrt(delta.x * delta.x + delta.z * delta.z)) * (180.0D / Math.PI));
    }

    private void setTarget(@Nullable LivingEntity target) {
        int newTarget = target == null ? -1 : target.getId();
        if (targetEntityId != newTarget) {
            targetEntityId = newTarget;
            sync();
        }
    }

    public TurretDefinition definition() {
        return getBlockState().getBlock() instanceof TurretHeadBlock block
                ? block.definition() : TurretDefinition.DISPOSABLE;
    }

    private void sync() {
        setChanged();
        // The block state does not change here, so vanilla sendBlockUpdated
        // never pushes the BE update packet - broadcast the client data
        // explicitly (aim target, priority profile, cooldown).
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getServer().getPlayerList().broadcastAll(
                    ClientboundBlockEntityDataPacket.create(this),
                    serverLevel.dimension());
        }
    }

    public float aimYaw() { return aimYaw; }
    public float aimPitch() { return aimPitch; }

    public TargetPriorityProfile priorityProfile() {
        return priorityProfile;
    }

    public void setPriorityProfile(TargetPriorityProfile profile) {
        if (profile == null || profile.equals(priorityProfile)) {
            return;
        }
        priorityProfile = profile;
        sync();
    }

    public boolean targets(LivingEntity entity) {
        return targetEntityId == entity.getId();
    }

    public static boolean shouldScan(long gameTime, BlockPos pos) {
        return Math.floorMod(gameTime + pos.asLong(),
                ModServerConfig.targetSearchTicks()) == 0;
    }

    public void applyNetworkAim(float yaw, float pitch, int targetId) {
        aimYaw = yaw;
        aimPitch = pitch;
        targetEntityId = targetId;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("data_version", 2);
        tag.putInt("cooldown", cooldown);
        tag.putFloat("aim_yaw", aimYaw);
        tag.putFloat("aim_pitch", aimPitch);
        savePriorityProfile(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        cooldown = Math.max(0, tag.getInt("cooldown"));
        aimYaw = tag.getFloat("aim_yaw");
        aimPitch = tag.getFloat("aim_pitch");
        priorityProfile = loadPriorityProfile(tag);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("target", targetEntityId);
        tag.putFloat("aim_yaw", aimYaw);
        tag.putFloat("aim_pitch", aimPitch);
        savePriorityProfile(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        targetEntityId = tag.getInt("target");
        aimYaw = tag.getFloat("aim_yaw");
        aimPitch = tag.getFloat("aim_pitch");
        priorityProfile = loadPriorityProfile(tag);
    }

    private void savePriorityProfile(CompoundTag tag) {
        tag.putInt("priority_max_health", priorityProfile.maximumHealth());
        tag.putInt("priority_missing_health", priorityProfile.missingHealth());
        tag.putInt("priority_distance", priorityProfile.distance());
        tag.putInt("priority_armor", priorityProfile.armor());
        tag.putInt("priority_player", priorityProfile.player());
    }

    private TargetPriorityProfile loadPriorityProfile(CompoundTag tag) {
        if (!tag.contains("priority_max_health")) {
            return TargetPriorityProfile.defaults(definition());
        }
        return new TargetPriorityProfile(
                tag.getInt("priority_max_health"),
                tag.getInt("priority_missing_health"),
                tag.getInt("priority_distance"),
                tag.getInt("priority_armor"),
                tag.getInt("priority_player"));
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

}
