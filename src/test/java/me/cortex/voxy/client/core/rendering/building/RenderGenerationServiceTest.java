package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

final class RenderGenerationServiceTest {
    @Test
    void updateDuringSourceReadQueuesAnotherBuild() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var storage = new MissingStorage();
        var world = new WorldEngine(storage);
        var manager = new ServiceManager(count -> {});
        var renderer = new RenderGenerationService(world, null, manager, false);
        long position = WorldEngine.getWorldSectionId(0, 0, 0, 0);
        //Inject the update at the source-read boundary without scheduler timing or a GPU.
        storage.duringRead = () -> renderer.enqueueTask(position);
        try {
            renderer.enqueueTask(position);
            renderer.enqueueTask(position);
            assertEquals(1, renderer.getTaskCount(), "Queued updates should still coalesce before processing starts");
            var serviceField = RenderGenerationService.class.getDeclaredField("service");
            serviceField.setAccessible(true);
            assertTrue(((Service) serviceField.get(renderer)).steal());
            var process = RenderGenerationService.class.getDeclaredMethod("processJob", RenderDataFactory.class, IntOpenHashSet.class);
            process.setAccessible(true);
            process.invoke(renderer, null, new IntOpenHashSet());
            assertEquals(1, renderer.getTaskCount(), "An update during a source read must not be merged into the old build");
        } finally {
            renderer.shutdown();
            world.free();
            manager.shutdown();
        }
    }

    private static final class MissingStorage extends SectionStorage {
        Runnable duringRead;
        @Override public int loadSection(WorldSection into) { this.duringRead.run(); return 1; }
        @Override public void saveSection(WorldSection section) {}
        @Override public void putIdMapping(int id, ByteBuffer data) {}
        @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return new Int2ObjectOpenHashMap<>(); }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public void iteratePositions(int level, LongConsumer callback) {}
    }
}
