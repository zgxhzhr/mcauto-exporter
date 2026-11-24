package io.github.zgxhzhr.meowarchive.exporter.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;

/**
 * 木色按钮：深棕底 + 木色描边 + 白字（无阴影），可选在左侧带一个手绘复选框。
 */
class WoodButton extends AbstractButton {

    private final Runnable action;
    private boolean checkBox;
    private boolean checked;

    WoodButton(int x, int y, int width, int height, String message, Runnable action) {
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
    public void renderButton(int mouseX, int mouseY, float partialTick) {
        boolean hovered = this.isHovered() || this.isFocused();
        MeowArchiveStyle.fill(this.x, this.y, this.x + width, this.y + height,
                hovered ? MeowArchiveStyle.BUTTON_BG_HOVER : MeowArchiveStyle.BUTTON_BG);
        MeowArchiveStyle.outline(this.x, this.y, width, height, MeowArchiveStyle.PANEL_BORDER);

        Font font = Minecraft.getInstance().font;
        int color = active ? MeowArchiveStyle.TEXT_COLOR : MeowArchiveStyle.SUBTEXT_COLOR;
        int textLeft = this.x;
        int textRight = this.x + width;
        if (checkBox) {
            int size = MeowArchiveStyle.checkSize();
            MeowArchiveStyle.checkBox(this.x + 6, this.y + (height - size) / 2, checked);
            textLeft += 6 + size + 4;
        }
        int textWidth = font.width(getMessage());
        int tx = textLeft + (textRight - textLeft - textWidth) / 2;
        int ty = this.y + (height - 8) / 2;
        font.draw(getMessage(), tx, ty, color);
    }
}
