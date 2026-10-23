package me.cortex.voxy.client.beacon;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.AbstractUploadStream;

import static org.lwjgl.opengl.GL45C.*;

public final class GlBeaconRenderer {
    private final Shader shader = Shader.make().add(ShaderType.VERTEX, "voxy:beacon/beam.vert")
            .add(ShaderType.FRAGMENT, "voxy:beacon/beam.frag").compile();
    private final GlBuffer uniform = new GlBuffer(80);
    private final int vao = glCreateVertexArrays();
    private GlBuffer data;

    public void render(Viewport<?> viewport, RenderProperties properties) {
        var frame = DistantBeacons.frame();
        var segments = BeaconGeometry.collect(viewport, frame);
        if (segments.isEmpty()) return;
        long bytes = segments.size() * 32L;
        if (this.data == null || this.data.size() < bytes) {
            long capacity = Math.max(bytes, this.data == null ? 1024 : this.data.size() * 2);
            if (this.data != null) this.data.free();
            this.data = new GlBuffer(capacity);
        }
        var upload = AbstractUploadStream.INSTANCE();
        BeaconGeometry.write(upload.upload(this.data, 0, bytes), segments);
        BeaconGeometry.writeUniform(upload.upload(this.uniform, 0, 80), viewport, frame.time());
        upload.commit();

        boolean stencil = glIsEnabled(GL_STENCIL_TEST), cull = glIsEnabled(GL_CULL_FACE), blend = glIsEnabled(GL_BLEND);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(properties.closerEqualDepthCompare());
        glDepthMask(true);
        this.shader.bind();
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.data.id);
        glBindVertexArray(this.vao);
        glDrawArraysInstanced(GL_TRIANGLES, 0, 24, segments.size());
        glBindVertexArray(0);
        if (stencil) glEnable(GL_STENCIL_TEST);
        if (cull) glEnable(GL_CULL_FACE);
        if (blend) glEnable(GL_BLEND);
    }

    public void free() {
        this.shader.free();
        this.uniform.free();
        if (this.data != null) this.data.free();
        glDeleteVertexArrays(this.vao);
    }
}
