package me.cortex.voxy.client.core.rendering.util;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

class SharedIndexBufferTest {
    @Test
    void terrainIndicesCoverEveryPackedQuadCount() {
        var indices = SharedIndexBuffer.generateQuadIndicesInt(SharedIndexBuffer.QUAD_CAPACITY);
        try {
            assertEquals(SharedIndexBuffer.CUBE_INDEX_OFFSET, indices.size);
            int[] pattern = {1, 2, 0, 1, 3, 2};
            for (int quad = 0; quad < SharedIndexBuffer.QUAD_CAPACITY; quad++) {
                for (int corner = 0; corner < 6; corner++) {
                    assertEquals(quad * 4 + pattern[corner],
                            MemoryUtil.memGetInt(indices.address + (quad * 6L + corner) * 4));
                }
            }
        } finally {
            indices.free();
        }
    }

    @Test
    void terrainTrianglesPreserveTheFlatAttributeProvokingVertex() {
        var shorts = SharedIndexBuffer.generateQuadIndicesShort(16384);
        var ints = SharedIndexBuffer.generateQuadIndicesInt(SharedIndexBuffer.QUAD_CAPACITY);
        try {
            for (int i = 0; i < 16384 * 6; i++) {
                assertEquals(Short.toUnsignedInt(MemoryUtil.memGetShort(shorts.address + i * 2L)),
                        MemoryUtil.memGetInt(ints.address + i * 4L));
            }
            for (int triangle = 0; triangle < SharedIndexBuffer.QUAD_CAPACITY * 2; triangle++) {
                assertEquals(1, MemoryUtil.memGetInt(ints.address + triangle * 12L) & 3);
            }
        } finally {
            shorts.free();
            ints.free();
        }
    }

    @Test
    void shortIndicesAcceptExactlyTheUnsignedVertexRange() {
        var indices = SharedIndexBuffer.generateQuadIndicesShort(16384);
        try {
            assertEquals(65535, Short.toUnsignedInt(MemoryUtil.memGetShort(indices.address + indices.size - 4)));
        } finally {
            indices.free();
        }
        assertThrows(IllegalArgumentException.class, () -> SharedIndexBuffer.generateQuadIndicesShort(16385));
        assertThrows(IllegalArgumentException.class, () -> SharedIndexBuffer.generateQuadIndicesInt(-1));
    }
}
