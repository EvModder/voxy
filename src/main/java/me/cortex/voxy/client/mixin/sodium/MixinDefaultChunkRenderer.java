package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.mixin.core.CommandEncoderAccessor;
import net.caffeinemc.mods.sodium.mixin.core.RenderPassAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Optional;
import java.util.OptionalDouble;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    public MixinDefaultChunkRenderer(ChunkVertexType vertexType) {
        super(vertexType);
    }


    /*
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V", shift = At.Shift.AFTER, ordinal = 1))
    private void voxy$forceRenderSomething(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, CallbackInfo ci, @Local RenderPass pass) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            ((GlCommandEncoderAccessor)(GlCommandEncoder)((CommandEncoderAccessor)RenderSystem.getDevice().createCommandEncoder()).sodium$getBackend()).sodium$setLastProgram(null);
            ((GlCommandEncoder)((CommandEncoderAccessor)RenderSystem.getDevice().createCommandEncoder()).sodium$getBackend()).trySetup((GlRenderPass) ((RenderPassAccessor) pass).getBackend(), Collections.emptyList());
        }
    }*/
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE, ordinal = 0), order = 999)
    private void voxy$forceRenderSomething(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, CallbackInfo ci) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Terrain", renderPass.getTarget().getColorTextureView(), Optional.empty(), renderPass.getTarget().getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(this.activeProgram);
                ((GlCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).sodium$getBackend()).trySetup((GlRenderPass) ((RenderPassAccessor) pass).getBackend(), Collections.emptyList());
            }
        }
    }


    /*
    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void voxy$cancelThingie(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBuffer uniformData, GpuBuffer sectionTimeInfo, CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass, fogParameters, terrainSampler);
            this.doRender(matrices, renderPass, camera, fogParameters);
            super.end(renderPass);
            ci.cancel();
        }
    }*/

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
    private void voxy$injectRender(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, CallbackInfo ci) {
        this.doRender(matrices, renderPass, camera, parameters);
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            var renderer = IVoxyRenderSystemHolder.getNullable();
            if (renderer != null) {
                Viewport<?> viewport = null;
                var target = renderPass.getTarget();
                if (IrisUtil.irisShaderPackEnabled()) {
                    viewport = renderer.getViewport();
                } else {
                    viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fogParameters, target.width, target.height, camera.x, camera.y, camera.z);
                }
                renderer.renderOpaque(viewport, ((GlTextureView)target.getDepthTextureView()).glId(), ((GlTextureView)target.getColorTextureView()).glId());
            }
        }
    }
}
