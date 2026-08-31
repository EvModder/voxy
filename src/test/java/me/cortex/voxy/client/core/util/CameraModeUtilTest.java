package me.cortex.voxy.client.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CameraModeUtilTest {
    @Test
    void keepsNormalPlayerCamerasOnTheLoDNearPlane() {
        assertFalse(CameraModeUtil.usesCloseNearPlane(false, true, 0.0));
        assertFalse(CameraModeUtil.usesCloseNearPlane(false, true, 16.0));
    }

    @Test
    void usesCloseNearPlaneInSpectatorMode() {
        assertTrue(CameraModeUtil.usesCloseNearPlane(true, true, 0.0));
    }

    @Test
    void usesCloseNearPlaneForSubstitutedCameraEntity() {
        assertTrue(CameraModeUtil.usesCloseNearPlane(false, false, 0.0));
    }

    @Test
    void usesCloseNearPlaneWhenCameraMovesAwayFromPlayer() {
        assertTrue(CameraModeUtil.usesCloseNearPlane(false, true, 36.01));
    }
}
