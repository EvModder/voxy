package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.beacon.IBeaconRenderState;
import me.cortex.voxy.client.beacon.DistantBeacons;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelRenderState.class)
public class MixinBeaconRenderState implements IBeaconRenderState {
    @Unique private DistantBeacons.Frame voxy$beacons = DistantBeacons.EMPTY;

    @Override public DistantBeacons.Frame voxy$getBeacons() { return this.voxy$beacons; }
    @Override public void voxy$setBeacons(DistantBeacons.Frame frame) { this.voxy$beacons = frame; }
}
