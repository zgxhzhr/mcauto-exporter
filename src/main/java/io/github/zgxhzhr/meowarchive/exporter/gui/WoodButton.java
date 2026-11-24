package io.github.zgxhzhr.meowarchive.exporter.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/**
 * 木色按钮：深棕底 + 木色描边 + 白字（无阴影），可选在左侧带一个手绘复选框。
 */
class WoodButton extends GuiButton {

    private final Runnable action;
    private boolean checkBox;
    private boolean checked;

    WoodButton(int id, int x, int y, int width, int height, String text, Runnable action) {
        super(id, x, y, width, height, text);
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

    void setMessage(String text) {
        this.displayString = text;
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        if (!this.visible) {
            return;
        }
        boolean hovered = mouseX >= this.x && mouseY >= this.y
                && mouseX < this.x + this.width && mouseY < this.y + this.height;
        MeowArchiveStyle.fill(this.x, this.y, this.x + this.width, this.y + this.height,
                hovered ? MeowArchiveStyle.BUTTON_BG_HOVER : MeowArchiveStyle.BUTTON_BG);
        MeowArchiveStyle.outline(this.x, this.y, this.width, this.height, MeowArchiveStyle.PANEL_BORDER);

        int color = this.enabled ? MeowArchiveStyle.TEXT_COLOR : MeowArchiveStyle.SUBTEXT_COLOR;
        int textLeft = this.x;
        int textRight = this.x + this.width;
        if (this.checkBox) {
            int size = MeowArchiveStyle.checkSize();
            MeowArchiveStyle.checkBox(this.x + 6, this.y + (this.height - size) / 2, this.checked);
            textLeft += 6 + size + 4;
        }
        Minecraft mc = Minecraft.getInstance();
        int textWidth = mc.fontRenderer.getStringWidth(this.displayString);
        int tx = textLeft + (textRight - textLeft - textWidth) / 2;
        int ty = this.y + (this.height - 8) / 2;
        mc.fontRenderer.drawString(this.displayString, tx, ty, color);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (this.action != null) {
            this.action.run();
        }
    }
}
