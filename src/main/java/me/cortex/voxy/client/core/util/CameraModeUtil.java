package me.cortex.voxy.client.core.util;

import net.minecraft.client.Minecraft;

public final class CameraModeUtil {
    //Bring detached views closer than the normal 8-16 block plane while retaining
    //more depth precision than vanilla's 0.05 near plane with Voxy's 48000 far plane.
    public static final float DETACHED_NEAR_PLANE = 1.0f;

    private CameraModeUtil() {}

    public static boolean usesCloseNearPlane() {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        var camera = minecraft.gameRenderer.mainCamera();
        if (player == null || !camera.isInitialized()) return false;

        return usesCloseNearPlane(player.isSpectator(), camera.entity() == player);
    }

    //Avoid comparing interpolated camera and tick positions, which can diverge during
    //teleports. This detects substituted camera entities, not every freecam implementation.
    static boolean usesCloseNearPlane(boolean spectator, boolean playerCamera) {
        return spectator || !playerCamera;
    }

    public static float selectNearPlane(float normalNearPlane) {
        return usesCloseNearPlane() ? Math.min(DETACHED_NEAR_PLANE, normalNearPlane) : normalNearPlane;
    }
}
