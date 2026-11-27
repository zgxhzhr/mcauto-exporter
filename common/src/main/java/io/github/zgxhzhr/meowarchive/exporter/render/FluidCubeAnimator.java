package io.github.zgxhzhr.meowarchive.exporter.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 动画流体的立体方块图标：把静止贴图（顶面）与流动贴图（侧面）各自的动画帧
 * 按 tick 时间轴合成，逐帧复用 {@link FluidIconRenderer#composeCube}。
 *
 * <p>只处理立体方块（Icon / IconSmall），平面静止图标保持静态 PNG。
 */
public final class FluidCubeAnimator {
    private static final int MAX_TICKS = 1200;

    private FluidCubeAnimator() {
    }

    /** 没有动画帧时返回 null；返回的帧已缩放并着色。 */
    public static AnimatedIconRenderer.AnimatedIcon render(Target target, int size) {
        try {
            Layer top = load(target.stillTexture, target.fluidColor, size);
            if (top == null) {
                return null;
            }
            Layer side = load(target.flowingTexture != null ? target.flowingTexture : target.stillTexture,
                    target.fluidColor, size);
            if (side == null) {
                side = top;
            }
            if (top.frames.size() <= 1 && side.frames.size() <= 1) {
                return null;
            }
            int total = lcmCapped(top.period, side.period);

            List<BufferedImage> frames = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            BufferedImage previous = null;
            int run = 0;
            int distinct = 0;
            for (int tick = 0; tick < total; tick++) {
                BufferedImage cube = FluidIconRenderer.composeCube(
                        top.frameAt(tick), side.frameAt(tick), size);
                if (previous != null && same(previous, cube)) {
                    run++;
                    continue;
                }
                if (previous != null) {
                    frames.add(previous);
                    delays.add(run * 50);
                    distinct++;
                }
                previous = cube;
                run = 1;
            }
            if (previous != null) {
                frames.add(previous);
                delays.add(run * 50);
                distinct++;
            }
            if (distinct < 2) {
                return null;
            }
            return new AnimatedIconRenderer.AnimatedIcon(frames, delays);
        } catch (Throwable t) {
            ExporterLog.LOG.warn("流体动画图标异常: {}", target.id, t);
            return null;
        }
    }

    /** 读贴图与同名 .mcmeta，按网格切帧，缩放到图标尺寸后着色。 */
    private static Layer load(ResourceLocation texture, int tint, int size) {
        if (texture == null || size <= 0) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            Resolved resolved = resolve(mc, texture);
            if (resolved == null) {
                return null;
            }
            BufferedImage sheet;
            try (InputStream in = resolved.resource.open()) {
                sheet = ImageIO.read(in);
            }
            if (sheet == null) {
                return null;
            }
            JsonObject animation = readAnimation(mc, resolved.mcmeta);
            if (animation == null) {
                return single(sheet, tint, size);
            }

            // 与 AnimationMetadataSection#calculateFrameSize 一致
            int frameW = animation.has("width") ? animation.get("width").getAsInt() : -1;
            int frameH = animation.has("height") ? animation.get("height").getAsInt() : -1;
            int frameTime = animation.has("frametime") ? Math.max(1, animation.get("frametime").getAsInt()) : 1;
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
                return single(sheet, tint, size);
            }
            int cols = sheet.getWidth() / frameW;
            int rows = sheet.getHeight() / frameH;
            int frameCount = cols * rows;
            if (frameCount <= 1) {
                return single(sheet, tint, size);
            }

            List<Integer> indices = new ArrayList<>();
            List<Integer> times = new ArrayList<>();
            if (animation.has("frames") && animation.get("frames").isJsonArray()) {
                for (JsonElement element : animation.getAsJsonArray("frames")) {
                    int index;
                    int time = frameTime;
                    if (element.isJsonObject()) {
                        JsonObject frame = element.getAsJsonObject();
                        if (!frame.has("index")) {
                            return single(sheet, tint, size);
                        }
                        index = frame.get("index").getAsInt();
                        if (frame.has("time")) {
                            time = Math.max(1, frame.get("time").getAsInt());
                        }
                    } else {
                        index = element.getAsInt();
                    }
                    if (index < 0 || index >= frameCount) {
                        return single(sheet, tint, size);
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
                BufferedImage part = sheet.getSubimage(sx, sy, frameW, frameH);
                frames.add(color(ImageUtil.fit(part, size), tint));
            }
            return new Layer(frames, times);
        } catch (Throwable t) {
            ExporterLog.LOG.debug("流体贴图动画读取失败 {}: {}", texture, t.toString());
            return null;
        }
    }

    private static Layer single(BufferedImage sheet, int tint, int size) {
        BufferedImage frame = ImageUtil.fit(FluidIconRenderer.firstFrame(sheet), size);
        return new Layer(List.of(color(frame, tint)), List.of(1));
    }

    private static BufferedImage color(BufferedImage image, int tint) {
        return tint == -1 ? image : FluidIconRenderer.applyTint(image, tint);
    }

    /** 依次尝试候选路径，返回第一个存在的资源及其 .mcmeta 位置。 */
    private static Resolved resolve(Minecraft mc, ResourceLocation texture) {
        for (ResourceLocation candidate : FluidIconRenderer.candidates(texture)) {
            var resource = mc.getResourceManager().getResource(candidate);
            if (resource.isPresent()) {
                ResourceLocation mcmeta = new ResourceLocation(
                        candidate.getNamespace(), candidate.getPath() + ".mcmeta");
                return new Resolved(resource.get(), mcmeta);
            }
        }
        return null;
    }

    private static JsonObject readAnimation(Minecraft mc, ResourceLocation location) {
        try {
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
        } catch (Throwable t) {
            return null;
        }
    }

    private static int lcmCapped(int a, int b) {
        long lcm = (long) a / gcd(a, b) * b;
        if (lcm <= 0 || lcm > MAX_TICKS) {
            return Math.max(a, b);
        }
        return (int) lcm;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
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

    private record Resolved(net.minecraft.server.packs.resources.Resource resource,
                            ResourceLocation mcmeta) {
    }

    /** 已缩放着色的帧序列 + 每帧持续 tick 数。 */
    private static final class Layer {
        final List<BufferedImage> frames;
        final List<Integer> times;
        final int period;

        Layer(List<BufferedImage> frames, List<Integer> times) {
            this.frames = frames;
            this.times = times;
            int sum = 0;
            for (int time : times) {
                sum += time;
            }
            this.period = Math.max(1, sum);
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
