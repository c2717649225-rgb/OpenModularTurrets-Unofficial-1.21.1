package omtteam.openmodularturrets.client;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.ChatFormatting;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.data.BaseMode;
import omtteam.openmodularturrets.menu.TurretBaseMenu;
import omtteam.openmodularturrets.network.BaseCommand;
import omtteam.openmodularturrets.network.BaseCommandPayload;
import omtteam.openmodularturrets.network.ClientTrustSnapshot;
import omtteam.openmodularturrets.network.TrustCommandPayload;
import omtteam.openmodularturrets.network.TrustOperation;
import omtteam.openmodularturrets.network.TrustScope;
import omtteam.openmodularturrets.network.TrustSnapshotPayload;
import omtteam.openmodularturrets.network.TrustSnapshotRequestPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class TurretBaseScreen extends AbstractContainerScreen<TurretBaseMenu> {
    private static final int MAIN_WIDTH = 176;
    private static final int WIDE_SIDE_X = 180;
    private static final int WIDE_SIDE_WIDTH = 164;
    private static final int NARROW_SIDE_WIDTH = 144;
    private static final int WIDE_TOTAL_WIDTH = WIDE_SIDE_X + WIDE_SIDE_WIDTH;
    private static final int LIST_Y = 45;
    private static final int LIST_ROW_HEIGHT = 12;
    private static final int LIST_ROWS = 5;

    private static final ResourceLocation[] TIER_TEXTURES = {
            guiTexture("turret_base_tier_one.png"),
            guiTexture("turret_base_tier_two.png"),
            guiTexture("turret_base_tier_three.png"),
            guiTexture("turret_base_tier_four.png"),
            guiTexture("turret_base_tier_five.png")
    };
    private static final ResourceLocation CONFIGURE_TEXTURE =
            guiTexture("configure.png");
    private static final ResourceLocation TRUSTED_PLAYERS_TEXTURE =
            guiTexture("trusted_players.png");

    private Page page = Page.OVERVIEW;
    private int sideX = WIDE_SIDE_X;
    private int sideWidth = WIDE_SIDE_WIDTH;
    private int listX = WIDE_SIDE_X + 2;
    private int listWidth = 143;
    private TrustScope trustScope = TrustScope.LOCAL;
    private UUID selectedTrust;
    private int trustScroll;
    private EditBox trustInput;
    private Button rangeDecreaseButton;
    private Button rangeIncreaseButton;
    private Button modeButton;
    private Button dropTurretsButton;
    private Button dropBaseButton;
    private Button multiButton;
    private Button hostileButton;
    private Button neutralButton;
    private Button playersButton;
    private Button securityButton;
    private Button scopeButton;
    private Button trustAddButton;
    private Button trustRemoveButton;
    private Button trustDecreaseButton;
    private Button trustIncreaseButton;
    private Button camouflageLightDecreaseButton;
    private Button camouflageLightIncreaseButton;
    private Button camouflageOpacityDecreaseButton;
    private Button camouflageOpacityIncreaseButton;
    private Button camouflageLightButton;
    private Button camouflageOpacityButton;
    private Button camouflageClearButton;

    public TurretBaseScreen(TurretBaseMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDE_TOTAL_WIDTH;
        imageHeight = 166;
        inventoryLabelX = 8;
        inventoryLabelY = 73;
    }

    @Override
    protected void init() {
        boolean narrow = width < WIDE_TOTAL_WIDTH;
        sideX = narrow ? MAIN_WIDTH : WIDE_SIDE_X;
        sideWidth = narrow ? NARROW_SIDE_WIDTH : WIDE_SIDE_WIDTH;
        listX = sideX + 2;
        listWidth = sideWidth - 21;
        imageWidth = sideX + sideWidth;
        super.init();
        trustScope = menu.useGlobalTrust() ? TrustScope.GLOBAL : TrustScope.LOCAL;
        buildWidgets();
    }

    private void buildWidgets() {
        rangeDecreaseButton = null;
        rangeIncreaseButton = null;
        modeButton = null;
        dropTurretsButton = null;
        dropBaseButton = null;
        multiButton = null;
        hostileButton = null;
        neutralButton = null;
        playersButton = null;
        securityButton = null;
        scopeButton = null;
        trustInput = null;
        trustAddButton = null;
        trustRemoveButton = null;
        trustDecreaseButton = null;
        trustIncreaseButton = null;
        camouflageLightDecreaseButton = null;
        camouflageLightIncreaseButton = null;
        camouflageOpacityDecreaseButton = null;
        camouflageOpacityIncreaseButton = null;
        camouflageLightButton = null;
        camouflageOpacityButton = null;
        camouflageClearButton = null;
        boolean mayUse = menu.accessLevel().allows(AccessLevel.USE);
        boolean admin = menu.accessLevel() == AccessLevel.ADMIN;
        int side = leftPos + sideX;
        int firstTabWidth = sideWidth / 3;
        int secondTabWidth = sideWidth / 3;
        int thirdTabWidth = sideWidth - firstTabWidth - secondTabWidth;

        addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.overview"),
                ignored -> switchPage(Page.OVERVIEW))
                .bounds(side, topPos, firstTabWidth - 1, 18).build());
        addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.targeting"),
                ignored -> switchPage(Page.TARGETING))
                .bounds(side + firstTabWidth, topPos, secondTabWidth - 1, 18).build());
        securityButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.security"),
                ignored -> switchPage(Page.SECURITY))
                .bounds(side + firstTabWidth + secondTabWidth, topPos,
                        thirdTabWidth, 18).build());
        securityButton.active = canOpenSecurity();

        switch (page) {
            case OVERVIEW -> buildOverviewWidgets(side, mayUse, admin);
            case TARGETING -> buildTargetingWidgets(side, mayUse);
            case SECURITY -> buildSecurityWidgets(side);
            case CAMOUFLAGE -> buildCamouflageWidgets(side);
        }
    }

    private void buildOverviewWidgets(int side, boolean mayUse, boolean admin) {
        rangeDecreaseButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.decrease"),
                ignored -> send(BaseCommand.ADJUST_RANGE, -1))
                .bounds(side, topPos + 108, 20, 18).build());
        rangeDecreaseButton.active = mayUse && menu.configuredRange() > 1;
        rangeIncreaseButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.increase"),
                ignored -> send(BaseCommand.ADJUST_RANGE, 1))
                .bounds(side + 22, topPos + 108, 20, 18).build());
        rangeIncreaseButton.active = mayUse
                && menu.configuredRange() < menu.maximumRange();

        modeButton = addRenderableWidget(Button.builder(modeLabel(),
                ignored -> send(BaseCommand.CYCLE_MODE, 0))
                .bounds(side + 44, topPos + 108, sideWidth - 44, 18).build());
        modeButton.active = admin;

        dropTurretsButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.drop_turrets"),
                ignored -> sendAndClose(BaseCommand.DROP_TURRETS, 0))
                .bounds(side, topPos + 128, sideWidth, 18).build());
        dropTurretsButton.active = admin;
        dropBaseButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.drop_base"),
                ignored -> send(BaseCommand.DROP_BASE, 0))
                .bounds(side, topPos + 148, sideWidth, 18).build());
        dropBaseButton.active = admin;
    }

    private void buildTargetingWidgets(int side, boolean mayUse) {
        hostileButton = addTargetButton(side, topPos + 25, 1,
                "gui.openmodularturrets.hostile", mayUse);
        neutralButton = addTargetButton(side, topPos + 47, 2,
                "gui.openmodularturrets.neutral", mayUse);
        playersButton = addTargetButton(side, topPos + 69, 4,
                "gui.openmodularturrets.players", mayUse);
        multiButton = addRenderableWidget(Button.builder(multiLabel(),
                ignored -> send(BaseCommand.TOGGLE_MULTI_TARGET, 0))
                .bounds(side, topPos + 91, sideWidth, 18).build());
        multiButton.active = mayUse;
        Button camouflage = addRenderableWidget(Button.builder(
                Component.translatable("gui.openmodularturrets.camouflage"),
                ignored -> switchPage(Page.CAMOUFLAGE))
                .bounds(side, topPos + 115, sideWidth, 18).build());
        camouflage.active = canOpenCamouflage();
        addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.back"),
                ignored -> switchPage(Page.OVERVIEW))
                .bounds(side, topPos + 138, sideWidth, 18).build());
    }

    private void buildSecurityWidgets(int side) {
        if (!canOpenSecurity()) {
            switchPage(Page.OVERVIEW);
            return;
        }
        int scopeWidth = sideWidth - 52;
        scopeButton = addRenderableWidget(Button.builder(scopeLabel(),
                ignored -> toggleTrustScope())
                .bounds(side, topPos + 21, scopeWidth, 18).build());
        scopeButton.active = isLocalOwner();
        addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.trust.refresh"),
                ignored -> requestTrustSnapshot())
                .bounds(side + scopeWidth + 2, topPos + 21, 50, 18).build());
        addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.trust.scroll_up"),
                ignored -> scrollTrust(-LIST_ROWS))
                .bounds(side + sideWidth - 16, topPos + LIST_Y, 16, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.trust.scroll_down"),
                ignored -> scrollTrust(LIST_ROWS))
                .bounds(side + sideWidth - 16, topPos + LIST_Y + 39, 16, 20).build());

        trustInput = new EditBox(font, side, topPos + 109, sideWidth, 18,
                Component.translatable("gui.openmodularturrets.trust.player_name"));
        trustInput.setMaxLength(TrustCommandPayload.MAX_TARGET_INPUT_LENGTH);
        trustInput.setHint(Component.translatable(
                "gui.openmodularturrets.trust.player_name"));
        addRenderableWidget(trustInput);

        boolean narrow = sideWidth == NARROW_SIDE_WIDTH;
        int addWidth = narrow ? 28 : 30;
        int removeWidth = narrow ? 38 : 46;
        int levelWidth = narrow ? 18 : 20;
        int gap = narrow ? 1 : 2;
        int removeX = side + addWidth + gap;
        int decreaseX = removeX + removeWidth + gap;
        int increaseX = decreaseX + levelWidth + gap;
        int backX = increaseX + levelWidth + gap;
        int backWidth = side + sideWidth - backX;
        trustAddButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.trust.add"),
                ignored -> addTrustedPlayer())
                .bounds(side, topPos + 129, addWidth, 18).build());
        trustRemoveButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.trust.remove"),
                ignored -> mutateSelected(TrustOperation.REMOVE, AccessLevel.NONE))
                .bounds(removeX, topPos + 129, removeWidth, 18).build());
        trustDecreaseButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.decrease"),
                ignored -> changeSelectedLevel(-1))
                .bounds(decreaseX, topPos + 129, levelWidth, 18).build());
        trustIncreaseButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.increase"),
                ignored -> changeSelectedLevel(1))
                .bounds(increaseX, topPos + 129, levelWidth, 18).build());
        addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.back"),
                ignored -> switchPage(Page.OVERVIEW))
                .bounds(backX, topPos + 129, backWidth, 18).build());
        requestTrustSnapshot();
    }

    private void buildCamouflageWidgets(int side) {
        if (!canOpenCamouflage()) {
            switchPage(Page.OVERVIEW);
            return;
        }
        camouflageLightDecreaseButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.decrease"),
                ignored -> send(BaseCommand.ADJUST_CAMOUFLAGE_LIGHT, -1))
                .bounds(side, topPos + 30, 20, 18).build());
        camouflageLightButton = addRenderableWidget(Button.builder(
                camouflageLightLabel(), ignored -> {
                }).bounds(side + 22, topPos + 30, sideWidth - 44, 18).build());
        camouflageLightButton.active = false;
        camouflageLightIncreaseButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.increase"),
                ignored -> send(BaseCommand.ADJUST_CAMOUFLAGE_LIGHT, 1))
                .bounds(side + sideWidth - 20, topPos + 30, 20, 18).build());

        camouflageOpacityDecreaseButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.decrease"),
                ignored -> send(BaseCommand.ADJUST_CAMOUFLAGE_OPACITY, -1))
                .bounds(side, topPos + 54, 20, 18).build());
        camouflageOpacityButton = addRenderableWidget(Button.builder(
                camouflageOpacityLabel(), ignored -> {
                }).bounds(side + 22, topPos + 54, sideWidth - 44, 18).build());
        camouflageOpacityButton.active = false;
        camouflageOpacityIncreaseButton = addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.increase"),
                ignored -> send(BaseCommand.ADJUST_CAMOUFLAGE_OPACITY, 1))
                .bounds(side + sideWidth - 20, topPos + 54, 20, 18).build());

        camouflageClearButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.openmodularturrets.camouflage.clear"),
                ignored -> send(BaseCommand.CLEAR_CAMOUFLAGE, 0))
                .bounds(side, topPos + 104, sideWidth, 18).build());
        camouflageClearButton.active = menu.camouflaged();
        addRenderableWidget(Button.builder(Component.translatable(
                "gui.openmodularturrets.back"),
                ignored -> switchPage(Page.TARGETING))
                .bounds(side, topPos + 138, sideWidth, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refreshButtonLabels();
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (mouseX >= leftPos + 153 && mouseX < leftPos + 167
                && mouseY >= topPos + 17 && mouseY < topPos + 68) {
            graphics.renderTooltip(font, Component.translatable(
                    "gui.openmodularturrets.energy",
                    menu.energy(), menu.maximumEnergy()), mouseX, mouseY);
            return;
        }
        renderBaseHelp(graphics, mouseX, mouseY);
    }

    /**
     * Keeps the contextual help from the 1.12 base/configuration screens while
     * leaving normal item-stack tooltips to {@link AbstractContainerScreen}.
     */
    private void renderBaseHelp(GuiGraphics graphics, int mouseX, int mouseY) {
        Component tooltip = slotHeaderTooltip(mouseX, mouseY);
        if (tooltip == null) {
            tooltip = switch (page) {
                case OVERVIEW -> overviewTooltip();
                case TARGETING -> targetingTooltip();
                case SECURITY -> securityTooltip(mouseX, mouseY);
                case CAMOUFLAGE -> null;
            };
        }
        if (tooltip != null) {
            graphics.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private Component slotHeaderTooltip(int mouseX, int mouseY) {
        if (inBounds(mouseX, mouseY, leftPos + 8, topPos + 6, 40, 14)) {
            return Component.translatable("tooltip.openmodularturrets.ammo_slot");
        }
        if (menu.tier() > 1
                && inBounds(mouseX, mouseY, leftPos + 71, topPos + 6, 40, 14)) {
            return Component.translatable("tooltip.openmodularturrets.addon_slot");
        }
        if (menu.tier() > 1
                && inBounds(mouseX, mouseY, leftPos + 71, topPos + 39, 40, 14)) {
            return Component.translatable("tooltip.openmodularturrets.upgrade_slot");
        }
        if (inBounds(mouseX, mouseY, leftPos + 123, topPos + 35, 12, 14)) {
            return Component.translatable("tooltip.openmodularturrets.maximum_range",
                    menu.maximumRange());
        }
        return null;
    }

    private Component overviewTooltip() {
        if (hovered(rangeDecreaseButton)) {
            return Component.translatable("tooltip.openmodularturrets.range_decrease");
        }
        if (hovered(rangeIncreaseButton)) {
            return Component.translatable("tooltip.openmodularturrets.range_increase");
        }
        if (hovered(modeButton)) {
            return Component.translatable("tooltip.openmodularturrets.mode");
        }
        if (hovered(dropTurretsButton)) {
            return Component.translatable("tooltip.openmodularturrets.drop_turrets");
        }
        if (hovered(dropBaseButton)) {
            return Component.translatable("tooltip.openmodularturrets.drop_base");
        }
        return null;
    }

    private Component targetingTooltip() {
        if (hovered(hostileButton)) {
            return Component.translatable("tooltip.openmodularturrets.target_hostile");
        }
        if (hovered(neutralButton)) {
            return Component.translatable("tooltip.openmodularturrets.target_neutral");
        }
        if (hovered(playersButton)) {
            return Component.translatable("tooltip.openmodularturrets.target_players");
        }
        if (hovered(multiButton)) {
            return Component.translatable("tooltip.openmodularturrets.multi_target");
        }
        return null;
    }

    private Component securityTooltip(int mouseX, int mouseY) {
        if (hovered(scopeButton)) {
            return Component.translatable("tooltip.openmodularturrets.trust.scope");
        }
        int side = leftPos + sideX;
        int scopeWidth = sideWidth - 52;
        if (inBounds(mouseX, mouseY, side + scopeWidth + 2, topPos + 21, 50, 18)) {
            return Component.translatable("tooltip.openmodularturrets.trust.refresh");
        }
        if (inBounds(mouseX, mouseY, side + sideWidth - 16, topPos + LIST_Y, 16, 20)) {
            return Component.translatable("tooltip.openmodularturrets.trust.scroll_up");
        }
        if (inBounds(mouseX, mouseY, side + sideWidth - 16,
                topPos + LIST_Y + 39, 16, 20)) {
            return Component.translatable("tooltip.openmodularturrets.trust.scroll_down");
        }
        if (trustInput != null && trustInput.isHovered()) {
            return Component.translatable("tooltip.openmodularturrets.trust.input");
        }
        if (hovered(trustAddButton)) {
            return Component.translatable("tooltip.openmodularturrets.trust.add");
        }
        if (hovered(trustRemoveButton)) {
            return Component.translatable("tooltip.openmodularturrets.trust.remove");
        }
        if (hovered(trustDecreaseButton)) {
            return Component.translatable("tooltip.openmodularturrets.trust.permission_down");
        }
        if (hovered(trustIncreaseButton)) {
            return Component.translatable("tooltip.openmodularturrets.trust.permission_up");
        }
        return null;
    }

    private static boolean hovered(Button button) {
        return button != null && button.isHovered();
    }

    private static boolean inBounds(int mouseX, int mouseY, int x, int y,
            int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        ResourceLocation texture = tierTexture();
        graphics.blit(texture, leftPos, topPos, 0, 0,
                MAIN_WIDTH, imageHeight, 256, 256);

        int maximum = Math.max(1, menu.maximumEnergy());
        int fill = Mth.clamp((int) ((long) menu.energy() * 51L / maximum), 0, 51);
        graphics.blit(texture, leftPos + 153, topPos + 17,
                178, 17, 14, 51, 256, 256);
        if (fill > 0) {
            int animation = (int) ((System.currentTimeMillis() / 120L) % 3L);
            int sourceX = switch (animation) {
                case 1 -> 215;
                case 2 -> 234;
                default -> 196;
            };
            graphics.blit(texture, leftPos + 153, topPos + 68 - fill,
                    sourceX, 68 - fill, 14, fill, 256, 256);
        }

        ResourceLocation panelTexture =
                page == Page.SECURITY ? TRUSTED_PLAYERS_TEXTURE : CONFIGURE_TEXTURE;
        graphics.blit(panelTexture, leftPos + sideX, topPos,
                0, 0, sideWidth, imageHeight, 256, 256);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        int dark = 0x303030;
        graphics.drawString(font, Component.translatable(
                "gui.openmodularturrets.ammo"), 8, 6, dark, false);
        if (menu.tier() > 1) {
            graphics.drawString(font, Component.translatable(
                    "gui.openmodularturrets.addons"), 71, 6, dark, false);
            graphics.drawString(font, Component.translatable(
                    "gui.openmodularturrets.upgrades"), 71, 39, dark, false);
        }
        graphics.drawString(font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, dark, false);

        switch (page) {
            case OVERVIEW -> renderOverview(graphics);
            case TARGETING -> renderTargeting(graphics);
            case SECURITY -> renderSecurity(graphics);
            case CAMOUFLAGE -> renderCamouflage(graphics);
        }
    }

    private void renderOverview(GuiGraphics graphics) {
        int x = sideX + 5;
        int y = 21;
        // Keep the final statistic clear of the first control row.  The old
        // screen used a compact fixed-pixel font; modern/resource-pack fonts
        // can occupy the full line height, so an 11px stride made the last
        // line touch the range/mode buttons at y=108.  Ten pixels preserves the
        // legacy information density while leaving a one-pixel text-safe gap.
        int line = 10;
        int color = 0xE8EEF2;
        drawLine(graphics, x, y, Component.translatable(
                "gui.openmodularturrets.owner", ownerName()), color);
        drawLine(graphics, x, y + line, Component.translatable(
                "gui.openmodularturrets.mode", modeName(menu.mode())), color);
        drawLine(graphics, x, y + line * 2, Component.translatable(
                "gui.openmodularturrets.redstone",
                Component.translatable(menu.redstonePowered()
                        ? "gui.openmodularturrets.powered"
                        : "gui.openmodularturrets.unpowered")), color);
        drawLine(graphics, x, y + line * 3, Component.translatable(
                "gui.openmodularturrets.active_state", onOff(menu.active())), color);
        drawLine(graphics, x, y + line * 4, Component.translatable(
                "gui.openmodularturrets.energy",
                String.format(Locale.ROOT, "%,d", menu.energy()),
                String.format(Locale.ROOT, "%,d", menu.maximumEnergy())), color);
        drawLine(graphics, x, y + line * 5, Component.translatable(
                "gui.openmodularturrets.range_value",
                menu.configuredRange(), menu.maximumRange()), color);
        drawLine(graphics, x, y + line * 6, Component.translatable(
                "gui.openmodularturrets.kill_summary",
                menu.kills(), menu.playerKills()), color);
        drawLine(graphics, x, y + line * 7, Component.translatable(
                "gui.openmodularturrets.shots_fired", menu.shotsFired()), color);
    }

    private void renderTargeting(GuiGraphics graphics) {
        // State is represented by the synchronized button labels.
    }

    private void renderCamouflage(GuiGraphics graphics) {
        graphics.drawCenteredString(font, Component.translatable(
                menu.camouflaged()
                        ? "gui.openmodularturrets.camouflage.applied"
                        : "gui.openmodularturrets.camouflage.none"),
                sideX + sideWidth / 2, 78, 0xE8EEF2);
        graphics.drawCenteredString(font, Component.translatable(
                "gui.openmodularturrets.camouflage.hint"),
                sideX + sideWidth / 2, 90, 0xC5CDD3);
    }

    private void renderSecurity(GuiGraphics graphics) {
        ClientTrustSnapshot.Snapshot snapshot = menu.trustSnapshot(trustScope);
        List<TrustSnapshotPayload.Entry> entries = snapshot.entries();
        clampTrustScroll(entries.size());
        if (selectedTrust != null
                && entries.stream().noneMatch(entry -> entry.player().equals(selectedTrust))) {
            selectedTrust = null;
        }

        graphics.fill(listX, LIST_Y, listX + listWidth,
                LIST_Y + LIST_ROW_HEIGHT * LIST_ROWS, 0xFF15191D);
        int end = Math.min(entries.size(), trustScroll + LIST_ROWS);
        for (int index = trustScroll; index < end; index++) {
            TrustSnapshotPayload.Entry entry = entries.get(index);
            int rowY = LIST_Y + (index - trustScroll) * LIST_ROW_HEIGHT;
            if (entry.player().equals(selectedTrust)) {
                graphics.fill(listX + 1, rowY + 1,
                        listX + listWidth - 1, rowY + LIST_ROW_HEIGHT, 0xFF56636D);
            }
            Component line = Component.translatable(
                    "gui.openmodularturrets.trust.entry",
                    entry.name(), accessName(AccessLevel.byId(entry.accessId())));
            graphics.drawString(font,
                    font.plainSubstrByWidth(line.getString(), listWidth - 5),
                    listX + 3, rowY + 2, 0xE8EEF2, false);
        }
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(
                    "gui.openmodularturrets.trust.empty"),
                    listX + listWidth / 2, LIST_Y + 25, 0x9AA4AB);
        }
        if (!entries.isEmpty()) {
            Component pageText = Component.translatable("gui.openmodularturrets.trust.page",
                    trustScroll + 1, end, entries.size(), snapshot.revision());
            graphics.drawString(font,
                    pageText,
                    sideX + 2, 99, 0xAEB8BE, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (page == Page.SECURITY && button == 0
                && mouseX >= leftPos + listX
                && mouseX < leftPos + listX + listWidth
                && mouseY >= topPos + LIST_Y
                && mouseY < topPos + LIST_Y + LIST_ROW_HEIGHT * LIST_ROWS) {
            List<TrustSnapshotPayload.Entry> entries =
                    menu.trustSnapshot(trustScope).entries();
            int index = trustScroll + (int) ((mouseY - topPos - LIST_Y)
                    / LIST_ROW_HEIGHT);
            if (index >= 0 && index < entries.size()) {
                selectedTrust = entries.get(index).player();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private Button addTargetButton(int x, int y, int flag,
            String translation, boolean mayUse) {
        Button button = addRenderableWidget(Button.builder(targetLabel(translation, flag),
                ignored -> send(BaseCommand.TOGGLE_TARGET_FLAG, flag))
                .bounds(x, y, sideWidth, 18).build());
        button.active = mayUse;
        return button;
    }

    private void switchPage(Page next) {
        if (next == Page.SECURITY && !canOpenSecurity()) {
            return;
        }
        if (next == Page.CAMOUFLAGE && !canOpenCamouflage()) {
            return;
        }
        page = next;
        selectedTrust = null;
        trustScroll = 0;
        clearWidgets();
        buildWidgets();
    }

    private void toggleTrustScope() {
        trustScope = trustScope == TrustScope.LOCAL ? TrustScope.GLOBAL : TrustScope.LOCAL;
        selectedTrust = null;
        trustScroll = 0;
        send(BaseCommand.SET_TRUST_SCOPE, trustScope == TrustScope.GLOBAL ? 1 : 0);
        requestTrustSnapshot();
    }

    private void requestTrustSnapshot() {
        PacketDistributor.sendToServer(new TrustSnapshotRequestPayload(
                menu.containerId, menu.base().getBlockPos(), trustScope.id()));
    }

    private void addTrustedPlayer() {
        if (trustInput == null) {
            return;
        }
        String input = trustInput.getValue().trim();
        if (input.isEmpty()) {
            return;
        }
        sendTrust(TrustOperation.ADD, input, AccessLevel.VIEW);
        trustInput.setValue("");
    }

    private void mutateSelected(TrustOperation operation, AccessLevel access) {
        TrustSnapshotPayload.Entry selected = selectedEntry();
        if (selected != null) {
            sendTrust(operation, selected.player().toString(), access);
        }
    }

    private void changeSelectedLevel(int change) {
        TrustSnapshotPayload.Entry selected = selectedEntry();
        if (selected == null) {
            return;
        }
        int id = Mth.clamp(selected.accessId() + change,
                AccessLevel.VIEW.id(), AccessLevel.ADMIN.id());
        sendTrust(TrustOperation.SET_LEVEL, selected.player().toString(),
                AccessLevel.byId(id));
    }

    private void sendTrust(TrustOperation operation, String target, AccessLevel access) {
        ClientTrustSnapshot.Snapshot snapshot = menu.trustSnapshot(trustScope);
        PacketDistributor.sendToServer(new TrustCommandPayload(
                menu.containerId,
                menu.base().getBlockPos(),
                trustScope.id(),
                operation.id(),
                target,
                access.id(),
                snapshot.revision()));
    }

    private TrustSnapshotPayload.Entry selectedEntry() {
        if (selectedTrust == null) {
            return null;
        }
        return menu.trustSnapshot(trustScope).entries().stream()
                .filter(entry -> entry.player().equals(selectedTrust))
                .findFirst()
                .orElse(null);
    }

    private void scrollTrust(int amount) {
        trustScroll += amount;
        clampTrustScroll(menu.trustSnapshot(trustScope).entries().size());
    }

    private void clampTrustScroll(int size) {
        int maximum = Math.max(0, size - LIST_ROWS);
        trustScroll = Mth.clamp(trustScroll, 0, maximum);
    }

    private void refreshButtonLabels() {
        boolean mayUse = menu.accessLevel().allows(AccessLevel.USE);
        boolean admin = menu.accessLevel() == AccessLevel.ADMIN;
        if (rangeDecreaseButton != null) {
            rangeDecreaseButton.active = mayUse && menu.configuredRange() > 1;
        }
        if (rangeIncreaseButton != null) {
            rangeIncreaseButton.active = mayUse
                    && menu.configuredRange() < menu.maximumRange();
        }
        if (modeButton != null) {
            modeButton.setMessage(modeLabel());
            modeButton.active = admin;
        }
        if (dropTurretsButton != null) {
            dropTurretsButton.active = admin;
        }
        if (dropBaseButton != null) {
            dropBaseButton.active = admin;
        }
        if (multiButton != null) {
            multiButton.setMessage(multiLabel());
            multiButton.active = mayUse;
        }
        if (hostileButton != null) {
            hostileButton.setMessage(targetLabel(
                    "gui.openmodularturrets.hostile", 1));
            hostileButton.active = mayUse;
        }
        if (neutralButton != null) {
            neutralButton.setMessage(targetLabel(
                    "gui.openmodularturrets.neutral", 2));
            neutralButton.active = mayUse;
        }
        if (playersButton != null) {
            playersButton.setMessage(targetLabel(
                    "gui.openmodularturrets.players", 4));
            playersButton.active = mayUse;
        }
        if (securityButton != null) {
            securityButton.active = canOpenSecurity();
        }
        if (scopeButton != null) {
            scopeButton.setMessage(scopeLabel());
            scopeButton.active = isLocalOwner();
        }
        boolean mayMutateTrust = canMutateTrust();
        if (trustInput != null) {
            trustInput.setEditable(mayMutateTrust);
        }
        if (trustAddButton != null) {
            trustAddButton.active = mayMutateTrust;
        }
        boolean mayMutateSelected = mayMutateTrust && selectedEntry() != null;
        if (trustRemoveButton != null) {
            trustRemoveButton.active = mayMutateSelected;
        }
        if (trustDecreaseButton != null) {
            trustDecreaseButton.active = mayMutateSelected;
        }
        if (trustIncreaseButton != null) {
            trustIncreaseButton.active = mayMutateSelected;
        }
        if (camouflageLightButton != null) {
            camouflageLightButton.setMessage(camouflageLightLabel());
        }
        boolean mayAdjustLight = isLocalOwner() && menu.tier() >= 4;
        if (camouflageLightDecreaseButton != null) {
            camouflageLightDecreaseButton.active = mayAdjustLight
                    && menu.camouflageLightValue() > 0;
        }
        if (camouflageLightIncreaseButton != null) {
            camouflageLightIncreaseButton.active = mayAdjustLight
                    && menu.camouflageLightValue() < 15;
        }
        if (camouflageOpacityButton != null) {
            camouflageOpacityButton.setMessage(camouflageOpacityLabel());
        }
        if (camouflageOpacityDecreaseButton != null) {
            camouflageOpacityDecreaseButton.active = mayAdjustLight
                    && menu.camouflageLightOpacity() > 0;
        }
        if (camouflageOpacityIncreaseButton != null) {
            camouflageOpacityIncreaseButton.active = mayAdjustLight
                    && menu.camouflageLightOpacity() < 15;
        }
        if (camouflageClearButton != null) {
            camouflageClearButton.active = menu.camouflaged();
        }
    }

    private Component modeLabel() {
        return Component.translatable(
                "gui.openmodularturrets.mode", modeName(menu.mode()));
    }

    private Component multiLabel() {
        return Component.translatable(
                "gui.openmodularturrets.multi_target_state",
                onOff(menu.multiTargeting()));
    }

    private Component scopeLabel() {
        return Component.translatable(
                "gui.openmodularturrets.trust.scope",
                Component.translatable(trustScope == TrustScope.LOCAL
                        ? "gui.openmodularturrets.trust.scope.local"
                        : "gui.openmodularturrets.trust.scope.global"));
    }

    private Component targetLabel(String key, int flag) {
        return Component.translatable(
                "gui.openmodularturrets.labeled_value",
                Component.translatable(key), onOff((menu.targetFlags() & flag) != 0));
    }

    private void drawLine(GuiGraphics graphics, int x, int y,
            Component line, int color) {
        graphics.drawString(font,
                font.plainSubstrByWidth(line.getString(), sideWidth - 10),
                x, y, color, false);
    }

    private Component ownerName() {
        String ownerName = menu.base().ownerName();
        return ownerName == null || ownerName.isBlank()
                ? Component.translatable("gui.openmodularturrets.unknown")
                : Component.nullToEmpty(ownerName);
    }

    private boolean canOpenSecurity() {
        return isLocalOwner()
                || menu.accessLevel() == AccessLevel.ADMIN && !menu.useGlobalTrust();
    }

    private boolean canMutateTrust() {
        return page == Page.SECURITY
                && (isLocalOwner() || trustScope == TrustScope.LOCAL
                        && menu.accessLevel() == AccessLevel.ADMIN
                        && !menu.useGlobalTrust());
    }

    private boolean isLocalOwner() {
        return minecraft != null && minecraft.player != null
                && menu.base().owner()
                        .filter(minecraft.player.getUUID()::equals)
                        .isPresent();
    }

    private boolean canOpenCamouflage() {
        return isLocalOwner();
    }

    private Component camouflageLightLabel() {
        return Component.translatable(
                "gui.openmodularturrets.camouflage.light",
                menu.camouflageLightValue());
    }

    private Component camouflageOpacityLabel() {
        return Component.translatable(
                "gui.openmodularturrets.camouflage.opacity",
                menu.camouflageLightOpacity());
    }

    private void send(BaseCommand command, int value) {
        PacketDistributor.sendToServer(new BaseCommandPayload(
                menu.containerId, menu.base().getBlockPos(), command.id(), value));
    }

    /**
     * Sends the command and immediately closes the base menu. Used by actions
     * that dismantle world blocks (dropping turrets), where keeping the screen
     * open would reference a container whose base may no longer exist.
     */
    private void sendAndClose(BaseCommand command, int value) {
        send(command, value);
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
    }

    private ResourceLocation tierTexture() {
        return TIER_TEXTURES[Mth.clamp(menu.tier(), 1, TIER_TEXTURES.length) - 1];
    }

    private static ResourceLocation guiTexture(String file) {
        return ResourceLocation.fromNamespaceAndPath(
                OpenModularTurrets.MOD_ID, "textures/gui/" + file);
    }

    private static Component onOff(boolean value) {
        ChatFormatting style = value ? ChatFormatting.GREEN : ChatFormatting.RED;
        return Component.translatable(value
                ? "gui.openmodularturrets.on"
                : "gui.openmodularturrets.off").withStyle(style);
    }

    private static Component modeName(BaseMode mode) {
        return Component.translatable(switch (mode) {
            case ALWAYS_ON -> "gui.openmodularturrets.mode.always_on";
            case ALWAYS_OFF -> "gui.openmodularturrets.mode.always_off";
            case INVERTED -> "gui.openmodularturrets.mode.inverted";
            case NONINVERTED -> "gui.openmodularturrets.mode.noninverted";
        });
    }

    private static Component accessName(AccessLevel access) {
        return Component.translatable(switch (access) {
            case NONE -> "gui.openmodularturrets.access.none";
            case VIEW -> "gui.openmodularturrets.access.view";
            case USE -> "gui.openmodularturrets.access.use";
            case ADMIN -> "gui.openmodularturrets.access.admin";
        });
    }

    private enum Page {
        OVERVIEW,
        TARGETING,
        SECURITY,
        CAMOUFLAGE
    }
}
