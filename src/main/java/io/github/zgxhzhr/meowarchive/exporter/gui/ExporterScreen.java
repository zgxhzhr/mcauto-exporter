package io.github.zgxhzhr.meowarchive.exporter.gui;

import io.github.zgxhzhr.meowarchive.exporter.client.ClientPlatform;
import io.github.zgxhzhr.meowarchive.exporter.config.ExporterConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportMode;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportRequest;
import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import io.github.zgxhzhr.meowarchive.exporter.scan.RegistryScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.Util;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 导出主界面：命名空间、类型、尺寸、模式、进度。
 *
 * <p>视觉层级：纯色底 → 木色主面板 → 分区（命名空间 / 导出选项 / 状态 / 操作）。
 */
@SideOnly(Side.CLIENT)
public final class ExporterScreen extends GuiScreen {
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
    private boolean animated = true;
    private int scroll;
    private String message = "";
    private WoodButton modeButton;
    private WoodButton itemsButton;
    private WoodButton entitiesButton;
    private WoodButton effectsButton;
    private WoodButton size32Button;
    private WoodButton size128Button;
    private WoodButton animatedButton;

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
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        namespaces.clear();
        animated = ExporterConfig.get().animatedIcons;
        Set<String> contentNamespaces = RegistryScanner.contentNamespaces();
        Map<String, String> modIdByLower = new HashMap<>();
        for (String id : ClientPlatform.loadedModIds()) {
            if (isHiddenNamespace(id)) {
                continue;
            }
            modIdByLower.put(id.toLowerCase(Locale.ROOT), id);
        }
        List<String> domains = new ArrayList<>(contentNamespaces);
        java.util.Collections.sort(domains);
        for (String domain : domains) {
            if (isHiddenNamespace(domain)) {
                continue;
            }
            // 注册域被 ResourceLocation 强制小写，modId 却可能是 "Jaffas" 这类带大写的名字；
            // 按小写匹配回原始 modId 取显示名，匹配不到就直接用域名，避免整块内容被漏掉。
            String modId = modIdByLower.get(domain.toLowerCase(Locale.ROOT));
            namespaces.add(new NsEntry(domain, modId == null ? domain : ClientPlatform.modName(modId)));
        }
        if (contentNamespaces.contains("minecraft")) {
            namespaces.add(0, new NsEntry("minecraft", "Minecraft"));
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
        modeButton = new WoodButton(1, rightX, y, rightW, 20, modeLabel(), () -> {
            if (mode == ExportMode.FULL) {
                mode = ExportMode.JSON_ONLY;
            } else if (mode == ExportMode.JSON_ONLY) {
                mode = ExportMode.ICONS_ONLY;
            } else {
                mode = ExportMode.FULL;
            }
            modeButton.setMessage(modeLabel());
        });
        buttonList.add(modeButton);

        int half = (rightW - 8) / 2;
        itemsButton = new WoodButton(2, rightX, y + 25, half, 20, "物品 / 方块", () -> {
            items = !items;
            itemsButton.setChecked(items);
        }).withCheckBox(items);
        buttonList.add(itemsButton);
        entitiesButton = new WoodButton(3, rightX + rightW - half, y + 25, half, 20, "实体", () -> {
            entities = !entities;
            entitiesButton.setChecked(entities);
        }).withCheckBox(entities);
        buttonList.add(entitiesButton);
        effectsButton = new WoodButton(4, rightX, y + 50, rightW, 20, "效果", () -> {
            effects = !effects;
            effectsButton.setChecked(effects);
        }).withCheckBox(effects);
        buttonList.add(effectsButton);

        size32Button = new WoodButton(5, rightX, y + 75, half, 20, "32×32", () -> {
            size32 = !size32;
            size32Button.setChecked(size32);
        }).withCheckBox(size32);
        buttonList.add(size32Button);
        size128Button = new WoodButton(6, rightX + rightW - half, y + 75, half, 20, "128×128", () -> {
            size128 = !size128;
            size128Button.setChecked(size128);
        }).withCheckBox(size128);
        buttonList.add(size128Button);

        buttonList.add(new WoodButton(7, rightX, y + 100, half, 20, "导出单个图标…",
                () -> mc.displayGuiScreen(new TargetPickerScreen(this, baseRequest(ExportMode.SINGLE_ICON)))));
        animatedButton = new WoodButton(11, rightX + rightW - half, y + 100, half, 20, "动图导出", () -> {
            animated = !animated;
            ExporterConfig.get().animatedIcons = animated;
            ExporterConfig.save();
            animatedButton.setChecked(animated);
        }).withCheckBox(animated);
        buttonList.add(animatedButton);

        int gap = 8;
        int closeW = 72;
        int openW = 92;
        int startW = Math.max(90, contentRight - contentLeft - closeW - openW - gap * 2);
        buttonList.add(new WoodButton(8, contentLeft, actionY, startW, 20, "开始导出", this::start));
        buttonList.add(new WoodButton(9, contentLeft + startW + gap, actionY, openW, 20, "打开目录",
                this::openOutput));
        buttonList.add(new WoodButton(10, contentRight - closeW, actionY, closeW, 20, "关闭",
                () -> mc.displayGuiScreen(null)));
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
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(path.toFile());
            } else {
                message = "当前系统不支持打开目录：" + path;
            }
        } catch (Throwable e) {
            message = "无法打开目录：" + e.getMessage();
        }
    }

    private String modeLabel() {
        return "模式：" + mode.label;
    }

    private static boolean isHiddenNamespace(String id) {
        return "minecraft".equals(id) || "java".equals(id) || "forge".equals(id)
                || "fml".equals(id) || "mcp".equals(id);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        MeowArchiveStyle.fill(0, 0, width, height, MeowArchiveStyle.SCREEN_BG);
        MeowArchiveStyle.panel(panelX, panelY, panelW, panelH);

        MeowArchiveStyle.centered(fontRenderer, "喵呜档案导出器", width / 2, panelY + 9,
                MeowArchiveStyle.HEADER_COLOR);
        MeowArchiveStyle.separator(panelX + 10, panelX + panelW - 10, panelY + 25);

        fontRenderer.drawString("命名空间", listX, contentTop, MeowArchiveStyle.HEADER_COLOR, false);
        String count = "已选 " + selected.size() + "/" + namespaces.size();
        fontRenderer.drawString(count, listX + listW - fontRenderer.getStringWidth(count), contentTop,
                MeowArchiveStyle.SUBTEXT_COLOR, false);
        fontRenderer.drawString("导出选项", rightX, contentTop, MeowArchiveStyle.HEADER_COLOR, false);

        renderList(mouseX, mouseY);
        renderStatus();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void renderList(int mouseX, int mouseY) {
        MeowArchiveStyle.fill(listX, listY, listX + listW, listY + listH, MeowArchiveStyle.LIST_BG);
        MeowArchiveStyle.outline(listX, listY, listW, listH, 0x558B5A2B);

        if (namespaces.isEmpty()) {
            fontRenderer.drawString("没有可导出的命名空间", listX + 8, listY + 8,
                    MeowArchiveStyle.SUBTEXT_COLOR, false);
            return;
        }

        MeowArchiveStyle.enableScissor(mc, listX, listY, listW, listH);
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
                MeowArchiveStyle.fill(listX, rowY, listX + listW, rowY + ROW_H - 1,
                        MeowArchiveStyle.ROW_SELECTED);
            } else if (hover) {
                MeowArchiveStyle.fill(listX, rowY, listX + listW, rowY + ROW_H - 1,
                        MeowArchiveStyle.ROW_HOVER);
            }
            MeowArchiveStyle.checkBox(listX + 6, rowY + 7, on);
            int textLeft = listX + 6 + MeowArchiveStyle.checkSize() + 6;
            int textW = listX + listW - 6 - textLeft;
            fontRenderer.drawString(MeowArchiveStyle.clamp(fontRenderer, entry.name, textW), textLeft,
                    rowY + 3, on ? MeowArchiveStyle.ACCENT : MeowArchiveStyle.TEXT_COLOR, false);
            fontRenderer.drawString(MeowArchiveStyle.clamp(fontRenderer, entry.id, textW), textLeft,
                    rowY + 13, MeowArchiveStyle.SUBTEXT_COLOR, false);
        }
        MeowArchiveStyle.disableScissor();

        int contentH = namespaces.size() * ROW_H;
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

    private void renderStatus() {
        ExportService service = ExportService.get();
        if (service.isRunning()) {
            String label = MeowArchiveStyle.clamp(fontRenderer, "导出中：" + service.currentLabel(),
                    contentRight - contentLeft);
            fontRenderer.drawString(label, contentLeft, statusY, MeowArchiveStyle.ACCENT, false);
            int barW = contentRight - contentLeft;
            MeowArchiveStyle.fill(contentLeft, statusY + 11, contentLeft + barW, statusY + 17, 0x66000000);
            int filled = service.total() == 0 ? 0
                    : (int) (barW * (service.done() / (float) service.total()));
            MeowArchiveStyle.fill(contentLeft, statusY + 11, contentLeft + filled, statusY + 17,
                    0xFFD0A060);
            String detail = service.done() + " / " + service.total();
            List<String> log = service.recentLog();
            if (!log.isEmpty()) {
                detail += " · " + log.get(log.size() - 1);
            }
            fontRenderer.drawString(MeowArchiveStyle.clamp(fontRenderer, detail,
                    contentRight - contentLeft), contentLeft, statusY + 20,
                    MeowArchiveStyle.SUBTEXT_COLOR, false);
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
        fontRenderer.drawString(MeowArchiveStyle.clamp(fontRenderer, line, contentRight - contentLeft),
                contentLeft, statusY, color, false);
        fontRenderer.drawString(MeowArchiveStyle.clamp(fontRenderer,
                "输出：" + ExporterConfig.get().outputPath(), contentRight - contentLeft),
                contentLeft, statusY + 11, MeowArchiveStyle.SUBTEXT_COLOR, false);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (mouseButton == 0 && MeowArchiveStyle.inRect(listX, listY, listW, listH, mouseX, mouseY)) {
            int row = (mouseY - listY) / ROW_H;
            int idx = scroll + row;
            if (idx < namespaces.size()) {
                String id = namespaces.get(idx).id;
                if (!selected.remove(id)) {
                    selected.add(id);
                }
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta != 0 && namespaces.size() > visibleRows) {
            int mouseX = Mouse.getEventX() * width / mc.displayWidth;
            int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            if (MeowArchiveStyle.inRect(listX, listY, listW, listH, mouseX, mouseY)) {
                scroll = Math.max(0, Math.min(namespaces.size() - visibleRows,
                        scroll - Integer.signum(delta)));
            }
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static final class NsEntry {
        final String id;
        final String name;

        NsEntry(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
