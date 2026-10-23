package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.beacon.BeaconGeometry;
import me.cortex.voxy.client.beacon.DistantBeacons;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.vk.*;
import org.lwjgl.vulkan.*;

import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.VK10.*;

public final class VkBeaconRenderer {
    private final VkFrameCtx ctx;
    private final VkUploadStream upload;
    private final VkShaderPipeline pipeline;
    private final VkBuffer uniform;
    private VkBuffer data;

    public VkBeaconRenderer(VkFrameCtx ctx, VkUploadStream upload, RenderProperties properties) {
        this.ctx = ctx;
        this.upload = upload;
        var desc = new VkShaderPipeline.GfxDesc();
        desc.name = "distant-beacons";
        desc.vertGlsl = VkShaderSource.load("voxy:beacon/beam.vert", java.util.Map.of());
        desc.fragGlsl = VkShaderSource.load("voxy:beacon/beam.frag", java.util.Map.of());
        desc.colorFormat = VK_FORMAT_R8G8B8A8_UNORM;
        desc.depthFormat = desc.stencilFormat = VK_FORMAT_D32_SFLOAT_S8_UINT;
        desc.depthCompare = properties.isReverseZ() ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL;
        desc.bindings = List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1));
        this.pipeline = new VkShaderPipeline(ctx, desc);
        this.uniform = new VkBuffer(ctx, 80);
    }

    public void render(VkViewport viewport) {
        var frame = DistantBeacons.frame();
        var segments = BeaconGeometry.collect(viewport, frame);
        if (segments.isEmpty()) return;
        long bytes = segments.size() * 32L;
        if (this.data == null || this.data.size() < bytes) {
            long capacity = Math.max(bytes, this.data == null ? 1024 : this.data.size() * 2);
            if (this.data != null) this.data.free();
            this.data = new VkBuffer(this.ctx, capacity);
        }
        // The previous frame may still read these buffers when this frame starts its uploads.
        this.ctx.barrier(VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        BeaconGeometry.write(this.upload.upload(this.data, 0, bytes), segments);
        BeaconGeometry.writeUniform(this.upload.upload(this.uniform, 0, 80), viewport, frame.time());
        this.upload.commit();
        this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT);
        var cmd = this.ctx.cmd();
        try (var stack = stackPush()) {
            var depth = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(viewport.depthStencil.view).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var color = VkRenderingAttachmentInfoKHR.calloc(1, stack).sType$Default()
                    .imageView(viewport.colourSSAO.view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var info = VkRenderingInfoKHR.calloc(stack).sType$Default().layerCount(1)
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(viewport.width).height(viewport.height)))
                    .pColorAttachments(color).pDepthAttachment(depth).pStencilAttachment(depth);
            vkCmdBeginRenderingKHR(cmd, info);
        }
        this.pipeline.bind(cmd);
        VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
        try (var bindings = this.pipeline.binder()) {
            bindings.ubo(0, this.uniform).ssbo(1, this.data).push(cmd);
        }
        vkCmdDraw(cmd, 24, segments.size(), 0, 0);
        vkCmdEndRenderingKHR(cmd);
        this.ctx.barrier(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT);
    }

    public void free() {
        this.pipeline.free();
        this.uniform.free();
        if (this.data != null) this.data.free();
    }
}
