package io.github.zgxhzhr.meowarchive.exporter;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.zgxhzhr.meowarchive.exporter.gui.EntityPreviewScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

/**
 * 中键拾取：手持喵呜机时对准实体按鼠标中键，打开单实体渲染界面。
 * 用直接读 GLFW 鼠标状态 + 边沿检测实现，跨加载器通用，不依赖按键映射，也不需要 mixin。
 */
public final class SingleEntityPicker {
    private static boolean wasDown;
    private static boolean pickSuppressed;
    private static InputConstants.Key pickKeyBackup;
    private static java.lang.reflect.Field pickKeyField;
    private static boolean pickKeyUnavailable;

    private SingleEntityPicker() {
    }

    /**
     * 必须在原版处理“选取方块”之前调用（Fabric START_CLIENT_TICK / NeoForge ClientTickEvent.Pre）。
     * 命中实体且手持喵呜机时，先吞掉排队的选取点击，再打开界面，避免手里的喵呜机被换成刷怪蛋。
     */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) {
            return;
        }
        boolean down = mc.mouseHandler.isMiddlePressed();
        boolean edge = down && !wasDown;
        wasDown = down;

        // 有些加载器/版本的客户端 tick 事件排在原版处理按键之后，单靠清点击队列拦不住选取方块。
        boolean suppress = mc.gui.screen() == null && mc.player != null && holding(mc)
                && mc.hitResult instanceof EntityHitResult;
        suppressPickKey(mc, suppress);

        if (!edge || mc.gui.screen() != null || mc.player == null) {
            return;
        }
        if (!holding(mc)) {
            return;
        }
        Entity target = crosshairEntity(mc);
        if (target == null) {
            return;
        }
        // 事件排在原版之前的加载器：点击已经进了队列，这里再清一次
        while (mc.options.keyPickItem.consumeClick()) {
            // 丢弃这次点击
        }
        mc.gui.setScreen(new EntityPreviewScreen(target));
    }

    private static void suppressPickKey(Minecraft mc, boolean suppress) {
        if (suppress == pickSuppressed || pickKeyUnavailable) {
            return;
        }
        try {
            java.lang.reflect.Field field = pickKeyField();
            if (field == null) {
                pickKeyUnavailable = true;
                return;
            }
            if (suppress) {
                pickKeyBackup = (InputConstants.Key) field.get(mc.options.keyPickItem);
                field.set(mc.options.keyPickItem, InputConstants.UNKNOWN);
            } else if (pickKeyBackup != null) {
                field.set(mc.options.keyPickItem, pickKeyBackup);
                pickKeyBackup = null;
            }
            pickSuppressed = suppress;
            // setKey 只改实例字段，静态查找表要重建后才会生效
            KeyMapping.resetMapping();
        } catch (Throwable ignored) {
            // 反射拿不到字段时放弃解绑，仍由上面的 consumeClick 兜底
            pickKeyUnavailable = true;
        }
    }

    /**
     * 各版本 KeyMapping 没有公开的 getKey()，字段名在加载器运行时也不同（Fabric 是 intermediary）。
     * 这里按类型找“可写的 InputConstants.Key 字段”，不依赖字段名。
     */
    private static java.lang.reflect.Field pickKeyField() {
        if (pickKeyField != null) {
            return pickKeyField;
        }
        for (java.lang.reflect.Field field : KeyMapping.class.getDeclaredFields()) {
            if (field.getType() == InputConstants.Key.class
                    && !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true);
                pickKeyField = field;
                return field;
            }
        }
        return null;
    }

    /** 命令入口：对准的实体直接打开界面。 */
    public static void openForCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        Entity target = crosshairEntity(mc);
        if (target == null) {
            return;
        }
        mc.gui.setScreen(new EntityPreviewScreen(target));
    }

    private static boolean holding(Minecraft mc) {
        return mc.player.getMainHandItem().is(ModItems.MIAO_WU_JI)
                || mc.player.getOffhandItem().is(ModItems.MIAO_WU_JI);
    }

    private static Entity crosshairEntity(Minecraft mc) {
        if (mc.hitResult instanceof EntityHitResult hit) {
            return hit.getEntity();
        }
        return null;
    }
}
