package io.github.zgxhzhr.meowarchive.exporter.gui;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.core.SingleEntityConfig;
import io.github.zgxhzhr.meowarchive.exporter.core.SingleEntityExporter;
import io.github.zgxhzhr.meowarchive.exporter.render.EntityIconRenderer;
import io.github.zgxhzhr.meowarchive.exporter.render.EntityRenderOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * 单实体渲染界面：左边实时预览，右边分页提供视角/外观/导出配置，底部一键出图。
 *
 * <p>入口：手持喵呜机中键点中实体；也可以用 /meowarchive entity 对准实体打开。
 */
public final class EntityPreviewScreen extends Screen {
    private static final int PAD = 12;
    private static final int ROW_STEP = 24;
    private static final int PREVIEW_TEX = 256;
    private static final String[] TABS = {"视角", "外观", "导出"};
    private static final int[] SIZES = {32, 128, 256, 512};

    private final Entity live;
    private final EntityType<?> type;
    private final Component entityName;
    private final SingleEntityConfig cfg;
    private final PreviewTexture preview = new PreviewTexture();

    private Entity posed;
    private int tab;
    private boolean dirty = true;
    private int previewFailures;
    private boolean exportPending;
    private volatile boolean renderPending;
    private volatile BufferedImage pendingImage;
    private String message = "拖动右侧滑条调整，满意后点“导出 PNG”";

    private WoodSlider offsetXSlider;
    private WoodSlider offsetYSlider;
    private boolean draggingPreview;
    private double dragStartX;
    private double dragStartY;
    private float dragStartOffsetX;
    private float dragStartOffsetY;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int previewX;
    private int previewY;
    private int previewBox;
    private int rightX;
    private int rightW;
    private int tabsY;
    private int contentTop;
    private int statusY;
    private int actionY;

    public EntityPreviewScreen(Entity source) {
        super(Component.literal("实体单独渲染"));
        this.live = source;
        this.type = source.getType();
        this.entityName = source.getDisplayName();
        this.cfg = SingleEntityConfig.get();
    }

    @Override
    protected void init() {
        panelW = Math.min(620, Math.max(280, width - 20));
        panelH = Math.min(330, Math.max(190, height - 20));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        previewBox = Math.max(84, Math.min(200, Math.min(panelH - 96, (int) (panelW * 0.38F))));
        previewX = panelX + PAD;
        previewY = panelY + 30;

        rightX = previewX + previewBox + 14;
        rightW = Math.max(120, panelX + panelW - PAD - rightX);
        tabsY = panelY + 30;
        contentTop = tabsY + 24;
        statusY = panelY + panelH - 42;
        actionY = panelY + panelH - 24;

        buildWidgets();
        dirty = true;
    }

    private void buildWidgets() {
        clearWidgets();
        offsetXSlider = null;
        offsetYSlider = null;

        int tabW = (rightW - 8) / 3;
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            String label = (tab == i ? "▸ " : "") + TABS[i];
            addRenderableWidget(new WoodButton(rightX + i * (tabW + 4), tabsY, tabW, 18,
                    Component.literal(label), () -> {
                tab = index;
                buildWidgets();
            }));
        }

        int y = contentTop;
        if (tab == 0) {
            addRowSlider(y, "水平角度", -180.0F, 180.0F,
                    cfg.options.yaw == null ? 30.0F : cfg.options.yaw,
                    v -> String.format(Locale.ROOT, "%.0f°", v),
                    v -> cfg.options.yaw = v);
            y += ROW_STEP;
            addRowSlider(y, "头部角度", -180.0F, 180.0F,
                    cfg.options.headYaw == null
                            ? (cfg.options.yaw == null ? 30.0F : cfg.options.yaw)
                            : cfg.options.headYaw,
                    v -> String.format(Locale.ROOT, "%.0f°", v),
                    v -> cfg.options.headYaw = v);
            y += ROW_STEP;
            addRowSlider(y, "俯仰", -60.0F, 60.0F, cfg.options.pitch,
                    v -> String.format(Locale.ROOT, "%.0f°", v),
                    v -> cfg.options.pitch = v);
            y += ROW_STEP;
            addRowSlider(y, "缩放", 0.4F, 2.5F, cfg.options.zoom,
                    v -> String.format(Locale.ROOT, "%.2fx", v),
                    v -> cfg.options.zoom = v);
            y += ROW_STEP;
            offsetXSlider = addRowSlider(y, "横向偏移", -0.4F, 0.4F, cfg.options.offsetX,
                    v -> String.format(Locale.ROOT, "%+.2f", v),
                    v -> cfg.options.offsetX = v);
            y += ROW_STEP;
            offsetYSlider = addRowSlider(y, "纵向偏移", -0.4F, 0.4F, cfg.options.offsetY,
                    v -> String.format(Locale.ROOT, "%+.2f", v),
                    v -> cfg.options.offsetY = v);
        } else if (tab == 1) {
            addRowSlider(y, "亮度", 0.3F, 1.6F, cfg.options.brightness,
                    v -> String.format(Locale.ROOT, "%.2fx", v),
                    v -> cfg.options.brightness = v);
            y += ROW_STEP;
            addRowSlider(y, "姿态帧", 0.0F, 80.0F, cfg.options.tick,
                    v -> String.format(Locale.ROOT, "%.0f", v),
                    v -> cfg.options.tick = Math.round(v));
            y += ROW_STEP;
            addToggle(y, "脚下阴影", cfg.options.shadow, on -> cfg.options.shadow = on);
            y += ROW_STEP;
            addRenderableWidget(new WoodButton(rightX, y, rightW, 20,
                    Component.literal("背景：" + backgroundLabel()), () -> {
                cycleBackground();
                buildWidgets();
            }));
        } else {
            int half = (rightW - 8) / 2;
            addToggle(y, "32×32", cfg.sizes.contains(32), on -> toggleSize(32, on), rightX, half);
            addToggle(y, "128×128", cfg.sizes.contains(128), on -> toggleSize(128, on),
                    rightX + rightW - half, half);
            addToggle(y + ROW_STEP, "256×256", cfg.sizes.contains(256), on -> toggleSize(256, on),
                    rightX, half);
            addToggle(y + ROW_STEP, "512×512", cfg.sizes.contains(512), on -> toggleSize(512, on),
                    rightX + rightW - half, half);
            addToggle(y + ROW_STEP * 2, "使用现场实体", cfg.useLiveEntity, on -> {
                cfg.useLiveEntity = on;
                posed = null;
                dirty = true;
            });
        }

        int gap = 6;
        int bw = Math.max(48, (panelW - PAD * 2 - gap * 2) / 3);
        addRenderableWidget(new WoodButton(panelX + PAD, actionY, bw, 20,
                Component.literal("导出 PNG"), this::export));
        addRenderableWidget(new WoodButton(panelX + PAD + bw + gap, actionY, bw, 20,
                Component.literal("重置参数"), this::reset));
        addRenderableWidget(new WoodButton(panelX + PAD + (bw + gap) * 2, actionY, bw, 20,
                Component.literal("关闭"), this::onClose));
    }

    private WoodSlider addRowSlider(int y, String label, float min, float max, float value,
                                    java.util.function.Function<Float, String> format,
                                    java.util.function.Consumer<Float> apply) {
        WoodSlider slider = new WoodSlider(rightX, y, rightW, Component.literal(label), min, max, value,
                format, v -> {
            apply.accept(v);
            dirty = true;
        });
        addRenderableWidget(slider);
        return slider;
    }

    private void addToggle(int y, String label, boolean on, java.util.function.Consumer<Boolean> apply) {
        addToggle(y, label, on, apply, rightX, rightW);
    }

    private void addToggle(int y, String label, boolean on, java.util.function.Consumer<Boolean> apply,
                           int x, int w) {
        boolean[] state = {on};
        WoodButton[] holder = new WoodButton[1];
        WoodButton button = new WoodButton(x, y, w, 20, Component.literal(label), () -> {
            state[0] = !state[0];
            holder[0].setChecked(state[0]);
            apply.accept(state[0]);
            dirty = true;
        }).withCheckBox(on);
        holder[0] = button;
        addRenderableWidget(button);
    }

    private void toggleSize(int size, boolean on) {
        if (on) {
            if (!cfg.sizes.contains(size)) {
                cfg.sizes.add(size);
            }
        } else {
            cfg.sizes.remove(Integer.valueOf(size));
        }
        buildWidgets();
    }

    private String backgroundLabel() {
        if (cfg.options.transparent) {
            return "透明";
        }
        return switch (cfg.options.background) {
            case 0xFFFFFFFF -> "白色";
            case 0xFF282018 -> "木色";
            case 0xFF00FF00 -> "绿幕";
            default -> "深灰";
        };
    }

    private void cycleBackground() {
        if (cfg.options.transparent) {
            cfg.options.transparent = false;
            cfg.options.background = 0xFF202020;
        } else if (cfg.options.background == 0xFF202020) {
            cfg.options.background = 0xFFFFFFFF;
        } else if (cfg.options.background == 0xFFFFFFFF) {
            cfg.options.background = 0xFF282018;
        } else if (cfg.options.background == 0xFF282018) {
            cfg.options.background = 0xFF00FF00;
        } else {
            cfg.options.transparent = true;
        }
        dirty = true;
    }

    private void reset() {
        cfg.options = new EntityRenderOptions();
        dirty = true;
        message = "已恢复默认参数";
        buildWidgets();
    }

    private void export() {
        if (exportPending) {
            return;
        }
        exportPending = true;
        message = "正在导出…";
    }

    /**
     * 预览和导出共用同一份“冻结”实体：现场模式拷 NBT 复制一份，默认模式按类型新建。
     */
    private Entity currentEntity() {
        if (posed != null) {
            return posed;
        }
        Entity built = null;
        if (cfg.useLiveEntity) {
            built = SingleEntityExporter.copyOf(live);
        }
        if (built == null) {
            try {
                built = type.create(Minecraft.getInstance().level, EntitySpawnReason.COMMAND);
            } catch (Throwable ignored) {
                built = null;
            }
        }
        posed = built == null ? live : built;
        return posed;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 纯色底，避免世界画面透上来
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, MeowArchiveStyle.SCREEN_BG);
        MeowArchiveStyle.panel(graphics, panelX, panelY, panelW, panelH);

        Font font = this.font;
        String title = "实体：" + MeowArchiveStyle.clamp(font, entityName.getString(), panelW - 80);
        MeowArchiveStyle.centered(graphics, font, title, panelX + panelW / 2, panelY + 9,
                MeowArchiveStyle.HEADER_COLOR);
        MeowArchiveStyle.separator(graphics, panelX + 10, panelX + panelW - 10, panelY + 24);

        graphics.fill(previewX, previewY, previewX + previewBox, previewY + previewBox,
                MeowArchiveStyle.LIST_BG);
        graphics.renderOutline(previewX, previewY, previewBox, previewBox, 0x558B5A2B);

        if (pendingImage != null) {
            preview.update(pendingImage);
            pendingImage = null;
        }
        if (dirty) {
            refreshPreview();
        }
        if (exportPending) {
            exportPending = false;
            SingleEntityConfig.save();
            Entity entity = currentEntity();
            SingleEntityExporter.exportAsync(entity, cfg.options, cfg.sizes, result -> message = result);
        }
        if (preview.ready()) {
            graphics.blit(preview.id(), previewX, previewY, previewX + previewBox, previewY + previewBox,
                    0.0F, 1.0F, 0.0F, 1.0F);
        } else {
            MeowArchiveStyle.centered(graphics, font, "渲染中…", previewX + previewBox / 2,
                    previewY + previewBox / 2 - 4, MeowArchiveStyle.SUBTEXT_COLOR);
        }

        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        graphics.drawString(font, MeowArchiveStyle.clamp(font, typeId, previewBox),
                previewX, previewY + previewBox + 5, MeowArchiveStyle.SUBTEXT_COLOR, false);
        String source = cfg.useLiveEntity ? "来源：现场实体" : "来源：默认实体";
        graphics.drawString(font, source, previewX, previewY + previewBox + 16,
                MeowArchiveStyle.SUBTEXT_COLOR, false);
        graphics.drawString(font, "拖动预览可调整偏移", previewX, previewY + previewBox + 27,
                MeowArchiveStyle.SUBTEXT_COLOR, false);

        graphics.drawString(font, MeowArchiveStyle.clamp(font, message, panelW - PAD * 2),
                panelX + PAD, statusY, MeowArchiveStyle.ACCENT, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void refreshPreview() {
        if (renderPending) {
            return;
        }
        // 上一次连续失败已经放弃，这次是用户改了参数重新触发，给一轮新的重试预算
        if (previewFailures >= 40) {
            previewFailures = 0;
        }
        Entity entity = currentEntity();
        if (entity == null) {
            dirty = false;
            return;
        }
        dirty = false;
        renderPending = true;
        EntityIconRenderer.renderAsync(entity, PREVIEW_TEX, cfg.options, image -> {
            renderPending = false;
            if (image != null) {
                pendingImage = image;
                previewFailures = 0;
            } else if (++previewFailures < 40) {
                // 打开界面的第一帧离屏缓冲偶尔还没准备好，下一帧再试，避免预览一直停在透明状态
                message = "预览渲染中…";
                dirty = true;
            } else {
                message = "预览渲染失败（详见日志）";
                ExporterLog.LOG.warn("单实体预览渲染为空：{}", entity.getType());
            }
        });
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && MeowArchiveStyle.inRect(previewX, previewY, previewBox, previewBox,
                event.x(), event.y())) {
            draggingPreview = true;
            dragStartX = event.x();
            dragStartY = event.y();
            dragStartOffsetX = cfg.options.offsetX;
            dragStartOffsetY = cfg.options.offsetY;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingPreview) {
            double box = Math.max(1, previewBox);
            float dx = (float) ((event.x() - dragStartX) / box);
            float dy = (float) ((event.y() - dragStartY) / box);
            cfg.options.offsetX = clamp(dragStartOffsetX + dx, -0.4F, 0.4F);
            cfg.options.offsetY = clamp(dragStartOffsetY - dy, -0.4F, 0.4F);
            if (offsetXSlider != null) {
                offsetXSlider.setValueSilently(cfg.options.offsetX);
            }
            if (offsetYSlider != null) {
                offsetYSlider.setValueSilently(cfg.options.offsetY);
            }
            dirty = true;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingPreview = false;
        return super.mouseReleased(event);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        SingleEntityConfig.save();
        preview.close();
        super.onClose();
    }
}
