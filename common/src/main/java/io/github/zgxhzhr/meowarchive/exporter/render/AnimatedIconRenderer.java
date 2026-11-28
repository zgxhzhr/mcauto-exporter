package io.github.zgxhzhr.meowarchive.exporter.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平面物品动画图标：不逐帧 GC 渲染，直接读模型引用的原始贴图与 mcmeta，
 * 按 tick 把各层帧序列叠成完整循环，再交给 GIF 编码。
 * <p>
 * 每个烘焙面按自身 UV 做仿射采样：整图面就是整张帧，遮罩裁出的面（桶内流体等）
 * 只取贴图对应区域，从而避免把液体铺满整个图标。
 * <p>
 * 1.19.2 的 DefaultVertexFormat.BLOCK 每顶点为 x,y,z,color,u,v,uv2（部分版本带 normal），
 * u/v 固定在 index 4/5，stride 由顶点数组长度 / 4 推出，因此对 7/8 int 两种布局都适用。
 * <p>
 * 1.19.2 的 TextureAtlasSprite 只暴露 getName()，也没有 getUniqueFrames，
 * 动画判定改为读 .png.mcmeta。
 */
public final class AnimatedIconRenderer {
    /** 全局周期上限（tick），超出时退回最大层周期。 */
    private static final int MAX_TICKS = 1200;
    /** 物品模型空间固定为 16×16，最终再缩放到目标尺寸。 */
    private static final int CANVAS = 16;
    /** 1.19.x 未公开 ItemColors，按类型反射取一次并缓存。 */
    private static ItemColors itemColors;
    private static boolean itemColorsResolved;

    private AnimatedIconRenderer() {
    }

    /** 合成结果：frames 已按 nearest 缩放到目标尺寸，delaysMillis 与之一一对应。 */
    public static final class AnimatedIcon {
        public final List<BufferedImage> frames;
        public final List<Integer> delaysMillis;

        public AnimatedIcon(List<BufferedImage> frames, List<Integer> delaysMillis) {
            this.frames = frames;
            this.delaysMillis = delaysMillis;
        }
    }

    public static AnimatedIcon render(ItemStack stack, int size, StringBuilder reason) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (stack.isEmpty()) {
                reason.append("空物品");
                return null;
            }
            BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
            if (model.isGui3d()) {
                reason.append("isGui3d=true（立体模型）");
                return null;
            }
            // 1.19.2 没有 getRenderPasses，单 pass 取全部 quad 即可
            List<Layer> layers = collectLayers(model, mc, reason);
            if (layers.isEmpty()) {
                return null;
            }
            AnimatedIcon icon = compose(layers, stack, size, mc);
            if (icon == null) {
                reason.append("合成后不足两帧（动画无变化）");
                return null;
            }
            reason.append("成功：").append(icon.frames.size()).append(" 帧");
            return icon;
        } catch (Throwable t) {
            reason.append("异常：").append(t);
            ExporterLog.LOG.warn("动画图标异常: {}", stack.getItem(), t);
            return null;
        }
    }

    /** 按面收集图层：每个面按其 UV 裁切采样，静态层与动画层都保留。 */
    private static List<Layer> collectLayers(BakedModel model, Minecraft mc, StringBuilder reason) throws IOException {
        float positionScale = detectPositionScale(model);
        List<QuadEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (BakedQuad quad : model.getQuads(null, null, RandomSource.create())) {
            TextureAtlasSprite sprite = quad.getSprite();
            QuadMapping mapping = QuadMapping.of(quad, sprite, positionScale);
            if (mapping == null) {
                // 侧立或退化的面在平面上没有面积，跳过即可
                continue;
            }
            String key = sprite.getName() + "#" + quad.getTintIndex() + "#" + mapping.key();
            if (seen.add(key)) {
                entries.add(new QuadEntry(sprite, quad.getTintIndex(), mapping));
            }
        }
        if (entries.isEmpty()) {
            reason.append("没有可用的平面面");
            return List.of();
        }
        // 1.19.2 没有 SpriteContents#getUniqueFrames，只用 mcmeta 做廉价预检，
        // 避免给每个静态物品都解码整张 PNG
        Map<String, JsonObject> animations = new LinkedHashMap<>();
        boolean animated = false;
        for (QuadEntry entry : entries) {
            String name = entry.sprite.getName().toString();
            if (!animations.containsKey(name)) {
                JsonObject animation = readAnimation(mc, new ResourceLocation(entry.sprite.getName().getNamespace(),
                        "textures/" + entry.sprite.getName().getPath() + ".png.mcmeta"));
                animations.put(name, animation);
                if (animation != null) {
                    animated = true;
                }
            } else if (animations.get(name) != null) {
                animated = true;
            }
        }
        if (!animated) {
            reason.append("贴图不是动画帧");
            return List.of();
        }
        Map<String, Layer> cache = new LinkedHashMap<>();
        List<Layer> layers = new ArrayList<>();
        for (QuadEntry entry : entries) {
            String name = entry.sprite.getName().toString();
            Layer template = cache.get(name);
            if (template == null) {
                template = loadLayer(entry.sprite, animations.get(name), mc);
                if (template == null) {
                    reason.append("读取贴图层失败：").append(name);
                    return List.of();
                }
                cache.put(name, template);
            }
            layers.add(template.with(entry.tint, entry.mapping));
        }
        if (positionScale != 1.0F) {
            reason.append("[坐标×16]");
        }
        if (entries.get(0).mapping.texcoordUv) {
            reason.append("[UV=0..16]");
        }
        return layers;
    }

    /**
     * 模型空间理论上固定 0..16，但部分烘焙路径会给出 0..1 的归一化坐标。
     * 用全部顶点的最大绝对值判断：不超过 1.5 就按归一化处理、放大 16 倍。
     */
    private static float detectPositionScale(BakedModel model) {
        float maxAbs = 0.0F;
        for (BakedQuad quad : model.getQuads(null, null, RandomSource.create())) {
            int[] data = quad.getVertices();
            int stride = data.length / 4;
            if (stride < 6) {
                continue;
            }
            for (int i = 0; i < 4; i++) {
                maxAbs = Math.max(maxAbs, Math.abs(Float.intBitsToFloat(data[i * stride])));
                maxAbs = Math.max(maxAbs, Math.abs(Float.intBitsToFloat(data[i * stride + 1])));
            }
        }
        return maxAbs > 0.0F && maxAbs <= 1.5F ? 16.0F : 1.0F;
    }

    /**
     * 读贴图与 mcmeta，切出帧序列与每帧持续 tick 数。
     * 返回的 Layer 尚未绑定 tint 与面映射，由调用方按面复制。
     */
    private static Layer loadLayer(TextureAtlasSprite sprite, JsonObject animation, Minecraft mc)
            throws IOException {
        ResourceLocation name = sprite.getName();
        BufferedImage sheet = readImage(mc, new ResourceLocation(name.getNamespace(),
                "textures/" + name.getPath() + ".png"));
        if (sheet == null) {
            return null;
        }
        // 没有动画元数据就是静态层：整张图作为唯一一帧，不能按网格切分
        if (animation == null) {
            return new Layer(List.of(sheet), List.of(1));
        }

        // 与 AnimationMetadataSection#calculateFrameSize 保持一致：
        // 两者都缺省时取图片宽高较小值作正方形帧；只缺一个时用图片对应边补足
        int frameW = -1;
        int frameH = -1;
        int frameTime = 1;
        if (animation.has("frametime")) {
            frameTime = Math.max(1, animation.get("frametime").getAsInt());
        }
        if (animation.has("width")) {
            frameW = animation.get("width").getAsInt();
        }
        if (animation.has("height")) {
            frameH = animation.get("height").getAsInt();
        }
        if (frameW <= 0 && frameH <= 0) {
            int square = Math.min(sheet.getWidth(), sheet.getHeight());
            frameW = square;
            frameH = square;
        } else if (frameW <= 0) {
            frameW = sheet.getWidth();
        } else if (frameH <= 0) {
            frameH = sheet.getHeight();
        }
        if (frameW <= 0 || frameH <= 0 || frameW > sheet.getWidth() || frameH > sheet.getHeight()) {
            return null;
        }
        int cols = sheet.getWidth() / frameW;
        int rows = sheet.getHeight() / frameH;
        if (cols < 1 || rows < 1) {
            return null;
        }
        int frameCount = cols * rows;

        List<Integer> indices = new ArrayList<>();
        List<Integer> times = new ArrayList<>();
        if (animation.has("frames") && animation.get("frames").isJsonArray()) {
            for (JsonElement element : animation.getAsJsonArray("frames")) {
                int index;
                int time = frameTime;
                if (element.isJsonObject()) {
                    JsonObject frame = element.getAsJsonObject();
                    if (!frame.has("index")) {
                        return null;
                    }
                    index = frame.get("index").getAsInt();
                    if (frame.has("time")) {
                        time = Math.max(1, frame.get("time").getAsInt());
                    }
                } else {
                    index = element.getAsInt();
                }
                if (index < 0 || index >= frameCount) {
                    return null;
                }
                indices.add(index);
                times.add(time);
            }
        }
        if (indices.isEmpty()) {
            for (int i = 0; i < frameCount; i++) {
                indices.add(i);
                times.add(frameTime);
            }
        }

        List<BufferedImage> frames = new ArrayList<>();
        for (int index : indices) {
            int sx = (index % cols) * frameW;
            int sy = (index / cols) * frameH;
            frames.add(sheet.getSubimage(sx, sy, frameW, frameH));
        }
        return new Layer(frames, times);
    }

    /** 逐 tick 合成：全局周期取各层周期最小公倍数（有上限），连续相同帧合并。 */
    private static AnimatedIcon compose(List<Layer> layers, ItemStack stack, int size, Minecraft mc) {
        long cycle = 1;
        boolean capped = false;
        for (Layer layer : layers) {
            long next = cycle / gcd(cycle, layer.period) * layer.period;
            if (next <= 0 || next > MAX_TICKS) {
                capped = true;
                break;
            }
            cycle = next;
        }
        int total = (int) cycle;
        if (capped) {
            total = 1;
            for (Layer layer : layers) {
                total = Math.max(total, layer.period);
            }
        }

        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        BufferedImage previous = null;
        int run = 0;
        int distinct = 0;
        for (int tick = 0; tick < total; tick++) {
            BufferedImage canvas = composeTick(layers, tick, stack, mc);
            if (previous != null && same(previous, canvas)) {
                run++;
                continue;
            }
            if (previous != null) {
                frames.add(scale(previous, size));
                delays.add(run * 50);
                distinct++;
            }
            previous = canvas;
            run = 1;
        }
        if (previous != null) {
            frames.add(scale(previous, size));
            delays.add(run * 50);
            distinct++;
        }
        if (distinct < 2) {
            return null;
        }
        return new AnimatedIcon(frames, delays);
    }

    /** 按每个面的映射逐像素采样叠加，遮罩裁出的面只覆盖对应区域。 */
    private static BufferedImage composeTick(List<Layer> layers, int tick, ItemStack stack, Minecraft mc) {
        int[] pixels = new int[CANVAS * CANVAS];
        ItemColors colors = itemColors(mc);
        for (Layer layer : layers) {
            BufferedImage frame = layer.frameAt(tick);
            int width = frame.getWidth();
            int height = frame.getHeight();
            int[] source = frame.getRGB(0, 0, width, height, null, 0, width);
            int tintColor = -1;
            if (layer.tint >= 0 && colors != null) {
                int color = colors.getColor(stack, layer.tint);
                if (color != -1 && (color & 0xFFFFFF) != 0xFFFFFF) {
                    tintColor = color;
                }
            }
            QuadMapping mapping = layer.mapping;
            for (int py = 0; py < CANVAS; py++) {
                float modelY = CANVAS - (py + 0.5F);
                if (modelY < mapping.minY || modelY > mapping.maxY) {
                    continue;
                }
                for (int px = 0; px < CANVAS; px++) {
                    float modelX = px + 0.5F;
                    if (modelX < mapping.minX || modelX > mapping.maxX) {
                        continue;
                    }
                    float u = mapping.a * modelX + mapping.b * modelY + mapping.c;
                    float v = mapping.d * modelX + mapping.e * modelY + mapping.f;
                    int sx = (int) Math.floor((u - mapping.spriteU0) / mapping.spriteUSpan * width);
                    int sy = (int) Math.floor((v - mapping.spriteV0) / mapping.spriteVSpan * height);
                    if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
                        continue;
                    }
                    int color = source[sy * width + sx];
                    if (tintColor != -1) {
                        color = tintPixel(color, tintColor);
                    }
                    int index = py * CANVAS + px;
                    pixels[index] = blend(pixels[index], color);
                }
            }
        }
        BufferedImage canvas = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        canvas.setRGB(0, 0, CANVAS, CANVAS, pixels, 0, CANVAS);
        return canvas;
    }

    /** 反射按字段类型定位 ItemColors；失败时返回 null（不着色）。 */
    private static ItemColors itemColors(Minecraft mc) {
        if (!itemColorsResolved) {
            itemColorsResolved = true;
            try {
                itemColors = findColors(Minecraft.class, mc);
                if (itemColors == null) {
                    itemColors = findColors(mc.getItemRenderer().getClass(), mc.getItemRenderer());
                }
            } catch (Throwable t) {
                ExporterLog.LOG.warn("读取物品着色表失败，动画图标将不着色", t);
            }
        }
        return itemColors;
    }

    private static ItemColors findColors(Class<?> type, Object instance) throws IllegalAccessException {
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (field.getType() == ItemColors.class) {
                field.setAccessible(true);
                return (ItemColors) field.get(instance);
            }
        }
        return null;
    }

    /** 读 PNG 并统一转 TYPE_INT_ARGB；缺失返回 null。 */
    private static BufferedImage readImage(Minecraft mc, ResourceLocation location) throws IOException {
        var resource = mc.getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream in = resource.get().open()) {
            BufferedImage raw = ImageIO.read(in);
            if (raw == null) {
                return null;
            }
            BufferedImage argb = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            g.drawImage(raw, 0, 0, null);
            g.dispose();
            return argb;
        }
    }

    /** 读 mcmeta 的 animation 节点；文件缺失或没有该节点返回 null。 */
    private static JsonObject readAnimation(Minecraft mc, ResourceLocation location) throws IOException {
        var resource = mc.getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            return null;
        }
        String text;
        try (InputStream in = resource.get().open()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonObject()) {
            return null;
        }
        JsonElement animation = root.getAsJsonObject().get("animation");
        return animation != null && animation.isJsonObject() ? animation.getAsJsonObject() : null;
    }

    /** 按 tint 颜色做 RGB 乘法，保留贴图 alpha。 */
    private static int tintPixel(int color, int argb) {
        int alpha = color >>> 24;
        int tr = (argb >> 16) & 0xFF;
        int tg = (argb >> 8) & 0xFF;
        int tb = argb & 0xFF;
        int r = ((color >> 16) & 0xFF) * tr / 255;
        int g = ((color >> 8) & 0xFF) * tg / 255;
        int b = (color & 0xFF) * tb / 255;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    /** 非预乘 alpha 的 SrcOver 叠加。 */
    private static int blend(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 0) {
            return dst;
        }
        if (sa == 255) {
            return src;
        }
        int da = dst >>> 24;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) {
            return 0;
        }
        int sr = (src >> 16) & 0xFF;
        int sg = (src >> 8) & 0xFF;
        int sb = src & 0xFF;
        int dr = (dst >> 16) & 0xFF;
        int dg = (dst >> 8) & 0xFF;
        int db = dst & 0xFF;
        int r = (sr * sa + dr * da * (255 - sa) / 255) / outA;
        int g = (sg * sa + dg * da * (255 - sa) / 255) / outA;
        int b = (sb * sa + db * da * (255 - sa) / 255) / outA;
        return (outA << 24) | (r << 16) | (g << 8) | b;
    }

    private static BufferedImage scale(BufferedImage src, int size) {
        if (src.getWidth() == size && src.getHeight() == size) {
            return src;
        }
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static boolean same(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** 一个烘焙面在 16×16 物品空间里的位置与 UV 映射。 */
    private static final class QuadMapping {
        final float minX;
        final float maxX;
        final float minY;
        final float maxY;
        /** u = a*x + b*y + c，v 同理。 */
        final float a;
        final float b;
        final float c;
        final float d;
        final float e;
        final float f;
        final float spriteU0;
        final float spriteUSpan;
        final float spriteV0;
        final float spriteVSpan;
        /** true 表示该面的 UV 是 0..16 贴图坐标，而非图集归一化坐标。 */
        final boolean texcoordUv;

        private QuadMapping(float minX, float maxX, float minY, float maxY,
                            float a, float b, float c, float d, float e, float f,
                            float spriteU0, float spriteUSpan, float spriteV0, float spriteVSpan,
                            boolean texcoordUv) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.e = e;
            this.f = f;
            this.spriteU0 = spriteU0;
            this.spriteUSpan = spriteUSpan;
            this.spriteV0 = spriteV0;
            this.spriteVSpan = spriteVSpan;
            this.texcoordUv = texcoordUv;
        }

        /**
         * 从 BakedQuad 顶点解析映射；顶点布局为 x,y,z,color,u,v,...（u/v 在 index 4/5）。
         * 侧立（投影面积为零）或非平面（z 不一致）的面返回 null。
         *
         * @param positionScale 模型坐标缩放（归一化模型为 16）
         */
        static QuadMapping of(BakedQuad quad, TextureAtlasSprite sprite, float positionScale) {
            int[] data = quad.getVertices();
            int stride = data.length / 4;
            if (stride < 6) {
                return null;
            }
            float[] x = new float[4];
            float[] y = new float[4];
            float[] z = new float[4];
            float[] u = new float[4];
            float[] v = new float[4];
            for (int i = 0; i < 4; i++) {
                int offset = i * stride;
                x[i] = Float.intBitsToFloat(data[offset]) * positionScale;
                y[i] = Float.intBitsToFloat(data[offset + 1]) * positionScale;
                z[i] = Float.intBitsToFloat(data[offset + 2]);
                u[i] = Float.intBitsToFloat(data[offset + 4]);
                v[i] = Float.intBitsToFloat(data[offset + 5]);
            }
            // UV 有两种约定：0..1 图集归一化坐标，或 0..16 贴图坐标
            boolean texcoordUv = false;
            for (int i = 0; i < 4; i++) {
                if (Math.abs(u[i]) > 1.001F || Math.abs(v[i]) > 1.001F) {
                    texcoordUv = true;
                    break;
                }
            }
            float spriteU0;
            float spriteUSpan;
            float spriteV0;
            float spriteVSpan;
            if (texcoordUv) {
                spriteU0 = 0.0F;
                spriteUSpan = 16.0F;
                spriteV0 = 0.0F;
                spriteVSpan = 16.0F;
            } else {
                spriteU0 = sprite.getU0();
                spriteUSpan = sprite.getU1() - spriteU0;
                spriteV0 = sprite.getV0();
                spriteVSpan = sprite.getV1() - spriteV0;
            }
            if (spriteUSpan <= 0.0F || spriteVSpan <= 0.0F) {
                return null;
            }
            float minZ = Math.min(Math.min(z[0], z[1]), Math.min(z[2], z[3]));
            float maxZ = Math.max(Math.max(z[0], z[1]), Math.max(z[2], z[3]));
            if (maxZ - minZ > 0.05F) {
                return null;
            }
            float dx1 = x[1] - x[0];
            float dy1 = y[1] - y[0];
            float dx2 = x[2] - x[0];
            float dy2 = y[2] - y[0];
            float det = dx1 * dy2 - dx2 * dy1;
            if (Math.abs(det) < 0.01F) {
                return null;
            }
            float du1 = u[1] - u[0];
            float du2 = u[2] - u[0];
            float dv1 = v[1] - v[0];
            float dv2 = v[2] - v[0];
            float a = (du1 * dy2 - du2 * dy1) / det;
            float b = (dx1 * du2 - dx2 * du1) / det;
            float c = u[0] - a * x[0] - b * y[0];
            float d = (dv1 * dy2 - dv2 * dy1) / det;
            float e = (dx1 * dv2 - dx2 * dv1) / det;
            float f = v[0] - d * x[0] - e * y[0];
            float minX = Math.min(Math.min(x[0], x[1]), Math.min(x[2], x[3]));
            float maxX = Math.max(Math.max(x[0], x[1]), Math.max(x[2], x[3]));
            float minY = Math.min(Math.min(y[0], y[1]), Math.min(y[2], y[3]));
            float maxY = Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3]));
            return new QuadMapping(minX, maxX, minY, maxY, a, b, c, d, e, f,
                    spriteU0, spriteUSpan, spriteV0, spriteVSpan, texcoordUv);
        }

        /** 去重键：同一贴图、同一位置、同一 UV 区域的面只画一次。 */
        String key() {
            return String.format("%.2f,%.2f,%.2f,%.2f|%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
                    minX, minY, maxX, maxY, a, b, c, d, e, f);
        }
    }

    private record QuadEntry(TextureAtlasSprite sprite, int tint, QuadMapping mapping) {
    }

    /** 单层：按 mcmeta 顺序排列的帧 + 每帧 tick 数 + tintIndex + 面映射。 */
    private static final class Layer {
        final List<BufferedImage> frames;
        final List<Integer> times;
        final int period;
        final int tint;
        final QuadMapping mapping;

        Layer(List<BufferedImage> frames, List<Integer> times) {
            this(frames, times, -1, null);
        }

        Layer(List<BufferedImage> frames, List<Integer> times, int tint, QuadMapping mapping) {
            this.frames = frames;
            this.times = times;
            this.tint = tint;
            this.mapping = mapping;
            int sum = 0;
            for (int time : times) {
                sum += time;
            }
            this.period = sum;
        }

        Layer with(int tint, QuadMapping mapping) {
            return new Layer(frames, times, tint, mapping);
        }

        BufferedImage frameAt(int tick) {
            int t = tick % period;
            for (int i = 0; i < frames.size(); i++) {
                t -= times.get(i);
                if (t < 0) {
                    return frames.get(i);
                }
            }
            return frames.get(frames.size() - 1);
        }
    }
}
