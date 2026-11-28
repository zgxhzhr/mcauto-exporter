package io.github.zgxhzhr.meowarchive.exporter.render;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    public static String base64Gif(byte[] gif) {
        return Base64.getEncoder().encodeToString(gif);
    }

    /**
     * 编码 GIF：每帧转 8 位索引图（0 号透明），帧延时 max(2, ms/10) 厘秒，
     * 附带 NETSCAPE2.0 无限循环扩展。失败抛 IllegalStateException。
     */
    public static byte[] gif(List<BufferedImage> frames, List<Integer> delaysMillis) {
        if (frames.isEmpty()) {
            throw new IllegalStateException("GIF 没有帧");
        }
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                writer.prepareWriteSequence(null);
                for (int i = 0; i < frames.size(); i++) {
                    BufferedImage indexed = indexed(frames.get(i));
                    IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(indexed), param);
                    int delay = Math.max(2, delaysMillis.get(Math.min(i, delaysMillis.size() - 1)) / 10);
                    setGraphicControl(metadata, delay);
                    if (i == 0) {
                        setLoopExtension(metadata);
                    }
                    writer.writeToSequence(new IIOImage(indexed, null, metadata), param);
                }
                writer.endWriteSequence();
            } finally {
                writer.dispose();
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length < 6 || bytes[0] != 'G' || bytes[1] != 'I' || bytes[2] != 'F') {
                throw new IllegalStateException("GIF 头部异常");
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 首帧元数据：追加 NETSCAPE2.0 应用扩展，loop=0 无限循环。 */
    private static void setLoopExtension(IIOMetadata metadata) throws IOException {
        String format = "javax_imageio_gif_image_1.0";
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
        IIOMetadataNode extension = new IIOMetadataNode("ApplicationExtension");
        extension.setAttribute("applicationID", "NETSCAPE");
        extension.setAttribute("authenticationCode", "2.0");
        extension.setUserObject(new byte[]{1, 0, 0});
        // JDK 的写侧要求以复数容器 ApplicationExtensions 包裹
        IIOMetadataNode extensions = new IIOMetadataNode("ApplicationExtensions");
        extensions.appendChild(extension);
        root.appendChild(extensions);
        metadata.setFromTree(format, root);
    }

    /** 帧级元数据：disposalMethod=2、0 号透明、延时厘秒。 */
    private static void setGraphicControl(IIOMetadata metadata, int delay) throws IOException {
        String format = "javax_imageio_gif_image_1.0";
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
        IIOMetadataNode control = child(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", "restoreToBackgroundColor");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "TRUE");
        control.setAttribute("transparentColorIndex", "0");
        control.setAttribute("delayTime", String.valueOf(delay));
        metadata.setFromTree(format, root);
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof IIOMetadataNode element && name.equals(element.getNodeName())) {
                return element;
            }
        }
        IIOMetadataNode created = new IIOMetadataNode(name);
        root.appendChild(created);
        return created;
    }

    /** 转 8 位索引图：alpha<128 记为透明，不透明色超过 255 种时量化到 6×6×6 调色板。 */
    private static BufferedImage indexed(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        Set<Integer> colors = new HashSet<>();
        boolean quantize = false;
        for (int y = 0; y < height && !quantize; y++) {
            for (int x = 0; x < width; x++) {
                int argb = src.getRGB(x, y);
                if ((argb >>> 24) < 128) {
                    continue;
                }
                colors.add(argb & 0xFFFFFF);
                if (colors.size() > 255) {
                    quantize = true;
                    break;
                }
            }
        }
        byte[] red = new byte[256];
        byte[] green = new byte[256];
        byte[] blue = new byte[256];
        Map<Integer, Integer> lookup = new HashMap<>();
        if (quantize) {
            for (int i = 1; i <= 216; i++) {
                int value = i - 1;
                red[i] = (byte) (value / 36 * 255 / 5);
                green[i] = (byte) (value / 6 % 6 * 255 / 5);
                blue[i] = (byte) (value % 6 * 255 / 5);
            }
        } else {
            int index = 1;
            for (int color : colors) {
                red[index] = (byte) ((color >> 16) & 0xFF);
                green[index] = (byte) ((color >> 8) & 0xFF);
                blue[index] = (byte) (color & 0xFF);
                lookup.put(color, index);
                index++;
            }
        }
        IndexColorModel model = new IndexColorModel(8, 256, red, green, blue, 0);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, model);
        WritableRaster raster = out.getRaster();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = src.getRGB(x, y);
                int index = 0;
                if ((argb >>> 24) >= 128) {
                    int rgb = argb & 0xFFFFFF;
                    if (quantize) {
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;
                        index = 1 + r * 5 / 255 * 36 + g * 5 / 255 * 6 + b * 5 / 255;
                    } else {
                        index = lookup.getOrDefault(rgb, 0);
                    }
                }
                raster.setSample(x, y, 0, index);
            }
        }
        return out;
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

    public static int opaqueCount(BufferedImage image) {
        if (image == null) {
            return 0;
        }
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 8) {
                    count++;
                }
            }
        }
        return count;
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
}
