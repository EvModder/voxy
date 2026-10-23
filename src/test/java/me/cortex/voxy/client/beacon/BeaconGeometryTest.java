package me.cortex.voxy.client.beacon;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BeaconGeometryTest {
    @Test
    void nativeUploadMatchesShaderStrideWithoutOverwritingAdjacentMemory() {
        var buffer = MemoryUtil.memAlloc(72);
        try {
            long address = MemoryUtil.memAddress(buffer);
            MemoryUtil.memSet(address, 0x5A, 72);
            BeaconGeometry.write(address, List.of(
                    new BeaconGeometry.Segment(1, 2, 3, 2048, 0xFF0080, 4),
                    new BeaconGeometry.Segment(5, 6, 7, 10, 0x00FF00, 8)));
            assertEquals(1, MemoryUtil.memGetFloat(address));
            assertEquals(2048, MemoryUtil.memGetFloat(address + 12));
            assertEquals(1, MemoryUtil.memGetFloat(address + 16));
            assertEquals(128 / 255f, MemoryUtil.memGetFloat(address + 24));
            assertEquals(5, MemoryUtil.memGetFloat(address + 32));
            assertEquals(8, MemoryUtil.memGetFloat(address + 60));
            assertEquals(0x5A5A5A5A5A5A5A5AL, MemoryUtil.memGetLong(address + 64));
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
}
