package io.github.zgxhzhr.mcauto.exporter.gui;

import io.github.zgxhzhr.mcauto.exporter.core.ExportMode;
import io.github.zgxhzhr.mcauto.exporter.core.ExportRequest;
import io.github.zgxhzhr.mcauto.exporter.core.ExportService;
import io.github.zgxhzhr.mcauto.exporter.core.Target;
import io.github.zgxhzhr.mcauto.exporter.scan.RegistryScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条图标导出：搜索并点选一个物品 / 实体 / 效果。
 */
public final class TargetPickerScreen extends Screen {
    private static final int ROW_H = 22;

    private final Screen parent;
    private final ExportRequest template;
    private final List<Target> all = new ArrayList<>();
    private final List<Target> filtered = new ArrayList<>();
    private EditBox search;
    private int scroll;
    private String message = "";

    private int listX;
    private int listW;
    private int listY;
    private int visible;

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

        listW = Math.min(420, width - 40);
        listX = (width - listW) / 2;
        listY = 62;
        visible = Math.max(3, (height - listY - 60) / ROW_H);

        search = new EditBox(font, listX, 34, listW, 18, Component.literal("搜索"));
        search.setMaxLength(128);
        search.setResponder(text -> refresh());
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(width / 2 - 50, height - 30, 100, 20).build());
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, height, 0xC0000000);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);

        int listH = visible * ROW_H;
        graphics.fill(listX - 4, listY - 4, listX + listW + 4, listY + listH + 4, 0xB0000000);
        for (int row = 0; row < visible; row++) {
            int idx = scroll + row;
            if (idx >= filtered.size()) {
                break;
            }
            Target target = filtered.get(idx);
            int rowY = listY + row * ROW_H;
            boolean hover = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hover) {
                graphics.fill(listX, rowY, listX + listW, rowY + ROW_H - 1, 0x30FFFFFF);
            }
            String kind;
            int kindColor;
            switch (target.kind) {
                case ITEM -> {
                    kind = "[物品]";
                    kindColor = 0xFFD966;
                }
                case ENTITY -> {
                    kind = "[实体]";
                    kindColor = 0x66E0E0;
                }
                default -> {
                    kind = "[效果]";
                    kindColor = 0xCC99FF;
                }
            }
            String name = !target.nameZh.isEmpty() ? target.nameZh : target.nameEn;
            if (name.isEmpty()) {
                name = target.id.getPath();
            }
            graphics.drawString(font, kind, listX + 2, rowY + 3, kindColor);
            graphics.drawString(font, clamp(name, listW - 60), listX + 8 + font.width(kind), rowY + 3, 0xFFFFFF);
            graphics.drawString(font, clamp(target.id.toString(), listW - 16), listX + 8, rowY + 13, 0xC0C0C0);
        }
        graphics.drawString(font, "共 " + filtered.size() + " 条 · 滚轮翻页 · 点击导出", listX, listY + listH + 8, 0xC8C8C8);
        if (!message.isEmpty()) {
            graphics.drawString(font, clamp(message, listW), listX, height - 48, 0xFF7777);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String clamp(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String dots = "…";
        return font.plainSubstrByWidth(text, maxWidth - font.width(dots)) + dots;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + visible * ROW_H) {
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
        if (filtered.size() > visible
                && mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + visible * ROW_H) {
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
