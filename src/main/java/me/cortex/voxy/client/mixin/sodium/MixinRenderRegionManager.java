package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = RenderRegionManager.class, remap = false)
public class MixinRenderRegionManager {
    @ModifyArg(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;)V", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;writeMeshTimes(III)V"), remap = false, index = 2)
    private int voxy$cancelFade(int time) {
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs==null) {
            return time;
        } else {
            return -1;
        }
    }
}