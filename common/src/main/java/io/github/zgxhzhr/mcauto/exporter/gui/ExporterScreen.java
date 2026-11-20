package io.github.zgxhzhr.mcauto.exporter.gui;

import io.github.zgxhzhr.mcauto.exporter.config.ExporterConfig;
import io.github.zgxhzhr.mcauto.exporter.core.ExportMode;
import io.github.zgxhzhr.mcauto.exporter.core.ExportRequest;
import io.github.zgxhzhr.mcauto.exporter.core.ExportService;
import io.github.zgxhzhr.mcauto.exporter.core.Target;
import io.github.zgxhzhr.mcauto.exporter.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导出主界面：命名空间、类型、尺寸、模式、进度。
 */
public final class ExporterScreen extends Screen {
    private static final int RIGHT_W = 220;
    private static final int ROW_H = 24;

    private final List<NsEntry> namespaces = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private ExportMode mode = ExportMode.FULL;
    private boolean items = true;
    private boolean entities = true;
    private boolean effects = true;
    private boolean size32 = true;
    private boolean size128 = true;
    private int scroll;
    private String message = "";
    private Button modeButton;
    private Button startButton;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int leftX;
    private int leftW;
    private int rightX;
    private int listTop;
    private int visibleRows;

    public ExporterScreen() {
        super(Component.literal("mcauto exporter"));
    }

    @Override
    protected void init() {
        namespaces.clear();
        Set<String> contentNamespaces = contentNamespaces();
        List<String> modIds = new ArrayList<>(Services.PLATFORM.loadedModIds());
        modIds.sort(String::compareTo);
        for (String id : modIds) {
            if (id.equals("minecraft") || id.equals("java") || id.startsWith("fabric")) {
                continue;
            }
            if (contentNamespaces.contains(id)) {
                namespaces.add(new NsEntry(id, Services.PLATFORM.modName(id)));
            }
        }
        if (selected.isEmpty() && !namespaces.isEmpty()) {
            selected.add(namespaces.get(0).id);
        }

        rightX = width - 16 - RIGHT_W;
        leftW = Math.max(120, Math.min(210, rightX - 40));
        leftX = Math.max(12, rightX - 16 - leftW);
        panelX = leftX - 8;
        panelY = 34;
        panelW = rightX + RIGHT_W + 8 - panelX;
        panelH = height - panelY - 12;
        listTop = 52;
        visibleRows = Math.max(3, (height - 70 - listTop) / ROW_H);

        int x = rightX;
        int y = 48;
        modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> {
            mode = switch (mode) {
                case FULL -> ExportMode.JSON_ONLY;
                case JSON_ONLY -> ExportMode.ICONS_ONLY;
                default -> ExportMode.FULL;
            };
            b.setMessage(modeLabel());
        }).bounds(x, y, RIGHT_W, 20).build());

        addRenderableWidget(Button.builder(typeLabel("物品 / 方块", items), b -> {
            items = !items;
            b.setMessage(typeLabel("物品 / 方块", items));
        }).bounds(x, y + 26, 106, 20).build());
        addRenderableWidget(Button.builder(typeLabel("实体", entities), b -> {
            entities = !entities;
            b.setMessage(typeLabel("实体", entities));
        }).bounds(x + 114, y + 26, 106, 20).build());
        addRenderableWidget(Button.builder(typeLabel("效果", effects), b -> {
            effects = !effects;
            b.setMessage(typeLabel("效果", effects));
        }).bounds(x, y + 52, RIGHT_W, 20).build());

        addRenderableWidget(Button.builder(sizeLabel(32, size32), b -> {
            size32 = !size32;
            b.setMessage(sizeLabel(32, size32));
        }).bounds(x, y + 78, 106, 20).build());
        addRenderableWidget(Button.builder(sizeLabel(128, size128), b -> {
            size128 = !size128;
            b.setMessage(sizeLabel(128, size128));
        }).bounds(x + 114, y + 78, 106, 20).build());

        addRenderableWidget(Button.builder(Component.literal("导出单个图标…"), b ->
                Minecraft.getInstance().setScreen(new TargetPickerScreen(this, baseRequest(ExportMode.SINGLE_ICON))))
                .bounds(x, y + 104, RIGHT_W, 20).build());

        startButton = addRenderableWidget(Button.builder(Component.literal("开始导出"), b -> start())
                .bounds(x, height - 54, 106, 20).build());
        addRenderableWidget(Button.builder(Component.literal("打开目录"), b -> openOutput())
                .bounds(x + 114, height - 54, 106, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(x, height - 30, RIGHT_W, 20).build());
    }

    private void start() {
        if (ExportService.get().isRunning()) {
            message = "已有任务在运行";
            return;
        }
        if (selected.isEmpty()) {
            message = "至少选择一个命名空间";
            return;
        }
        if (!items && !entities && !effects) {
            message = "至少选择一种类型";
            return;
        }
        if (!size32 && !size128) {
            message = "至少选择一个尺寸";
            return;
        }
        message = "";
        String err = ExportService.get().start(baseRequest(mode));
        if (err != null) {
            message = err;
        }
    }

    private ExportRequest baseRequest(ExportMode exportMode) {
        ExportRequest request = new ExportRequest(exportMode);
        request.namespaces.addAll(selected);
        if (items) {
            request.kinds.add(Target.Kind.ITEM);
        }
        if (entities) {
            request.kinds.add(Target.Kind.ENTITY);
        }
        if (effects) {
            request.kinds.add(Target.Kind.EFFECT);
        }
        if (size32) {
            request.sizes.add(32);
        }
        if (size128) {
            request.sizes.add(128);
        }
        return request;
    }

    private void openOutput() {
        Path path = ExporterConfig.get().outputPath();
        try {
            Util.getPlatform().openUri(path.toUri().toString());
        } catch (Throwable e) {
            message = "无法打开目录：" + e.getMessage();
        }
    }

    private Component modeLabel() {
        return Component.literal("模式：" + mode.label);
    }

    private Component typeLabel(String name, boolean on) {
        return Component.literal((on ? "[x] " : "[ ] ") + name);
    }

    private Component sizeLabel(int size, boolean on) {
        return Component.literal((on ? "[x] " : "[ ] ") + size + "×" + size);
    }

    private Set<String> contentNamespaces() {
        Set<String> out = new HashSet<>();
        BuiltInRegistries.ITEM.keySet().forEach(id -> out.add(id.getNamespace()));
        BuiltInRegistries.MOB_EFFECT.keySet().forEach(id -> out.add(id.getNamespace()));
        BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> out.add(id.getNamespace()));
        return out;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, height, 0xC0000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0121212);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, 0x66FFFFFF);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0x66FFFFFF);

        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        String head = "命名空间 · 点击选择";
        String count = "已选 " + selected.size() + "/" + namespaces.size();
        graphics.drawString(font, clamp(head, leftW - font.width(count) - 10), leftX, 40, 0xFFFFFF);
        graphics.drawString(font, count, leftX + leftW - font.width(count), 40, 0xDCDCDC);

        int listH = visibleRows * ROW_H;
        graphics.fill(leftX - 4, listTop - 2, leftX + leftW, listTop + listH + 2, 0x90000000);
        for (int row = 0; row < visibleRows; row++) {
            int idx = scroll + row;
            if (idx >= namespaces.size()) {
                break;
            }
            NsEntry entry = namespaces.get(idx);
            int rowY = listTop + row * ROW_H;
            boolean on = selected.contains(entry.id);
            boolean hover = mouseX >= leftX && mouseX < leftX + leftW
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (on) {
                graphics.fill(leftX, rowY, leftX + leftW, rowY + ROW_H - 1, 0x3055CC55);
            } else if (hover) {
                graphics.fill(leftX, rowY, leftX + leftW, rowY + ROW_H - 1, 0x30FFFFFF);
            }
            graphics.drawString(font, (on ? "■ " : "□ ") + clamp(entry.name, leftW - 18),
                    leftX + 2, rowY + 3, on ? 0x7CFF7C : 0xFFFFFF);
            graphics.drawString(font, clamp(entry.id, leftW - 18), leftX + 12, rowY + 13, 0xC0C0C0);
        }
        graphics.drawString(font, "滚轮翻页", leftX, listTop + listH + 6, 0xA8A8A8);

        ExportService service = ExportService.get();
        int infoY = 180;
        if (service.isRunning()) {
            graphics.drawString(font, clamp("导出中：" + service.currentLabel(), RIGHT_W), rightX, infoY, 0xFFFF80);
            int barlen = RIGHT_W;
            graphics.fill(rightX, infoY + 14, rightX + barlen, infoY + 22, 0x66000000);
            int filled = service.total() == 0 ? 0 : (int) (barlen * (service.done() / (float) service.total()));
            graphics.fill(rightX, infoY + 14, rightX + filled, infoY + 22, 0xFF55CC55);
            graphics.drawString(font, service.done() + " / " + service.total(), rightX, infoY + 28, 0xFFFFFF);
            int line = infoY + 44;
            List<String> log = service.recentLog();
            for (String entry : log.subList(Math.max(0, log.size() - 4), log.size())) {
                if (line > height - 62) {
                    break;
                }
                graphics.drawString(font, clamp(entry, RIGHT_W), rightX, line, 0xB0B0B0);
                line += 11;
            }
            startButton.active = false;
        } else {
            startButton.active = true;
            if (!service.error().isEmpty()) {
                graphics.drawString(font, clamp(service.error(), RIGHT_W), rightX, infoY, 0xFF7777);
            }
        }
        if (!message.isEmpty()) {
            graphics.drawString(font, clamp(message, RIGHT_W), rightX, height - 72, 0xFFAA55);
        }
        graphics.drawString(font, clamp("输出：" + ExporterConfig.get().outputPath(), leftW),
                leftX, height - 16, 0xC8C8C8);
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
        if (mouseX >= leftX && mouseX < leftX + leftW
                && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_H) {
            int row = (int) ((mouseY - listTop) / ROW_H);
            int idx = scroll + row;
            if (idx < namespaces.size()) {
                String id = namespaces.get(idx).id;
                if (!selected.remove(id)) {
                    selected.add(id);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (namespaces.size() > visibleRows
                && mouseX >= leftX && mouseX < leftX + leftW
                && mouseY >= listTop && mouseY < listTop + visibleRows * ROW_H) {
            scroll = Math.max(0, Math.min(namespaces.size() - visibleRows,
                    scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record NsEntry(String id, String name) {
    }
}
