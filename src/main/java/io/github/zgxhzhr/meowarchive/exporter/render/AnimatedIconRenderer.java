package io.github.zgxhzhr.meowarchive.exporter.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 平面物品动画图标：不逐帧 GC 渲染，直接读物品各渲染层引用的原始贴图与 mcmeta，
 * 按 tick 把各层帧序列 alpha 叠成完整循环，再交给 GIF 编码。
 * 1.7.10 没有烘焙模型：用 getRenderPasses/getIcon/getColorFromItemStack 收集层，
 * 贴图路径由 getSpriteNumber 决定走 textures/blocks 还是 textures/items。
 * 任一层贴图读取/解析失败即整体放弃。
 */
public final class AnimatedIconRenderer {
    /** 全局周期上限（tick），超出时退回最大层周期。 */
    private static final int MAX_TICKS = 1200;

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
            if (stack == null || stack.getItem() == null) {
                return null;
            }
            if (rendersIn3D(stack)) {
                return null;
            }
            Minecraft mc = Minecraft.getMinecraft();
            List<Layer> layers = collectLayers(stack, mc);
            if (layers.isEmpty()) {
                return null;
            }
            return compose(layers, stack, size);
        } catch (Throwable t) {
            ExporterLog.LOG.warn("动画图标异常: " + (stack == null || stack.getItem() == null
                    ? "null" : String.valueOf(stack.getItem())), t);
            return null;
        }
    }

    /** 3D 方块走物品栏立体渲染，不属于平面图标。 */
    private static boolean rendersIn3D(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        try {
            Block block = Block.getBlockFromItem(stack.getItem());
            return block != null && RenderBlocks.renderItemIn3d(block.getRenderType());
        } catch (Throwable e) {
            return false;
        }
    }

    /** 按渲染层顺序收集（贴图 + pass 去重），无图标时退回 getIconIndex。 */
    private static List<Layer> collectLayers(ItemStack stack, Minecraft mc) throws IOException {
        Item item = stack.getItem();
        int passes = Math.max(1, item.getRenderPasses(stack.getItemDamage()));
        List<IIcon> icons = new ArrayList<>();
        List<Integer> tints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int pass = 0; pass < passes; pass++) {
            IIcon icon = item.getIcon(stack, pass);
            if (icon == null) {
                icon = stack.getIconIndex();
            }
            if (icon == null) {
                continue;
            }
            String name = icon.getIconName();
            if (name == null || name.isEmpty()) {
                return Collections.emptyList();
            }
            String key = name + "#" + pass;
            if (seen.add(key)) {
                icons.add(icon);
                tints.add(pass);
            }
        }
        if (icons.isEmpty()) {
            IIcon fallback = stack.getIconIndex();
            if (fallback == null || fallback.getIconName() == null || fallback.getIconName().isEmpty()) {
                return Collections.emptyList();
            }
            icons.add(fallback);
            tints.add(-1);
        }

        int spriteNumber = item.getSpriteNumber();
        // 只用 mcmeta 做廉价预检，避免给每个静态物品都解码整张 PNG
        List<JsonObject> animations = new ArrayList<>();
        boolean animated = false;
        for (IIcon icon : icons) {
            JsonObject animation = readAnimation(mc, resourcePath(mc, icon, spriteNumber, ".png.mcmeta"));
            animations.add(animation);
            if (animation != null) {
                animated = true;
            }
        }
        if (!animated) {
            return Collections.emptyList();
        }
        List<Layer> layers = new ArrayList<>();
        for (int i = 0; i < icons.size(); i++) {
            Layer layer = loadLayer(icons.get(i), tints.get(i), animations.get(i), spriteNumber, mc);
            if (layer == null) {
                // 任一层缺失就整体放弃，避免输出半成品
                return Collections.emptyList();
            }
            layers.add(layer);
        }
        return layers;
    }

    /** 1.7.10 的贴图基础目录由物品的 spriteNumber 决定。 */
    private static ResourceLocation resourcePath(Minecraft mc, IIcon icon, int spriteNumber, String suffix) {
        ResourceLocation name = new ResourceLocation(icon.getIconName());
        String base = spriteNumber == 0 ? "textures/blocks/" : "textures/items/";
        return new ResourceLocation(name.getResourceDomain(), base + name.getResourcePath() + suffix);
    }

    /** 读贴图与 mcmeta，切出帧序列与每帧持续 tick 数。 */
    private static Layer loadLayer(IIcon icon, int tint, JsonObject animation, int spriteNumber, Minecraft mc)
            throws IOException {
        BufferedImage sheet = readImage(mc, resourcePath(mc, icon, spriteNumber, ".png"));
        if (sheet == null) {
            return null;
        }
        // 没有动画元数据就是静态层：整张图作为唯一一帧，不能按网格切分
        if (animation == null) {
            List<BufferedImage> single = new ArrayList<>();
            single.add(sheet);
            List<Integer> once = new ArrayList<>();
            once.add(1);
            return new Layer(single, once, tint);
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
    private static AnimatedIcon compose(List<Layer> layers, ItemStack stack, int size) {
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
            BufferedImage canvas = composeTick(layers, tick, canvasW, canvasH, stack);
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

    private static BufferedImage composeTick(List<Layer> layers, int tick, int width, int height, ItemStack stack) {
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (Layer layer : layers) {
            BufferedImage frame = layer.frameAt(tick);
            if (layer.tint >= 0) {
                int color = stack.getItem().getColorFromItemStack(stack, layer.tint);
                if (color != -1 && (color & 0xFFFFFF) != 0xFFFFFF) {
                    frame = tint(frame, color);
                }
            }
            g.drawImage(frame, 0, 0, width, height, null);
        }
        g.dispose();
        return canvas;
    }

    /** 读 PNG 并统一转 TYPE_INT_ARGB；缺失返回 null。 */
    private static BufferedImage readImage(Minecraft mc, ResourceLocation location) throws IOException {
        IResourceManager manager = mc.getResourceManager();
        IResource resource;
        try {
            resource = manager.getResource(location);
        } catch (IOException e) {
            return null;
        }
        if (resource == null) {
            return null;
        }
        InputStream in = resource.getInputStream();
        try {
            BufferedImage raw = ImageIO.read(in);
            if (raw == null) {
                return null;
            }
            BufferedImage argb = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            g.drawImage(raw, 0, 0, null);
            g.dispose();
            return argb;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 读 mcmeta 的 animation 节点；文件缺失或没有该节点返回 null。 */
    private static JsonObject readAnimation(Minecraft mc, ResourceLocation location) throws IOException {
        IResourceManager manager = mc.getResourceManager();
        IResource resource;
        try {
            resource = manager.getResource(location);
        } catch (IOException e) {
            return null;
        }
        if (resource == null) {
            return null;
        }
        String text;
        InputStream in = resource.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        JsonElement root = new JsonParser().parse(text);
        if (root == null || !root.isJsonObject()) {
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

    /** 单层：按 mcmeta 顺序排列的帧 + 每帧 tick 数 + 着色 pass。 */
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
