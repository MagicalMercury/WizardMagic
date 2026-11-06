package com.wizardmagic.common.blocks.research_desk;

import com.wizardmagic.WizardMagic;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 研究台 GUI。
 *
 * <p>背景使用 {@code textures/gui/research_desk.png}（176×166，原版容器布局）：
 * 纸槽在 (80,36)，玩家背包 3×9 从 (8,84) 起，快捷栏从 (8,142) 起，
 * 与 {@link ResearchDeskMenu} 里 {@code addSlot} 的坐标一一对应。</p>
 *
 * <p><b>注意 blit 重载</b>：7 参数版本 {@code blit(rl, x, y, u, v, w, h)} 内部把纹理尺寸
 * 硬编码成 256×256，贴图不是 256 见方时会只采样左上角一部分再拉伸（表现为整体放大、错位）。
 * 这里必须用带 {@code textureWidth/textureHeight} 的 9 参数重载。</p>
 */
public class ResearchDeskScreen extends AbstractContainerScreen<ResearchDeskMenu> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(WizardMagic.MODID, "textures/gui/research_desk.png");

    public ResearchDeskScreen(ResearchDeskMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 72;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 9 参数重载：显式给出纹理尺寸，避免被当成 256×256 采样
        graphics.blit(TEXTURE, leftPos, topPos, 0.0F, 0.0F,
                imageWidth, imageHeight, imageWidth, imageHeight);
    }
}
