package io.github.zgxhzhr.meowarchive.exporter.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
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
 */
public final class AnimatedIconRenderer {
    /** 全局周期上限（tick），超出时退回最大层周期。 */
    private static final int MAX_TICKS = 1200;
    /** 物品模型空间固定为 16×16，最终再缩放到目标尺寸。 */
    private static final int CANVAS = 16;
    /** 1.21.11 没有公开图层访问器，按字段类型反射取一次并缓存。 */
    private static Field layersField;

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
            if (stack == null || stack.isEmpty() || mc.level == null) {
                reason.append("空物品");
                return null;
            }
            ItemStackRenderState state = new ItemStackRenderState();
            mc.getItemModelResolver().updateForTopItem(state, stack,
                    net.minecraft.world.item.ItemDisplayContext.GUI, mc.level, null, 0);
            if (state.isEmpty()) {
                reason.append("模型为空");
                return null;
            }
            if (state.usesBlockLight()) {
                reason.append("usesBlockLight=true（立体模型）");
                return null;
            }
            List<Layer> layers = collectLayers(state, mc, reason);
            if (layers.isEmpty()) {
                return null;
            }
            AnimatedIcon icon = compose(layers, size);
            if (icon == null) {
                reason.append("合成后不足两帧（动画无变化）");
                return null;
            }
            reason.append("成功：").append(icon.frames.size()).append(" 帧");
            return icon;
        } catch (Throwable t) {
            reason.append("异常：").append(t);
            ExporterLog.LOG.warn("动画图标异常: {}", stack == null ? null : stack.getItem(), t);
            return null;
        }
    }

    /** 按面收集图层：每个面按其 UV 裁切采样，静态层与动画层都保留。 */
    private static List<Layer> collectLayers(ItemStackRenderState state, Minecraft mc, StringBuilder reason)
            throws IOException {
        float positionScale = detectPositionScale(state);
        List<QuadEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ItemStackRenderState.LayerRenderState layer : stateLayers(state)) {
            // 1.21.11：prepareTintLayers(0) 只返回已解析的 tint 色表，不会重置
            int[] tintColors = layer.prepareTintLayers(0);
            for (BakedQuad quad : layer.prepareQuadList()) {
                TextureAtlasSprite sprite = quad.sprite();
                QuadMapping mapping = QuadMapping.of(quad, sprite, positionScale);
                if (mapping == null) {
                    // 侧立或退化的面在平面上没有面积，跳过即可
                    continue;
                }
                int tintIndex = quad.tintIndex();
                int tint = tintIndex >= 0 && tintIndex < tintColors.length ? tintColors[tintIndex] : -1;
                String key = sprite.contents().name() + "#" + tintIndex + "#" + tint + "#" + mapping.key();
                if (seen.add(key)) {
                    entries.add(new QuadEntry(sprite, tint, mapping));
                }
            }
        }
        if (entries.isEmpty()) {
            reason.append("没有可用的平面面");
            return List.of();
        }
        // 先用贴图自带帧数做廉价判断，避免给每个静态物品都解码 PNG/mcmeta
        boolean animated = false;
        for (QuadEntry entry : entries) {
            try {
                if (entry.sprite.contents().getUniqueFrames().count() > 1) {
                    animated = true;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!animated) {
            reason.append("贴图不是动画帧");
            return List.of();
        }
        Map<String, Layer> cache = new LinkedHashMap<>();
        List<Layer> layers = new ArrayList<>();
        for (QuadEntry entry : entries) {
            String name = entry.sprite.contents().name().toString();
            Layer template = cache.get(name);
            if (template == null) {
                template = loadLayer(entry.sprite, mc);
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
    private static float detectPositionScale(ItemStackRenderState state) {
        float maxAbs = 0.0F;
        for (ItemStackRenderState.LayerRenderState layer : stateLayers(state)) {
            for (BakedQuad quad : layer.prepareQuadList()) {
                for (int i = 0; i < 4; i++) {
                    var pos = quad.position(i);
                    maxAbs = Math.max(maxAbs, Math.abs(pos.x()));
                    maxAbs = Math.max(maxAbs, Math.abs(pos.y()));
                }
            }
        }
        return maxAbs > 0.0F && maxAbs <= 1.5F ? 16.0F : 1.0F;
    }

    /** 反射读取 ItemStackRenderState 的活动图层；失败时返回空列表。 */
    private static List<ItemStackRenderState.LayerRenderState> stateLayers(ItemStackRenderState state) {
        List<ItemStackRenderState.LayerRenderState> layers = new ArrayList<>();
        try {
            if (layersField == null) {
                layersField = findLayersField();
            }
            if (layersField == null) {
                return layers;
            }
            Object[] array = (Object[]) layersField.get(state);
            // 新构造的 state 未被使用的槽位为 null，无需依赖活动层计数字段
            for (Object value : array) {
                if (value instanceof ItemStackRenderState.LayerRenderState layer) {
                    layers.add(layer);
                }
            }
        } catch (Throwable t) {
            ExporterLog.LOG.warn("读取物品渲染图层失败，动画图标跳过", t);
        }
        return layers;
    }

    /** 按类型定位 LayerRenderState[] 字段，避免依赖混淆后的字段名。 */
    private static Field findLayersField() throws NoSuchFieldException {
        for (Field field : ItemStackRenderState.class.getDeclaredFields()) {
            if (field.getType().isArray()
                    && field.getType().getComponentType() == ItemStackRenderState.LayerRenderState.class) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException("ItemStackRenderState.layers");
    }

    /**
     * 读贴图与 mcmeta，切出帧序列与每帧持续 tick 数。
     * 返回的 Layer 尚未绑定 tint 与面映射，由调用方按面复制。
     */
    private static Layer loadLayer(TextureAtlasSprite sprite, Minecraft mc) throws IOException {
        Identifier name = sprite.contents().name();
        BufferedImage sheet = readImage(mc, Identifier.fromNamespaceAndPath(name.getNamespace(),
                "textures/" + name.getPath() + ".png"));
        if (sheet == null) {
            return null;
        }
        JsonObject animation = readAnimation(mc, Identifier.fromNamespaceAndPath(name.getNamespace(),
                "textures/" + name.getPath() + ".png.mcmeta"));
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
    private static AnimatedIcon compose(List<Layer> layers, int size) {
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
            BufferedImage canvas = composeTick(layers, tick);
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
    private static BufferedImage composeTick(List<Layer> layers, int tick) {
        int[] pixels = new int[CANVAS * CANVAS];
        for (Layer layer : layers) {
            BufferedImage frame = layer.frameAt(tick);
            int width = frame.getWidth();
            int height = frame.getHeight();
            int[] source = frame.getRGB(0, 0, width, height, null, 0, width);
            int tintColor = layer.tint >= 0 && (layer.tint & 0xFFFFFF) != 0xFFFFFF ? layer.tint : -1;
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

    /** 读 PNG 并统一转 TYPE_INT_ARGB；缺失返回 null。 */
    private static BufferedImage readImage(Minecraft mc, Identifier location) throws IOException {
        var resources = mc.getResourceManager().getResourceStack(location);
        if (resources.isEmpty()) {
            return null;
        }
        try (InputStream in = resources.get(0).open()) {
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
    private static JsonObject readAnimation(Minecraft mc, Identifier location) throws IOException {
        var resources = mc.getResourceManager().getResourceStack(location);
        if (resources.isEmpty()) {
            return null;
        }
        String text;
        try (InputStream in = resources.get(0).open()) {
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
    private static int tintPixel(int argb, int tint) {
        int alpha = argb >>> 24;
        int r = ((tint >> 16) & 0xFF) * ((argb >> 16) & 0xFF) / 255;
        int g = ((tint >> 8) & 0xFF) * ((argb >> 8) & 0xFF) / 255;
        int b = (tint & 0xFF) * (argb & 0xFF) / 255;
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
         * 从 BakedQuad 解析映射。1.21.11 的 BakedQuad 不再是 int[] 顶点数组：
         * 位置由 position(i) 返回 Vector3fc，UV 由 packedUV(i) 返回 UVPair 打包的 long
         * （高 32 位 u 的 float 位，低 32 位 v 的 float 位）。
         * 侧立（投影面积为零）或非平面（z 不一致）的面返回 null。
         *
         * @param positionScale 模型坐标缩放（归一化模型为 16）
         */
        static QuadMapping of(BakedQuad quad, TextureAtlasSprite sprite, float positionScale) {
            float[] x = new float[4];
            float[] y = new float[4];
            float[] z = new float[4];
            float[] u = new float[4];
            float[] v = new float[4];
            for (int i = 0; i < 4; i++) {
                var pos = quad.position(i);
                x[i] = pos.x() * positionScale;
                y[i] = pos.y() * positionScale;
                z[i] = pos.z();
                long packed = quad.packedUV(i);
                u[i] = Float.intBitsToFloat((int) (packed >>> 32));
                v[i] = Float.intBitsToFloat((int) packed);
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

    /** 单层：按 mcmeta 顺序排列的帧 + 每帧 tick 数 + tint 颜色 + 面映射。 */
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
