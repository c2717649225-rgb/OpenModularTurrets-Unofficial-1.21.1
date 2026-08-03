package omtteam.openmodularturrets.registration;

import java.util.List;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.entity.ProjectileKind;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, OpenModularTurrets.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> AMPED = register("amped");
    public static final DeferredHolder<SoundEvent, SoundEvent> BULLET_HIT =
            register("bullet_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> DISPOSABLE =
            register("disposable");
    public static final DeferredHolder<SoundEvent, SoundEvent> GRENADE = register("grenade");
    public static final DeferredHolder<SoundEvent, SoundEvent> INCENDIARY =
            register("incendiary");
    public static final DeferredHolder<SoundEvent, SoundEvent> LASER = register("laser");
    public static final DeferredHolder<SoundEvent, SoundEvent> LASER_HIT =
            register("laser_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> MACHINE_GUN =
            register("machine_gun");
    public static final DeferredHolder<SoundEvent, SoundEvent> PLASMA_LAUNCH =
            register("plasma_launch");
    public static final DeferredHolder<SoundEvent, SoundEvent> POTATO = register("potato");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAIL_GUN =
            register("rail_gun");
    public static final DeferredHolder<SoundEvent, SoundEvent> RAIL_GUN_HIT =
            register("rail_gun_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> RELATIVISTIC =
            register("relativistic");
    public static final DeferredHolder<SoundEvent, SoundEvent> ROCKET = register("rocket");
    public static final DeferredHolder<SoundEvent, SoundEvent> TELEPORT =
            register("teleport");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURRET_DEPLOY =
            register("turret_deploy");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURRET_RETRACT =
            register("turret_retract");
    public static final DeferredHolder<SoundEvent, SoundEvent> WARNING = register("warning");

    public static final List<DeferredHolder<SoundEvent, SoundEvent>> ALL = List.of(
            AMPED, BULLET_HIT, DISPOSABLE, GRENADE, INCENDIARY, LASER, LASER_HIT,
            MACHINE_GUN, PLASMA_LAUNCH, POTATO, RAIL_GUN, RAIL_GUN_HIT,
            RELATIVISTIC, ROCKET, TELEPORT, TURRET_DEPLOY, TURRET_RETRACT, WARNING);

    private ModSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String id) {
        ResourceLocation location =
                ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID, id);
        return SOUND_EVENTS.register(id, () -> SoundEvent.createVariableRangeEvent(location));
    }

    public static SoundEvent launchFor(TurretDefinition definition) {
        return switch (definition) {
            case DISPOSABLE -> DISPOSABLE.value();
            case POTATO -> POTATO.value();
            case MACHINE_GUN -> MACHINE_GUN.value();
            case INCENDIARY -> INCENDIARY.value();
            case GRENADE -> GRENADE.value();
            case RELATIVISTIC -> RELATIVISTIC.value();
            case ROCKET -> ROCKET.value();
            case TELEPORTER -> TELEPORT.value();
            case LASER -> LASER.value();
            case RAIL_GUN -> RAIL_GUN.value();
            case PLASMA -> PLASMA_LAUNCH.value();
        };
    }

    @Nullable
    public static SoundEvent impactFor(ProjectileKind kind) {
        return kind == ProjectileKind.BULLET ? BULLET_HIT.value() : null;
    }

    @Nullable
    public static SoundEvent rayImpactFor(TurretDefinition definition) {
        return switch (definition) {
            case LASER -> LASER_HIT.value();
            case RAIL_GUN -> RAIL_GUN_HIT.value();
            default -> null;
        };
    }

    public static void register(IEventBus modEventBus) {
        SOUND_EVENTS.register(modEventBus);
    }
}
