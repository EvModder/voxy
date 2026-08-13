package me.cortex.voxy.common.config;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

public interface IMappingStorage {
    int NON_ATOMIC_MAPPING = -1;

    void putIdMapping(int id, ByteBuffer data);
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
