package io.github.zgxhzhr.meowarchive.exporter.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.lwjgl.opengl.GL11;

/**
 * 木头质感界面配色与绘制工具，与其它导出工具的 UI 风格保持一致。
 * 纯静态、无状态，供各 Screen 共用。
 */
final class MeowArchiveStyle {

    static final int SCREEN_BG = 0xFF101010;
    static final int PANEL_BG = 0xFF282018;
    static final int PANEL_BORDER = 0xFF8B5A2B;
    static final int LIST_BG = 0xFF1A1510;
    static final int ROW_HOVER = 0x33FFFFFF;
    static final int ROW_SELECTED = 0x40D0A060;
    static final int HEADER_COLOR = 0xFFFFE082;
    static final int TEXT_COLOR = 0xFFFFFFFF;
    static final int SUBTEXT_COLOR = 0xFFB0B0B0;
    static final int ACCENT = 0xFFD0A060;
    static final int BUTTON_BG = 0xFF3A2C1C;
    static final int BUTTON_BG_HOVER = 0xFF4A3826;
    static final int DANGER = 0xFFFF7766;

    private static final int CHECK_BORDER = 0xFFA89070;
    private static final int CHECK_INNER = 0xFF18120C;
    private static final int CHECK_MARK = 0xFFFFD700;
    private static final int CHECK_SIZE = 9;

    /** 手绘对勾图案（7×7 网格）。默认字体没有 ☐/☑ 字形，只能画像素。 */
    private static final int[][] CHECK_PIXELS = {
            {5, 0},
            {4, 1}, {5, 1},
            {3, 2}, {4, 2},
            {1, 3}, {2, 3}, {3, 3}, {4, 3},
            {0, 4}, {1, 4}, {2, 4}, {3, 4},
            {1, 5}, {2, 5},
            {2, 6}
    };

    private MeowArchiveStyle() {
    }

    static boolean inRect(int x, int y, int w, int h, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    static void fill(int x1, int y1, int x2, int y2, int color) {
        Gui.drawRect(x1, y1, x2, y2, color);
    }

    static void outline(int x, int y, int w, int h, int color) {
        fill(x, y, x + w, y + 1, color);
        fill(x, y + h - 1, x + w, y + h, color);
        fill(x, y, x + 1, y + h, color);
        fill(x + w - 1, y, x + w, y + h, color);
    }

    static void panel(int x, int y, int w, int h) {
        fill(x, y, x + w, y + h, PANEL_BG);
        fill(x, y, x + w, y + 1, PANEL_BORDER);
        fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        fill(x, y, x + 1, y + h, PANEL_BORDER);
        fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
        fill(x + 1, y + 1, x + w - 1, y + 2, ACCENT);
        fill(x + 1, y + h - 2, x + w - 1, y + h - 1, ACCENT);
    }

    static void separator(int x1, int x2, int y) {
        fill(x1, y, x2, y + 1, 0x55D0A060);
    }

    static void checkBox(int x, int y, boolean checked) {
        fill(x, y, x + CHECK_SIZE, y + CHECK_SIZE, CHECK_BORDER);
        fill(x + 1, y + 1, x + CHECK_SIZE - 1, y + CHECK_SIZE - 1, CHECK_INNER);
        if (checked) {
            for (int[] p : CHECK_PIXELS) {
                fill(x + 1 + p[0], y + 1 + p[1], x + 2 + p[0], y + 2 + p[1], CHECK_MARK);
            }
        }
    }

    static int checkSize() {
        return CHECK_SIZE;
    }

    /** 超宽截断加省略号。 */
    static String clamp(net.minecraft.client.gui.FontRenderer font, String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String dots = "…";
        int room = maxWidth - font.getStringWidth(dots);
        if (room <= 0) {
            return dots;
        }
        return font.trimStringToWidth(text, room) + dots;
    }

    /** 无阴影居中文字：阴影会把深色底上的彩色字糊掉。 */
    static void centered(net.minecraft.client.gui.FontRenderer font, String text, int centerX, int y, int color) {
        font.drawString(text, centerX - font.getStringWidth(text) / 2, y, color);
    }

    /** 列表裁剪：换算成窗口像素坐标后交给 GL。 */
    static void enableScissor(Minecraft mc, int x, int y, int w, int h) {
        double scale = mc.mainWindow.getGuiScaleFactor();
        int sx = (int) (x * scale);
        int sy = mc.mainWindow.getFramebufferHeight() - (int) ((y + h) * scale);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, (int) (w * scale), (int) (h * scale));
    }

    static void disableScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
