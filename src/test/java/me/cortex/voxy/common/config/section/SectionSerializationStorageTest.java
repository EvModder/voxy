package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.ActiveSectionTracker;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SectionSerializationStorageTest {
    @Test
    void deletesInvalidDataAndFallsBackToCompactSkyLitAir() {
        long key = WorldEngine.getWorldSectionId(0, 1, 2, 3);
        var backend = new MemoryStorageBackend(0);
        var invalid = new MemoryBuffer(Long.BYTES);
        try {
            MemoryUtil.memPutLong(invalid.address, key);
            backend.setSectionData(key, invalid);
        } finally {
            invalid.free();
        }

        try {
            var storage = new SectionSerializationStorage(backend);
            var tracker = new ActiveSectionTracker(0, storage::loadSection, 0);
            var section = tracker.acquire(key, false);

            assertTrue(section.isUniform());
            assertEquals(Mapper.airWithLight(15), section.getUniformValue());

            section.release();
            assertTrue(section.isFreed());

            var scratch = new MemoryBuffer(16);
            try {
                assertNull(backend.getSectionData(key, scratch));
            } finally {
                scratch.free();
            }
        } finally {
            backend.close();
        }
    }
}
