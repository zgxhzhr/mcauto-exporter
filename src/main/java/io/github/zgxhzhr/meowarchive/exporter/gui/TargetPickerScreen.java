package io.github.zgxhzhr.meowarchive.exporter.gui;

import io.github.zgxhzhr.meowarchive.exporter.core.ExportMode;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import io.github.zgxhzhr.meowarchive.exporter.scan.RegistryScanner;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条图标导出：搜索并点选一个物品 / 实体 / 效果。
 */
@SideOnly(Side.CLIENT)
public final class TargetPickerScreen extends GuiScreen {
    private static final int ROW_H = 24;
    private static final int PAD = 14;

    private final GuiScreen parent;
    private final ExportRequest template;
    private final List<Target> all = new ArrayList<>();
    private final List<Target> filtered = new ArrayList<>();
    private GuiTextField search;
    private int scroll;
    private String message = "";

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentLeft;
    private int contentRight;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int visible;
    private int messageY;

    public TargetPickerScreen(GuiScreen parent, ExportRequest template) {
        this.parent = parent;
        this.template = template;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        all.clear();
        try {
            all.addAll(RegistryScanner.scan(template));
        } catch (Throwable e) {
            message = "扫描失败：" + e.getMessage();
        }

        panelW = Math.min(520, width - 32);
        panelH = height - 32;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        contentLeft = panelX + PAD;
        contentRight = panelX + panelW - PAD;
        listX = contentLeft;
        listW = contentRight - contentLeft;
        listY = panelY + 58;
        int actionY = panelY + panelH - PAD - 20;
        listH = Math.max(36, actionY - 24 - listY);
        visible = Math.max(2, listH / ROW_H);
        messageY = actionY - 14;

        search = new GuiTextField(0, fontRendererObj, contentLeft, panelY + 34, listW, 18);
        search.setMaxStringLength(128);
        search.setFocused(true);
        buttonList.add(new WoodButton(1, contentRight - 80, actionY, 80, 20, "返回",
                () -> mc.displayGuiScreen(parent)));
        refresh();
    }

    private void refresh() {
        String query = search == null ? "" : search.getText().trim().toLowerCase();
        filtered.clear();
        for (Target target : all) {
            String hay = (target.nameZh + " " + target.nameEn + " " + target.id).toLowerCase();
            if (query.isEmpty() || hay.contains(query)) {
                filtered.add(target);
            }
        }
        scroll = 0;
    }

    private void pick(Target target) {
        ExportRequest request = new ExportRequest(ExportMode.SINGLE_ICON);
        request.namespaces.addAll(template.namespaces);
        request.kinds.addAll(template.kinds);
        request.sizes.addAll(template.sizes);
        request.singleTarget = target;
        String error = ExportService.get().start(request);
        if (error != null) {
            message = error;
            return;
        }
        mc.displayGuiScreen(parent);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        MeowArchiveStyle.fill(0, 0, width, height, MeowArchiveStyle.SCREEN_BG);
        MeowArchiveStyle.panel(panelX, panelY, panelW, panelH);

        MeowArchiveStyle.centered(fontRendererObj, "选择要导出的条目", width / 2, panelY + 9,
                MeowArchiveStyle.HEADER_COLOR);
        MeowArchiveStyle.separator(panelX + 10, panelX + panelW - 10, panelY + 25);

        MeowArchiveStyle.fill(listX, listY, listX + listW, listY + listH, MeowArchiveStyle.LIST_BG);
        MeowArchiveStyle.outline(listX, listY, listW, listH, 0x558B5A2B);
        renderRows(mouseX, mouseY);

        String hint = "共 " + filtered.size() + " 条 · 滚轮翻页 · 点击导出该条图标（只写该命名空间目录下的 PNG）";
        fontRendererObj.drawString(MeowArchiveStyle.clamp(fontRendererObj, hint, contentRight - contentLeft - 90),
                contentLeft, messageY, MeowArchiveStyle.SUBTEXT_COLOR, false);
        if (!message.isEmpty()) {
            fontRendererObj.drawString(MeowArchiveStyle.clamp(fontRendererObj, message,
                    contentRight - contentLeft), contentLeft, messageY - 11,
                    MeowArchiveStyle.DANGER, false);
        }

        search.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void renderRows(int mouseX, int mouseY) {
        if (filtered.isEmpty()) {
            fontRendererObj.drawString("没有匹配的条目", listX + 8, listY + 8,
                    MeowArchiveStyle.SUBTEXT_COLOR, false);
            return;
        }
        MeowArchiveStyle.enableScissor(mc, listX, listY, listW, listH);
        for (int row = 0; row < visible; row++) {
            int idx = scroll + row;
            if (idx >= filtered.size()) {
                break;
            }
            Target target = filtered.get(idx);
            int rowY = listY + row * ROW_H;
            if (MeowArchiveStyle.inRect(listX, rowY, listW, ROW_H, mouseX, mouseY)) {
                MeowArchiveStyle.fill(listX, rowY, listX + listW, rowY + ROW_H - 1,
                        MeowArchiveStyle.ROW_HOVER);
            }
            String kind;
            int kindColor;
            if (target.kind == Target.Kind.ITEM) {
                kind = "[物品]";
                kindColor = 0xFFFFD966;
            } else if (target.kind == Target.Kind.ENTITY) {
                kind = "[实体]";
                kindColor = 0xFF66E0E0;
            } else {
                kind = "[效果]";
                kindColor = 0xFFCC99FF;
            }
            String name = !target.nameZh.isEmpty() ? target.nameZh : target.nameEn;
            if (name.isEmpty()) {
                name = target.id.getResourcePath();
            }
            int kindW = fontRendererObj.getStringWidth(kind);
            fontRendererObj.drawString(kind, listX + 6, rowY + 3, kindColor, false);
            fontRendererObj.drawString(MeowArchiveStyle.clamp(fontRendererObj, name,
                    listX + listW - 6 - (listX + 6 + kindW + 4)), listX + 6 + kindW + 4, rowY + 3,
                    MeowArchiveStyle.TEXT_COLOR, false);
            fontRendererObj.drawString(MeowArchiveStyle.clamp(fontRendererObj, target.id.toString(),
                    listW - 12), listX + 6, rowY + 13, MeowArchiveStyle.SUBTEXT_COLOR, false);
        }
        MeowArchiveStyle.disableScissor();

        int contentH = filtered.size() * ROW_H;
        if (contentH > listH) {
            int trackH = listH - 4;
            int thumbH = Math.max(16, trackH * listH / contentH);
            int maxScroll = contentH - listH;
            int progress = Math.min(trackH - thumbH,
                    (int) ((long) scroll * (trackH - thumbH) / Math.max(1, maxScroll)));
            int barX = listX + listW - 4;
            MeowArchiveStyle.fill(barX, listY + 2 + progress, barX + 2,
                    listY + 2 + progress + thumbH, 0x66FFFFFF);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        search.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 0 && MeowArchiveStyle.inRect(listX, listY, listW, listH, mouseX, mouseY)) {
            int row = (mouseY - listY) / ROW_H;
            int idx = scroll + row;
            if (idx < filtered.size()) {
                pick(filtered.get(idx));
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (search.textboxKeyTyped(typedChar, keyCode)) {
            refresh();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta != 0 && filtered.size() > visible) {
            int mouseX = Mouse.getEventX() * width / mc.displayWidth;
            int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            if (MeowArchiveStyle.inRect(listX, listY, listW, listH, mouseX, mouseY)) {
                scroll = Math.max(0, Math.min(filtered.size() - visible, scroll - Integer.signum(delta)));
            }
        }
    }

    @Override
    public void onGuiClosed() {
        // 关闭时不做额外处理，返回按钮负责切回上一级
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
