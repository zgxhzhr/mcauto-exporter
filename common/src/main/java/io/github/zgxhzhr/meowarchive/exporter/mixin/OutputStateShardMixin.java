package io.github.zgxhzhr.meowarchive.exporter.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.zgxhzhr.meowarchive.exporter.render.IconCapture;
import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 图标捕获期间把 RenderType 的输出目标换成自建离屏纹理。
 *
 * <p>1.21.5 的 RenderType 不再看当前绑定的帧缓冲，而是每次绘制时向 OutputStateShard
 * 询问渲染目标（通常是主目标）。捕获期间在这里返回离屏目标，画完即恢复。
 */
@Mixin(RenderStateShard.OutputStateShard.class)
public class OutputStateShardMixin {

    @Inject(method = "getRenderTarget", at = @At("HEAD"), cancellable = true)
    private void meowarchive$overrideRenderTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget override = IconCapture.overrideTarget();
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
