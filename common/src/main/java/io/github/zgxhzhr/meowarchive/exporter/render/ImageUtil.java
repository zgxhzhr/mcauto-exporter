package io.github.zgxhzhr.meowarchive.exporter.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

public final class ImageUtil {
    private ImageUtil() {
    }

    public static BufferedImage fit(BufferedImage src, int size) {
        if (src.getWidth() == size && src.getHeight() == size) {
            return src;
        }
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                src.getWidth() < size
                        ? java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    public static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String base64(BufferedImage image) {
        return Base64.getEncoder().encodeToString(png(image));
    }

    public static int colorCount(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) != 0) {
                    colors.add(argb);
                }
            }
        }
        return colors.size();
    }

    public static boolean blank(BufferedImage image) {
        if (image == null) {
            return true;
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 8) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 非透明像素的包围盒：{minX, minY, maxX, maxY}；全透明返回 null。 */
    public static int[] bounds(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 8) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX, maxY};
    }

    /**
     * 把离屏渲染结果裁到实际内容，再等比缩放到目标画布中央。
     * 实体模型的实际外廓常与包围盒不一致（牛角、龙翼、模组自定义模型），
     * 依赖包围盒算缩放会溢出或过小，这里以像素外廓为准。
     */
    public static BufferedImage fitContent(BufferedImage src, int size, float fill) {
        int[] b = bounds(src);
        if (b == null) {
            return null;
        }
        int w = b[2] - b[0] + 1;
        int h = b[3] - b[1] + 1;
        float k = Math.min(size * fill / w, size * fill / h);
        int dw = Math.max(1, Math.round(w * k));
        int dh = Math.max(1, Math.round(h * k));
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        // 放大时保留像素边缘（最近邻），缩小时用双线性做超采样抗锯齿
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                k > 1.0F ? java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src.getSubimage(b[0], b[1], w, h), (size - dw) / 2, (size - dh) / 2, dw, dh, null);
        g.dispose();
        return out;
    }
}
