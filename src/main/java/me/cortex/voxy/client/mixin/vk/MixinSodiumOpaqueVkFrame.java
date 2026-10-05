package me.cortex.voxy.client.mixin.vk;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.vk.MinecraftVkHost;
import me.cortex.voxy.client.core.vk.MinecraftVkHostAdapter;
import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.3 keeps the native pass open across Sodium and entity draws. Render LoDs
// before that pass instead of issuing barriers inside its dynamic rendering scope.
// Vanilla then depth-tests against LoDs; transparency is still rendered afterward.
@Mixin(LevelRenderer.class)
public class MixinSodiumOpaqueVkFrame {
    @Inject(method = "lambda$addMainPass$0", at = @At("HEAD"))
    private void voxy$renderVkFrame(CallbackInfo ci) {
        if (!(MinecraftVkHost.get() instanceof MinecraftVkHostAdapter adapter)) return;
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null || renderer.vkCore == null) return;
        var matrices = ((LevelRendererExtension) this).sodium$getMatrices();
        var minecraft = Minecraft.getInstance();
        var camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (matrices == null || !camera.initialized) return;
        try {
            renderer.vkCore.renderFrame(minecraft.gameRenderer.mainRenderTarget(), adapter, matrices,
                    camera.pos.x, camera.pos.y, camera.pos.z);
        } catch (Throwable t) {
            Logger.error("Voxy VK frame failed", t);
        }
    }
}
