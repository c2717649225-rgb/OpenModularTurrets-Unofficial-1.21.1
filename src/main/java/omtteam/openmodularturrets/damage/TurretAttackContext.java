package omtteam.openmodularturrets.damage;

import net.minecraft.core.BlockPos;

public record TurretAttackContext(BlockPos sourceBasePos, int fakeDropsLevel,
        boolean suppressLoot) {
    public TurretAttackContext {
        sourceBasePos = sourceBasePos.immutable();
        fakeDropsLevel = Math.clamp(fakeDropsLevel, -1, 3);
    }
}
