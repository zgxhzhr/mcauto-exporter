package io.github.zgxhzhr.mcauto.exporter.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHoveredOrFocused();
        graphics.fill(getX(), getY(), getX() + width, getY() + height,
                hovered ? McAutoStyle.BUTTON_BG_HOVER : McAutoStyle.BUTTON_BG);
        graphics.renderOutline(getX(), getY(), width, height, McAutoStyle.PANEL_BORDER);

        Font font = Minecraft.getInstance().font;
        int color = active ? McAutoStyle.TEXT_COLOR : McAutoStyle.SUBTEXT_COLOR;
        int textLeft = getX();
        int textRight = getX() + width;
        if (checkBox) {
            int size = McAutoStyle.checkSize();
            McAutoStyle.checkBox(graphics, getX() + 6, getY() + (height - size) / 2, checked);
            textLeft += 6 + size + 4;
        }
        int textWidth = font.width(getMessage());
        int tx = textLeft + (textRight - textLeft - textWidth) / 2;
        int ty = getY() + (height - 8) / 2;
        graphics.drawString(font, getMessage(), tx, ty, color, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
