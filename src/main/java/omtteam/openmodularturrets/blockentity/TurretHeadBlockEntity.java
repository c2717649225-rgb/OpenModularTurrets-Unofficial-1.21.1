package omtteam.openmodularturrets.blockentity;

import java.util.Optional;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.data.TargetPriorityProfile;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretAddonRules;
import omtteam.openmodularturrets.network.ModNetwork;
import omtteam.openmodularturrets.network.TurretAimPayload;
import omtteam.openmodularturrets.registration.ModBlockEntities;
import omtteam.openmodularturrets.registration.ModSounds;
import omtteam.openmodularturrets.service.TurretCombatService;
import omtteam.openmodularturrets.service.TurretTargetingService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

public final class TurretHeadBlockEntity extends BlockEntity {
    private int cooldown;
    private int targetEntityId = -1;
    private float aimYaw;
    private float aimPitch;
    private int idleTicks;
    private TargetPriorityProfile priorityProfile;
    @Nullable
    private Direction cachedBaseDirection;
    private long nextBaseValidationTick;
    private final TurretTargetingService targetingService = new TurretTargetingService();

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
        long gameTime = level.getGameTime();
        boolean staggeredScan = shouldScan(gameTime, pos);
        boolean forceLineOfSight = turret.cooldown <= 0;
        int range = base.range();
        if (turret.targetingService.validateCurrentTarget(serverLevel, pos, base,
                range, definition, target, gameTime, forceLineOfSight) == null) {
            target = null;
            turret.setTarget(null);
            if (staggeredScan) {
                target = turret.targetingService.findTarget(serverLevel, pos, base,
                        range, definition, turret.priorityProfile, gameTime);
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
            TurretCombatService.CombatResult combat = TurretCombatService.executeVolley(
                    serverLevel, pos, target, definition, consumed.get(), base);
            if (base.damageAmpLevel() > 0 && definition.damageAmpFraction() > 0.0F) {
                serverLevel.playSound(null, pos, ModSounds.AMPED.value(),
                        SoundSource.BLOCKS, ModServerConfig.turretSoundVolume(),
                        0.5F + serverLevel.random.nextFloat());
            }
            TurretDefinition.LaunchSound fixedLaunch = definition.launchSound();
            serverLevel.playSound(null, pos, ModSounds.launchFor(definition),
                    SoundSource.BLOCKS,
                    fixedLaunch != null ? fixedLaunch.volume()
                            : ModServerConfig.turretSoundVolume(),
                    fixedLaunch != null ? fixedLaunch.pitch()
                            : 0.5F + serverLevel.random.nextFloat());
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(pos),
                    new TurretAimPayload(pos, turret.aimYaw, turret.aimPitch,
                            target.getId()));
            turret.cooldown = base.adjustedFireInterval(definition);
            // Aim is sent through the lightweight payload above.  The full
            // BlockEntity update remains reserved for durable target changes.
            turret.markForSave();
        }
    }

    private Optional<TurretBaseBlockEntity> findBase() {
        if (level == null) {
            return Optional.empty();
        }
        long gameTime = level.getGameTime();
        TurretDefinition definition = definition();
        if (cachedBaseDirection != null) {
            if (level.getBlockEntity(worldPosition.relative(cachedBaseDirection))
                    instanceof TurretBaseBlockEntity base) {
                if (gameTime >= nextBaseValidationTick
                        && !base.canSupportTurret(worldPosition, definition)) {
                    invalidateBaseCache();
                } else {
                    if (gameTime >= nextBaseValidationTick) {
                        nextBaseValidationTick = gameTime + 20L;
                    }
                    return Optional.of(base);
                }
            } else {
                invalidateBaseCache();
            }
        }
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(direction))
                    instanceof TurretBaseBlockEntity base
                    && base.canSupportTurret(worldPosition, definition)) {
                cachedBaseDirection = direction;
                nextBaseValidationTick = gameTime + 20L;
                return Optional.of(base);
            }
        }
        return Optional.empty();
    }

    public void invalidateBaseCache() {
        cachedBaseDirection = null;
        nextBaseValidationTick = Long.MIN_VALUE;
    }

    /**
     * Resolves the attachment direction for the client renderer without
     * repeating a six-face scan every frame.
     */
    public Direction baseDirectionForRender() {
        if (level == null) {
            return Direction.DOWN;
        }
        if (cachedBaseDirection != null) {
            if (level.getBlockEntity(worldPosition.relative(cachedBaseDirection))
                    instanceof TurretBaseBlockEntity) {
                return cachedBaseDirection;
            }
            cachedBaseDirection = null;
        }
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(direction))
                    instanceof TurretBaseBlockEntity) {
                cachedBaseDirection = direction;
                return direction;
            }
        }
        return Direction.DOWN;
    }

    @Nullable
    public LivingEntity currentTarget() {
        if (level == null || targetEntityId < 0) {
            return null;
        }
        return level.getEntity(targetEntityId) instanceof LivingEntity living ? living : null;
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
            targetingService.onTargetChanged(newTarget);
            sync();
        }
    }

    public TurretDefinition definition() {
        return getBlockState().getBlock() instanceof TurretHeadBlock block
                ? block.definition() : TurretDefinition.DISPOSABLE;
    }

    private void sync() {
        markForSave();
        // Target changes and priority edits need an explicit tracking update;
        // per-shot aim remains in TurretAimPayload.
        if (level instanceof ServerLevel) {
            ModNetwork.sendBlockEntityUpdateToTracking(this);
        }
    }

    private void markForSave() {
        setChanged();
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
        return TurretTargetingService.shouldScan(gameTime, pos);
    }

    public void applyNetworkAim(float yaw, float pitch, int targetId) {
        aimYaw = yaw;
        aimPitch = pitch;
        targetEntityId = targetId;
        targetingService.clearLineOfSightCache();
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
