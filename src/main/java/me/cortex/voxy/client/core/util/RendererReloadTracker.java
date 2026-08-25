package me.cortex.voxy.client.core.util;

public final class RendererReloadTracker {
    private static long currentFrame;
    private long lastReloadFrame = Long.MIN_VALUE;

    public static void advanceFrame() {
        currentFrame++;
    }

    public static long currentFrame() {
        return currentFrame;
    }

    public Action onAllChanged(boolean rendererPresent, long frame) {
        if (!rendererPresent) {
            this.lastReloadFrame = frame;
            return Action.CREATE;
        }
        if (this.lastReloadFrame == frame) return Action.NONE;
        this.lastReloadFrame = frame;
        return Action.RELOAD;
    }

    public enum Action {
        NONE,
        CREATE,
        RELOAD
    }
}
