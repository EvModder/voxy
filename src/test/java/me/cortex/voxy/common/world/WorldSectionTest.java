package me.cortex.voxy.common.world;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldSectionTest {
    private static final long UNIFORM_VALUE = Mapper.composeMappingId((byte) 15, 1, 2);

    @Test
    void staysCompactUntilWrittenWithADifferentValue() {
        var section = section();
        section.setUniform(UNIFORM_VALUE);

        assertTrue(section.isUniform());
        assertNull(section._rawOrNull());
        assertEquals(UNIFORM_VALUE, section.get(WorldSection.getIndex(3, 4, 5)));
        assertEquals(UNIFORM_VALUE, section.set(3, 4, 5, UNIFORM_VALUE));
        assertTrue(section.isUniform());

        long differentValue = Mapper.composeMappingId((byte) 7, 3, 4);
        assertEquals(UNIFORM_VALUE, section.set(3, 4, 5, differentValue));
        assertFalse(section.isUniform());
        assertEquals(differentValue, section.get(WorldSection.getIndex(3, 4, 5)));
        assertEquals(UNIFORM_VALUE, section.get(WorldSection.getIndex(3, 4, 6)));
    }

    @Test
    void materializesOneInitializedArrayUnderContention() throws Exception {
        var section = section();
        section.setUniform(UNIFORM_VALUE);
        var start = new CountDownLatch(1);
        var arrays = new ArrayList<long[]>();

        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<long[]>>();
            for (int index = 0; index < 32; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return section.materialize();
                }));
            }
            start.countDown();
            for (var future : futures) {
                arrays.add(future.get());
            }
        }

        long[] published = arrays.getFirst();
        for (long[] array : arrays) {
            assertSame(published, array);
        }
        for (long value : published) {
            assertEquals(UNIFORM_VALUE, value);
        }
    }

    @Test
    void serializesIdenticallyToDenseUniformData() {
        var compact = section();
        compact.setUniform(UNIFORM_VALUE);
        compact._unsafeSetNonEmptyChildren((byte) 0x5A);

        var dense = section();
        Arrays.fill(dense.materialize(), UNIFORM_VALUE);
        dense._unsafeSetNonEmptyChildren((byte) 0x5A);

        var compactData = SaveLoadSystem3.serialize(compact).copy();
        try {
            var denseData = SaveLoadSystem3.serialize(dense);
            assertEquals(denseData.size, compactData.size);
            byte[] expected = new byte[(int) compactData.size];
            byte[] actual = new byte[(int) denseData.size];
            compactData.asByteBuffer().get(expected);
            denseData.asByteBuffer().get(actual);
            assertArrayEquals(expected, actual);
        } finally {
            compactData.free();
        }
    }

    @Test
    void deserializesSingleValueDataWithoutMaterializing() {
        var source = section();
        source.setUniform(UNIFORM_VALUE);
        source._unsafeSetNonEmptyChildren((byte) 0x3C);

        var target = section();
        assertTrue(SaveLoadSystem3.deserialize(target, SaveLoadSystem3.serialize(source)));

        assertTrue(target.isUniform());
        assertNull(target._rawOrNull());
        assertEquals(UNIFORM_VALUE, target.getUniformValue());
        assertEquals((byte) 0x3C, target.getNonEmptyChildren());
        assertEquals(WorldSection.SECTION_VOLUME, target.getNonEmptyBlockCount());
    }

    @Test
    void deserializesNonUniformDataAsDense() {
        var source = section();
        source.setUniform(UNIFORM_VALUE);
        source.set(7, 8, 9, Mapper.airWithLight(15));

        var target = section();
        assertTrue(SaveLoadSystem3.deserialize(target, SaveLoadSystem3.serialize(source)));

        assertFalse(target.isUniform());
        assertEquals(Mapper.airWithLight(15), target.get(WorldSection.getIndex(7, 8, 9)));
        assertEquals(UNIFORM_VALUE, target.get(WorldSection.getIndex(7, 8, 10)));
        assertEquals(WorldSection.SECTION_VOLUME - 1, target.getNonEmptyBlockCount());
    }

    @Test
    void rejectsTruncatedDataWithoutMutatingTheSection() {
        var truncated = new MemoryBuffer(15).zero();
        try {
            var target = section();
            assertFalse(SaveLoadSystem3.deserialize(target, truncated));
            assertTrue(target.isUniform());
            assertEquals(Mapper.AIR, target.getUniformValue());
            assertEquals(0, target.getNonEmptyChildren());
        } finally {
            truncated.free();
        }
    }

    @Test
    void rejectsInvalidLutIndicesWithoutMutatingTheSection() {
        var source = section();
        source.setUniform(UNIFORM_VALUE);
        var invalid = SaveLoadSystem3.serialize(source).copy();
        try {
            MemoryUtil.memPutShort(invalid.address + Long.BYTES * 2L, (short) 1);

            var target = section();
            assertFalse(SaveLoadSystem3.deserialize(target, invalid));
            assertTrue(target.isUniform());
            assertEquals(Mapper.AIR, target.getUniformValue());
            assertEquals(0, target.getNonEmptyChildren());
        } finally {
            invalid.free();
        }
    }

    @Test
    void missingSectionsRemainCompactSkyLitAir() {
        var tracker = new ActiveSectionTracker(0, ignored -> 1, 0);
        var section = tracker.acquire(0, 0, 0, 0, false);

        assertTrue(section.isUniform());
        assertNull(section._rawOrNull());
        assertEquals(Mapper.airWithLight(15), section.getUniformValue());

        section.release();
        assertTrue(section.isFreed());
    }

    @Test
    void failedSectionsFallBackToCompactSkyLitAir() {
        var tracker = new ActiveSectionTracker(0, ignored -> -1, 0);
        var section = tracker.acquire(0, 0, 0, 0, false);

        assertTrue(section.isUniform());
        assertNull(section._rawOrNull());
        assertEquals(Mapper.airWithLight(15), section.getUniformValue());

        section.release();
        assertTrue(section.isFreed());
    }

    private static WorldSection section() {
        return WorldSection._createRawUntrackedUnsafeSection(0, 1, 2, 3);
    }
}
