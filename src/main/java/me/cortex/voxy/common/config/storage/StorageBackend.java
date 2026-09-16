package me.cortex.voxy.common.config.storage;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntToLongFunction;

public abstract class StorageBackend implements IMappingStorage, IStoredSectionPositionIterator {

    //Implementation may use the scratch buffer as the return value, it MUST NOT free the scratch buffer
    public abstract MemoryBuffer getSectionData(long key, MemoryBuffer scratch);

    public abstract void setSectionData(long key, MemoryBuffer data);

    //Consume each value before requesting the next: serializers and compressors reuse scratch memory.
    public void setSectionDataBatch(int count, IntToLongFunction keyAt, IntFunction<MemoryBuffer> dataAt) {
        for (int i = 0; i < count; i++) {
            this.setSectionData(keyAt.applyAsLong(i), dataAt.apply(i));
        }
    }

    public abstract void deleteSectionData(long key);

    public abstract void flush();

    public abstract void close();

    public List<StorageBackend> getChildBackends() {
        return List.of();
    }

    public final List<StorageBackend> collectAllBackends() {
        List<StorageBackend> backends = new ArrayList<>();
        backends.add(this);
        for (var child : this.getChildBackends()) {
            backends.addAll(child.collectAllBackends());
        }
        return backends;
    }
}
