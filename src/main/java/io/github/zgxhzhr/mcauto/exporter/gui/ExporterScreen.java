package io.github.zgxhzhr.mcauto.exporter.gui;

import io.github.zgxhzhr.mcauto.exporter.config.ExporterConfig;
import io.github.zgxhzhr.mcauto.exporter.core.ExportMode;
import io.github.zgxhzhr.mcauto.exporter.core.ExportRequest;
import io.github.zgxhzhr.mcauto.exporter.core.ExportService;
import io.github.zgxhzhr.mcauto.exporter.core.Target;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
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
    private static final int LEFT_W = 190;
    private static final int ROW_H = 14;
    private static final int VISIBLE_ROWS = 10;

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

    public ExporterScreen() {
        super(Component.literal("mcauto exporter"));
    }

    @Override
    protected void init() {
        namespaces.clear();
        Set<String> contentNamespaces = contentNamespaces();
        List<String> modIds = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            modIds.add(mod.getMetadata().getId());
        }
        modIds.sort(String::compareTo);
        for (String id : modIds) {
            if (id.equals("minecraft") || id.equals("java") || id.startsWith("fabric")) {
                continue;
            }
            if (contentNamespaces.contains(id)) {
                String name = FabricLoader.getInstance().getModContainer(id)
                        .map(m -> m.getMetadata().getName()).orElse(id);
                namespaces.add(new NsEntry(id, name));
            }
        }
        if (selected.isEmpty() && !namespaces.isEmpty()) {
            selected.add(namespaces.get(0).id);
        }

        int x = width - 250;
        int y = 44;
        modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> {
            mode = switch (mode) {
                case FULL -> ExportMode.JSON_ONLY;
                case JSON_ONLY -> ExportMode.ICONS_ONLY;
                default -> ExportMode.FULL;
            };
            b.setMessage(modeLabel());
        }).bounds(x, y, 220, 20).build());

        addRenderableWidget(Button.builder(typeLabel("物品 / 方块", items), b -> {
            items = !items;
            b.setMessage(typeLabel("物品 / 方块", items));
        }).bounds(x, y + 26, 108, 20).build());
        addRenderableWidget(Button.builder(typeLabel("实体", entities), b -> {
            entities = !entities;
            b.setMessage(typeLabel("实体", entities));
        }).bounds(x + 112, y + 26, 108, 20).build());
        addRenderableWidget(Button.builder(typeLabel("效果", effects), b -> {
            effects = !effects;
            b.setMessage(typeLabel("效果", effects));
        }).bounds(x, y + 50, 108, 20).build());

        addRenderableWidget(Button.builder(sizeLabel(32, size32), b -> {
            size32 = !size32;
            b.setMessage(sizeLabel(32, size32));
        }).bounds(x, y + 76, 108, 20).build());
        addRenderableWidget(Button.builder(sizeLabel(128, size128), b -> {
            size128 = !size128;
            b.setMessage(sizeLabel(128, size128));
        }).bounds(x + 112, y + 76, 108, 20).build());

        addRenderableWidget(Button.builder(Component.literal("导出单个图标…"), b ->
                Minecraft.getInstance().setScreen(new TargetPickerScreen(this, baseRequest(ExportMode.SINGLE_ICON))))
                .bounds(x, y + 104, 220, 20).build());

        startButton = addRenderableWidget(Button.builder(Component.literal("开始导出"), b -> start())
                .bounds(x, height - 58, 108, 20).build());
        addRenderableWidget(Button.builder(Component.literal("打开目录"), b -> openOutput())
                .bounds(x + 112, height - 58, 108, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(x, height - 34, 220, 20).build());
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
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        graphics.drawString(font, "命名空间", 16, 30, 0xA0A0A0);

        int listX = 12;
        int listY = 44;
        int listH = VISIBLE_ROWS * ROW_H;
        graphics.fill(listX - 4, listY - 2, listX + LEFT_W - 4, listY + listH + 2, 0x66000000);
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int idx = scroll + row;
            if (idx >= namespaces.size()) {
                break;
            }
            NsEntry entry = namespaces.get(idx);
            int rowY = listY + row * ROW_H;
            boolean on = selected.contains(entry.id);
            boolean hover = mouseX >= listX && mouseX < listX + LEFT_W - 8
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hover) {
                graphics.fill(listX, rowY, listX + LEFT_W - 8, rowY + ROW_H, 0x33FFFFFF);
            }
            graphics.drawString(font, (on ? "■ " : "□ ") + entry.name, listX + 2, rowY + 3,
                    on ? 0x7CFF7C : 0xD0D0D0);
            graphics.drawString(font, entry.id, listX + LEFT_W - 8 - font.width(entry.id), rowY + 3, 0x808080);
        }
        graphics.drawString(font, "滚轮翻页 · 点击选择", 14, listY + listH + 8, 0x808080);

        ExportService service = ExportService.get();
        int x = width - 250;
        if (service.isRunning()) {
            graphics.drawString(font, "导出中：" + service.currentLabel(), x, height - 150, 0xFFFF80);
            int barlen = 220;
            graphics.fill(x, height - 134, x + barlen, height - 126, 0x66000000);
            int filled = service.total() == 0 ? 0 : (int) (barlen * (service.done() / (float) service.total()));
            graphics.fill(x, height - 134, x + filled, height - 126, 0xFF55CC55);
            graphics.drawString(font, service.done() + " / " + service.total(),
                    x, height - 120, 0xFFFFFF);
            int line = height - 108;
            for (String entry : service.recentLog().subList(Math.max(0, service.recentLog().size() - 4),
                    service.recentLog().size())) {
                graphics.drawString(font, entry, x, line, 0xA0A0A0);
                line += 10;
            }
            startButton.active = false;
        } else {
            startButton.active = true;
            graphics.drawString(font, "输出：" + ExporterConfig.get().outputPath(), 14, height - 34, 0x909090);
            if (!service.error().isEmpty()) {
                graphics.drawString(font, service.error(), x, height - 150, 0xFF6666);
            }
        }
        if (!message.isEmpty()) {
            graphics.drawString(font, message, x, height - 170, 0xFFAA55);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int listX = 12;
        int listY = 44;
        if (mouseX >= listX && mouseX < listX + LEFT_W - 8 && mouseY >= listY && mouseY < listY + VISIBLE_ROWS * ROW_H) {
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
        if (namespaces.size() > VISIBLE_ROWS) {
            scroll = Math.max(0, Math.min(namespaces.size() - VISIBLE_ROWS,
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
