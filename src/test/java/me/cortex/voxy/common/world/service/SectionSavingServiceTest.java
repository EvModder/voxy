package me.cortex.voxy.common.world.service;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.thread.UnifiedServiceThreadPool;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SectionSavingServiceTest {
    @Test
    void stalledStorageDoesNotOccupyTerrainWorkersAndQueuedSavesAreBatched() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var entered = new CountDownLatch(1);
        var unblock = new CountDownLatch(1);
        var saved = new CountDownLatch(65);
        var sizes = new CopyOnWriteArrayList<Integer>();
        var storage = new FailingOnceStorage() {
            @Override public void saveSections(List<WorldSection> sections) {
                sizes.add(sections.size());
                entered.countDown();
                try {
                    assertTrue(unblock.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                sections.forEach(section -> saved.countDown());
            }
        };
        var pool = new UnifiedServiceThreadPool();
        pool.setNumThreads(1);
        var terrainRan = new CountDownLatch(1);
        var terrain = pool.serviceManager.createServiceNoCleanup(() -> terrainRan::countDown, 1);
        var saving = new SectionSavingService();
        var world = new WorldEngine(storage);
        world.setSaveCallback(saving::enqueueSave);
        try {
            for (int i = 0; i < 65; i++) {
                var section = world.acquire(0, i, 0, 0);
                world.markDirty(section);
                section.release();
                if (i == 0) assertTrue(entered.await(5, TimeUnit.SECONDS));
            }
            terrain.execute();
            assertTrue(terrainRan.await(5, TimeUnit.SECONDS));
            unblock.countDown();
            assertTrue(saved.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(1, 32, 32), sizes);
        } finally {
            unblock.countDown();
            saving.shutdown();
            terrain.shutdown();
            pool.shutdown();
            world.free();
        }
    }

    @Test
    void preservesDirtySectionAfterFailedSave() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var storage = new FailingOnceStorage();
        var savingService = new SectionSavingService();
        var engine = new WorldEngine(storage);
        engine.setSaveCallback(savingService::enqueueSave);

        var section = engine.acquire(0, 0, 0, 0);
        engine.markDirty(section);
        assertTrue(engine.saveSection(section));
        assertTrue(storage.firstAttempt.await(5, TimeUnit.SECONDS));
        assertTrue(await(section::shouldSave, Duration.ofSeconds(5)));

        section.release();
        assertTrue(storage.saved.await(5, TimeUnit.SECONDS));
        assertTrue(await(section::isFreed, Duration.ofSeconds(5)));
        assertEquals(2, storage.attempts.get());

        savingService.shutdown();
        engine.free();
    }

    private static boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static class FailingOnceStorage extends SectionStorage {
        private final AtomicInteger attempts = new AtomicInteger();
        private final CountDownLatch firstAttempt = new CountDownLatch(1);
        private final CountDownLatch saved = new CountDownLatch(1);

        @Override
        public int loadSection(WorldSection into) {
            return 1;
        }

        @Override
        public void saveSection(WorldSection section) {
            if (this.attempts.incrementAndGet() == 1) {
                this.firstAttempt.countDown();
                throw new IllegalStateException("expected test failure");
            }
            this.saved.countDown();
        }

        @Override
        public void putIdMapping(int id, ByteBuffer data) {}

        @Override
        public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
            return new Int2ObjectOpenHashMap<>();
        }

        @Override
        public int getOrCreateIdMapping(int entryType, byte[] identity, IntFunction<byte[]> serializedMappingFactory) {
            return NON_ATOMIC_MAPPING;
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public void iteratePositions(int level, LongConsumer callback) {}
    }
}
