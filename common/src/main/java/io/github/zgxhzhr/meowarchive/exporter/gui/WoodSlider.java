package io.github.zgxhzhr.meowarchive.exporter.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 木色滑条：上行标签+数值，下行轨道+手柄。拖动时实时回调。
 */
class WoodSlider extends AbstractWidget {

    private final float min;
    private final float max;
    private final Function<Float, String> format;
    private final Consumer<Float> onChange;
    private float value;
    private boolean dragging;

    WoodSlider(int x, int y, int width, Component label, float min, float max, float value,
               Function<Float, String> format, Consumer<Float> onChange) {
        super(x, y, width, 22, label);
        this.min = min;
        this.max = max;
        this.value = clamp(value);
        this.format = format;
        this.onChange = onChange;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, getMessage(), getX(), getY(), MeowArchiveStyle.TEXT_COLOR, false);
        String text = format.apply(value);
        graphics.drawString(font, text, getX() + width - font.width(text), getY(),
                MeowArchiveStyle.SUBTEXT_COLOR, false);

        int trackY = getY() + 12;
        int trackH = 6;
        int trackW = width;
        graphics.fill(getX(), trackY, getX() + trackW, trackY + trackH, 0xFF1A1510);
        graphics.renderOutline(getX(), trackY, trackW, trackH, 0x558B5A2B);
        float t = (value - min) / (max - min);
        int handleX = getX() + Math.round(t * (trackW - 1));
        graphics.fill(getX(), trackY, handleX + 1, trackY + trackH, MeowArchiveStyle.ACCENT);
        int handleColor = isHoveredOrFocused() || dragging ? 0xFFFFE082 : MeowArchiveStyle.PANEL_BORDER;
        graphics.fill(handleX - 1, trackY - 1, handleX + 2, trackY + trackH + 1, handleColor);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOver(mouseX, mouseY)) {
            dragging = true;
            applyMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            applyMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return false;
    }

    private void applyMouse(double mouseX) {
        double t = width <= 1 ? 0.0 : (mouseX - getX()) / (width - 1.0);
        t = Math.max(0.0, Math.min(1.0, t));
        float next = clamp((float) (min + (max - min) * t));
        if (next != value) {
            value = next;
            onChange.accept(value);
        }
    }

    /** 外部（如拖动预览）更新数值时使用，只移动手柄、不触发回调。 */
    void setValueSilently(float v) {
        value = clamp(v);
    }

    private float clamp(float v) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
