package me.cortex.voxy.common.config;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

public interface IMappingStorage {
    int NON_ATOMIC_MAPPING = -1;

    void putIdMapping(int id, ByteBuffer data);

    default void putIdMappings(Int2ObjectMap<byte[]> mappings) {
        for (var entry : mappings.int2ObjectEntrySet()) {
            var buffer = MemoryUtil.memAlloc(entry.getValue().length);
            try {
                buffer.put(entry.getValue()).flip();
                this.putIdMapping(entry.getIntKey(), buffer);
            } finally { MemoryUtil.memFree(buffer); }
        }
    }
    Int2ObjectOpenHashMap<byte[]> getIdMappingsData();

    default int getOrCreateIdMapping(
            int entryType, byte[] identity, IntFunction<byte[]> serializedMappingFactory) {
        return NON_ATOMIC_MAPPING;
    }

    default long getIdMappingVersion() {
        return -1;
    }

    void flush();
    void close();
}
