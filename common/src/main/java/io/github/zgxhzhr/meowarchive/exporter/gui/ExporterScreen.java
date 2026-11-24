package io.github.zgxhzhr.meowarchive.exporter.gui;

import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportMode;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import io.github.zgxhzhr.meowarchive.exporter.platform.Services;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导出主界面：命名空间、类型、尺寸、模式、进度。
 *
 * <p>视觉层级：纯色底 → 木色主面板 → 分区（命名空间 / 导出选项 / 状态 / 操作）。
 */
public final class ExporterScreen extends Screen {
    private static final int ROW_H = 24;
    private static final int PAD = 14;

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
    private WoodButton modeButton;
    private WoodButton itemsButton;
    private WoodButton entitiesButton;
    private WoodButton effectsButton;
    private WoodButton size32Button;
    private WoodButton size128Button;
    private WoodButton startButton;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentLeft;
    private int contentRight;
    private int contentTop;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int visibleRows;
    private int rightX;
    private int rightW;
    private int statusY;
    private int actionY;

    public ExporterScreen() {
        super(Component.literal("Meow Archive Exporter"));
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

        panelW = Math.min(560, width - 32);
        panelH = height - 32;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        contentLeft = panelX + PAD;
        contentRight = panelX + panelW - PAD;
        contentTop = panelY + 32;

        rightW = Math.min(208, Math.max(150, (contentRight - contentLeft) / 2));
        rightX = contentRight - rightW;
        listX = contentLeft;
        listW = rightX - 12 - listX;
        listY = contentTop + 13;

        actionY = panelY + panelH - PAD - 20;
        statusY = actionY - 30;
        listH = Math.max(36, statusY - 8 - listY);
        visibleRows = Math.max(2, listH / ROW_H);

        int y = contentTop + 13;
        modeButton = addRenderableWidget(new WoodButton(rightX, y, rightW, 20, modeLabel(), () -> {
            mode = switch (mode) {
                case FULL -> ExportMode.JSON_ONLY;
                case JSON_ONLY -> ExportMode.ICONS_ONLY;
                default -> ExportMode.FULL;
            };
            modeButton.setMessage(modeLabel());
        }));

        int half = (rightW - 8) / 2;
        itemsButton = addRenderableWidget(new WoodButton(rightX, y + 25, half, 20,
                Component.literal("物品 / 方块"), () -> {
            items = !items;
            itemsButton.setChecked(items);
        }).withCheckBox(items));
        entitiesButton = addRenderableWidget(new WoodButton(rightX + rightW - half, y + 25, half, 20,
                Component.literal("实体"), () -> {
            entities = !entities;
            entitiesButton.setChecked(entities);
        }).withCheckBox(entities));
        effectsButton = addRenderableWidget(new WoodButton(rightX, y + 50, rightW, 20,
                Component.literal("效果"), () -> {
            effects = !effects;
            effectsButton.setChecked(effects);
        }).withCheckBox(effects));

        size32Button = addRenderableWidget(new WoodButton(rightX, y + 75, half, 20,
                Component.literal("32×32"), () -> {
            size32 = !size32;
            size32Button.setChecked(size32);
        }).withCheckBox(size32));
        size128Button = addRenderableWidget(new WoodButton(rightX + rightW - half, y + 75, half, 20,
                Component.literal("128×128"), () -> {
            size128 = !size128;
            size128Button.setChecked(size128);
        }).withCheckBox(size128));

        addRenderableWidget(new WoodButton(rightX, y + 100, rightW, 20,
                Component.literal("导出单个图标…"), () ->
                Minecraft.getInstance().setScreen(new TargetPickerScreen(this, baseRequest(ExportMode.SINGLE_ICON)))));

        int gap = 8;
        int closeW = 72;
        int openW = 92;
        int startW = Math.max(90, contentRight - contentLeft - closeW - openW - gap * 2);
        startButton = addRenderableWidget(new WoodButton(contentLeft, actionY, startW, 20,
                Component.literal("开始导出"), this::start));
        addRenderableWidget(new WoodButton(contentLeft + startW + gap, actionY, openW, 20,
                Component.literal("打开目录"), this::openOutput));
        addRenderableWidget(new WoodButton(contentRight - closeW, actionY, closeW, 20,
                Component.literal("关闭"), this::onClose));
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

    private Set<String> contentNamespaces() {
        Set<String> out = new HashSet<>();
        BuiltInRegistries.ITEM.keySet().forEach(id -> out.add(id.getNamespace()));
        BuiltInRegistries.MOB_EFFECT.keySet().forEach(id -> out.add(id.getNamespace()));
        BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> out.add(id.getNamespace()));
        return out;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不使用原版模糊/泥土背景：纯色底更稳，也避免世界画面透上来降低可读性。
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, MeowArchiveStyle.SCREEN_BG);
        MeowArchiveStyle.panel(graphics, panelX, panelY, panelW, panelH);

        Font font = this.font;
        MeowArchiveStyle.centered(graphics, font, title.getString(), width / 2, panelY + 9, MeowArchiveStyle.HEADER_COLOR);
        MeowArchiveStyle.separator(graphics, panelX + 10, panelX + panelW - 10, panelY + 25);

        // 分区标题
        graphics.drawString(font, "命名空间", listX, contentTop, MeowArchiveStyle.HEADER_COLOR, false);
        String count = "已选 " + selected.size() + "/" + namespaces.size();
        graphics.drawString(font, count, listX + listW - font.width(count), contentTop,
                MeowArchiveStyle.SUBTEXT_COLOR, false);
        graphics.drawString(font, "导出选项", rightX, contentTop, MeowArchiveStyle.HEADER_COLOR, false);

        renderList(graphics, mouseX, mouseY);
        renderStatus(graphics);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = this.font;
        graphics.fill(listX, listY, listX + listW, listY + listH, MeowArchiveStyle.LIST_BG);
        graphics.renderOutline(listX, listY, listW, listH, 0x558B5A2B);

        if (namespaces.isEmpty()) {
            graphics.drawString(font, "没有可导出的命名空间", listX + 8, listY + 8,
                    MeowArchiveStyle.SUBTEXT_COLOR, false);
            return;
        }

        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int row = 0; row < visibleRows; row++) {
            int idx = scroll + row;
            if (idx >= namespaces.size()) {
                break;
            }
            NsEntry entry = namespaces.get(idx);
            int rowY = listY + row * ROW_H;
            boolean on = selected.contains(entry.id);
            boolean hover = MeowArchiveStyle.inRect(listX, rowY, listW, ROW_H, mouseX, mouseY);
            if (on) {
                graphics.fill(listX, rowY, listX + listW, rowY + ROW_H - 1, MeowArchiveStyle.ROW_SELECTED);
            } else if (hover) {
                graphics.fill(listX, rowY, listX + listW, rowY + ROW_H - 1, MeowArchiveStyle.ROW_HOVER);
            }
            MeowArchiveStyle.checkBox(graphics, listX + 6, rowY + 7, on);
            int textLeft = listX + 6 + MeowArchiveStyle.checkSize() + 6;
            int textW = listX + listW - 6 - textLeft;
            graphics.drawString(font, MeowArchiveStyle.clamp(font, entry.name, textW), textLeft, rowY + 3,
                    on ? MeowArchiveStyle.ACCENT : MeowArchiveStyle.TEXT_COLOR, false);
            graphics.drawString(font, MeowArchiveStyle.clamp(font, entry.id, textW), textLeft, rowY + 13,
                    MeowArchiveStyle.SUBTEXT_COLOR, false);
        }
        graphics.disableScissor();

        int contentH = namespaces.size() * ROW_H;
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

    private void renderStatus(GuiGraphics graphics) {
        Font font = this.font;
        ExportService service = ExportService.get();
        if (service.isRunning()) {
            String label = MeowArchiveStyle.clamp(font, "导出中：" + service.currentLabel(),
                    contentRight - contentLeft);
            graphics.drawString(font, label, contentLeft, statusY, MeowArchiveStyle.ACCENT, false);
            int barW = contentRight - contentLeft;
            graphics.fill(contentLeft, statusY + 11, contentLeft + barW, statusY + 17, 0x66000000);
            int filled = service.total() == 0 ? 0 : (int) (barW * (service.done() / (float) service.total()));
            graphics.fill(contentLeft, statusY + 11, contentLeft + filled, statusY + 17, 0xFFD0A060);
            String detail = service.done() + " / " + service.total();
            List<String> log = service.recentLog();
            if (!log.isEmpty()) {
                detail += " · " + log.get(log.size() - 1);
            }
            graphics.drawString(font, MeowArchiveStyle.clamp(font, detail, contentRight - contentLeft),
                    contentLeft, statusY + 20, MeowArchiveStyle.SUBTEXT_COLOR, false);
            return;
        }
        String line;
        int color;
        if (!message.isEmpty()) {
            line = message;
            color = MeowArchiveStyle.ACCENT;
        } else if (!service.error().isEmpty()) {
            line = service.error();
            color = MeowArchiveStyle.DANGER;
        } else if (!service.lastResult().isEmpty()) {
            line = service.lastResult();
            color = MeowArchiveStyle.ACCENT;
        } else {
            line = "就绪";
            color = MeowArchiveStyle.SUBTEXT_COLOR;
        }
        graphics.drawString(font, MeowArchiveStyle.clamp(font, line, contentRight - contentLeft),
                contentLeft, statusY, color, false);
        graphics.drawString(font,
                MeowArchiveStyle.clamp(font, "输出：" + ExporterConfig.get().outputPath(),
                        contentRight - contentLeft),
                contentLeft, statusY + 11, MeowArchiveStyle.SUBTEXT_COLOR, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (MeowArchiveStyle.inRect(listX, listY, listW, listH, mouseX, mouseY)) {
            int row = (int) ((mouseY - listY) / ROW_H);
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
                && MeowArchiveStyle.inRect(listX, listY, listW, listH, mouseX, mouseY)) {
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
