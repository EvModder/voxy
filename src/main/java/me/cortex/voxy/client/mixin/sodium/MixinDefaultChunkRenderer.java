package me.cortex.voxy.client.mixin.sodium;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import com.mojang.renderpearl.api.textures.GpuSampler;
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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private boolean[] shouldDraw;

    @Inject(method = "prepare", at = @At("TAIL"))
    private void voxy$forceOpaque(ChunkRenderListIterable renderLists, CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci) {
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer != null && !renderer.isVulkanBackend()) {
            this.shouldDraw[DefaultTerrainRenderPasses.getPassIndex(DefaultTerrainRenderPasses.CUTOUT)] = true;
        }
    }

    public MixinDefaultChunkRenderer(ChunkVertexType vertexType) {
        super(vertexType);
    }

    // Sodium 0.9.3-beta.5 changed end(TerrainRenderPass) to end(). Exactly one of these must apply.
    @Group(name = "voxy$renderBeforeEnd", min = 1, max = 1)
    @Inject(method = "render", require = 0, at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
    private void voxy$injectRender(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, com.mojang.renderpearl.api.commands.RenderPass pass, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, net.minecraft.client.renderer.oit.OitStage stage, CallbackInfo ci) {
        this.doRender(matrices, renderPass, camera, parameters);
    }

    @Group(name = "voxy$renderBeforeEnd", min = 1, max = 1)
    @Inject(method = "render", require = 0, at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end()V", shift = At.Shift.BEFORE))
    private void voxy$injectRenderSodium093(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, com.mojang.renderpearl.api.commands.RenderPass pass, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, net.minecraft.client.renderer.oit.OitStage stage, CallbackInfo ci) {
        this.doRender(matrices, renderPass, camera, parameters);
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            var renderer = IVoxyRenderSystemHolder.getNullable();
            if (renderer != null) {
                if (renderer.isVulkanBackend()) {
                    //Vulkan backend: Voxy renders via its own frame hook
                    // (MixinSodiumOpaqueVkFrame). Sodium 0.9.1 also renders through MC's
                    // Vulkan device, so target.get*TextureView() returns a
                    // VulkanGpuTextureView with no glId() — the GL-interop path below
                    // must not run (the cast would ClassCastException before
                    // renderOpaque's own VK early-return is reached).
                    return;
                }
                Viewport<?> viewport = null;
                var target = renderPass.getTarget();
                if (IrisUtil.USED_IRIS_VIEWPORT) {
                    viewport = renderer.getViewport();
                    IrisUtil.USED_IRIS_VIEWPORT = false;
                } else {
                    viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fogParameters, target.width, target.height, camera.x, camera.y, camera.z);
                }
                renderer.renderOpaque(viewport, ((GlTextureView)target.getDepthTextureView()).glId(), ((GlTextureView)target.getColorTextureView()).glId());
            }
        }
    }
}
