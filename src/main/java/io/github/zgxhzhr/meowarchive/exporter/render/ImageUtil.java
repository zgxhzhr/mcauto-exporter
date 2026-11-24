package io.github.zgxhzhr.meowarchive.exporter.render;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                src.getWidth() < size
                        ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
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

    /** 按源图坐标裁剪一块正方形区域，再按最近邻缩放到目标尺寸。 */
    public static BufferedImage crop(BufferedImage src, int sx, int sy, int w, int h) {
        if (sx < 0 || sy < 0 || sx + w > src.getWidth() || sy + h > src.getHeight()) {
            return null;
        }
        return src.getSubimage(sx, sy, w, h);
    }
}
