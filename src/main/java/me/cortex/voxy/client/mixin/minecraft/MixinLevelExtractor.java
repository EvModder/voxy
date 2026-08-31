package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.util.RendererReloadTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class MixinLevelExtractor {
    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Unique
    private final RendererReloadTracker voxy$reloadTracker = new RendererReloadTracker();

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxy$onSetLevel(ClientLevel level, CallbackInfo cir) {
        this.voxy$reloadTracker.onLevelChanged();
        ((IVoxyRenderSystemHolder)this.levelRenderer).voxy$setWorld(level);
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void voxy$onResourceManagerReload(ResourceManager resourceManager, CallbackInfo ci) {
        this.voxy$reloadTracker.onResourceManagerReload();
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void voxy$reload(CallbackInfo cir) {
        var holder = (IVoxyRenderSystemHolder)this.levelRenderer;
        switch (this.voxy$reloadTracker.onAllChanged(
                holder.voxy$getRenderSystem() != null)) {
            case CREATE -> holder.voxy$createRenderer();
            case RELOAD -> {
                holder.voxy$shutdownRenderer();
                holder.voxy$createRenderer();
            }
            case NONE -> { }
        }
    }
}
