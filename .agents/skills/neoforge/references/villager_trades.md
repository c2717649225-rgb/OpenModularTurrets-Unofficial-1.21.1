# NeoForge 1.21.1 村民与流浪商人交易修改指南

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。


为了将模组的自定义道具（例如工具、材料、特殊装备）自然融入生存探索体验中，除了全局掉落修改器（GLM），最常用的方式就是**将物品加入到村民或流浪商人的交易列表**中。

在 NeoForge 1.21.1 中，我们需要在游戏事件总线上订阅专用事件，并通过 `MerchantOffer` 动态追加交易。

---

## 1. 修改村民交易 (VillagerTradesEvent)

村民的交易列表是根据**职业 (Profession)** 和**交易等级 (Level，1~5级)** 分层管理的。

我们必须在 **GAME 事件总线**（`NeoForge.EVENT_BUS`，即 `Bus.GAME`）上订阅 `VillagerTradesEvent`：

```java
package com.tutorial.tutorialmod.event;

import com.tutorial.tutorialmod.TutorialMod;
import com.tutorial.tutorialmod.item.ModItems;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.BasicItemListing;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import java.util.List;

@EventBusSubscriber(modid = TutorialMod.MODID) // 1.21.1+ 已废弃 bus 参数，默认监听 GAME 事件总线
public class ModVillagerTradeRegistrar {

    @SubscribeEvent
    public static void addVillagerTrades(VillagerTradesEvent event) {
        // 1. 过滤职业：例如，只修改“图书管理员 (LIBRARIAN)”的交易
        if (event.getType() == VillagerProfession.LIBRARIAN) {
            
            // 2. 获取该职业对应交易等级的列表 (这里获取 2 级，即“学徒”等级)
            // 1代表新手，2代表学徒，3代表旅人，4代表专家，5代表大师
            List<net.minecraft.world.entity.npc.VillagerTrades.ItemListing> apprenticeTrades = 
                    event.getTrades().get(2);

            // 3. 动态追加一项交易：用 5 个绿宝石 + 1 个普通骨头，换取模组示例物品
            // BasicItemListing 是 NeoForge 提供的简易交易实现，封装了买入和卖出 ItemStack
            apprenticeTrades.add(new BasicItemListing(
                    new ItemStack(Items.EMERALD, 5),          // 买入第一槽：5个绿宝石
                    new ItemStack(Items.BONE, 1),             // 买入第二槽：1个骨头 (可选)
                    new ItemStack(ModItems.EXAMPLE_ITEM.get(), 1), // 卖出槽：模组示例物品
                    12,                                       // 最大交易次数 (超次数锁定)
                    5,                                        // 交易给村民带来的经验值 (XP)
                    0.05F                                     // 价格乘数 (当村民打折或涨价时的比例)
            ));
        }
    }
}
```

---

## 2. 修改流浪商人交易 (WandererTradesEvent)

流浪商人（Wandering Trader）没有职业和等级划分，其交易被分为**普通交易 (Generic)** 和**稀有交易 (Rare)**：

```java
    @SubscribeEvent
    public static void addWanderingTraderTrades(net.neoforged.neoforge.event.village.WandererTradesEvent event) {
        // 获取流浪商人的普通交易列表
        List<net.minecraft.world.entity.npc.VillagerTrades.ItemListing> genericTrades = 
                event.getGenericTrades();
                
        // 获取流浪商人的稀有交易列表
        List<net.minecraft.world.entity.npc.VillagerTrades.ItemListing> rareTrades = 
                event.getRareTrades();

        // 在稀有交易中，添加用 12 个绿宝石换取模组的“红宝石”
        rareTrades.add(new BasicItemListing(
                new ItemStack(Items.EMERALD, 12),
                new ItemStack(ModItems.RUBY.get(), 1),
                3,     // 只能交易 3 次
                10,    // 10 XP
                0.2F   // 价格乘数
        ));
    }
```

---

## 3. 高级扩展：完全自定义交易匹配条件 (MerchantOffer Factory)

如果您需要根据买入物品的数据组件进行动态匹配（例如：只有当玩家提交的绿宝石拥有特定的附魔或标签时才进行交易），可以通过 lambda 表达式完全重写交易创建过程，生成底层的 `MerchantOffer`：

```java
        // 自定义工厂方式注入交易
        apprenticeTrades.add((entity, random) -> {
            // 在此可以根据游戏内的数据组件或随机数动态更改交易项
            
            // ItemCost 是 1.20.5 之后用来规范化交易买入成本的包装类
            ItemCost firstCost = new ItemCost(Items.EMERALD, 16);
            ItemStack result = new ItemStack(ModItems.RUBY_PICKAXE.get());
            
            // 构造 MerchantOffer
            return new MerchantOffer(
                    firstCost, 
                    result, 
                    4,    // 最大使用次数 4
                    15,   // 村民获得 15 经验
                    0.15F // 价格折算系数
            );
        });
```

### 💡 核心参数避坑总结：
*   **最大交易次数**：不要设为 `Integer.MAX_VALUE`，那会导致该交易无限可用且永不锁定，破坏原版生存平衡。通常为 8、12 或 16。
*   **价格折算系数 (priceMultiplier)**：原版村民如果被治愈（僵尸村民治愈），折算系数会影响打折后的价格。普通的非贵重物品一般为 `0.05F`（打折敏感度低）；如果是极其贵重的装备或附魔书，建议设为 `0.2F`。