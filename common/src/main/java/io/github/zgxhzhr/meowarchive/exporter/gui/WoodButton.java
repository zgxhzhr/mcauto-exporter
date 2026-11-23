package io.github.zgxhzhr.meowarchive.exporter.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 木色按钮：深棕底 + 木色描边 + 白字（无阴影），可选在左侧带一个手绘复选框。
 */
class WoodButton extends AbstractButton {

    private final Runnable action;
    private boolean checkBox;
    private boolean checked;

    WoodButton(int x, int y, int width, int height, Component message, Runnable action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    WoodButton withCheckBox(boolean on) {
        this.checkBox = true;
        this.checked = on;
        return this;
    }

    void setChecked(boolean on) {
        this.checked = on;
    }

    @Override
    public void onPress() {
        this.action.run();
    }

    @Override
    public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHoveredOrFocused();
        MeowArchiveStyle.fill(pose, this.x, this.y, this.x + width, this.y + height,
                hovered ? MeowArchiveStyle.BUTTON_BG_HOVER : MeowArchiveStyle.BUTTON_BG);
        MeowArchiveStyle.outline(pose, this.x, this.y, width, height, MeowArchiveStyle.PANEL_BORDER);

        Font font = Minecraft.getInstance().font;
        int color = active ? MeowArchiveStyle.TEXT_COLOR : MeowArchiveStyle.SUBTEXT_COLOR;
        int textLeft = this.x;
        int textRight = this.x + width;
        if (checkBox) {
            int size = MeowArchiveStyle.checkSize();
            MeowArchiveStyle.checkBox(pose, this.x + 6, this.y + (height - size) / 2, checked);
            textLeft += 6 + size + 4;
        }
        int textWidth = font.width(getMessage());
        int tx = textLeft + (textRight - textLeft - textWidth) / 2;
        int ty = this.y + (height - 8) / 2;
        font.draw(pose, getMessage(), tx, ty, color);
    }

    @Override
    public void updateNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
