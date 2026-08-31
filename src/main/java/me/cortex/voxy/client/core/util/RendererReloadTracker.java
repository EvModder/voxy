package me.cortex.voxy.client.core.util;

public final class RendererReloadTracker {
    private boolean resourceReloadPending;

    public void onResourceManagerReload() {
        this.resourceReloadPending = true;
    }

    public void onLevelChanged() {
        this.resourceReloadPending = false;
    }

    public Action onAllChanged(boolean rendererPresent) {
        if (!rendererPresent) {
            this.resourceReloadPending = false;
            return Action.CREATE;
        }
        if (!this.resourceReloadPending) return Action.NONE;
        this.resourceReloadPending = false;
        return Action.RELOAD;
    }

    public enum Action {
        NONE,
        CREATE,
        RELOAD
    }
}
