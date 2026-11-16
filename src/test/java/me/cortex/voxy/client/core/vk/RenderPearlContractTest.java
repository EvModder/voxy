package me.cortex.voxy.client.core.vk;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

class RenderPearlContractTest {
    @Test
    void ssaoTransitionsCoverComputeAndRestoreMinecraftLayout() throws IOException {
        var ssao = read("me/cortex/voxy/client/core/vk/render/VkSSAO");
        var compute = ssao.methods.stream().filter(m -> m.name.equals("compute")).findFirst().orElseThrow();
        int hostCalls = 0, depthCalls = 0, outputCalls = 0;
        for (var instruction : compute.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.name.equals("transitionMcImage")) {
                assertTrue(call.desc.endsWith("ZIII)V"));
                var args = trailingConstants(call, 3);
                assertArrayEquals(hostCalls++ == 0
                                ? new int[]{VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT}
                                : new int[]{VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT}, args,
                        "Minecraft depth must use GENERAL, sampled by COMPUTE in both directions");
            } else if (call.owner.endsWith("/VkImage2D") && call.name.equals("transition")) {
                var args = trailingConstants(call, 5);
                if (args[0] == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && args[2] == VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT) {
                    assertEquals(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                            args[1], "Depth writes need EARLY and LATE_FRAGMENT_TESTS");
                    depthCalls++;
                }
                if (args[0] == VK_IMAGE_LAYOUT_GENERAL) {
                    assertArrayEquals(new int[]{VK_IMAGE_LAYOUT_GENERAL,
                                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT,
                                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT}, args,
                            "SSAO overwrite must follow colour attachment writes and compositor reads");
                    outputCalls++;
                }
            }
        }
        assertEquals(2, hostCalls);
        assertEquals(1, depthCalls);
        assertEquals(1, outputCalls);
    }

    private static int[] trailingConstants(MethodInsnNode call, int count) {
        var values = new int[count];
        var instruction = call.getPrevious();
        for (int i = count - 1; i >= 0; i--) {
            while (instruction.getOpcode() < 0) instruction = instruction.getPrevious();
            if (instruction instanceof org.objectweb.asm.tree.IntInsnNode n) values[i] = n.operand;
            else if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode n) values[i] = (Integer) n.cst;
            else {
                assertTrue(instruction.getOpcode() >= org.objectweb.asm.Opcodes.ICONST_M1
                        && instruction.getOpcode() <= org.objectweb.asm.Opcodes.ICONST_5);
                values[i] = instruction.getOpcode() - org.objectweb.asm.Opcodes.ICONST_0;
            }
            instruction = instruction.getPrevious();
        }
        return values;
    }

    @Test
    void bundledShadercCompilesTheHostCompositor() {
        var definitions = java.util.Map.of("USE_REVERSE_Z", "", "USE_ZERO_ONE_DEPTH", "", "EMIT_COLOUR", "");
        for (String shader : List.of("post/fullscreen2.vert", "post/setup_stencil_depth.frag", "post/blit_texture_depth_cutout.frag",
                "beacon/beam.vert", "beacon/beam.frag")) {
            var type = shader.endsWith(".vert") ? me.cortex.voxy.client.core.gl.shader.ShaderType.VERTEX
                    : me.cortex.voxy.client.core.gl.shader.ShaderType.FRAGMENT;
            var spirv = ShadercCompiler.compile(VkShaderSource.load("voxy:" + shader, definitions), type, shader);
            assertTrue(spirv.remaining() > 20);
            assertEquals(0x07230203, spirv.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0));
        }
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static ClassNode read(String name) throws IOException {
        try (var stream = RenderPearlContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    @Test
    void requiredMinecraftAndSodiumInjectionMethodsStillExist() throws IOException {
        var loader = getClass().getClassLoader();
        var failures = new ArrayList<String>();
        var groupMatches = new java.util.HashMap<String, Boolean>();
        try (var reader = new InputStreamReader(loader.getResourceAsStream("client.voxy.mixins.json"))) {
            var config = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            for (var entry : config.getAsJsonArray("client")) {
                String name = entry.getAsString();
                if (!(name.startsWith("minecraft.") || name.startsWith("sodium.") || name.startsWith("vk."))) continue;
                var mixin = read("me/cortex/voxy/client/mixin/" + name.replace('.', '/'));
                var annotation = mixin.invisibleAnnotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                        .findFirst().orElseThrow();
                var targets = new ArrayList<String>();
                if (value(annotation, "value") instanceof List<?> types) {
                    types.forEach(t -> targets.add(((Type) t).getInternalName()));
                }
                if (value(annotation, "targets") instanceof List<?> names) {
                    names.forEach(n -> targets.add(n.toString().replace('.', '/')));
                }
                for (var target : targets) {
                    var targetClass = read(target);
                    for (var field : mixin.fields) {
                        if (field.visibleAnnotations != null && field.visibleAnnotations.stream().anyMatch(a ->
                                a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))) {
                            if (targetClass.fields.stream().noneMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc))) {
                                failures.add(name + " -> field " + field.name + field.desc);
                            }
                        }
                    }
                    for (var method : mixin.methods) {
                        if (method.visibleAnnotations == null) continue;
                        // Grouped alternatives (one per Sodium version) only need one member to match.
                        String group = java.util.stream.Stream.of(method.visibleAnnotations, method.invisibleAnnotations)
                                .filter(java.util.Objects::nonNull).flatMap(List::stream)
                                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;"))
                                .map(a -> name + "#" + value(a, "name")).findFirst().orElse(null);
                        if (group != null) groupMatches.putIfAbsent(group, false);
                        for (var injector : method.visibleAnnotations) {
                            var selectors = value(injector, "method");
                            if (!(selectors instanceof List<?> list)) continue;
                            for (var selector : list) {
                                String signature = selector.toString();
                                var selected = targetClass.methods.stream().filter(m ->
                                        signature.equals(m.name) || signature.equals(m.name + m.desc)).toList();
                                if (selected.isEmpty()) failures.add(name + " -> " + signature);
                                if (injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                                    var arguments = java.util.Arrays.asList(Type.getArgumentTypes(method.desc));
                                    int callback = -1;
                                    for (int i = 0; i < arguments.size(); i++) {
                                        if (arguments.get(i).getClassName().startsWith("org.spongepowered.asm.mixin.injection.callback.CallbackInfo")) callback = i;
                                    }
                                    if (callback > 0) {
                                        var captured = arguments.subList(0, callback);
                                        if (selected.stream().noneMatch(m -> captured.equals(java.util.Arrays.asList(Type.getArgumentTypes(m.desc))))) {
                                            failures.add(name + " -> " + signature + " callback arguments changed");
                                        }
                                    }
                                }
                                var atValue = value(injector, "at");
                                var ats = atValue instanceof List<?> l ? l : atValue == null ? List.of() : List.of(atValue);
                                for (var atObject : ats) {
                                    var at = (AnnotationNode) atObject;
                                    if (!"INVOKE".equals(value(at, "value"))) continue;
                                    var invoked = value(at, "target");
                                    if (invoked == null) continue;
                                    boolean found = selected.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                                            .anyMatch(i -> i instanceof MethodInsnNode call
                                                    && invoked.equals("L" + call.owner + ";" + call.name + call.desc));
                                    if (group != null) {
                                        if (found) groupMatches.put(group, true);
                                    } else if (!found) failures.add(name + " -> " + signature + " invokes " + invoked);
                                }
                            }
                        }
                    }
                }
            }
        }
        groupMatches.forEach((group, matched) -> {
            if (!matched) failures.add(group + " -> no injector matches");
        });
        assertTrue(failures.isEmpty(), failures.toString());
    }

    @Test
    void adapterUsesThePersistentMinecraftEncoder() throws IOException {
        var device = read("com/mojang/renderpearl/backend/vulkan/VulkanDevice");
        var create = device.methods.stream().filter(m -> m.name.equals("createCommandEncoder")
                && m.desc.equals("()Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;"))
                .findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(create.instructions.toArray()).anyMatch(i ->
                i instanceof org.objectweb.asm.tree.FieldInsnNode f && f.name.equals("commandEncoder")));
        var encoder = read("com/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder");
        assertTrue(encoder.methods.stream().anyMatch(m -> m.name.equals("commandBuffer")
                && m.desc.equals("()Lorg/lwjgl/vulkan/VkCommandBuffer;")));
    }

    @Test
    void mainPassHookPrecedesNativeRenderPassCreation() throws IOException {
        var renderer = read("net/minecraft/client/renderer/LevelRenderer");
        var main = renderer.methods.stream().filter(m -> m.name.equals("lambda$addMainPass$0"))
                .findFirst().orElseThrow();
        int open = -1, solid = -1, close = -1;
        var instructions = main.instructions.toArray();
        for (int i = 0; i < instructions.length; i++) {
            if (!(instructions[i] instanceof MethodInsnNode call)) continue;
            if (call.name.equals("createRenderPass")) open = i;
            if (call.name.equals("executeSolid")) solid = i;
            if (call.owner.equals("com/mojang/renderpearl/api/commands/RenderPass")
                    && call.name.equals("close") && close == -1) close = i;
        }
        assertTrue(open >= 0 && solid > open && close > solid,
                "26.3 frame scope changed: re-audit the Vulkan HEAD hook before shipping");
    }
}
