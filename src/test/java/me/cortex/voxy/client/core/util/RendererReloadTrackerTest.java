package me.cortex.voxy.client.core.util;

import org.junit.jupiter.api.Test;

import static me.cortex.voxy.client.core.util.RendererReloadTracker.Action.CREATE;
import static me.cortex.voxy.client.core.util.RendererReloadTracker.Action.NONE;
import static me.cortex.voxy.client.core.util.RendererReloadTracker.Action.RELOAD;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RendererReloadTrackerTest {
    @Test
    void createsMissingRenderer() {
        var tracker = new RendererReloadTracker();

        assertEquals(CREATE, tracker.onAllChanged(false));
    }

    @Test
    void ignoresChunkOnlyRefreshes() {
        var tracker = new RendererReloadTracker();

        assertEquals(NONE, tracker.onAllChanged(true));
    }

    @Test
    void reloadsOnceAfterResourceChange() {
        var tracker = new RendererReloadTracker();
        tracker.onResourceManagerReload();

        assertEquals(RELOAD, tracker.onAllChanged(true));
        assertEquals(NONE, tracker.onAllChanged(true));
    }

    @Test
    void newLevelUsesCurrentResourcesWithoutAnotherReload() {
        var tracker = new RendererReloadTracker();
        tracker.onResourceManagerReload();
        tracker.onLevelChanged();

        assertEquals(NONE, tracker.onAllChanged(true));
    }

    @Test
    void creatingMissingRendererConsumesPendingResourceChange() {
        var tracker = new RendererReloadTracker();
        tracker.onResourceManagerReload();

        assertEquals(CREATE, tracker.onAllChanged(false));
        assertEquals(NONE, tracker.onAllChanged(true));
    }
}
