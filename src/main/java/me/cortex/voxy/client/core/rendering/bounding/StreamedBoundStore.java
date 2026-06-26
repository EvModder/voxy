package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;

//This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
public final class StreamedBoundStore implements IBoundStore {
    private static final int INIT_MAX_CHUNK_COUNT = 1<<12;
    private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_CHUNK_COUNT*8);//Stored as ivec2
    private int count;//NOTE: count here is in INTS, not LONGS (i.e. 1 pos is 2 ints)
    private boolean didChange = false;
    private int[] visibleSections = new int[INIT_MAX_CHUNK_COUNT*2];
    public StreamedBoundStore() {
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    @Override
    public void preRender(Viewport<?> viewport) {
        if (this.count == 0 || !this.didChange) return;
        if (this.count*4>this.chunkPosBuffer.size()) {
            this.chunkPosBuffer.free();
            this.chunkPosBuffer = new GlBuffer(((long) Math.ceil(this.count*1.25))*4);
        }
        long addr = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 0, this.count*4);
        UnsafeUtil.memcpy(this.visibleSections, this.count, addr);
        UploadStream.INSTANCE.commit();
        this.didChange = false;
    }

    public void reset() {
        this.count = 0;
        this.didChange = true;
    }

    public void put(long pos) {
        this.visibleSections[this.count++] = (int)(pos&0xFFFFFFFFL);
        this.visibleSections[this.count++] = (int)((pos>>>32)&0xFFFFFFFFL);
        if (this.count >= this.visibleSections.length-2) {
            this.visibleSections = Arrays.copyOf(this.visibleSections, (int) (this.visibleSections.length*1.25));
        }
    }

    public void free() {
        this.chunkPosBuffer.free();
    }

    @Override
    public GlBuffer getBuffer() {
        return this.chunkPosBuffer;
    }

    @Override
    public int getCount() {
        return this.count>>1;
    }
}
