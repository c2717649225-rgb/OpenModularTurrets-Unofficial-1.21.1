package omtteam.openmodularturrets.damage;

import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

public final class TurretDamageSource extends DamageSource {
    private static final GameProfile FAKE_PLAYER_PROFILE = new GameProfile(
            UUID.fromString("c5c97afa-fc98-44ab-944a-e67681a66b19"),
            "openmodularturrets:fakeplayer");
    /** Looting prototypes indexed by fake-drops level; built lazily on the
     *  server thread once registries are live, then copied per hit. */
    private static final ItemStack[] LOOTING_SWORD_PROTOTYPES = new ItemStack[4];

    private final TurretAttackContext context;

    public TurretDamageSource(Holder<DamageType> type, @Nullable Entity directEntity,
            @Nullable Entity causingEntity, TurretAttackContext context) {
        super(type, directEntity, causingEntity);
        this.context = context;
    }

    public static TurretDamageSource create(ServerLevel level, Holder<DamageType> type,
            @Nullable Entity directEntity, TurretAttackContext context) {
        return new TurretDamageSource(type, directEntity,
                prepareFakePlayer(level, context.fakeDropsLevel()), context);
    }

    @Nullable
    private static FakePlayer prepareFakePlayer(ServerLevel level, int fakeDropsLevel) {
        if (fakeDropsLevel < 0) {
            return null;
        }
        FakePlayer player = FakePlayerFactory.get(level, FAKE_PLAYER_PROFILE);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                lootingSword(level, fakeDropsLevel));
        var luck = player.getAttribute(Attributes.LUCK);
        if (luck == null) {
            throw new IllegalStateException("OMT FakePlayer is missing the luck attribute");
        }
        luck.setBaseValue(fakeDropsLevel);
        return player;
    }

    private static ItemStack lootingSword(ServerLevel level, int lootLevel) {
        int boundedLevel = Math.min(lootLevel, LOOTING_SWORD_PROTOTYPES.length - 1);
        ItemStack prototype = LOOTING_SWORD_PROTOTYPES[boundedLevel];
        if (prototype == null) {
            prototype = new ItemStack(Items.DIAMOND_SWORD);
            if (boundedLevel > 0) {
                var looting = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                        .getHolderOrThrow(Enchantments.LOOTING);
                prototype.enchant(looting, boundedLevel);
            }
            LOOTING_SWORD_PROTOTYPES[boundedLevel] = prototype;
        }
        return prototype.copy();
    }

    public TurretAttackContext context() {
        return context;
    }
}
