package io.github.zgxhzhr.meowarchive.exporter.gui;

import io.github.zgxhzhr.meowarchive.exporter.core.ExportMode;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import io.github.zgxhzhr.meowarchive.exporter.scan.RegistryScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条图标导出：搜索并点选一个物品 / 实体 / 效果。
 */
public final class TargetPickerScreen extends Screen {
    private static final int ROW_H = 24;
    private static final int PAD = 14;

    private final Screen parent;
    private final ExportRequest template;
    private final List<Target> all = new ArrayList<>();
    private final List<Target> filtered = new ArrayList<>();
    private EditBox search;
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

    public TargetPickerScreen(Screen parent, ExportRequest template) {
        super(Component.literal("选择要导出的条目"));
        this.parent = parent;
        this.template = template;
    }

    @Override
    protected void init() {
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

        search = new EditBox(font, contentLeft, panelY + 34, listW, 18, Component.literal("搜索"));
        search.setMaxLength(128);
        search.setResponder(text -> refresh());
        addRenderableWidget(search);
        addRenderableWidget(new WoodButton(contentRight - 80, actionY, 80, 20,
                Component.literal("返回"), this::onClose));
        refresh();
    }

    private void refresh() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase();
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
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 纯色底，不用原版模糊背景
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, MeowArchiveStyle.SCREEN_BG);
        MeowArchiveStyle.panel(graphics, panelX, panelY, panelW, panelH);

        Font font = this.font;
        MeowArchiveStyle.centered(graphics, font, title.getString(), width / 2, panelY + 9, MeowArchiveStyle.HEADER_COLOR);
        MeowArchiveStyle.separator(graphics, panelX + 10, panelX + panelW - 10, panelY + 25);

        graphics.fill(listX, listY, listX + listW, listY + listH, MeowArchiveStyle.LIST_BG);
        graphics.renderOutline(listX, listY, listW, listH, 0x558B5A2B);
        renderRows(graphics, font, mouseX, mouseY);

        String hint = "共 " + filtered.size() + " 条 · 滚轮翻页 · 点击导出该条图标（只写该命名空间目录下的 PNG）";
        graphics.drawString(font, MeowArchiveStyle.clamp(font, hint, contentRight - contentLeft - 90),
                contentLeft, messageY, MeowArchiveStyle.SUBTEXT_COLOR, false);
        if (!message.isEmpty()) {
            graphics.drawString(font, MeowArchiveStyle.clamp(font, message, contentRight - contentLeft),
                    contentLeft, messageY - 11, MeowArchiveStyle.DANGER, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRows(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (filtered.isEmpty()) {
            graphics.drawString(font, "没有匹配的条目", listX + 8, listY + 8,
                    MeowArchiveStyle.SUBTEXT_COLOR, false);
            return;
        }
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int row = 0; row < visible; row++) {
            int idx = scroll + row;
            if (idx >= filtered.size()) {
                break;
            }
            Target target = filtered.get(idx);
            int rowY = listY + row * ROW_H;
            if (MeowArchiveStyle.inRect(listX, rowY, listW, ROW_H, mouseX, mouseY)) {
                graphics.fill(listX, rowY, listX + listW, rowY + ROW_H - 1, MeowArchiveStyle.ROW_HOVER);
            }
            String kind;
            int kindColor;
            switch (target.kind) {
                case ITEM -> {
                    kind = "[物品]";
                    kindColor = 0xFFFFD966;
                }
                case ENTITY -> {
                    kind = "[实体]";
                    kindColor = 0xFF66E0E0;
                }
                default -> {
                    kind = "[效果]";
                    kindColor = 0xFFCC99FF;
                }
            }
            String name = !target.nameZh.isEmpty() ? target.nameZh : target.nameEn;
            if (name.isEmpty()) {
                name = target.id.getPath();
            }
            int kindW = font.width(kind);
            graphics.drawString(font, kind, listX + 6, rowY + 3, kindColor, false);
            graphics.drawString(font,
                    MeowArchiveStyle.clamp(font, name, listX + listW - 6 - (listX + 6 + kindW + 4)),
                    listX + 6 + kindW + 4, rowY + 3, MeowArchiveStyle.TEXT_COLOR, false);
            graphics.drawString(font, MeowArchiveStyle.clamp(font, target.id.toString(), listW - 12),
                    listX + 6, rowY + 13, MeowArchiveStyle.SUBTEXT_COLOR, false);
        }
        graphics.disableScissor();

        int contentH = filtered.size() * ROW_H;
        if (contentH > listH) {
            int trackH = listH - 4;
            int thumbH = Math.max(16, trackH * listH / contentH);
            int maxScroll = contentH - listH;
            int progress = Math.min(trackH - thumbH,
                    (int) ((long) scroll * (trackH - thumbH) / Math.max(1, maxScroll)));
            int barX = listX + listW - 4;
            graphics.fill(barX, listY + 2 + progress, barX + 2, listY + 2 + progress + thumbH, 0x66FFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (MeowArchiveStyle.inRect(listX, listY, listW, listH, mouseX, mouseY)) {
            int row = (int) ((mouseY - listY) / ROW_H);
            int idx = scroll + row;
            if (idx < filtered.size()) {
                pick(filtered.get(idx));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (filtered.size() > visible && MeowArchiveStyle.inRect(listX, listY, listW, listH, mouseX, mouseY)) {
            scroll = Math.max(0, Math.min(filtered.size() - visible, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
