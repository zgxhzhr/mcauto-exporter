package io.github.zgxhzhr.meowarchive.exporter.render;

/**
 * 单实体渲染参数。批量导出使用默认值，效果与原先保持一致。
 */
public final class EntityRenderOptions {
    /** 水平角度；null 表示按渲染器类型自动选择（原版 30°，GeckoLib 等 15°）。 */
    public Float yaw;
    /** 头部朝向；null 表示跟随身体（与 yaw 相同）。 */
    public Float headYaw;
    /** 俯仰角度，正值为从下往上看。 */
    public float pitch = 0.0F;
    /** 缩放倍率，1 为自动构图后的原始比例。 */
    public float zoom = 1.0F;
    /** 横向偏移，按画布比例（-0.5~0.5）。 */
    public float offsetX = 0.0F;
    /** 纵向偏移，按画布比例，正值为向上（-0.5~0.5）。 */
    public float offsetY = 0.0F;
    /** 自动构图时内容占画布的比例（0.4~1.0）。 */
    public float margin = 0.88F;
    /** 整体亮度倍率（0.2~1.6）。 */
    public float brightness = 1.0F;
    /** 是否自动迭代缩放并居中。 */
    public boolean autoFit = true;
    /** 是否在脚下绘制椭圆阴影。 */
    public boolean shadow = false;
    /** true 为透明背景，false 时用 background 填充。 */
    public boolean transparent = true;
    /** 不透明背景色（ARGB）。 */
    public int background = 0xFF202020;
    /** 姿态帧：渲染前把 tickCount 设成该值。 */
    public int tick = 20;

    public EntityRenderOptions copy() {
        EntityRenderOptions o = new EntityRenderOptions();
        o.yaw = yaw;
        o.headYaw = headYaw;
        o.pitch = pitch;
        o.zoom = zoom;
        o.offsetX = offsetX;
        o.offsetY = offsetY;
        o.margin = margin;
        o.brightness = brightness;
        o.autoFit = autoFit;
        o.shadow = shadow;
        o.transparent = transparent;
        o.background = background;
        o.tick = tick;
        return o;
    }
}
