package io.github.zgxhzhr.meowarchive.exporter;

import io.github.zgxhzhr.meowarchive.exporter.gui.EntityPreviewScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/**
 * 中键拾取：手持喵呜机时对准实体按鼠标中键，打开单实体渲染界面。
 * 用直接读 GLFW 鼠标状态 + 边沿检测实现，跨加载器通用，不依赖按键映射，也不需要 mixin。
 */
public final class SingleEntityPicker {
    private static boolean wasDown;

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
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),
                GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        boolean edge = down && !wasDown;
        wasDown = down;
        if (!edge || mc.screen != null || mc.player == null) {
            return;
        }
        if (!holding(mc)) {
            return;
        }
        Entity target = crosshairEntity(mc);
        if (target == null) {
            return;
        }
        // 排空原版中键“选取方块”的点击队列，否则创造模式会把手中物品换成刷怪蛋
        while (mc.options.keyPickItem.consumeClick()) {
            // 丢弃这次点击
        }
        mc.setScreen(new EntityPreviewScreen(target));
    }

    /** 命令入口：对准的实体直接打开界面。 */
    public static void openForCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        Entity target = crosshairEntity(mc);
        if (target == null) {
            return;
        }
        mc.setScreen(new EntityPreviewScreen(target));
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
