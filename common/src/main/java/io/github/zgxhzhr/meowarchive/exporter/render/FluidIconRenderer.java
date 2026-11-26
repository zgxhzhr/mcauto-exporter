package io.github.zgxhzhr.meowarchive.exporter.render;

import io.github.zgxhzhr.meowarchive.exporter.core.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * 流体图标。两套产物：立体方块（顶面静止贴图 + 侧面流动贴图，等价于百科里水的立方体图标）
 * 与平面图（静止贴图优先，缺失时退回桶物品）。
 */
public final class FluidIconRenderer {
    private FluidIconRenderer() {
    }

    /** 立体方块图标；没有静止贴图时返回 null。 */
    public static BufferedImage renderCube(Target target, int size) {
        BufferedImage top = loadTexture(target.stillTexture, target.fluidColor, size);
        if (top == null) {
            return null;
        }
        BufferedImage side = loadTexture(
                target.flowingTexture != null ? target.flowingTexture : target.stillTexture,
                target.fluidColor, size);
        if (side == null) {
            side = top;
        }

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        double s = size;
        // 等距投影：上下两个三角形构成顶面菱形，左右两块平行四边形构成侧面
        double[] topQuad = {0.5 * s, 0, s, 0.25 * s, 0.5 * s, 0.5 * s, 0, 0.25 * s};
        double[] leftQuad = {0, 0.25 * s, 0.5 * s, 0.5 * s, 0.5 * s, s, 0, 0.75 * s};
        double[] rightQuad = {s, 0.25 * s, 0.5 * s, 0.5 * s, 0.5 * s, s, s, 0.75 * s};

        drawFace(g, top, topQuad);
        drawFace(g, side, leftQuad);
        drawFace(g, side, rightQuad);

        // 侧面压暗，模拟方块光照（左浅右深）
        fillShade(g, leftQuad, new Color(0, 0, 0, 45));
        fillShade(g, rightQuad, new Color(0, 0, 0, 90));
        g.dispose();
        return out;
    }

    /** 平面图标：静止贴图优先，缺失/全透明时退回对应桶物品。 */
    public static BufferedImage renderFlat(Target target, int size) {
        BufferedImage still = loadTexture(target.stillTexture, target.fluidColor, size);
        if (still != null && !ImageUtil.blank(still)) {
            target.fluidStillIconSource = "texture";
            return still;
        }
        if (!target.bucket.isEmpty()) {
            BufferedImage bucket = ItemIconRenderer.render(target.bucket, size);
            if (bucket != null && !ImageUtil.blank(bucket)) {
                target.fluidStillIconSource = "bucket";
                return bucket;
            }
        }
        target.fluidStillIconSource = "";
        return null;
    }

    /** 把正方形贴图映射到平行四边形，贴图坐标按面顶点插值。 */
    private static void drawFace(Graphics2D g, BufferedImage texture, double[] quad) {
        double w = texture.getWidth();
        double h = texture.getHeight();
        // quad：P00, P10, P11, P01
        double ux = (quad[2] - quad[0]) / w;
        double uy = (quad[3] - quad[1]) / w;
        double vx = (quad[6] - quad[0]) / h;
        double vy = (quad[7] - quad[1]) / h;
        AffineTransform transform = new AffineTransform(ux, uy, vx, vy, quad[0], quad[1]);
        g.drawImage(texture, transform, null);
    }

    private static void fillShade(Graphics2D g, double[] quad, Color color) {
        g.setColor(color);
        g.fillPolygon(new Polygon(
                new int[]{(int) Math.round(quad[0]), (int) Math.round(quad[2]),
                        (int) Math.round(quad[4]), (int) Math.round(quad[6])},
                new int[]{(int) Math.round(quad[1]), (int) Math.round(quad[3]),
                        (int) Math.round(quad[5]), (int) Math.round(quad[7])},
                4));
    }

    /** 读取贴图：补全资源路径 → 取动画首帧 → 缩放到目标尺寸 → 着色。 */
    private static BufferedImage loadTexture(ResourceLocation texture, int tint, int size) {
        if (texture == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            BufferedImage source = null;
            for (ResourceLocation candidate : candidates(texture)) {
                var resource = mc.getResourceManager().getResource(candidate);
                if (resource.isEmpty()) {
                    continue;
                }
                try (InputStream in = resource.get().open()) {
                    source = ImageIO.read(in);
                }
                if (source != null) {
                    break;
                }
            }
            if (source == null) {
                return null;
            }
            BufferedImage scaled = ImageUtil.fit(firstFrame(source), size);
            return tint < 0 ? scaled : applyTint(scaled, tint);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 流体静止贴图多为动画长条（如 16x6400 = 400 帧，或横向 interlace），
     * 直接缩放会把所有帧压成条纹，这里只取第一帧。
     */
    private static BufferedImage firstFrame(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (h > w && h % w == 0) {
            return src.getSubimage(0, 0, w, w);
        }
        if (w > h && w % h == 0) {
            return src.getSubimage(0, 0, h, h);
        }
        return src;
    }

    /**
     * 平台返回的静止贴图通常是「纹理 id」而非资源路径
     * （如 minecraft:block/water_still），需要补 textures/ 前缀与 .png 后缀才能读到。
     */
    private static ResourceLocation[] candidates(ResourceLocation texture) {
        String path = texture.getPath();
        boolean isAssetPath = path.startsWith("textures/");
        ResourceLocation withPrefix = ResourceLocation.fromNamespaceAndPath(
                texture.getNamespace(),
                isAssetPath ? path : "textures/" + path + (path.endsWith(".png") ? "" : ".png"));
        ResourceLocation noExtension = ResourceLocation.fromNamespaceAndPath(
                texture.getNamespace(), path.endsWith(".png") ? path.substring(0, path.length() - 4) : path);
        return new ResourceLocation[]{texture, withPrefix, noExtension};
    }

    /** 按流体着色做乘法，保留原贴图的明暗。 */
    private static BufferedImage applyTint(BufferedImage image, int argb) {
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
}
