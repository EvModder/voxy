package me.cortex.voxy.client.core.util;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;

public final class CameraModeUtil {
    private static final double DETACHED_DISTANCE_SQUARED = 6.0 * 6.0;

    private CameraModeUtil() {}

    public static boolean usesCloseNearPlane() {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        var camera = minecraft.gameRenderer.mainCamera();
        if (player == null || !camera.isInitialized()) return false;

        return usesCloseNearPlane(
                player.isSpectator(),
                camera.entity() == player,
                camera.position().distanceToSqr(player.getEyePosition()));
    }

    static boolean usesCloseNearPlane(boolean spectator, boolean playerCamera, double distanceSquared) {
        return spectator || !playerCamera || distanceSquared > DETACHED_DISTANCE_SQUARED;
    }

    public static float selectNearPlane(float normalNearPlane) {
        return usesCloseNearPlane() ? Camera.PROJECTION_Z_NEAR : normalNearPlane;
    }
}
