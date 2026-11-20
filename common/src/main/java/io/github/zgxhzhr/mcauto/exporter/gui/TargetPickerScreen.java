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
    private static final int ROW_H = 14;
    private static final int VISIBLE = 14;

    private final Screen parent;
    private final ExportRequest template;
    private final List<Target> all = new ArrayList<>();
    private final List<Target> filtered = new ArrayList<>();
    private EditBox search;
    private int scroll;
    private String message = "";

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
        search = new EditBox(font, width / 2 - 150, 32, 300, 18, Component.literal("搜索"));
        search.setResponder(text -> refresh());
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(width / 2 - 40, height - 30, 80, 20).build());
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
        Minecraft.getInstance().setScreen(new ExporterScreen());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        int listX = width / 2 - 150;
        int listY = 58;
        int listW = 300;
        graphics.fill(listX - 4, listY - 2, listX + listW + 4, listY + VISIBLE * ROW_H + 2, 0x66000000);
        for (int row = 0; row < VISIBLE; row++) {
            int idx = scroll + row;
            if (idx >= filtered.size()) {
                break;
            }
            Target target = filtered.get(idx);
            int rowY = listY + row * ROW_H;
            boolean hover = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hover) {
                graphics.fill(listX, rowY, listX + listW, rowY + ROW_H, 0x33FFFFFF);
            }
            String kind = switch (target.kind) {
                case ITEM -> "[物品]";
                case ENTITY -> "[实体]";
                case EFFECT -> "[效果]";
            };
            String name = !target.nameZh.isEmpty() ? target.nameZh : target.nameEn;
            graphics.drawString(font, kind + " " + name, listX + 2, rowY + 3, 0xFFFFFF);
            graphics.drawString(font, target.id.toString(), listX + listW - font.width(target.id.toString()),
                    rowY + 3, 0x808080);
        }
        graphics.drawString(font, "共 " + filtered.size() + " 条，滚轮翻页", listX, listY + VISIBLE * ROW_H + 8, 0x909090);
        if (!message.isEmpty()) {
            graphics.drawString(font, message, listX, height - 48, 0xFF6666);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int listX = width / 2 - 150;
        int listY = 58;
        if (mouseX >= listX && mouseX < listX + 300 && mouseY >= listY && mouseY < listY + VISIBLE * ROW_H) {
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
        if (filtered.size() > VISIBLE) {
            scroll = Math.max(0, Math.min(filtered.size() - VISIBLE, scroll - (int) Math.signum(scrollY)));
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
