package omtteam.openmodularturrets.registration;

import omtteam.openmodularturrets.OpenModularTurrets;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.EntityType;

public final class ModTags {
    private ModTags() {
    }

    public static final class Items {
        public static final TagKey<Item> AMMUNITION = create("ammo/all");
        public static final TagKey<Item> BULLETS = create("ammo/machine_gun");
        public static final TagKey<Item> GRENADES = create("ammo/grenade");
        public static final TagKey<Item> ROCKETS = create("ammo/rocket");
        public static final TagKey<Item> SLUGS = create("ammo/rail_gun");
        public static final TagKey<Item> INCENDIARY_AMMO = create("ammo/incendiary");
        public static final TagKey<Item> POTATO_AMMO = create("ammo/potato");
        public static final TagKey<Item> DISPOSABLE_AMMO = create("ammo/disposable");
        public static final TagKey<Item> ADDONS = create("addons");
        public static final TagKey<Item> UPGRADES = create("upgrades");

        private Items() {
        }

        private static TagKey<Item> create(String path) {
            return TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID, path));
        }
    }

    public static final class EntityTypes {
        public static final TagKey<EntityType<?>> TARGET_BLACKLIST = TagKey.create(
                Registries.ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath(
                        OpenModularTurrets.MOD_ID, "target_blacklist"));

        private EntityTypes() {
        }
    }
}
