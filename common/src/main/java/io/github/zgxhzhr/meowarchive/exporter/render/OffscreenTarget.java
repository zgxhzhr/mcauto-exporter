package io.github.zgxhzhr.meowarchive.exporter.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * 自建离屏帧缓冲。不使用原版 RenderTarget / TextureTarget，
 * 因为它们的纹理与 GlStateManager 缓存会在不同尺寸之间互相串扰：
 * 上一次渲染的内容会残留在下一次的纹理里，表现为图标里出现一个缩小副本。
 */
final class OffscreenTarget {

    private final int size;
    private final int framebuffer;
    private final int colorTexture;
    private final int depthTexture;

    OffscreenTarget(int size) {
        this.size = size;
        this.framebuffer = GL30.glGenFramebuffers();
        this.colorTexture = GlStateManager.genTexture();
        this.depthTexture = GlStateManager.genTexture();

        GlStateManager.bindTexture(this.colorTexture);
        GlStateManager.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
        setNearest();

        GlStateManager.bindTexture(this.depthTexture);
        GlStateManager.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, size, size, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (IntBuffer) null);
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
        GlStateManager.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    /** 绑定为绘制目标，并把视口、裁剪框、清屏状态全部重置到本帧缓冲。 */
    void begin() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glViewport(0, 0, this.size, this.size);
        // 清屏会被颜色掩码 / 深度掩码影响：GUI 阶段的残留状态会让清屏静默失效，
        // 复用来的纹理里就会留下上一次（另一个尺寸）渲染的内容，
        // 表现为图标左下角出现一个缩小副本。
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.depthMask(true);
        GlStateManager.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GlStateManager.clearDepth(1.0);
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, true);
    }

    BufferedImage read() {
        ByteBuffer buffer = MemoryUtil.memAlloc(this.size * this.size * 4);
        try {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, this.size, this.size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            BufferedImage image = new BufferedImage(this.size, this.size, BufferedImage.TYPE_INT_ARGB);
            byte[] row = new byte[this.size * 4];
            boolean any = false;
            for (int y = 0; y < this.size; y++) {
                buffer.position((this.size - 1 - y) * this.size * 4);
                buffer.get(row);
                for (int x = 0; x < this.size; x++) {
                    int r = row[x * 4] & 0xFF;
                    int g = row[x * 4 + 1] & 0xFF;
                    int b = row[x * 4 + 2] & 0xFF;
                    int a = row[x * 4 + 3] & 0xFF;
                    if (a > 8) {
                        any = true;
                    }
                    image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            return any ? image : null;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    void close() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glDeleteFramebuffers(this.framebuffer);
        GlStateManager.deleteTexture(this.colorTexture);
        GlStateManager.deleteTexture(this.depthTexture);
    }
}
