package io.github.zgxhzhr.meowarchive.exporter.mixin;

import io.github.zgxhzhr.meowarchive.exporter.core.ExportService;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.14.4 的 Fabric API 还没有 HudRenderCallback（fabric-rendering-v1 从 1.15 才提供），
 * 这里在 Gui#render 末尾插入导出器的 HUD 阶段渲染。
 */
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void meowarchive$onHudRender(float partialTicks, CallbackInfo ci) {
        ExportService.get().onHudRender();
    }
}
