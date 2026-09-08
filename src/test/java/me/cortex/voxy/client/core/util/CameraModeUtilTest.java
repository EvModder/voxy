package me.cortex.voxy.client.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CameraModeUtilTest {
    @Test
    void keepsPlayerOwnedCamerasOnTheLoDNearPlane() {
        assertFalse(CameraModeUtil.usesCloseNearPlane(false, true));
    }

    @Test
    void usesCloseNearPlaneInSpectatorMode() {
        assertTrue(CameraModeUtil.usesCloseNearPlane(true, true));
    }

    @Test
    void usesCloseNearPlaneForSubstitutedCameraEntity() {
        assertTrue(CameraModeUtil.usesCloseNearPlane(false, false));
    }

}
