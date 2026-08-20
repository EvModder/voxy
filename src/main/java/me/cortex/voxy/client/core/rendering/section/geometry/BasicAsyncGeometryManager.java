package me.cortex.voxy.client.core.rendering.section.geometry;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.HierarchicalBitSet;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Consumer;


//Is basicly the manager for an "undefined" data store, the underlying store is irrelevant
// this manager serves as an overlay, that is, it allows an implementation to do "async management" of the data store
public class BasicAsyncGeometryManager implements IGeometryManager {
    public static final int SECTION_METADATA_SIZE = 32;

    private static final long GEOMETRY_ELEMENT_SIZE = 8;
    private final HierarchicalBitSet allocationSet;
    private final AllocationArena allocationHeap = new AllocationArena();
    private final ObjectArrayList<SectionMeta> sectionMetadata = new ObjectArrayList<>(1<<15);

    //Changes that need to be applied to the underlying data store to match this state
    private final IntOpenHashSet invalidatedIds = new IntOpenHashSet(1024);//Ids that need to be invalidated
    //TODO: maybe change from it pointing to MemoryBuffer, to BuiltSection
    //Note!: the int part is an unsigned int ptr, must be scaled by GEOMETRY_ELEMENT_SIZE
    private final Int2ObjectOpenHashMap<MemoryBuffer> heapUploads = new Int2ObjectOpenHashMap<>(1024);//Uploads into the buffer at the given location
    private final IntOpenHashSet heapRemoveUploads = new IntOpenHashSet(1024);//Any removals are added here, so that it can be properly synced
    private long usedCapacity = 0;
    private long lastCapacityWarning;
    private int rejectedAllocations;

    public BasicAsyncGeometryManager(int maxSectionCount, long geometryCapacity) {
        this.allocationSet = new HierarchicalBitSet(maxSectionCount);
        if (geometryCapacity%GEOMETRY_ELEMENT_SIZE != 0)  throw new IllegalStateException();
        this.allocationHeap.setLimit(geometryCapacity/GEOMETRY_ELEMENT_SIZE);
    }

    @Override
    public int uploadSection(BuiltSection section) {
        return this.uploadReplaceSection(-1, section);
    }

    @Override
    public int uploadReplaceSection(int oldId, BuiltSection section) {
        if (section.isEmpty()) {
            throw new IllegalArgumentException("sectionData is empty, cannot upload nothing");
        }

        if (oldId != -1) {
            if (!this.allocationSet.isSet(oldId)) {
                throw new IllegalStateException("Id was not already allocated. id: " + oldId);
            }
            var oldMeta = this.sectionMetadata.get(oldId);
            var newMeta = this.createReplacementMeta(oldMeta, section);
            if (newMeta == null) return OUT_OF_CAPACITY;
            this.sectionMetadata.set(oldId, newMeta);
            this.invalidatedIds.add(oldId);
            return oldId;
        }

        int newId =  this.allocationSet.allocateNext();
        if (newId == HierarchicalBitSet.SET_FULL) {
            throw new IllegalStateException("Tried adding section when section count is already at capacity");
        }
        if (newId > this.sectionMetadata.size()) {
            throw new IllegalStateException("Size exceeds limits: " + newId + ", " + this.sectionMetadata.size() + ", " + this.allocationSet.getCount());
        }

        if (newId < this.sectionMetadata.size()) {
            if (this.sectionMetadata.get(newId) != null) {
                throw new IllegalStateException();
            }
        }

        var newMeta = this.createMeta(section);
        if (newMeta == null) {
            if (!this.allocationSet.free(newId)) throw new IllegalStateException();
            return OUT_OF_CAPACITY;
        }

        if (newId == this.sectionMetadata.size()) {
            this.sectionMetadata.add(newMeta);
        } else {
            if (this.sectionMetadata.set(newId, newMeta) != null) {
                throw new IllegalStateException();
            }
        }

        //Invalidate the section id
        this.invalidatedIds.add(newId);

        //HierarchicalOcclusionTraverser.HACKY_SECTION_COUNT = this.allocationSet.getCount();
        return newId;
    }

    @Override
    public void removeSection(int id) {
        if (!this.allocationSet.free(id)) {
            throw new IllegalStateException("Id was not already allocated. id: " + id);
        }
        var oldMetadata = this.sectionMetadata.set(id, null);
        this.removeGeometry(oldMetadata.geometryPtr);
        this.invalidatedIds.add(id);
    }

    private SectionMeta createMeta(BuiltSection section) {
        if ((section.geometryBuffer.size%GEOMETRY_ELEMENT_SIZE)!=0) throw new IllegalStateException();
        int size = (int) (section.geometryBuffer.size/GEOMETRY_ELEMENT_SIZE);
        int upsized = allocationSize(size);
        int addr = (int)this.allocationHeap.alloc(upsized);
        if (addr == -1) {
            this.logRejectedAllocation(upsized);
            return null;
        }
        this.usedCapacity += upsized;
        this.setUpload(addr, section.geometryBuffer);
        return new SectionMeta(section.position, section.aabb, addr, size, section.offsets, section.childExistence);
    }

    private SectionMeta createReplacementMeta(SectionMeta oldMeta, BuiltSection section) {
        if ((section.geometryBuffer.size%GEOMETRY_ELEMENT_SIZE)!=0) throw new IllegalStateException();
        int size = (int) (section.geometryBuffer.size/GEOMETRY_ELEMENT_SIZE);
        int requiredSize = allocationSize(size);
        int oldSize = (int) this.allocationHeap.getSize(Integer.toUnsignedLong(oldMeta.geometryPtr));
        int addr = oldMeta.geometryPtr;

        if (requiredSize <= oldSize) {
            this.usedCapacity -= this.allocationHeap.shrink(Integer.toUnsignedLong(addr), requiredSize);
        } else if (this.allocationHeap.expand(Integer.toUnsignedLong(addr), requiredSize-oldSize)) {
            this.usedCapacity += requiredSize-oldSize;
        } else {
            addr = (int) this.allocationHeap.alloc(requiredSize);
            if (addr == -1) {
                this.logRejectedAllocation(requiredSize);
                return null;
            }
            this.usedCapacity += requiredSize;
            this.removeGeometry(oldMeta.geometryPtr);
        }

        this.setUpload(addr, section.geometryBuffer);
        return new SectionMeta(section.position, section.aabb, addr, size, section.offsets, section.childExistence);
    }

    private static int allocationSize(int size) {
        //Clamp size upwards to ranges of 128 elements.
        return (size+127)&~127;
    }

    private void setUpload(int addr, MemoryBuffer upload) {
        var previous = this.heapUploads.put(addr, upload);
        if (previous != null && previous != upload) previous.free();
        this.heapRemoveUploads.remove(addr);
    }

    private void removeGeometry(int ptr) {
        this.usedCapacity -= this.allocationHeap.free(Integer.toUnsignedLong(ptr));
        var upload = this.heapUploads.remove(ptr);
        if (upload != null) upload.free();
        this.heapRemoveUploads.add(ptr);
    }

    private void logRejectedAllocation(int requestedSize) {
        this.rejectedAllocations++;
        long now = System.nanoTime();
        if (this.lastCapacityWarning != 0 && now-this.lastCapacityWarning < 10_000_000_000L) return;

        long topRemaining = this.allocationHeap.getLimit()-this.allocationHeap.getSize();
        long largestBlock = Math.max(topRemaining, this.allocationHeap.getLargestFreeBlockSize());
        long free = this.allocationHeap.getLimit()-this.usedCapacity;
        String state = free >= requestedSize ? "fragmented" : "full";
        Logger.warn("Geometry arena is", state, "and cannot fit", requestedSize*GEOMETRY_ELEMENT_SIZE, "bytes; free:", free*GEOMETRY_ELEMENT_SIZE,
                "largest contiguous block:", largestBlock*GEOMETRY_ELEMENT_SIZE, "rejecting one LoD geometry update; rejected since last warning:", this.rejectedAllocations);
        this.rejectedAllocations = 0;
        this.lastCapacityWarning = now;
    }

    @Override
    public void downloadAndRemove(int id, Consumer<BuiltSection> callback) {
        throw new IllegalStateException("Not yet implemented");
    }

    public Int2ObjectOpenHashMap<MemoryBuffer> getUploads() {
        return this.heapUploads;
    }

    public IntOpenHashSet getHeapRemovals() {
        return this.heapRemoveUploads;
    }

    public int getSectionCount() {
        return this.allocationSet.getCount();
    }

    public long getGeometryUsedBytes() {
        return this.usedCapacity * GEOMETRY_ELEMENT_SIZE;
    }

    public IntOpenHashSet getUpdateIds() {
        return this.invalidatedIds;
    }

    public void writeMetadata(int sectionId, long ptr) {
        var sec = this.sectionMetadata.get(sectionId);
        if (sec == null) {
            //Write nothing
            MemoryUtil.memSet(ptr, 0, SECTION_METADATA_SIZE);
        } else {
            sec.writeMetadata(ptr);
        }
    }

    public void writeMetadataSplit(int sectionId, long ptrA, long ptrB) {
        if (SECTION_METADATA_SIZE != 32) {//This system only works with 32 byte metadata
            throw new IllegalStateException();
        }
        var sec = this.sectionMetadata.get(sectionId);
        if (sec == null) {
            //Write nothing
            MemoryUtil.memSet(ptrA, 0, 16);
            MemoryUtil.memSet(ptrB, 0, 16);
        } else {
            sec.writeMetadataSplitParts(ptrA, ptrB);
        }
    }

    private record SectionMeta(long position, int aabb, int geometryPtr, int itemCount, int[] offsets, byte childExistence) {
        public void writeMetadata(long ptr) {
            this.writeMetadataSplitParts(ptr, ptr+16);
        }

        public void writeMetadataSplitParts(long ptrA, long ptrB) {//First 16 bytes are put into ptrA the remaining 16 bytes are put into ptrB
            //Split the long into 2 ints to solve endian issues
            MemoryUtil.memPutInt(ptrA, (int) (this.position>>32)); ptrA += 4;
            MemoryUtil.memPutInt(ptrA, (int) this.position); ptrA += 4;
            MemoryUtil.memPutInt(ptrA, (int) this.aabb); ptrA += 4;
            MemoryUtil.memPutInt(ptrA, this.geometryPtr + this.offsets[0]); ptrA += 4;

            MemoryUtil.memPutInt(ptrB, (this.offsets[1]-this.offsets[0])|((this.offsets[2]-this.offsets[1])<<16)); ptrB += 4;
            MemoryUtil.memPutInt(ptrB, (this.offsets[3]-this.offsets[2])|((this.offsets[4]-this.offsets[3])<<16)); ptrB += 4;
            MemoryUtil.memPutInt(ptrB, (this.offsets[5]-this.offsets[4])|((this.offsets[6]-this.offsets[5])<<16)); ptrB += 4;
            MemoryUtil.memPutInt(ptrB, (this.offsets[7]-this.offsets[6])|((this.itemCount -this.offsets[7])<<16)); ptrB += 4;
        }
    }
}
