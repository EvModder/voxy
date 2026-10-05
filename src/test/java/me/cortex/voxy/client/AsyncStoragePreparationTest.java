package me.cortex.voxy.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldSection;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

class AsyncStoragePreparationTest {
    @Test
    void opensOffCallerThreadAndTransfersOwnershipOnce() throws Exception {
        var storage = new TestStorage();
        var ready = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        try (var preparation = new AsyncStoragePreparation<String>()) {
            assertFalse(preparation.prepare("world", () -> {
                assertNotSame(caller, Thread.currentThread());
                return storage;
            }, ready::countDown));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(preparation.prepare("world", () -> { throw new AssertionError("Opened twice"); }, () -> {}));
            assertSame(storage, preparation.take("world"));
            assertThrows(IllegalStateException.class, () -> preparation.take("world"));
        }
        assertEquals(0, storage.closed.get());
        storage.close();
        assertEquals(1, storage.closed.get());
    }

    @Test
    void cancellationClosesAnInFlightResultWithoutCallingReady() throws Exception {
        var storage = new TestStorage();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callbacks = new AtomicInteger();
        var preparation = new AsyncStoragePreparation<String>();
        preparation.prepare("world", () -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try { done = release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) {}
            }
            return storage;
        }, callbacks::incrementAndGet);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            preparation.close();
        } finally { release.countDown(); }
        assertTrue(storage.closedLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, storage.closed.get());
        assertEquals(0, callbacks.get());
        assertFalse(preparation.prepare("world", () -> storage, callbacks::incrementAndGet));
    }

    private static class TestStorage extends SectionStorage {
        final AtomicInteger closed = new AtomicInteger();
        final CountDownLatch closedLatch = new CountDownLatch(1);
        @Override public void close() { closed.incrementAndGet(); closedLatch.countDown(); }
        @Override public void flush() {}
        @Override public int loadSection(WorldSection section) { throw new UnsupportedOperationException(); }
        @Override public void saveSection(WorldSection section) { throw new UnsupportedOperationException(); }
        @Override public void putIdMapping(int id, ByteBuffer data) { throw new UnsupportedOperationException(); }
        @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { throw new UnsupportedOperationException(); }
        @Override public void iteratePositions(int level, LongConsumer callback) { throw new UnsupportedOperationException(); }
    }
}
