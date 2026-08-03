package omtteam.openmodularturrets.item;

import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.data.MemoryCardProfile;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.registration.ModDataComponents;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class MemoryCardItem extends Item {
    public MemoryCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof TurretBaseBlockEntity base)) {
            if (!player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            clearProfile(context.getItemInHand(), context.getLevel(), player);
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        if (context.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        ItemStack stack = context.getItemInHand();
        if (player.isShiftKeyDown()) {
            if (!base.accessFor(player).allows(AccessLevel.VIEW)) {
                player.displayClientMessage(
                        Component.translatable("message.openmodularturrets.access_denied"), false);
                return InteractionResult.FAIL;
            }
            stack.set(ModDataComponents.MEMORY_CARD_PROFILE.value(), base.createProfile());
            player.displayClientMessage(
                    Component.translatable("message.openmodularturrets.memory_card.saved"), false);
            return InteractionResult.SUCCESS;
        }

        MemoryCardProfile profile = stack.get(ModDataComponents.MEMORY_CARD_PROFILE.value());
        if (profile == null) {
            player.displayClientMessage(
                    Component.translatable("message.openmodularturrets.memory_card.empty"), false);
            return InteractionResult.FAIL;
        }
        if (!base.applyProfile(player, profile)) {
            player.displayClientMessage(
                    Component.translatable("message.openmodularturrets.access_denied"), false);
            return InteractionResult.FAIL;
        }
        player.displayClientMessage(
                Component.translatable("message.openmodularturrets.memory_card.loaded"), false);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        clearProfile(stack, level, player);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
            List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        // Legacy 1.12 memory-card card: three usage hints, then the stored
        // profile when present (range, mode, multi-targeting, attack flags).
        tooltipComponents.add(Component.translatable(
                "tooltip.openmodularturrets.memory_card.desc1"));
        tooltipComponents.add(Component.translatable(
                "tooltip.openmodularturrets.memory_card.desc2"));
        tooltipComponents.add(Component.translatable(
                "tooltip.openmodularturrets.memory_card.desc3"));
        MemoryCardProfile profile = stack.get(ModDataComponents.MEMORY_CARD_PROFILE.value());
        if (profile == null) {
            tooltipComponents.add(Component.translatable(
                    "tooltip.openmodularturrets.memory_card.empty").withStyle(ChatFormatting.GRAY));
        } else {
            tooltipComponents.add(Component.translatable(
                    "tooltip.openmodularturrets.memory_card.range", profile.range())
                    .withStyle(ChatFormatting.GOLD));
            tooltipComponents.add(Component.translatable(
                    "tooltip.openmodularturrets.memory_card.mode",
                    Component.translatable("gui.openmodularturrets.mode."
                            + profile.mode().name().toLowerCase(Locale.ROOT)))
                    .withStyle(ChatFormatting.AQUA));
            tooltipComponents.add(Component.translatable(
                    "tooltip.openmodularturrets.memory_card.multi_targeting",
                    yesNo(profile.multiTargeting())).withStyle(ChatFormatting.AQUA));
            tooltipComponents.add(Component.translatable(
                    "tooltip.openmodularturrets.memory_card.attack_hostile",
                    yesNo(profile.attackHostile())).withStyle(ChatFormatting.AQUA));
            tooltipComponents.add(Component.translatable(
                    "tooltip.openmodularturrets.memory_card.attack_neutral",
                    yesNo(profile.attackNeutral())).withStyle(ChatFormatting.AQUA));
            tooltipComponents.add(Component.translatable(
                    "tooltip.openmodularturrets.memory_card.attack_players",
                    yesNo(profile.attackPlayers())).withStyle(ChatFormatting.AQUA));
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    private static Component yesNo(boolean value) {
        return Component.translatable(value
                ? "tooltip.openmodularturrets.yes" : "tooltip.openmodularturrets.no");
    }

    private static void clearProfile(ItemStack stack, Level level, Player player) {
        if (level.isClientSide) {
            return;
        }
        stack.remove(ModDataComponents.MEMORY_CARD_PROFILE.value());
        player.displayClientMessage(
                Component.translatable("message.openmodularturrets.memory_card.cleared"), false);
    }
}
