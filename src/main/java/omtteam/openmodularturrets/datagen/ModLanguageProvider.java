package omtteam.openmodularturrets.datagen;

import java.util.Locale;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.registration.ModBlocks;
import omtteam.openmodularturrets.registration.ModItems;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public final class ModLanguageProvider extends LanguageProvider {
    private final String locale;

    public ModLanguageProvider(PackOutput output, String locale) {
        super(output, OpenModularTurrets.MOD_ID, locale);
        this.locale = locale;
    }

    @Override
    protected void addTranslations() {
        boolean chinese = locale.equals("zh_cn");
        add("container.openmodularturrets.turret_base",
                chinese ? "模块化炮塔基座" : "Modular Turret Base");
        add("container.openmodularturrets.inventory_expander",
                chinese ? "炮塔弹药扩展器" : "Turret Ammunition Expander");
        addBaseGuiTranslations(chinese);
        addTrustGuiTranslations(chinese);
        addLegacyTooltipTranslations(chinese);

        add("message.openmodularturrets.target_warning",
                chinese ? "警告：你已进入模块化炮塔的警戒范围！"
                        : "Warning: you have entered a modular turret's targeting area!");
        add("message.openmodularturrets.access_denied",
                chinese ? "你没有操作该基座的权限" : "You do not have access to this base");
        add("message.openmodularturrets.trust.player_not_found",
                chinese ? "找不到该玩家" : "Player not found");
        add("message.openmodularturrets.trust.list_full",
                chinese ? "信任玩家列表已满" : "The trusted-player list is full");
        add("message.openmodularturrets.trust.updated",
                chinese ? "信任玩家设置已更新" : "Trusted-player settings updated");
        add("message.openmodularturrets.trust.invalid_name",
                chinese ? "请输入有效的玩家名称" : "Enter a valid player name");
        add("message.openmodularturrets.memory_card.saved",
                chinese ? "基座设置已写入记忆卡" : "Base settings saved to memory card");
        add("message.openmodularturrets.memory_card.empty",
                chinese ? "记忆卡中没有设置" : "The memory card is empty");
        add("message.openmodularturrets.memory_card.loaded",
                chinese ? "已从记忆卡载入基座设置" : "Base settings loaded from memory card");
        add("message.openmodularturrets.memory_card.cleared",
                chinese ? "已清除记忆卡中的基座设置" : "Cleared base settings from memory card");
        add("tooltip.openmodularturrets.memory_card.empty",
                chinese ? "未存储基座设置" : "No base settings stored");
        add("tooltip.openmodularturrets.memory_card.desc1", chinese ? "潜行右键基座保存数据" : "Shift-RightClick base stores data");
        add("tooltip.openmodularturrets.memory_card.desc2", chinese ? "潜行右键其他位置清除数据" : "Shift-RightClick anywhere else to clear");
        add("tooltip.openmodularturrets.memory_card.desc3", chinese ? "右键基座从卡片复制数据" : "RightClick base copies data from card");
        add("tooltip.openmodularturrets.memory_card.range",
                chinese ? "：%s" : ": %s");
        add("tooltip.openmodularturrets.memory_card.stored",
                chinese ? "已存储基座设置（范围：%s）" : "Base settings stored (range: %s)");
        add("tooltip.openmodularturrets.memory_card.mode",
                chinese ? "模式：%s" : "Mode: %s");
        add("tooltip.openmodularturrets.memory_card.multi_targeting",
                chinese ? "多目标：%s" : "Multi-targeting: %s");
        add("tooltip.openmodularturrets.memory_card.attack_hostile",
                chinese ? "攻击敌对生物：%s" : "Attacks hostiles: %s");
        add("tooltip.openmodularturrets.memory_card.attack_neutral",
                chinese ? "攻击中立生物：%s" : "Attacks neutrals: %s");
        add("tooltip.openmodularturrets.memory_card.attack_players",
                chinese ? "攻击玩家：%s" : "Attacks players: %s");
        add("tooltip.openmodularturrets.memory_card.use",
                chinese ? "潜行点击基座保存；普通点击基座载入"
                        : "Sneak-use a base to save; use a base to load");
        add("tooltip.openmodularturrets.memory_card.clear",
                chinese ? "潜行对空中或非基座方块使用以清除"
                        : "Sneak-use in air or on a non-base block to clear");
        add("itemGroup.openmodularturrets",
                chinese ? "开放式炮台：非官方版" : "OpenModularTurrets-Unofficial");

        addDeathMessages(chinese);
        addSoundSubtitles(chinese);

        ModBlocks.ALL.forEach(block -> {
            String id = block.getId().getPath();
            add("block." + OpenModularTurrets.MOD_ID + "." + id, blockName(id, chinese));
        });
        ModItems.REGULAR_ITEMS.forEach(item -> {
            String id = item.getId().getPath();
            add("item." + OpenModularTurrets.MOD_ID + "." + id, itemName(id, chinese));
        });
    }

    private void addLegacyTooltipTranslations(boolean chinese) {
        add("tooltip.openmodularturrets.hold_shift", chinese ? "[按 Shift 查看详细信息]" : "[Hold Shift for details]");
        add("tooltip.openmodularturrets.base.title", chinese ? "炮塔基座" : "Turret Base");
        add("tooltip.openmodularturrets.turret.title", chinese ? "炮塔" : "Turret");
        add("tooltip.openmodularturrets.inventory_expander.title", chinese ? "基座物品栏扩展器" : "Base Inventory Expander");
        add("tooltip.openmodularturrets.power_expander.title", chinese ? "基座能量扩展器" : "Base Energy Expander");
        add("tooltip.openmodularturrets.manual_charger.title", chinese ? "基础炮塔基座摇柄" : "Basic Turret Base Lever");
        add("tooltip.openmodularturrets.loot_deleter.title", chinese ? "基座附加组件 - 战利品删除器" : "Turret Base Addon - Loot Deleter");
        add("tooltip.openmodularturrets.loot_deleter.detail1", chinese ? "移除附着基座击杀的" : "Removes all dropped loot from");
        add("tooltip.openmodularturrets.loot_deleter.detail2", chinese ? "敌人掉落的全部战利品" : "enemies killed by attached base");
        add("tooltip.openmodularturrets.flavour.loot_deleter", chinese ? "只是一点烟雾" : "Just a bit of smoke.");
        add("tooltip.openmodularturrets.addon.title", chinese ? "炮塔附加组件" : "Turret Addon");
        add("tooltip.openmodularturrets.upgrade.title", chinese ? "炮塔升级" : "Turret Upgrade");
        add("tooltip.openmodularturrets.ammo.title", chinese ? "炮塔弹药" : "Turret Ammunition");
        add("tooltip.openmodularturrets.component.title", chinese ? "炮塔组件" : "Turret Component");
        add("tooltip.openmodularturrets.tier", chinese ? "等级：%s" : "Tier: %s");
        add("tooltip.openmodularturrets.energy_capacity", chinese ? "能量容量：%s FE" : "Energy capacity: %s FE");
        add("tooltip.openmodularturrets.energy_input", chinese ? "最大输入：%s FE/t" : "Maximum input: %s FE/t");
        add("tooltip.openmodularturrets.turret_limit", chinese ? "炮塔上限：%s" : "Turret limit: %s");
        add("tooltip.openmodularturrets.required_base", chinese ? "所需基座等级：%s" : "Required base tier: %s");
        add("tooltip.openmodularturrets.turret_simultaneous_limit", chinese ? "同屏上限：%s" : "Max simultaneous: %s");
        add("tooltip.openmodularturrets.range_stat", chinese ? "基础范围：%s 格" : "Base range: %s blocks");
        add("tooltip.openmodularturrets.damage_stat", chinese ? "伤害：%s" : "Damage: %s");
        add("tooltip.openmodularturrets.fire_rate_stat", chinese ? "射速：%s 发/秒" : "Fire rate: %s shots/s");
        add("tooltip.openmodularturrets.energy_per_shot", chinese ? "每发能量：%s FE" : "Energy per shot: %s FE");
        add("tooltip.openmodularturrets.ammo_required", chinese ? "需要弹药：%s" : "Requires ammunition: %s");
        add("tooltip.openmodularturrets.extra_slots", chinese ? "新增槽位：%s" : "Added slots: %s");
        add("tooltip.openmodularturrets.stack_limit",
                chinese ? "每格堆叠上限：%s" : "Maximum stack size per slot: %s");
        add("tooltip.openmodularturrets.extra_energy", chinese ? "额外容量：%s FE" : "Extra capacity: %s FE");
        add("tooltip.openmodularturrets.manual_charger.energy", chinese ? "每次摇动：%s FE" : "Energy per turn: %s FE");
        add("tooltip.openmodularturrets.yes", chinese ? "是" : "Yes");
        add("tooltip.openmodularturrets.no", chinese ? "否" : "No");
        add("tooltip.openmodularturrets.component.detail", chinese ? "用于制造对应等级的炮塔" : "Used to craft the matching turret tier");
        add("tooltip.openmodularturrets.io_bus.detail", chinese ? "用于炮塔 I/O 与高级配方" : "Used by turret I/O and advanced recipes");
        add("tooltip.openmodularturrets.addon.concealer", chinese ? "允许炮塔伪装" : "Allows turret camouflage");
        add("tooltip.openmodularturrets.addon.damage_amp", chinese ? "按目标当前生命值提高伤害" : "Adds damage based on target current health");
        add("tooltip.openmodularturrets.addon.potentia", chinese ? "允许安装更多附加组件" : "Allows additional addons to be installed");
        add("tooltip.openmodularturrets.addon.recycler", chinese ? "有几率不消耗弹药" : "May preserve ammunition when firing");
        add("tooltip.openmodularturrets.addon.redstone_reactor", chinese ? "消耗红石产生能量" : "Consumes redstone to generate energy");
        add("tooltip.openmodularturrets.addon.serial_port", chinese ? "提供旧版串口兼容接口" : "Provides the legacy serial-port integration point");
        add("tooltip.openmodularturrets.addon.solar_panel", chinese ? "在天空可见时产生能量" : "Generates energy while the sky is visible");
        add("tooltip.openmodularturrets.addon.fake_drops", chinese ? "让击杀生成伪装掉落物" : "Makes kills generate configured fake drops");
        add("tooltip.openmodularturrets.addon.concealer.desc", chinese ? "炮塔没有射击时隐藏炮塔" : "Conceals turrets when they aren't shooting");
        add("tooltip.openmodularturrets.addon.concealer.flavour", chinese ? "DiggyDiggy Hole!" : "DiggyDiggy Hole!");
        add("tooltip.openmodularturrets.addon.damage_amp.desc", chinese ? "每发子弹增加目标最大生命值百分之几的伤害（取决于炮台）" : "Adds a few % of maxHP of target (dep. on turret) as damage per projectile");
        add("tooltip.openmodularturrets.addon.damage_amp.flavour", chinese ? "我们来到这了，巨人" : "Here we are juggernaught.");
        add("tooltip.openmodularturrets.addon.potentia.desc", chinese ? "将管道输入的 Potentia 转换为 RF，并提供内部 Potentia 容量" : "Converts piped Potentia into RF and adds an internal Potentia capacity");
        add("tooltip.openmodularturrets.addon.potentia.flavour", chinese ? "释放潜能" : "Unleash the potential.");
        add("tooltip.openmodularturrets.addon.recycler.desc", chinese ? "每发有几率不消耗弹药，也有几率额外生成弹药" : "Adds a chance for a turret to not use ammo, as well as a chance to generate ammo, per shot");
        add("tooltip.openmodularturrets.addon.recycler.flavour", chinese ? "减少，重用，重申" : "Reduce, reuse, reassert.");
        add("tooltip.openmodularturrets.addon.redstone_reactor.desc", chinese ? "消耗弹药栏中的红石粉产生 RF" : "Generates RF per redstone dust in Ammo inventory");
        add("tooltip.openmodularturrets.addon.redstone_reactor.flavour", chinese ? "升华，精炼" : "Sublimation, refined.");
        add("tooltip.openmodularturrets.addon.serial_port.desc", chinese ? "启用 OpenComputers 与 ComputerCraft 兼容" : "Enables OpenComputers and ComputerCraft compatibility");
        add("tooltip.openmodularturrets.addon.serial_port.flavour", chinese ? "谁还需要 OOP?" : "Who needs OOP?");
        add("tooltip.openmodularturrets.addon.solar_panel.desc", chinese ? "在阳光下产生 RF/t" : "Generates RF/t in sunlight");
        add("tooltip.openmodularturrets.addon.solar_panel.flavour", chinese ? "伟大的太阳神!" : "Good Helios!");
        add("tooltip.openmodularturrets.addon.fake_drops.desc", chinese ? "让炮塔击杀的生物像被玩家击杀一样掉落战利品" : "Makes mobs killed by turrets drop loot as if a player killed them");
        add("tooltip.openmodularturrets.addon.fake_drops.flavour", chinese ? "开启炫酷模式!" : "Turn on the swag!");
        add("tooltip.openmodularturrets.addon.solar_panel.value", chinese ? "天空可见时：+%s FE/t" : "Sky visible: +%s FE/t");
        add("tooltip.openmodularturrets.addon.redstone_reactor.value", chinese ? "红石粉 +%s FE，红石块 +%s FE（每 %s tick）" : "Redstone dust +%s FE, redstone block +%s FE (every %s ticks)");
        add("tooltip.openmodularturrets.addon.damage_amp.value", chinese ? "每级：每点目标当前生命 +%s%% 伤害" : "Per level: +%s%% damage per target health point");
        add("tooltip.openmodularturrets.addon.recycler.value", chinese ? "每发：%s%% 概率保留弹药" : "Per shot: %s%% chance to preserve ammo");
        add("tooltip.openmodularturrets.addon.concealer.value", chinese ? "炮塔闲置 %s 秒（%s tick）后隐藏" : "Conceals turret after %s idle seconds (%s ticks)");
        add("tooltip.openmodularturrets.addon.fake_drops.value", chinese ? "伪装掉落等级：最多 %s 级" : "Fake drop level: up to %s");
        add("tooltip.openmodularturrets.upgrade.accuracy.value", chinese ? "每级：散布 -%s%%" : "Per level: spread -%s%%");
        add("tooltip.openmodularturrets.upgrade.efficiency.value", chinese ? "每级：能量消耗 -%s%%" : "Per level: energy cost -%s%%");
        add("tooltip.openmodularturrets.upgrade.fire_rate.value", chinese ? "每级：射速 +%s%%（随炮台）" : "Per level: fire rate +%s%% (per turret)");
        add("tooltip.openmodularturrets.upgrade.range.value", chinese ? "每级：射程 +%s 格" : "Per level: range +%s blocks");
        add("tooltip.openmodularturrets.upgrade.scatter_shot.value", chinese ? "每级：+%s 枚弹体（能量消耗按弹数增加）" : "Per level: +%s projectile (energy cost scales with count)");
        add("tooltip.openmodularturrets.upgrade.stacks", chinese ? "可重叠放置4个,效果叠加." : "Stacks up to 4 levels.");
        add("tooltip.openmodularturrets.upgrade.accuracy", chinese ? "降低散布，提高命中精度" : "Reduces spread and improves accuracy");
        add("tooltip.openmodularturrets.upgrade.efficiency", chinese ? "降低每发能量消耗" : "Reduces energy consumed per shot");
        add("tooltip.openmodularturrets.upgrade.fire_rate", chinese ? "提高射击频率" : "Increases firing rate");
        add("tooltip.openmodularturrets.upgrade.range", chinese ? "提高炮塔最大范围" : "Increases maximum turret range");
        add("tooltip.openmodularturrets.upgrade.scatter_shot", chinese ? "一次发射多枚弹体，额外消耗能量" : "Fires multiple projectiles at additional energy cost");
        add("tooltip.openmodularturrets.upgrade.accuracy.desc", chinese ? "降低散布，提高精准度" : "accuracy per upgrade");
        add("tooltip.openmodularturrets.upgrade.accuracy.flavour", chinese ? "好眼力，狙击手，我来开枪，你负责跑" : "Good eye sniper, I'll shoot, you run");
        add("tooltip.openmodularturrets.upgrade.efficiency.desc", chinese ? "每次升级降低每发 RF 消耗" : "RF use per shot, per upgrade");
        add("tooltip.openmodularturrets.upgrade.efficiency.flavour", chinese ? "我找到了!" : "Eureka!");
        add("tooltip.openmodularturrets.upgrade.fire_rate.desc", chinese ? "每次升级提高射速" : "fire rate per upgrade");
        add("tooltip.openmodularturrets.upgrade.fire_rate.flavour", chinese ? "耗时，耗心" : "Time consumer, time consuming.");
        add("tooltip.openmodularturrets.upgrade.range.desc", chinese ? "每次升级增加方块范围" : "block range per upgrade");
        add("tooltip.openmodularturrets.upgrade.range.flavour", chinese ? "能把叉子递给我吗?" : "Will you pass me the fork?");
        add("tooltip.openmodularturrets.upgrade.scatter_shot.desc", chinese ? "每次射击增加 2 枚弹体，代价是弹药 +2、精度下降、RF 消耗增加" : "Adds 2 projectiles to each shot, at the cost of +2 ammo usage, decr. accuracy and increased RF usage");
        add("tooltip.openmodularturrets.upgrade.scatter_shot.flavour", chinese ? "你只需要射出更多子弹" : "You just need to shoot more bullets.");
        add("tooltip.openmodularturrets.upgrade.turretinfo", chinese ? "放置在炮塔基座中以提升连接炮塔的性能。" : "For effects see tooltips of turrets");
        add("tooltip.openmodularturrets.ammo.ammo_bullet.desc", chinese ? "供机枪炮塔使用的普通子弹" : "A simple bullet for use with the machine gun turret");
        add("tooltip.openmodularturrets.ammo.ammo_grenade.desc", chinese ? "供榴弹发射器使用的好榴弹" : "Nice grenade for the grenade launcher");
        add("tooltip.openmodularturrets.ammo.ammo_rocket.desc", chinese ? "火箭当然是由火箭发射器使用的" : "Rockets are being used by the rocket launcher, obviously!");
        add("tooltip.openmodularturrets.ammo.ammo_ferro_slug.desc", chinese ? "这种高级外观的磁弹供轨道炮使用" : "This advanced looking slug is for the railgun");
        add("tooltip.openmodularturrets.ammo.ammo_blazing_clay.desc", chinese ? "供燃烧炮塔使用的燃烧黏土" : "Blazing clay for the Incendiary Turret");
        add("tooltip.openmodularturrets.ammo.ammo_fake_disposable.desc", chinese ? "内部占位弹药，请勿使用" : "Internal placeholder ammunition; do not use");
        add("tooltip.openmodularturrets.turret.desc", chinese ? "炮塔的头部,放置在炮塔基座上。" : "A turret head, place on a turret base.");
        add("tooltip.openmodularturrets.turret.flavour", chinese ? "普通人的助眠良药" : "Poor man's sleeping aid");
        add("tooltip.openmodularturrets.base.desc", chinese ? "模块化炮塔的核心" : "The core of a modular turret");
        add("tooltip.openmodularturrets.base.flavour", chinese ? "到处都是树……" : "There are trees, everywhere...");
        add("tooltip.openmodularturrets.flavour.base.1", chinese ? "到处都是树……" : "There are trees, everywhere...");
        // Legacy 1.12 sectioned tooltip: coloured "--Section--" headers.
        add("tooltip.openmodularturrets.section.info", chinese ? "--基础信息--" : "--Info--");
        add("tooltip.openmodularturrets.section.energy", chinese ? "--能量信息--" : "--Energy--");
        add("tooltip.openmodularturrets.section.extras", chinese ? "--附加物--" : "--Extras--");
        add("tooltip.openmodularturrets.section.damage", chinese ? "--伤害输出--" : "--Damage Output--");
        add("tooltip.openmodularturrets.label.tier", chinese ? "等级" : "Tier");
        add("tooltip.openmodularturrets.label.range", chinese ? "范围" : "Range (in blocks)");
        add("tooltip.openmodularturrets.label.accuracy", chinese ? "精准度" : "Accuracy");
        add("tooltip.openmodularturrets.label.ammo", chinese ? "弹药类型" : "Ammo Type");
        add("tooltip.openmodularturrets.label.tier_required", chinese ? "需求基座等级" : "Base Minimum Tier");
        add("tooltip.openmodularturrets.label.turret_limit", chinese ? "炮塔上限" : "Turret limit");
        add("tooltip.openmodularturrets.label.rf_max", chinese ? "最大容量" : "Max capacity");
        add("tooltip.openmodularturrets.label.rf_io", chinese ? "最大输入" : "Max IO");
        add("tooltip.openmodularturrets.label.damage_stat", chinese ? "弹射伤害" : "Projectile Damage");
        add("tooltip.openmodularturrets.label.aoe", chinese ? "溅射半径" : "AOE Radius");
        add("tooltip.openmodularturrets.label.fire_rate", chinese ? "射击/秒" : "Shots/s");
        add("tooltip.openmodularturrets.label.energy_stat", chinese ? "每次射击耗能" : "Energy usage per shot");
        add("tooltip.openmodularturrets.health", chinese ? "颗心" : "Hearts");
        add("tooltip.openmodularturrets.accuracy.low", chinese ? "低" : "Low");
        add("tooltip.openmodularturrets.accuracy.medium", chinese ? "中" : "Medium");
        add("tooltip.openmodularturrets.accuracy.high", chinese ? "高" : "High");
        add("tooltip.openmodularturrets.accuracy.exact", chinese ? "精准" : "Pin Point");
        add("tooltip.openmodularturrets.ammo_type.0", chinese ? "圆石/木板" : "See config file, default. cobble/planks");
        add("tooltip.openmodularturrets.ammo_type.1", chinese ? "子弹" : "Bullet");
        add("tooltip.openmodularturrets.ammo_type.2", chinese ? "榴弹" : "Grenade");
        add("tooltip.openmodularturrets.ammo_type.3", chinese ? "追踪火箭" : "Homing Rocket");
        add("tooltip.openmodularturrets.ammo_type.4", chinese ? "无需" : "None");
        add("tooltip.openmodularturrets.ammo_type.5", chinese ? "磁性弹头" : "Ferro-Magnetic Slugs");
        add("tooltip.openmodularturrets.ammo_type.6", chinese ? "马铃薯" : "Potato");
        add("tooltip.openmodularturrets.ammo_type.7", chinese ? "燃烧黏土" : "Blazing Clay");
        add("tooltip.openmodularturrets.ammo_type.air", chinese ? "空气" : "Air");
        add("tooltip.openmodularturrets.base_tier.1", chinese ? "一级炮塔基座" : "Tier 1");
        add("tooltip.openmodularturrets.base_tier.2", chinese ? "二级炮塔基座" : "Tier 2");
        add("tooltip.openmodularturrets.base_tier.3", chinese ? "三级炮塔基座" : "Tier 3");
        add("tooltip.openmodularturrets.base_tier.4", chinese ? "四级炮塔基座" : "Tier 4");
        add("tooltip.openmodularturrets.base_tier.5", chinese ? "五级炮塔基座" : "Tier 5");
        add("tooltip.openmodularturrets.extras.addons.0", chinese ? "无" : "None");
        add("tooltip.openmodularturrets.extras.addons.1", chinese ? "1x附件插槽" : "1x Addon slot");
        add("tooltip.openmodularturrets.extras.addons.2", chinese ? "2x附件插槽" : "2x Addon slots");
        add("tooltip.openmodularturrets.extras.upgrade.0", chinese ? "无" : "None");
        add("tooltip.openmodularturrets.extras.upgrade.1", chinese ? "1x升级组件插槽" : "1x Upgrade slot");
        add("tooltip.openmodularturrets.extras.upgrade.2", chinese ? "2x升级组件插槽" : "2x Upgrade slot");
        // Legacy per-turret flavour footers (1.12 ships these in English).
        add("tooltip.openmodularturrets.flavour.turret.0", chinese ? "穷人的安眠药" : "Poor man's sleeping aid.");
        add("tooltip.openmodularturrets.flavour.turret.1", chinese ? "突突突!!" : "TRRRAA!! TRRAATRRAA!!");
        add("tooltip.openmodularturrets.flavour.turret.2a", chinese ? "另一个例子是" : "Another example is the inductive");
        add("tooltip.openmodularturrets.flavour.turret.2b", chinese ? "马悖论的形式" : "form of the horse paradox.");
        add("tooltip.openmodularturrets.flavour.turret.3", chinese ? "红雾" : "Red mist.");
        add("tooltip.openmodularturrets.flavour.turret.4", chinese ? "来吧，牛顿才不在乎" : "Go ahead, Newton doesn't care.");
        add("tooltip.openmodularturrets.flavour.turret.5", chinese ? "BFG" : "BFG.");
        add("tooltip.openmodularturrets.flavour.turret.6", chinese ? "PVC 杰作" : "A PVC masterpiece.");
        add("tooltip.openmodularturrets.flavour.turret.7", chinese ? "燃烧吧" : "Burn, baby, burn.");
        add("tooltip.openmodularturrets.flavour.turret.8", chinese ? "超越光速" : "Faster than light.");
        add("tooltip.openmodularturrets.flavour.turret.9a", chinese ? "奇异点" : "The singularity");
        add("tooltip.openmodularturrets.flavour.turret.9b", chinese ? "就在眼前" : "is upon us.");
        add("tooltip.openmodularturrets.flavour.turret.plasma", chinese ? "电浆炮已就绪" : "Plasma cannons ready.");
        add("tooltip.openmodularturrets.flavour.base.2", chinese ? "我会吸干你的血" : "I'll draw your blood.");
        add("tooltip.openmodularturrets.flavour.base.3", chinese ? "我说，祝你今天愉快!" : "I said good day sir!");
        add("tooltip.openmodularturrets.flavour.base.4", chinese ? "发动攻击!" : "Press the attack!");
        add("tooltip.openmodularturrets.flavour.base.5a", chinese ? "我好奇这东西" : "I wonder if this thing");
        add("tooltip.openmodularturrets.flavour.base.5b", chinese ? "能不能做滚筒动作" : "can do a barrel-roll...");
        add("tooltip.openmodularturrets.expander.inv.title", chinese ? "基座物品栏扩展器" : "Turret Base Inventory Expander");
        add("tooltip.openmodularturrets.expander.inv.stack", chinese ? "每格只能接受最大堆叠数量为" : "Each slot can only accept a max stack size of");
        add("tooltip.openmodularturrets.expander.power.title", chinese ? "基座能量扩展器" : "Turret Base RF Expander");
        add("tooltip.openmodularturrets.flavour.expander.inv.1", chinese ? "积少成多" : "A little goes a long way.");
        add("tooltip.openmodularturrets.flavour.expander.inv.2", chinese ? "补给!" : "Supplies!");
        add("tooltip.openmodularturrets.flavour.expander.inv.3", chinese ? "毛发，继承者与野兔" : "Hair, heir and hare.");
        add("tooltip.openmodularturrets.flavour.expander.inv.4", chinese ? "可以用作漂浮装置" : "Can be used as a floatation device.");
        add("tooltip.openmodularturrets.flavour.expander.inv.5", chinese ? "非常空间，很多物品栏" : "Very space, much inventory.");
        add("tooltip.openmodularturrets.flavour.expander.power.1", chinese ? "现在带风味了!" : "Now with flavour!");
        add("tooltip.openmodularturrets.flavour.expander.power.2", chinese ? "动能扩展" : "Kinetic expansion.");
        add("tooltip.openmodularturrets.flavour.expander.power.3", chinese ? "那里，他们的和他们是" : "There, their and they're.");
        add("tooltip.openmodularturrets.flavour.expander.power.4", chinese ? "可以用作沉没装置" : "Can be used as a sinking device.");
        add("tooltip.openmodularturrets.flavour.expander.power.5", chinese ? "不对称战争" : "Asymmetrical warfare.");
        add("tooltip.openmodularturrets.manual_charger.desc", chinese ? "用来产生能量的摇柄，放置在 1 级基座上" : "A lever to generate energy, place on tier 1 base");
        add("tooltip.openmodularturrets.inventory_expander.desc", chinese ? "为基座增加 9 格可用物品栏空间" : "Adds 9 slots of inventory that the turret base can use");
        add("tooltip.openmodularturrets.inventory_expander.flavour", chinese ? "积少成多" : "A little goes a long way.");
        add("tooltip.openmodularturrets.power_expander.desc", chinese ? "为基座增加 RF 容量" : "Adds RF capacity to a turret base");
        add("tooltip.openmodularturrets.power_expander.value", chinese ? "为基座增加 %s RF 容量" : "Adds %s RF capacity to a turret base");
        add("tooltip.openmodularturrets.power_expander.flavour", chinese ? "现在带风味了!" : "Now with flavour!");
        add("tooltip.openmodularturrets.ammo.ammo_blazing_clay", chinese ? "供燃烧炮塔使用" : "Ammunition for the incendiary turret");
        add("tooltip.openmodularturrets.ammo.ammo_bullet", chinese ? "供机枪炮塔使用" : "Ammunition for the machine gun turret");
        add("tooltip.openmodularturrets.ammo.ammo_ferro_slug", chinese ? "供轨道炮使用" : "Ammunition for the rail gun turret");
        add("tooltip.openmodularturrets.ammo.ammo_grenade", chinese ? "供榴弹炮塔使用" : "Ammunition for the grenade turret");
        add("tooltip.openmodularturrets.ammo.ammo_rocket", chinese ? "供火箭炮塔使用" : "Ammunition for the rocket turret");
        add("tooltip.openmodularturrets.ammo.ammo_fake_disposable", chinese ? "内部占位弹药；不要使用" : "Internal placeholder ammunition; do not use");
        add("tooltip.openmodularturrets.ammo.throwable_bullet", chinese ? "可投掷的子弹" : "A throwable bullet");
        add("tooltip.openmodularturrets.ammo.throwable_grenade", chinese ? "可投掷的榴弹" : "A throwable grenade");
    }

    private void addBaseGuiTranslations(boolean chinese) {
        add("message.openmodularturrets.camouflage_rejected",
                chinese ? "无法将该方块用作基座伪装" : "That block cannot be used as base camouflage");
        add("message.openmodularturrets.camouflage_clear_rejected",
                chinese ? "只有基座所有者可以移除伪装" : "Only the base owner can remove camouflage");
        add("gui.openmodularturrets.camouflage", chinese ? "基座伪装" : "Camouflage");
        add("gui.openmodularturrets.camouflage.light",
                chinese ? "亮度：%s" : "Light: %s");
        add("gui.openmodularturrets.camouflage.opacity",
                chinese ? "遮光：%s" : "Opacity: %s");
        add("gui.openmodularturrets.camouflage.clear",
                chinese ? "移除伪装" : "Clear Camouflage");
        add("gui.openmodularturrets.camouflage.applied",
                chinese ? "状态：已应用" : "State: Applied");
        add("gui.openmodularturrets.camouflage.none",
                chinese ? "状态：未应用" : "State: None");
        add("gui.openmodularturrets.camouflage.hint",
                chinese ? "手持完整方块右击基座" : "Use a full block on the base");
        add("gui.openmodularturrets.toggle", chinese ? "启用/停用" : "Toggle");
        add("gui.openmodularturrets.range", chinese ? "范围：%s" : "Range: %s");
        add("gui.openmodularturrets.active", chinese ? "运行" : "Active");
        add("gui.openmodularturrets.active_state",
                chinese ? "运行：%s" : "Active: %s");
        add("gui.openmodularturrets.decrease", "-");
        add("gui.openmodularturrets.increase", "+");
        add("gui.openmodularturrets.labeled_value", "%1$s: %2$s");
        add("gui.openmodularturrets.hostile", chinese ? "敌对生物" : "Hostile");
        add("gui.openmodularturrets.neutral", chinese ? "中立生物" : "Neutral");
        add("gui.openmodularturrets.players", chinese ? "玩家" : "Players");
        add("gui.openmodularturrets.multi_target", chinese ? "多目标" : "Multi");
        add("gui.openmodularturrets.multi_target_state",
                chinese ? "多目标：%s" : "Multi target: %s");
        add("gui.openmodularturrets.range_compact", "%1$s/%2$s");
        add("gui.openmodularturrets.range_label", chinese ? "范围" : "Range");
        add("gui.openmodularturrets.range_value",
                chinese ? "范围：%1$s（上限 %2$s）" : "Range: %1$s (maximum %2$s)");
        add("gui.openmodularturrets.on", chinese ? "开" : "On");
        add("gui.openmodularturrets.off", chinese ? "关" : "Off");
        add("gui.openmodularturrets.unknown", chinese ? "未知" : "Unknown");
        add("gui.openmodularturrets.yes", chinese ? "是" : "Yes");
        add("gui.openmodularturrets.no", chinese ? "否" : "No");
        add("gui.openmodularturrets.overview", chinese ? "概览" : "Overview");
        add("gui.openmodularturrets.targeting", chinese ? "目标设置" : "Targeting");
        add("gui.openmodularturrets.security", chinese ? "安全设置" : "Security");
        add("gui.openmodularturrets.mode",
                chinese ? "红石控制：%s" : "Redstone mode: %s");
        add("gui.openmodularturrets.mode.always_on", chinese ? "始终开启" : "Always On");
        add("gui.openmodularturrets.mode.always_off", chinese ? "始终关闭" : "Always Off");
        add("gui.openmodularturrets.mode.inverted",
                chinese ? "无信号时开启" : "On When Unpowered");
        add("gui.openmodularturrets.mode.noninverted",
                chinese ? "有信号时开启" : "On When Powered");
        add("gui.openmodularturrets.redstone",
                chinese ? "红石信号：%s" : "Redstone signal: %s");
        add("gui.openmodularturrets.powered", chinese ? "有信号" : "Powered");
        add("gui.openmodularturrets.unpowered", chinese ? "无信号" : "Unpowered");
        add("gui.openmodularturrets.energy", chinese ? "能量：%s / %s FE" : "Energy: %s / %s FE");
        add("gui.openmodularturrets.tier", chinese ? "等级：%s" : "Tier: %s");
        add("gui.openmodularturrets.owner", chinese ? "所有者：%s" : "Owner: %s");
        add("gui.openmodularturrets.kills", chinese ? "击杀：%s" : "Kills: %s");
        add("gui.openmodularturrets.player_kills",
                chinese ? "玩家击杀：%s" : "Player kills: %s");
        add("gui.openmodularturrets.kill_summary",
                chinese ? "击杀：%1$s（玩家 %2$s）"
                        : "Kills: %1$s (players %2$s)");
        add("gui.openmodularturrets.shots_fired",
                chinese ? "已发射：%s" : "Shots fired: %s");
        add("gui.openmodularturrets.ammo", chinese ? "弹药" : "Ammo");
        add("gui.openmodularturrets.addons", chinese ? "附加组件" : "Addons");
        add("gui.openmodularturrets.upgrades", chinese ? "升级" : "Upgrades");
        add("gui.openmodularturrets.inventory", chinese ? "物品栏" : "Inventory");
        add("gui.openmodularturrets.configure", chinese ? "配置" : "Configure");
        add("gui.openmodularturrets.targeting_options",
                chinese ? "目标选项" : "Targeting Options");
        add("gui.openmodularturrets.single_target", chinese ? "单目标" : "Single");
        add("gui.openmodularturrets.drop_base", chinese ? "拆除基座" : "Drop Base");
        add("gui.openmodularturrets.drop_turrets", chinese ? "拆除炮塔" : "Drop Turrets");
        add("gui.openmodularturrets.back", chinese ? "返回" : "Back");
        add("tooltip.openmodularturrets.energy",
                chinese ? "当前能量：%s / %s FE" : "Stored energy: %s / %s FE");
        add("tooltip.openmodularturrets.range_increase",
                chinese ? "增加炮塔搜索范围" : "Increase turret targeting range");
        add("tooltip.openmodularturrets.range_decrease",
                chinese ? "减小炮塔搜索范围" : "Decrease turret targeting range");
        add("tooltip.openmodularturrets.mode",
                chinese ? "循环切换四种红石控制模式" : "Cycle the four redstone control modes");
        add("tooltip.openmodularturrets.drop_base",
                chinese ? "拆除基座并掉落其内容物" : "Remove the base and drop its contents");
        add("tooltip.openmodularturrets.drop_turrets",
                chinese ? "拆除连接到该基座的全部炮塔" : "Remove every turret attached to this base");
        add("tooltip.openmodularturrets.ammo_slot",
                chinese ? "放入炮塔使用的弹药" : "Insert ammunition used by attached turrets");
        add("tooltip.openmodularturrets.addon_slot",
                chinese ? "安装炮塔附加组件" : "Install turret addons");
        add("tooltip.openmodularturrets.upgrade_slot",
                chinese ? "安装炮塔升级" : "Install turret upgrades");
        add("tooltip.openmodularturrets.maximum_range",
                chinese ? "当前炮塔组合允许的最大范围：%s 格"
                        : "Maximum range allowed by the attached turrets: %s blocks");
        add("tooltip.openmodularturrets.target_hostile",
                chinese ? "切换是否攻击敌对生物" : "Toggle targeting hostile mobs");
        add("tooltip.openmodularturrets.target_neutral",
                chinese ? "切换是否攻击中立生物" : "Toggle targeting neutral mobs");
        add("tooltip.openmodularturrets.target_players",
                chinese ? "切换是否攻击玩家" : "Toggle targeting players");
        add("tooltip.openmodularturrets.multi_target",
                chinese ? "切换单目标和多目标攻击" : "Toggle between single-target and multi-target firing");
    }

    private void addTrustGuiTranslations(boolean chinese) {
        add("gui.openmodularturrets.trust_scope", chinese ? "信任范围" : "Trust Scope");
        add("gui.openmodularturrets.trust.title",
                chinese ? "信任玩家管理" : "Trusted Players");
        add("gui.openmodularturrets.trust.local", chinese ? "本地列表" : "Local List");
        add("gui.openmodularturrets.trust.global", chinese ? "全局列表" : "Global List");
        add("gui.openmodularturrets.trust.use_global",
                chinese ? "使用全局信任列表" : "Use Global Trust List");
        add("gui.openmodularturrets.trust.add", chinese ? "添加" : "Add");
        add("gui.openmodularturrets.trust.remove", chinese ? "移除" : "Remove");
        add("gui.openmodularturrets.trust.level_up",
                chinese ? "提高权限" : "Increase Access");
        add("gui.openmodularturrets.trust.level_down",
                chinese ? "降低权限" : "Decrease Access");
        add("gui.openmodularturrets.trust.input",
                chinese ? "输入玩家名称" : "Enter Player Name");
        add("gui.openmodularturrets.trust.refresh", chinese ? "刷新" : "Refresh");
        add("gui.openmodularturrets.trust.entry", "%1$s [%2$s]");
        add("gui.openmodularturrets.trust.page",
                chinese ? "%1$s-%2$s / %3$s，版本 %4$s"
                        : "%1$s-%2$s / %3$s, revision %4$s");
        add("gui.openmodularturrets.trust.page_empty",
                chinese ? "0 / 0，版本 %s" : "0 / 0, revision %s");
        add("gui.openmodularturrets.trust.scope",
                chinese ? "范围：%s" : "Scope: %s");
        add("gui.openmodularturrets.trust.scope.local",
                chinese ? "本地" : "Local");
        add("gui.openmodularturrets.trust.scope.global",
                chinese ? "全局" : "Global");
        add("gui.openmodularturrets.trust.scroll_up", "▲");
        add("gui.openmodularturrets.trust.scroll_down", "▼");
        add("gui.openmodularturrets.trust.player_name",
                chinese ? "玩家名称" : "Player Name");
        add("gui.openmodularturrets.trust.permissions", chinese ? "权限" : "Permissions");
        add("gui.openmodularturrets.trust.empty",
                chinese ? "没有信任玩家" : "No trusted players");
        add("gui.openmodularturrets.trust.access.none",
                chinese ? "无权限" : "No Access");
        add("gui.openmodularturrets.trust.access.view",
                chinese ? "查看界面" : "Open GUI");
        add("gui.openmodularturrets.trust.access.use",
                chinese ? "更改设置" : "Change Settings");
        add("gui.openmodularturrets.trust.access.admin",
                chinese ? "管理员" : "Administrator");
        add("gui.openmodularturrets.access.none", chinese ? "无权限" : "No Access");
        add("gui.openmodularturrets.access.view", chinese ? "查看界面" : "Open GUI");
        add("gui.openmodularturrets.access.use", chinese ? "更改设置" : "Change Settings");
        add("gui.openmodularturrets.access.admin", chinese ? "管理员" : "Administrator");
        add("tooltip.openmodularturrets.trust.add",
                chinese ? "将玩家加入当前信任列表" : "Add a player to the selected trust list");
        add("tooltip.openmodularturrets.trust.remove",
                chinese ? "将选中的玩家移出信任列表" : "Remove the selected trusted player");
        add("tooltip.openmodularturrets.trust.permission_down",
                chinese ? "降低选中玩家的权限" : "Decrease the selected player's access");
        add("tooltip.openmodularturrets.trust.permission_up",
                chinese ? "提高选中玩家的权限" : "Increase the selected player's access");
        add("tooltip.openmodularturrets.trust.scope",
                chinese ? "切换本地与全局信任列表" : "Switch between the local and global trust lists");
        add("tooltip.openmodularturrets.trust.input",
                chinese ? "输入要添加到信任列表的玩家名称" : "Enter the player name to add to this trust list");
        add("tooltip.openmodularturrets.trust.refresh",
                chinese ? "从服务器刷新信任列表" : "Refresh the trust list from the server");
        add("tooltip.openmodularturrets.trust.scroll_up",
                chinese ? "向上滚动信任列表" : "Scroll the trust list up");
        add("tooltip.openmodularturrets.trust.scroll_down",
                chinese ? "向下滚动信任列表" : "Scroll the trust list down");
    }

    private void addDeathMessages(boolean chinese) {
        add("death.attack.openmodularturrets.turret_projectile",
                chinese ? "%1$s 被模块化炮塔射杀了"
                        : "%1$s was shot by a modular turret");
        add("death.attack.openmodularturrets.turret_projectile.player",
                chinese ? "%1$s 被 %2$s 的模块化炮塔射杀了"
                        : "%1$s was shot by %2$s's modular turret");
        add("death.attack.openmodularturrets.turret_explosion",
                chinese ? "%1$s 葬身于炮塔爆炸" : "%1$s was caught in a turret blast");
        add("death.attack.openmodularturrets.turret_fire",
                chinese ? "%1$s 被模块化炮塔烧成了灰"
                        : "%1$s was incinerated by a modular turret");
        add("death.attack.openmodularturrets.turret_armor_piercing",
                chinese ? "%1$s 被模块化炮塔贯穿了"
                        : "%1$s was pierced by a modular turret");
    }

    private static String blockName(String id, boolean chinese) {
        int tier = tier(id);
        if (id.startsWith("turret_base_tier_")) {
            return chinese ? "炮塔基座（等级 " + tier + "）" : "Turret Base (Tier " + tier + ")";
        }
        if (id.startsWith("expander_inv_tier_")) {
            return chinese ? "基座物品栏扩展器（等级 " + tier + "）"
                    : "Base Inventory Expander (Tier " + tier + ")";
        }
        if (id.startsWith("expander_power_tier_")) {
            return chinese ? "基座能量扩展器（等级 " + tier + "）"
                    : "Base Power Expander (Tier " + tier + ")";
        }
        return switch (id) {
            case "base_addon_loot_deleter" -> chinese ? "基座附加组件：战利品删除器" : "Base Addon - Loot Deleter";
            case "lever_block" -> chinese ? "基础炮塔基座摇柄" : "Basic Turret Base Crank";
            case "disposable_item_turret" -> chinese ? "一次性物品炮塔" : "Disposable Item Turret";
            case "potato_cannon_turret" -> chinese ? "马铃薯炮塔" : "Potato Cannon Turret";
            case "machine_gun_turret" -> chinese ? "机枪炮塔" : "Machine Gun Turret";
            case "incendiary_turret" -> chinese ? "燃烧炮塔" : "Incendiary Turret";
            case "grenade_turret" -> chinese ? "榴弹炮塔" : "Grenade Launcher Turret";
            case "relativistic_turret" -> chinese ? "相对论炮塔" : "Relativistic Turret";
            case "rocket_turret" -> chinese ? "火箭炮塔" : "Rocket Launcher Turret";
            case "teleporter_turret" -> chinese ? "传送炮塔" : "Teleporter Turret";
            case "laser_turret" -> chinese ? "激光炮塔" : "Laser Turret";
            case "rail_gun_turret" -> chinese ? "轨道炮塔" : "Rail Gun Turret";
            case "plasma_turret" -> chinese ? "等离子炮塔" : "Plasma Launcher Turret";
            default -> humanize(id);
        };
    }

    private static String itemName(String id, boolean chinese) {
        int tier = tier(id);
        if (id.startsWith("sensor_tier_")) {
            return chinese ? "传感器（等级 " + tier + "）" : "Sensor (Tier " + tier + ")";
        }
        if (id.startsWith("chamber_tier_")) {
            return chinese ? "膛室（等级 " + tier + "）" : "Chamber (Tier " + tier + ")";
        }
        if (id.startsWith("barrel_tier_")) {
            return chinese ? "炮管（等级 " + tier + "）" : "Barrel (Tier " + tier + ")";
        }
        return switch (id) {
            case "io_bus" -> chinese ? "I/O 总线" : "I/O Bus";
            case "addon_concealer" -> chinese ? "附加组件：炮塔隐蔽器" : "Addon - Turret Concealer";
            case "addon_damage_amp" -> chinese ? "附加组件：伤害增幅器" : "Addon - Damage Amplifier";
            case "addon_potentia" -> chinese ? "附加组件：Potentia 转换器" : "Addon - Potentia Converter";
            case "addon_recycler" -> chinese ? "附加组件：回收器" : "Addon - Recycler";
            case "addon_redstone_reactor" -> chinese ? "附加组件：红石反应堆" : "Addon - Redstone Reactor";
            case "addon_serial_port" -> chinese ? "附加组件：串行端口" : "Addon - Serial Port";
            case "addon_solar_panel" -> chinese ? "附加组件：太阳能板" : "Addon - Solar Panel";
            case "addon_fake_drops" -> chinese ? "附加组件：模拟玩家掉落" : "Addon - Fake Drops";
            case "upgrade_accuracy" -> chinese ? "升级：精度" : "Upgrade - Accuracy";
            case "upgrade_efficiency" -> chinese ? "升级：效率" : "Upgrade - Efficiency";
            case "upgrade_fire_rate" -> chinese ? "升级：射速" : "Upgrade - Fire Rate";
            case "upgrade_range" -> chinese ? "升级：射程" : "Upgrade - Range";
            case "upgrade_scatter_shot" -> chinese ? "升级：散射" : "Upgrade - Scatter Shot";
            case "ammo_blazing_clay" -> chinese ? "弹药：燃烧黏土" : "Ammo - Blazing Clay";
            case "ammo_bullet" -> chinese ? "弹药：子弹" : "Ammo - Bullet";
            case "ammo_ferro_slug" -> chinese ? "弹药：铁磁弹" : "Ammo - Ferro-Magnetic Slug";
            case "ammo_grenade" -> chinese ? "弹药：榴弹" : "Ammo - Grenade";
            case "ammo_rocket" -> chinese ? "弹药：火箭" : "Ammo - Rocket";
            case "ammo_fake_disposable" -> chinese ? "弹药：一次性投射物" : "Ammo - Disposable Projectile";
            case "throwable_bullet" -> chinese ? "可投掷子弹" : "Throwable Bullet";
            case "throwable_grenade" -> chinese ? "可投掷榴弹" : "Throwable Grenade";
            case "memory_card" -> chinese ? "炮塔存储卡" : "Turret Memory Card";
            default -> humanize(id);
        };
    }

    private void addSoundSubtitles(boolean chinese) {
        add("subtitles.openmodularturrets.amped",
                chinese ? "炮塔伤害增幅器启动" : "Turret damage amplifier activates");
        add("subtitles.openmodularturrets.bullet_hit",
                chinese ? "炮塔子弹命中" : "Turret bullet hits");
        add("subtitles.openmodularturrets.disposable",
                chinese ? "一次性物品炮塔发射" : "Disposable item turret fires");
        add("subtitles.openmodularturrets.grenade",
                chinese ? "榴弹炮塔发射" : "Grenade turret fires");
        add("subtitles.openmodularturrets.incendiary",
                chinese ? "燃烧炮塔发射" : "Incendiary turret fires");
        add("subtitles.openmodularturrets.laser",
                chinese ? "激光炮塔发射" : "Laser turret fires");
        add("subtitles.openmodularturrets.laser_hit",
                chinese ? "激光命中" : "Laser hits");
        add("subtitles.openmodularturrets.machine_gun",
                chinese ? "机枪炮塔发射" : "Machine gun turret fires");
        add("subtitles.openmodularturrets.plasma_launch",
                chinese ? "等离子炮塔发射" : "Plasma turret fires");
        add("subtitles.openmodularturrets.potato",
                chinese ? "马铃薯炮塔发射" : "Potato cannon fires");
        add("subtitles.openmodularturrets.rail_gun",
                chinese ? "轨道炮发射" : "Rail gun fires");
        add("subtitles.openmodularturrets.rail_gun_hit",
                chinese ? "轨道炮命中" : "Rail gun hits");
        add("subtitles.openmodularturrets.relativistic",
                chinese ? "相对论炮塔发射" : "Relativistic turret fires");
        add("subtitles.openmodularturrets.rocket",
                chinese ? "火箭炮塔发射" : "Rocket turret fires");
        add("subtitles.openmodularturrets.teleport",
                chinese ? "传送炮塔启动" : "Teleporter turret activates");
        add("subtitles.openmodularturrets.turret_deploy",
                chinese ? "炮塔展开" : "Turret deploys");
        add("subtitles.openmodularturrets.turret_retract",
                chinese ? "炮塔收回" : "Turret retracts");
        add("subtitles.openmodularturrets.warning",
                chinese ? "炮塔发出警报" : "Turret warning");
    }

    private static int tier(String id) {
        if (id.endsWith("_one")) {
            return 1;
        }
        if (id.endsWith("_two")) {
            return 2;
        }
        if (id.endsWith("_three")) {
            return 3;
        }
        if (id.endsWith("_four")) {
            return 4;
        }
        if (id.endsWith("_five")) {
            return 5;
        }
        return 0;
    }

    private static String humanize(String id) {
        String[] words = id.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT))
                    .append(word.substring(1));
        }
        return result.toString();
    }
}
