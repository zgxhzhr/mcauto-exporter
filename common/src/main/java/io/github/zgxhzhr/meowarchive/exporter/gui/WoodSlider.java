package io.github.zgxhzhr.meowarchive.exporter.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 木色滑条：上行标签+数值，下行轨道+手柄。拖动时实时回调。
 */
class WoodSlider extends AbstractSliderButton {

    private final Component label;
    private final float min;
    private final float max;
    private final Function<Float, String> format;
    private final Consumer<Float> onChange;

    WoodSlider(int x, int y, int width, Component label, float min, float max, float value,
               Function<Float, String> format, Consumer<Float> onChange) {
        super(x, y, width, 22, label, normalize(value, min, max));
        this.label = label;
        this.min = min;
        this.max = max;
        this.format = format;
        this.onChange = onChange;
        updateMessage();
    }

    private static double normalize(float value, float min, float max) {
        if (max <= min) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (value - min) / (double) (max - min)));
    }

    float current() {
        return min + (max - min) * (float) value;
    }

    /** 外部（如拖动预览）更新数值时使用，只移动手柄、不触发回调。 */
    void setValueSilently(float v) {
        value = normalize(v, min, max);
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(format.apply(current())));
    }

    @Override
    protected void applyValue() {
        onChange.accept(current());
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        Font font = Minecraft.getInstance().font;
        int textColor = active ? MeowArchiveStyle.TEXT_COLOR : MeowArchiveStyle.SUBTEXT_COLOR;
        graphics.drawString(font, label, getX(), getY(), textColor, false);
        String text = format.apply(current());
        graphics.drawString(font, text, getX() + width - font.width(text), getY(),
                MeowArchiveStyle.SUBTEXT_COLOR, false);

        int trackY = getY() + 12;
        int trackH = 6;
        graphics.fill(getX(), trackY, getX() + width, trackY + trackH, MeowArchiveStyle.LIST_BG);
        graphics.renderOutline(getX(), trackY, width, trackH, 0x558B5A2B);
        int handleX = getX() + Math.round((float) value * (width - 1));
        graphics.fill(getX(), trackY, handleX + 1, trackY + trackH, MeowArchiveStyle.ACCENT);
        int handleColor = isHovered() ? 0xFFFFE082 : MeowArchiveStyle.PANEL_BORDER;
        graphics.fill(handleX - 1, trackY - 1, handleX + 2, trackY + trackH + 1, handleColor);
        handleCursor(graphics);
    }
}
