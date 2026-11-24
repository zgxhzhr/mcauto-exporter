package io.github.zgxhzhr.meowarchive.exporter.render;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * 自建离屏帧缓冲。每个目标新建、用完即删，不复用纹理：
 * 上一次渲染的内容会残留在复用的纹理里，表现为图标里出现一个缩小副本。
 */
final class OffscreenTarget {

    private final int size;
    private final int framebuffer;
    private final int colorTexture;
    private final int depthTexture;

    OffscreenTarget(int size) {
        this.size = size;
        this.framebuffer = GL30.glGenFramebuffers();
        this.colorTexture = GlStateManager.generateTexture();
        this.depthTexture = GlStateManager.generateTexture();

        GlStateManager.bindTexture(this.colorTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        setNearest();

        GlStateManager.bindTexture(this.depthTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, size, size, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
        setNearest();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.colorTexture, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.depthTexture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GlStateManager.bindTexture(0);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            close();
            throw new IllegalStateException("离屏帧缓冲不完整: " + status);
        }
    }

    private void setNearest() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    /** 绑定为绘制目标，并把视口、裁剪框、清屏状态、投影矩阵全部重置到本帧缓冲。 */
    void begin() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glViewport(0, 0, this.size, this.size);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, this.size, this.size, 0.0D, 1000.0D, 3000.0D);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        // 1.13 的 GUI 几何在 z≈0～300 之间，投影近平面是 1000，
        // 必须像原版 setupOverlayRendering 那样整体后移 2000，否则全部被近裁剪掉。
        GlStateManager.translatef(0.0F, 0.0F, -2000.0F);
    }

    BufferedImage read() {
        ByteBuffer buffer = BufferUtils.createByteBuffer(this.size * this.size * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, this.size, this.size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        BufferedImage image = new BufferedImage(this.size, this.size, BufferedImage.TYPE_INT_ARGB);
        boolean any = false;
        for (int y = 0; y < this.size; y++) {
            for (int x = 0; x < this.size; x++) {
                int i = ((this.size - 1 - y) * this.size + x) * 4;
                int r = buffer.get(i) & 0xFF;
                int g = buffer.get(i + 1) & 0xFF;
                int b = buffer.get(i + 2) & 0xFF;
                int a = buffer.get(i + 3) & 0xFF;
                if (a > 8) {
                    any = true;
                }
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return any ? image : null;
    }

    void close() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glDeleteFramebuffers(this.framebuffer);
        GlStateManager.deleteTexture(this.colorTexture);
        GlStateManager.deleteTexture(this.depthTexture);
    }
}
