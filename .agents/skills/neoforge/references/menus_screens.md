# NeoForge 1.21.1 容器与 GUI 屏幕 (Menus & Screens) 参考指南

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。


制作带界面的方块（如自定义熔炉、发电机、储物箱等）涉及**双端通信机制**：
* **服务端 (Server)**：管理容器逻辑（`AbstractContainerMenu`），负责处理物品槽的数据交互、槽位判定、Shift 点击转移等核心逻辑。
* **客户端 (Client)**：管理视觉渲染（`AbstractContainerScreen`），负责绘制背景贴图、绘制进度条、处理鼠标交互和渲染 Tooltips。

---

## 1. 注册 `MenuType`（使用 `IContainerFactory`）

如果界面在初始化时需要知道对应的方块位置（`BlockPos`）或其它数据，必须使用 `IContainerFactory` 在客户端构造 Menu 时读取网络缓存。

```java
package com.tutorial.tutorialmod.menu;

import com.tutorial.tutorialmod.TutorialMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, TutorialMod.MODID);

    // 注册自定义机器的 MenuType (使用 IMenuTypeExtension.create 绑定 IContainerFactory)
    public static final DeferredHolder<MenuType<?>, MenuType<MyMachineMenu>> MY_MACHINE_MENU =
            MENU_TYPES.register("my_machine_menu", () ->
                    IMenuTypeExtension.create((windowId, inv, data) -> {
                        // 在客户端读取服务端通过 writeExtraData 发送的 BlockPos
                        return new MyMachineMenu(windowId, inv, data.readBlockPos());
                    })
            );
}
```

---

## 2. 服务端容器类 (`AbstractContainerMenu`)

容器类主要负责分配物品槽位 (Slots)。槽位索引的划分非常关键，也是实现 Shift-Click（快捷移动）逻辑的基础。

```java
package com.tutorial.tutorialmod.menu;

import com.tutorial.tutorialmod.block.entity.MyMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

public class MyMachineMenu extends AbstractContainerMenu {
    private final MyMachineBlockEntity blockEntity;
    private final ContainerLevelAccess levelAccess;

    // 客户端构造器 (通过 IContainerFactory 调用)
    public MyMachineMenu(int containerId, Inventory playerInv, BlockPos pos) {
        this(containerId, playerInv, (MyMachineBlockEntity) playerInv.player.level().getBlockEntity(pos));
    }

    // 主构造器 (双端共用)
    public MyMachineMenu(int containerId, Inventory playerInv, MyMachineBlockEntity blockEntity) {
        super(ModMenuTypes.MY_MACHINE_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.levelAccess = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        // 1. 绑定机器本身的物品槽 (假设机器有 1 个输入槽和 1 个输出槽，使用 Capability/Attachment 里的 IItemHandler)
        IItemHandler handler = blockEntity.getItemHandler(null);
        if (handler != null) {
            // 参数：数据源, 槽位索引, 屏幕上的 X 坐标, Y 坐标
            this.addSlot(new SlotItemHandler(handler, 0, 56, 35)); // 输入槽
            this.addSlot(new SlotItemHandler(handler, 1, 116, 35)); // 输出槽
        }

        // 2. 绑定玩家的主背包 (共 3 行，每行 9 格，索引 9-35)
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }

        // 3. 绑定玩家的快捷栏 (Hotbar, 1 行 9 格，索引 0-8)
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }

        // 4. 使用 DataSlot 同步能量/进度等整型数据 (替换 1.12.2 旧版 IContainerListener)
        this.addDataSlot(new DataSlot() {
            @Override public int get() { return blockEntity.getEnergyStored(); }
            @Override public void set(int value) { blockEntity.setEnergyStored(value); }
        });
    }

    @Override
    public boolean stillValid(Player player) {
        // 安全检测：如果玩家离机器太远，自动关闭界面
        return stillValid(this.levelAccess, player, blockEntity.getBlockState().getBlock());
    }

    // 4. Shift 点击快速移动槽位算法 (防死锁八股文代码)
    // 槽位索引划分：0-1 为机器槽，2-28 为玩家背包，29-37 为玩家快捷栏
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        
        if (slot != null && slot.hasItem()) {
            ItemStack itemstack1 = slot.getItem();
            itemstack = itemstack1.copy();
            
            if (index < 2) { // 机器内部槽位 -> 玩家背包或快捷栏
                if (!this.moveItemStackTo(itemstack1, 2, 38, true)) {
                    return ItemStack.EMPTY;
                }
            } else { // 玩家背包或快捷栏 -> 机器内部槽位
                // 优先移动到机器输入槽 (索引 0)
                if (!this.moveItemStackTo(itemstack1, 0, 1, false)) {
                    // 背包与快捷栏之间互转
                    if (index < 29) { // 玩家背包 -> 快捷栏
                        if (!this.moveItemStackTo(itemstack1, 29, 38, false)) {
                            return ItemStack.EMPTY;
                        }
                    } else { // 快捷栏 -> 玩家背包
                        if (!this.moveItemStackTo(itemstack1, 2, 29, false)) {
                            return ItemStack.EMPTY;
                        }
                    }
                }
            }

            if (itemstack1.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (itemstack1.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, itemstack1);
        }

        return itemstack;
    }
}
```

---

## 3. 客户端屏幕类 (`AbstractContainerScreen`)

屏幕类负责 UI 的动态绘制和交互显示。

```java
package com.tutorial.tutorialmod.client;

import com.tutorial.tutorialmod.TutorialMod;
import com.tutorial.tutorialmod.menu.MyMachineMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class MyMachineScreen extends AbstractContainerScreen<MyMachineMenu> {
    // 绑定 UI 的纹理贴图路径 (assets/tutorialmod/textures/gui/container/my_machine.png)
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(TutorialMod.MODID, "textures/gui/container/my_machine.png");

    public MyMachineScreen(MyMachineMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;  // 贴图宽度
        this.imageHeight = 166; // 贴图高度
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick); // 渲染半透明背景
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);                // 渲染物品悬浮提示
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, TEXTURE);

        // 计算 UI 在屏幕中央渲染的起算点
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // 绘制背景大纹理
        graphics.blit(TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);

        // 动态绘制进度条示例 (假设机器能量有变化，在这里计算并遮罩绘制)
        // int progress = this.menu.getScaledProgress();
        // graphics.blit(TEXTURE, x + 79, y + 34, 176, 14, progress, 17);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 渲染前景文字（标题和玩家背包名称）
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 4210752);
        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 4210752);
    }
}
```

---

## 4. 方块实体集成与打开界面

方块实体必须实现 NeoForge 的 `IMenuProvider` 接口以支持网络发送额外数据：

```java
package com.tutorial.tutorialmod.block.entity;

import com.tutorial.tutorialmod.menu.MyMachineMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class MyMachineBlockEntity extends BlockEntity implements MenuProvider {

    public MyMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MY_MACHINE.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.tutorialmod.my_machine");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        // 实例化服务端 Menu 容器
        return new MyMachineMenu(containerId, playerInv, this);
    }

    // 关键：通过网络缓冲区将 BlockPos 发送给客户端构造 Menu (使用 NeoForge 1.21.1 专用的 writeClientSideData)
    @Override
    public void writeClientSideData(net.minecraft.world.inventory.AbstractContainerMenu menu, net.minecraft.network.RegistryFriendlyByteBuf buffer) {
        buffer.writeBlockPos(this.worldPosition);
    }
}
```

在方块的右键交互事件中执行打开界面：
```java
// 在 Block 类的 useWithoutItem 方法中：
if (!level.isClientSide()) {
    BlockEntity be = level.getBlockEntity(pos);
    if (be instanceof MyMachineBlockEntity machine) {
        // ServerPlayer.openMenu 会自动序列化 writeExtraData 并通知客户端打开界面
        player.openMenu(machine);
    }
}
```

---

## 5. 客户端 Screen 绑定注册

所有的 Screen 绑定都必须在客户端事件总线上进行注册：

```java
package com.tutorial.tutorialmod.client;

import com.tutorial.tutorialmod.TutorialMod;
import com.tutorial.tutorialmod.menu.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = TutorialMod.MODID, value = Dist.CLIENT)
public class ClientMenuScreenRegistrar {

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        // 绑定注册 MyMachineMenu 关联的界面为 MyMachineScreen
        event.register(ModMenuTypes.MY_MACHINE_MENU.get(), MyMachineScreen::new);
    }
}
```

---

## 6. 使用 ContainerData 进行动态数值同步（防溢出拆分）

在 GUI 界面中，经常需要同步动态数值（例如：机器的冶炼进度、发电机剩余电量）。Minecraft 原版提供的解决方案是 `ContainerData` 数据通道。

> [!IMPORTANT]
> **原版网络封包限制**：原版底层同步 `ContainerData` 的数据包（`ClientboundContainerSetDataPacket`）在网络传输时会将值强制缩剪为 **16 位有符号短整型（`short`，范围为 -32768 ~ 32767）**。
> 如果您的电能或进度最大值超过了 32767（如 100,000 电量），直接写入会发生**数据截断溢出**，在客户端界面显示成错乱的负数。

### 6.1 工业级解决方案：32位整型拆分为双16位短整型

我们可以用**两个数据通道槽位**组合来同步一个 32 位的 `int` 变量。

#### 1. 在方块实体 (Block Entity) 中声明和拆分数据

```java
import net.minecraft.world.inventory.ContainerData;

public class MyMachineBlockEntity extends BlockEntity {
    private int energy = 0; // 32位整型，可能高达数百万
    
    // 声明一个包含 2 个插槽的数据通道 (0号存放低16位，1号存放高16位)
    protected final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            switch (index) {
                case 0:
                    // 提取低 16 位
                    return (short) (MyMachineBlockEntity.this.energy & 0xFFFF);
                case 1:
                    // 提取高 16 位并移位
                    return (short) ((MyMachineBlockEntity.this.energy >> 16) & 0xFFFF);
                default:
                    return 0;
            }
        }

        @Override
        public void set(int index, int value) {
            // 在写入时（主要由客户端收到同步包时触发），重构 32 位整型
            switch (index) {
                case 0:
                    // 使用 0xFFFF 遮罩消除符号位拓展污染
                    MyMachineBlockEntity.this.energy = (MyMachineBlockEntity.this.energy & 0xFFFF0000) | (value & 0xFFFF);
                    break;
                case 1:
                    MyMachineBlockEntity.this.energy = (MyMachineBlockEntity.this.energy & 0x0000FFFF) | ((value & 0xFFFF) << 16);
                    break;
            }
        }

        @Override
        public int getCount() {
            return 2; // 总插槽数为 2
        }
    };
    
    // ... 将 dataAccess 传递给 Menu 构造器
}
```

#### 2. 在容器 (Menu) 中注册数据通道

在服务端的 Menu 构造器中注册该数据通道，以便游戏底层的网络代码自动同步：

```java
public class MyMachineMenu extends AbstractContainerMenu {
    private final ContainerData data;

    // 服务端构造器
    public MyMachineMenu(int containerId, Inventory playerInv, MyMachineBlockEntity blockEntity) {
        super(ModMenuTypes.MY_MACHINE_MENU.get(), containerId);
        this.data = blockEntity.dataAccess;
        
        // 关键：在 Menu 中注册数据通道，绑定后服务端会自动监控其 get() 变化并发送包
        this.addDataSlots(this.data);
    }
    
    // 提供给客户端 Screen 的快捷数据读取方法
    public int getEnergy() {
        // 在客户端读取时，利用位运算重构还原 32 位大整数
        int low = this.data.get(0);
        int high = this.data.get(1);
        
        // 注意：low & 0xFFFF 遮罩绝对不能省！Java 会自动将 short 强转为 int，若低16位最高位为1，不加遮罩会导致符号位自动填充 1 污染高位
        return (high << 16) | (low & 0xFFFF);
    }
}
```

#### 3. 在客户端 (Screen) 中渲染读取的值

在 `MyMachineScreen` 的 `renderBg` 方法中，可以直接通过 `menu.getEnergy()` 渲染出无精度损耗的高精度进度条或电量值：

```java
@Override
protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    // ... 绘制背景
    int currentEnergy = this.menu.getEnergy();
    // 渲染电能槽...
}
```
通过这种**位移和遮罩防污染拆分方案**，可以完全免去手写网络发包的麻烦，实现对高达 2,147,483,647 大数值的动态零延迟同步。