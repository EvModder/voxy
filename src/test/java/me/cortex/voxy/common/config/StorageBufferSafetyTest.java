package me.cortex.voxy.common.config;

import me.cortex.voxy.common.config.compressors.LZ4Compressor;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageBufferSafetyTest {
    @Test
    void undersizedReadDoesNotWritePastScratch() {
        var backend = new MemoryStorageBackend();
        var source = new MemoryBuffer(16).zero();
        var scratch = new MemoryBuffer(16);
        try {
            scratch.asByteBuffer().putLong(0, -1).putLong(8, -1);
            backend.setSectionData(1, source);
            var limited = scratch.createUntrackedUnfreeableReference().subSize(1);
            assertThrows(IllegalStateException.class, () -> backend.getSectionData(1, limited));
            assertEquals(-1L, scratch.asByteBuffer().getLong(0));
            assertEquals(-1L, scratch.asByteBuffer().getLong(8));
        } finally {
            backend.close();
            source.free();
            scratch.free();
        }
    }

    @Test
    void lz4RoundTripReportsFullOutputLength() {
        var compressor = new LZ4Compressor();
        var source = new MemoryBuffer(65536).zero();
        try {
            var compressed = compressor.compress(source).copy();
            try {
                assertTrue(compressed.size < source.size);
                var decoded = compressor.decompress(compressed);
                assertEquals(source.size, decoded.size);
                assertEquals(-1, source.asByteBuffer().mismatch(decoded.asByteBuffer()));
            } finally { compressed.free(); }
        } finally { source.free(); }
    }

    @Test
    void truncatedCompressedDataFailsExplicitly() {
        var invalid = new MemoryBuffer(3).zero();
        try {
            assertThrows(IllegalStateException.class, () -> new LZ4Compressor().decompress(invalid));
            var error = assertThrows(IllegalStateException.class,
                    () -> new ZSTDCompressor(1).decompress(invalid));
            assertTrue(error.getMessage().startsWith("ZSTD decompression failed:"));
        } finally { invalid.free(); }
    }
}
