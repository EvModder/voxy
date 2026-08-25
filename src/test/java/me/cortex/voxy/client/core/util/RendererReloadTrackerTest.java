package me.cortex.voxy.client.core.util;

import org.junit.jupiter.api.Test;

import static me.cortex.voxy.client.core.util.RendererReloadTracker.Action.CREATE;
import static me.cortex.voxy.client.core.util.RendererReloadTracker.Action.NONE;
import static me.cortex.voxy.client.core.util.RendererReloadTracker.Action.RELOAD;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RendererReloadTrackerTest {
    @Test
    void createsMissingRendererAndCoalescesSameFrameReload() {
        var tracker = new RendererReloadTracker();

        assertEquals(CREATE, tracker.onAllChanged(false, 10));
        assertEquals(NONE, tracker.onAllChanged(true, 10));
    }

    @Test
    void reloadsOncePerFrame() {
        var tracker = new RendererReloadTracker();

        assertEquals(RELOAD, tracker.onAllChanged(true, 10));
        assertEquals(NONE, tracker.onAllChanged(true, 10));
        assertEquals(RELOAD, tracker.onAllChanged(true, 11));
    }

    @Test
    void missingRendererCanBeCreatedAgainWithinSameFrame() {
        var tracker = new RendererReloadTracker();

        assertEquals(CREATE, tracker.onAllChanged(false, 10));
        assertEquals(CREATE, tracker.onAllChanged(false, 10));
    }
}
