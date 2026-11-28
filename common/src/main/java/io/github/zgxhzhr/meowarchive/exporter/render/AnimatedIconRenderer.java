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
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 平面物品动画图标：不逐帧 GC 渲染，直接读模型引用的原始贴图与 mcmeta，
 * 按 tick 把各层帧序列 alpha 叠成完整循环，再交给 GIF 编码。
 * 只处理 isGui3d()==false 的模型；任一层贴图读取/解析失败即整体放弃。
 */
public final class AnimatedIconRenderer {
    /** 全局周期上限（tick），超出时退回最大层周期。 */
    private static final int MAX_TICKS = 1200;
    /** 1.21.1 未公开 ItemColors，按类型反射取一次并缓存。 */
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

    public static AnimatedIcon render(ItemStack stack, int size) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (stack.isEmpty()) {
                return null;
            }
            BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
            if (model.isGui3d()) {
                return null;
            }
            // 1.21.1 没有 getRenderPasses，单 pass 取全部 quad 即可
            List<Layer> layers = collectLayers(model, mc);
            if (layers.isEmpty()) {
                return null;
            }
            return compose(layers, stack, size, mc);
        } catch (Throwable t) {
            ExporterLog.LOG.warn("动画图标异常: {}", stack.getItem(), t);
            return null;
        }
    }

    /** 按出现顺序收集各层（贴图 + tintIndex 去重），无 quad 或非平面模型时直接放弃。 */
    private static List<Layer> collectLayers(BakedModel model, Minecraft mc) throws IOException {
        List<TextureAtlasSprite> sprites = new ArrayList<>();
        List<Integer> tints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (BakedQuad quad : model.getQuads(null, null, RandomSource.create())) {
            if (quad.getDirection() != null) {
                // 带方向的 3D 面（桶、方块模型等）无法用平面贴图重建，
                // 强行按整张贴图叠会溢出轮廓，交给静态 PNG 渲染
                return List.of();
            }
            TextureAtlasSprite sprite = quad.getSprite();
            String key = sprite.contents().name() + "#" + quad.getTintIndex();
            if (seen.add(key)) {
                sprites.add(sprite);
                tints.add(quad.getTintIndex());
            }
        }
        if (sprites.isEmpty()) {
            // 没有烘焙 quad（BEWLR / 内置模型，如各种桶）：只有粒子贴图可以读到，
            // 而粒子贴图往往是桶内的流体，按整张叠出来就是一整块液体，直接放弃动画
            return List.of();
        }
        if (sprites.size() > 1) {
            // 多贴图层（桶身 + 桶内流体等）按整张贴图堆叠同样会画错位置，放弃动画
            return List.of();
        }
        // 先用贴图自带帧数做廉价判断，避免给每个静态物品都解码 PNG/mcmeta
        boolean animated = false;
        for (TextureAtlasSprite sprite : sprites) {
            try {
                if (sprite.contents().getUniqueFrames().count() > 1) {
                    animated = true;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!animated) {
            return List.of();
        }
        List<Layer> layers = new ArrayList<>();
        for (int i = 0; i < sprites.size(); i++) {
            Layer layer = loadLayer(sprites.get(i), tints.get(i), mc);
            if (layer == null) {
                // 任一层缺失就整体放弃，避免输出半成品
                return List.of();
            }
            layers.add(layer);
        }
        return layers;
    }

    /** 读贴图与 mcmeta，切出帧序列与每帧持续 tick 数。 */
    private static Layer loadLayer(TextureAtlasSprite sprite, int tint, Minecraft mc) throws IOException {
        ResourceLocation name = sprite.contents().name();
        BufferedImage sheet = readImage(mc, ResourceLocation.fromNamespaceAndPath(name.getNamespace(),
                "textures/" + name.getPath() + ".png"));
        if (sheet == null) {
            return null;
        }
        JsonObject animation = readAnimation(mc, ResourceLocation.fromNamespaceAndPath(name.getNamespace(),
                "textures/" + name.getPath() + ".png.mcmeta"));
        // 没有动画元数据就是静态层：整张图作为唯一一帧，不能按网格切分
        if (animation == null) {
            return new Layer(List.of(sheet), List.of(1), tint);
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
        return new Layer(frames, times, tint);
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

        int canvasW = layers.get(0).frames.get(0).getWidth();
        int canvasH = layers.get(0).frames.get(0).getHeight();
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        BufferedImage previous = null;
        int run = 0;
        int distinct = 0;
        for (int tick = 0; tick < total; tick++) {
            BufferedImage canvas = composeTick(layers, tick, canvasW, canvasH, stack, mc);
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

    private static BufferedImage composeTick(List<Layer> layers, int tick, int width, int height,
                                             ItemStack stack, Minecraft mc) {
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        ItemColors colors = itemColors(mc);
        for (Layer layer : layers) {
            BufferedImage frame = layer.frameAt(tick);
            if (layer.tint >= 0 && colors != null) {
                int color = colors.getColor(stack, layer.tint);
                if (color != -1 && (color & 0xFFFFFF) != 0xFFFFFF) {
                    frame = tint(frame, color);
                }
            }
            g.drawImage(frame, 0, 0, width, height, null);
        }
        g.dispose();
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
    private static BufferedImage tint(BufferedImage image, int argb) {
        int tr = (argb >> 16) & 0xFF;
        int tg = (argb >> 8) & 0xFF;
        int tb = argb & 0xFF;
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                int a = (p >>> 24) & 0xFF;
                int r = ((p >> 16) & 0xFF) * tr / 255;
                int g = ((p >> 8) & 0xFF) * tg / 255;
                int b = (p & 0xFF) * tb / 255;
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
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

    /** 单层：按 mcmeta 顺序排列的帧 + 每帧 tick 数 + tintIndex。 */
    private static final class Layer {
        final List<BufferedImage> frames;
        final List<Integer> times;
        final int tint;
        final int period;

        Layer(List<BufferedImage> frames, List<Integer> times, int tint) {
            this.frames = frames;
            this.times = times;
            this.tint = tint;
            int sum = 0;
            for (int time : times) {
                sum += time;
            }
            this.period = sum;
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
