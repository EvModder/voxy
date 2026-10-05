package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Owns unopened-world storage until it is handed to a WorldEngine. */
final class AsyncStoragePreparation<K> implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "Voxy storage preparation");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<K, Entry> pending = new HashMap<>();
    private boolean closed;

    synchronized boolean prepare(K key, Supplier<SectionStorage> open, Runnable ready) {
        if (this.closed) return false;
        var entry = this.pending.get(key);
        if (entry != null) return entry.storage != null;
        entry = new Entry();
        this.pending.put(key, entry);
        Entry job = entry;
        this.worker.execute(() -> {
            try {
                SectionStorage storage = open.get();
                synchronized (this) {
                    if (this.closed) { storage.close(); return; }
                    job.storage = storage;
                }
                ready.run();
            } catch (Exception e) {
                synchronized (this) {
                    if (!this.closed) Logger.error("Voxy storage preparation failed; LoDs remain disabled for this world until reconnect", e);
                }
            }
        });
        return false;
    }

    synchronized SectionStorage take(K key) {
        Entry entry = this.pending.remove(key);
        if (this.closed || entry == null || entry.storage == null) throw new IllegalStateException("Storage is not ready");
        return entry.storage;
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        this.worker.shutdownNow();
        for (Entry entry : this.pending.values()) if (entry.storage != null) {
            try { entry.storage.close(); }
            catch (Exception e) { Logger.error("Could not close prepared Voxy storage", e); }
        }
        this.pending.clear();
    }

    private static final class Entry { SectionStorage storage; }
}
