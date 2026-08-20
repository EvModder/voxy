package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.other.Mapper;
import org.lwjgl.system.MemoryUtil;

public class SaveLoadSystem3 {
    public static final int STORAGE_VERSION = 0;
    private static final long HEADER_SIZE = Long.BYTES * 2L;
    private static final long BLOCK_INDEX_SIZE = WorldSection.SECTION_VOLUME * Short.BYTES;
    private static final long LUT_OFFSET = HEADER_SIZE + BLOCK_INDEX_SIZE;

    private record SerializationCache(Long2ShortOpenHashMap lutMapCache, MemoryBuffer memoryBuffer) {
        public SerializationCache() {
            this(new Long2ShortOpenHashMap(1024), ThreadLocalMemoryBuffer.create(WorldSection.SECTION_VOLUME*2+WorldSection.SECTION_VOLUME*8+1024));
            this.lutMapCache.defaultReturnValue((short) -1);
        }
    }
    public static int lin2z(int i) {//y,z,x
        int x = i&0x1F;
        int y = (i>>10)&0x1F;
        int z = (i>>5)&0x1F;
        return Integer.expand(x,0b1001001001001)|Integer.expand(y,0b10010010010010)|Integer.expand(z,0b100100100100100);

        //zyxzyxzyxzyxzyx
    }

    public static int z2lin(int i) {
        int x = Integer.compress(i, 0b1001001001001);
        int y = Integer.compress(i, 0b10010010010010);
        int z = Integer.compress(i, 0b100100100100100);
        return x|(y<<10)|(z<<5);
    }

    private static final ThreadLocal<SerializationCache> CACHE = ThreadLocal.withInitial(SerializationCache::new);

    //TODO: Cache like long2short and the short and other data to stop allocs
    public static MemoryBuffer serialize(WorldSection section) {
        var cache = CACHE.get();
        var data = section._rawOrNull();

        Long2ShortOpenHashMap LUT = cache.lutMapCache; LUT.clear();

        MemoryBuffer buffer = cache.memoryBuffer().createUntrackedUnfreeableReference();
        long ptr = buffer.address;

        MemoryUtil.memPutLong(ptr, section.key); ptr += 8;
        long metadataPtr = ptr; ptr += 8;

        if (data == null) {
            long blockPtr = ptr;
            ptr += WorldSection.SECTION_VOLUME*2;
            MemoryUtil.memSet(blockPtr, 0, WorldSection.SECTION_VOLUME*2);
            MemoryUtil.memPutLong(ptr, section.getUniformValue());
            ptr += 8;

            long metadata = 1;
            metadata |= Byte.toUnsignedLong(section.getNonEmptyChildren())<<16;
            MemoryUtil.memPutLong(metadataPtr, metadata);
            return buffer.subSize(ptr-buffer.address);
        }

        long blockPtr = ptr; ptr += WorldSection.SECTION_VOLUME*2;
        long prev = data[0]; MemoryUtil.memPutLong(ptr, prev); ptr+=8; LUT.put(prev, (short) 0);
        short mapping = 0;
        for (long block : data) {
            if (prev != block) {
                prev = block;
                mapping = LUT.putIfAbsent(block, (short) LUT.size());
                if (mapping == -1) {
                    mapping = (short) (LUT.size()-1);
                    MemoryUtil.memPutLong(ptr, block); ptr+=8;
                }
            }
            MemoryUtil.memPutShort(blockPtr, mapping); blockPtr+=2;
        }
        if (LUT.size() >= 1<<16) {
            throw new IllegalStateException();
        }

        //TODO: note! can actually have the first (last?) byte of metadata be the storage version!
        long metadata = 0;
        metadata |= Integer.toUnsignedLong(LUT.size());//Bottom 2 bytes
        metadata |= Byte.toUnsignedLong(section.getNonEmptyChildren())<<16;//Next byte
        //5 bytes free

        MemoryUtil.memPutLong(metadataPtr, metadata);
        //TODO: do hash

        return buffer.subSize(ptr-buffer.address);//Does not get freed
    }

    public static boolean deserialize(WorldSection section, MemoryBuffer data) {
        if (data.size < LUT_OFFSET) {
            return invalid(section, "data is truncated");
        }

        long ptr = data.address;
        long key = MemoryUtil.memGetLong(ptr); ptr += 8;

        if (section.key != key) {
            return invalid(section, "stored key " + key + " does not match requested key " + section.key);
        }

        final long metadata = MemoryUtil.memGetLong(ptr); ptr += 8;
        final int lutSize = (int) (metadata & 0xFFFF);
        if (lutSize < 1 || lutSize > WorldSection.SECTION_VOLUME) {
            return invalid(section, "invalid LUT size " + lutSize);
        }

        long requiredSize = LUT_OFFSET + lutSize * Long.BYTES;
        if (data.size < requiredSize) {
            return invalid(section, "LUT data is truncated");
        }

        long indexPtr = ptr;
        for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
            int mapping = Short.toUnsignedInt(MemoryUtil.memGetShort(indexPtr + i * Short.BYTES));
            if (mapping >= lutSize) {
                return invalid(section, "voxel " + i + " references LUT index " + mapping + " of " + lutSize);
            }
        }

        final byte nonEmptyChildren = (byte) ((metadata>>>16)&0xFF);
        final long lutBasePtr = data.address + LUT_OFFSET;
        if (lutSize == 1) {
            long value = MemoryUtil.memGetLong(lutBasePtr);
            section.setUniform(value);
            section.nonEmptyChildren = nonEmptyChildren;
            if (section.lvl == 0) {
                section.nonEmptyBlockCount = Mapper.isAir(value) ? 0 : WorldSection.SECTION_VOLUME;
            }
            return true;
        }

        final var blockData = section.materialize();
        for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
            blockData[i] = MemoryUtil.memGetLong(lutBasePtr + Short.toUnsignedLong(MemoryUtil.memGetShort(ptr)) * 8L);ptr += 2;
        }
        section.nonEmptyChildren = nonEmptyChildren;

        if (section.lvl == 0) {
            int emptyBlockCount = 0;
            for (long block : blockData) {
                emptyBlockCount += Mapper.isAir(block) ? 1 : 0;
            }
            section.nonEmptyBlockCount = WorldSection.SECTION_VOLUME-emptyBlockCount;
        }

        return true;
    }

    private static boolean invalid(WorldSection section, String reason) {
        Logger.error("Invalid serialized section " + WorldEngine.pprintPos(section.key) + ": " + reason);
        return false;
    }
}
