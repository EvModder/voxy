package me.cortex.voxy.common.world.other;

import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.MappingIdentity;
import me.cortex.voxy.common.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;


//There are independent mappings for biome and block states, these get combined in the shader and allow for more
// variaty of things
public class Mapper {
    private static final long MAPPING_VERSION_CHECK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int BIOME_TYPE = 2;

    private final IMappingStorage storage;
    public static final long UNKNOWN_MAPPING = -1;
    public static final long AIR = 0;

    private final ReentrantLock blockLock = new ReentrantLock();
    private final ConcurrentHashMap<BlockState, StateEntry> block2stateEntry = new ConcurrentHashMap<>(2000,0.75f, 10);
    private final ObjectArrayList<StateEntry> blockId2stateEntry = new ObjectArrayList<>();
    private volatile StateEntry[] blockStateSnapshot = new StateEntry[0];


    private final ReentrantLock biomeLock = new ReentrantLock();
    private final ReentrantLock mappingRefreshLock = new ReentrantLock();
    private final ConcurrentHashMap<String, BiomeEntry> biome2biomeEntry = new ConcurrentHashMap<>(2000,0.75f, 10);
    private final ObjectArrayList<BiomeEntry> biomeId2biomeEntry = new ObjectArrayList<>();

    private volatile Consumer<StateEntry> newStateCallback;
    private volatile Consumer<BiomeEntry> newBiomeCallback;
    private volatile long mappingVersion = -1;
    private volatile long nextMappingVersionCheck;
    public Mapper(IMappingStorage storage) {
        this.storage = storage;
        //Insert air since its a special entry (index 0)
        var airEntry = new StateEntry(0, Blocks.AIR.defaultBlockState());
        this.block2stateEntry.put(airEntry.state, airEntry);
        this.blockId2stateEntry.add(airEntry);
        this.publishBlockStateSnapshot();

        this.mappingVersion = this.storage.getIdMappingVersion();
        this.loadFromStorage();
    }


    public static boolean isAir(long id) {
        //Note: air can mean void, cave or normal air, as the block state is remapped during ingesting
        return (id&(((1L<<20)-1)<<27)) == 0;
    }

    public static int getBlockId(long id) {
        return (int) ((id>>27)&((1<<20)-1));
    }

    public static int getBiomeId(long id) {
        return (int) ((id>>47)&0x1FF);
    }

    public static int getLightId(long id) {
        return (int) ((id>>56)&0xFF);
    }

    public static long withLight(long id, int light) {
        return (id&(~(0xFFL<<56)))|(Integer.toUnsignedLong(light&0xFF)<<56);
    }

    public static long withBlockBiome(long id, int block, int biome) {
        return (id&(0xFFL<<56))|(Integer.toUnsignedLong(block)<<27)|(Integer.toUnsignedLong(biome)<<47);
    }

    public static long airWithLight(int light) {
        return Integer.toUnsignedLong(light&0xFF)<<56;
    }

    public void setStateCallback(Consumer<StateEntry> stateCallback) {
        this.newStateCallback = stateCallback;
    }

    public void setBiomeCallback(Consumer<BiomeEntry> biomeCallback) {
        this.newBiomeCallback = biomeCallback;
    }

    private void loadFromStorage() {
        //TODO: FIXME: have/store the minecraft version the mappings are from (the data version)
        // SharedConstants.getGameVersion().dataVersion().id()
        // then use this to create an update path instead

        var mappings = this.storage.getIdMappingsData();
        List<StateEntry> sentries = new ArrayList<>();
        List<BiomeEntry> bentries = new ArrayList<>();
        List<Pair<byte[], Integer>> sentryErrors = new ArrayList<>();

        boolean[] forceResave = new boolean[1];
        for (var entry : mappings.int2ObjectEntrySet()) {
            int entryType = entry.getIntKey()>>>30;
            int id = entry.getIntKey() & ((1<<30)-1);
            if (entryType == BLOCK_STATE_TYPE) {
                var sentry = StateEntry.deserialize(id, entry.getValue(), forceResave);
                if (sentry.state.isAir()) {
                    Logger.error("Deserialization was air, removed block");
                    sentryErrors.add(new Pair<>(entry.getValue(), id));
                    continue;
                }
                sentries.add(sentry);
                var oldEntry = this.block2stateEntry.putIfAbsent(sentry.state, sentry);
                if (oldEntry != null) {
                    //forceResave[0] |= true;
                    Logger.warn("Multiple mappings for blockstate, using old state, expect things to possibly go really badly. " + oldEntry.id + ":" + sentry.id + ":" + sentry.state );
                }
            } else if (entryType == BIOME_TYPE) {
                var bentry = BiomeEntry.deserialize(id, entry.getValue());
                bentries.add(bentry);
                if (this.biome2biomeEntry.put(bentry.biome, bentry) != null) {
                    throw new IllegalStateException("Multiple mappings for biome entry");
                }
            } else {
                throw new IllegalStateException("Unknown entryType");
            }
        }

        if (!sentryErrors.isEmpty()) {
            forceResave[0] |= true;
            //Insert garbage types into the mapping for those blocks, TODO:FIXME: Need to upgrade the type or have a solution to error blocks
            var rand = new Random();
            for (var error : sentryErrors) {
                while (true) {
                    var state = new StateEntry(error.right(), Block.BLOCK_STATE_REGISTRY.byId(rand.nextInt(Block.BLOCK_STATE_REGISTRY.size() - 1)));
                    if (this.block2stateEntry.put(state.state, state) == null) {
                        sentries.add(state);
                        break;
                    }
                }
            }
        }

        //Insert into the arrays
        sentries.stream().sorted(Comparator.comparing(a->a.id)).forEach(entry -> {
            if (this.blockId2stateEntry.size() != entry.id) {
                throw new IllegalStateException("Block entry not ordered");
            }
            this.blockId2stateEntry.add(entry);
        });
        this.publishBlockStateSnapshot();

        bentries.stream().sorted(Comparator.comparing(a->a.id)).forEach(entry -> {
            if (this.biomeId2biomeEntry.size() != entry.id) {
                throw new IllegalStateException("Biome entry not ordered. got " + entry.biome + " with id " + entry.id + " expected id " + this.biomeId2biomeEntry.size());
            }
            this.biomeId2biomeEntry.add(entry);
        });

        if (forceResave[0]) {
            Logger.warn("Forced state resave triggered");
            this.forceResaveStates();
        }
    }

    public final int getBlockStateCount() {
        return this.blockStateSnapshot.length;
    }

    private StateEntry registerNewBlockState(BlockState state) {
        var candidate = new StateEntry(0, state);
        byte[] identity = MappingIdentity.fromSerializedMapping(candidate.serialize());
        int sharedId = this.storage.getOrCreateIdMapping(BLOCK_STATE_TYPE, identity,
                id -> new StateEntry(id, state).serialize());
        if (sharedId != IMappingStorage.NON_ATOMIC_MAPPING) {
            return this.installSharedBlockState(sharedId, state);
        }

        StateEntry entry;
        this.blockLock.lock();
        try {
            entry = this.block2stateEntry.get(state);
            if (entry != null) {
                return entry;
            }

            entry = new StateEntry(this.blockId2stateEntry.size(), state);
            this.blockId2stateEntry.add(entry);
            this.block2stateEntry.put(state, entry);
            this.publishBlockStateSnapshot();
        } finally {
            this.blockLock.unlock();
        }

        this.putMapping(entry.id | (BLOCK_STATE_TYPE << 30), entry.serialize());
        //this.storage.flush();

        if (this.newStateCallback != null) {
            this.newStateCallback.accept(entry);
        }
        return entry;
    }

    private BiomeEntry registerNewBiome(String biome) {
        var candidate = new BiomeEntry(0, biome);
        byte[] identity = MappingIdentity.fromSerializedMapping(candidate.serialize());
        int sharedId = this.storage.getOrCreateIdMapping(BIOME_TYPE, identity,
                id -> new BiomeEntry(id, biome).serialize());
        if (sharedId != IMappingStorage.NON_ATOMIC_MAPPING) {
            return this.installSharedBiome(sharedId, biome);
        }

        BiomeEntry entry;
        this.biomeLock.lock();
        try {
            entry = this.biome2biomeEntry.get(biome);
            if (entry != null) {
                return entry;
            }
            entry = new BiomeEntry(this.biomeId2biomeEntry.size(), biome);
            this.biomeId2biomeEntry.add(entry);
            this.biome2biomeEntry.put(biome, entry);
        } finally {
            this.biomeLock.unlock();
        }

        this.putMapping(entry.id | (BIOME_TYPE << 30), entry.serialize());
        //this.storage.flush();

        if (this.newBiomeCallback != null) {
            this.newBiomeCallback.accept(entry);
        }
        return entry;
    }

    private StateEntry installSharedBlockState(int id, BlockState state) {
        StateEntry entry = null;
        boolean added = false;
        this.blockLock.lock();
        try {
            if (id < this.blockId2stateEntry.size()) {
                entry = this.blockId2stateEntry.get(id);
                if (!entry.state.equals(state)) {
                    throw new IllegalStateException("Shared block-state mapping conflict at ID " + id);
                }
            } else if (id == this.blockId2stateEntry.size()) {
                var candidate = new StateEntry(id, state);
                var existing = this.block2stateEntry.putIfAbsent(state, candidate);
                if (existing != null && existing.id != id) {
                    throw new IllegalStateException("Shared block state has multiple IDs");
                }
                entry = existing == null ? candidate : existing;
                this.blockId2stateEntry.add(entry);
                this.publishBlockStateSnapshot();
                added = true;
            }

            if (entry != null) {
                var existing = this.block2stateEntry.putIfAbsent(state, entry);
                if (existing != null && existing.id != id) {
                    throw new IllegalStateException("Shared block state has multiple IDs");
                }
            }
        } finally {
            this.blockLock.unlock();
        }

        if (entry == null) {
            this.refreshMappingsFromStorage();
            entry = this.block2stateEntry.get(state);
            if (entry == null || entry.id != id) {
                throw new IllegalStateException("Shared block-state mapping did not become visible locally");
            }
        } else if (added && this.newStateCallback != null) {
            this.newStateCallback.accept(entry);
        }
        return entry;
    }

    private BiomeEntry installSharedBiome(int id, String biome) {
        BiomeEntry entry = null;
        boolean added = false;
        this.biomeLock.lock();
        try {
            if (id < this.biomeId2biomeEntry.size()) {
                entry = this.biomeId2biomeEntry.get(id);
                if (!entry.biome.equals(biome)) {
                    throw new IllegalStateException("Shared biome mapping conflict at ID " + id);
                }
            } else if (id == this.biomeId2biomeEntry.size()) {
                var candidate = new BiomeEntry(id, biome);
                var existing = this.biome2biomeEntry.putIfAbsent(biome, candidate);
                if (existing != null && existing.id != id) {
                    throw new IllegalStateException("Shared biome has multiple IDs");
                }
                entry = existing == null ? candidate : existing;
                this.biomeId2biomeEntry.add(entry);
                added = true;
            }

            if (entry != null) {
                var existing = this.biome2biomeEntry.putIfAbsent(biome, entry);
                if (existing != null && existing.id != id) {
                    throw new IllegalStateException("Shared biome has multiple IDs");
                }
            }
        } finally {
            this.biomeLock.unlock();
        }

        if (entry == null) {
            this.refreshMappingsFromStorage();
            entry = this.biome2biomeEntry.get(biome);
            if (entry == null || entry.id != id) {
                throw new IllegalStateException("Shared biome mapping did not become visible locally");
            }
        } else if (added && this.newBiomeCallback != null) {
            this.newBiomeCallback.accept(entry);
        }
        return entry;
    }

    private void putMapping(int mappingKey, byte[] serialized) {
        ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
        try {
            buffer.put(serialized).rewind();
            this.storage.putIdMapping(mappingKey, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }


    //TODO:FIXME: IS VERY SLOW NEED TO MAKE IT LOCK FREE, or at minimum use a concurrent map
    public long getBaseId(byte light, BlockState state, Holder<Biome> biome) {
        if (state.isAir()) return Byte.toUnsignedLong(light) <<56;//Special case and fast return for air, dont care about the biome
        return composeMappingId(light, this.getIdForBlockState(state), this.getIdForBiome(biome));
    }

    public BlockState getBlockStateFromBlockId(int blockId) {
        return this.getStateEntry(blockId).state;
    }

    public int getIdForBlockState(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        var mapping = this.block2stateEntry.get(state);
        if (mapping == null) {
            mapping = this.registerNewBlockState(state);
        }
        return mapping.id;
    }

    public int getBlockStateOpacity(long mappingId) {
        return this.getBlockStateOpacity(getBlockId(mappingId));
    }

    public int getBlockStateOpacity(int blockId) {
        return this.getStateEntry(blockId).opacity;
    }

    private StateEntry getStateEntry(int blockId) {
        StateEntry[] entries = this.blockStateSnapshot;
        if (blockId < 0 || blockId >= entries.length) {
            this.refreshMappingsFromStorage();
            entries = this.blockStateSnapshot;
        }
        return entries[blockId];
    }

    public int getIdForBiome(Holder<Biome> biome) {
        String biomeId = biome.unwrapKey().get().identifier().toString();
        var entry = this.biome2biomeEntry.get(biomeId);
        if (entry == null) {
            entry = this.registerNewBiome(biomeId);
        }
        return entry.id;
    }

    public static long composeMappingId(byte light, int blockId, int biomeId) {
        if (blockId == AIR) {//Dont care about biome for air
            return Byte.toUnsignedLong(light)<<56;
        }
        return (Byte.toUnsignedLong(light)<<56)|(Integer.toUnsignedLong(biomeId) << 47)|(Integer.toUnsignedLong(blockId)<<27);
    }

    public StateEntry[] getStateEntries() {
        return this.blockStateSnapshot.clone();
    }

    public BiomeEntry[] getBiomeEntries() {
        this.refreshMappingsIfChanged();
        this.biomeLock.lock();
        try {
            var set = new ArrayList<>(this.biomeId2biomeEntry);
            BiomeEntry[] out = new BiomeEntry[set.size()];
            int index = 0;
            for (var entry : set) {
                if (entry.id != index) {
                    throw new IllegalStateException();
                }
                out[index++] = entry;
            }
            return out;
        } finally {
            this.biomeLock.unlock();
        }
    }

    public void refreshMappingsIfChanged() {
        long now = System.nanoTime();
        if (now < this.nextMappingVersionCheck) {
            return;
        }
        this.nextMappingVersionCheck = now + MAPPING_VERSION_CHECK_INTERVAL_NANOS;
        this.refreshMappingsNowIfChanged();
    }

    public void refreshMappingsNowIfChanged() {
        long currentVersion = this.storage.getIdMappingVersion();
        if (currentVersion >= 0 && currentVersion != this.mappingVersion) {
            this.refreshMappingsFromStorage();
        }
    }

    private void refreshMappingsFromStorage() {
        this.mappingRefreshLock.lock();
        List<StateEntry> addedStates = new ArrayList<>();
        List<BiomeEntry> addedBiomes = new ArrayList<>();
        try {
            long observedVersion = this.storage.getIdMappingVersion();
            var mappings = this.storage.getIdMappingsData();
            List<StateEntry> states = new ArrayList<>();
            List<BiomeEntry> biomes = new ArrayList<>();
            boolean[] forceResave = new boolean[1];
            for (var mapping : mappings.int2ObjectEntrySet()) {
                int entryType = mapping.getIntKey() >>> 30;
                int id = mapping.getIntKey() & ((1 << 30) - 1);
                if (entryType == BLOCK_STATE_TYPE) {
                    states.add(StateEntry.deserialize(id, mapping.getValue(), forceResave));
                } else if (entryType == BIOME_TYPE) {
                    biomes.add(BiomeEntry.deserialize(id, mapping.getValue()));
                }
            }
            if (forceResave[0]) {
                throw new IllegalStateException("Shared mappings require a data fix and cannot be refreshed safely");
            }

            states.sort(Comparator.comparingInt(entry -> entry.id));
            this.blockLock.lock();
            try {
                for (var entry : states) {
                    if (entry.id < this.blockId2stateEntry.size()) {
                        var current = this.blockId2stateEntry.get(entry.id);
                        if (!current.state.equals(entry.state)) {
                            throw new IllegalStateException("Shared block-state mapping conflict at ID " + entry.id);
                        }
                        continue;
                    }
                    if (entry.id != this.blockId2stateEntry.size()) {
                        throw new IllegalStateException("Gap in shared block-state mappings at ID " + entry.id);
                    }
                    var current = this.block2stateEntry.putIfAbsent(entry.state, entry);
                    if (current != null && current.id != entry.id) {
                        throw new IllegalStateException("Shared block state has multiple IDs");
                    }
                    this.blockId2stateEntry.add(entry);
                    addedStates.add(entry);
                }
                if (!addedStates.isEmpty()) {
                    this.publishBlockStateSnapshot();
                }
            } finally {
                this.blockLock.unlock();
            }

            biomes.sort(Comparator.comparingInt(entry -> entry.id));
            this.biomeLock.lock();
            try {
                for (var entry : biomes) {
                    if (entry.id < this.biomeId2biomeEntry.size()) {
                        var current = this.biomeId2biomeEntry.get(entry.id);
                        if (!current.biome.equals(entry.biome)) {
                            throw new IllegalStateException("Shared biome mapping conflict at ID " + entry.id);
                        }
                        continue;
                    }
                    if (entry.id != this.biomeId2biomeEntry.size()) {
                        throw new IllegalStateException("Gap in shared biome mappings at ID " + entry.id);
                    }
                    var current = this.biome2biomeEntry.putIfAbsent(entry.biome, entry);
                    if (current != null && current.id != entry.id) {
                        throw new IllegalStateException("Shared biome has multiple IDs");
                    }
                    this.biomeId2biomeEntry.add(entry);
                    addedBiomes.add(entry);
                }
            } finally {
                this.biomeLock.unlock();
            }
            this.mappingVersion = observedVersion;
        } finally {
            this.mappingRefreshLock.unlock();
        }

        if (this.newStateCallback != null) {
            addedStates.forEach(this.newStateCallback);
        }
        if (this.newBiomeCallback != null) {
            addedBiomes.forEach(this.newBiomeCallback);
        }
    }

    private void publishBlockStateSnapshot() {
        this.blockStateSnapshot = this.blockId2stateEntry.toArray(StateEntry[]::new);
    }

    public void forceResaveStates() {
        var blocks = new ArrayList<>(this.block2stateEntry.values());
        var biomes = new ArrayList<>(this.biome2biomeEntry.values());


        for (var entry : blocks) {
            if (entry.state.isAir() && entry.id == 0) {
                continue;
            }
            if (this.blockId2stateEntry.indexOf(entry) != entry.id) {
                throw new IllegalStateException("State Id NOT THE SAME, very critically bad. arr:" + this.blockId2stateEntry.indexOf(entry) + " entry: " + entry.id);
            }
            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | (BLOCK_STATE_TYPE<<30), buffer);
            MemoryUtil.memFree(buffer);
        }

        for (var entry : biomes) {
            if (this.biomeId2biomeEntry.indexOf(entry) != entry.id) {
                throw new IllegalStateException("Biome Id NOT THE SAME, very critically bad");
            }

            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | (BIOME_TYPE<<30), buffer);
            MemoryUtil.memFree(buffer);
        }

        this.storage.flush();
    }

    public void close() {

    }


    public static final class StateEntry {
        public final int id;
        public final BlockState state;
        public final int opacity;
        public StateEntry(int id, BlockState state) {
            this.id = id;
            this.state = state;
            //Override opacity of leaves to be solid
            if (state.getBlock() instanceof LeavesBlock) {
                this.opacity = 15;
            } else {
                this.opacity = state.getLightDampening();
            }
        }

        public byte[] serialize() {
            try {
                var serialized = new CompoundTag();
                serialized.putInt("id", this.id);
                serialized.put("block_state", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, this.state).result().get());
                var out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static StateEntry deserialize(int id, byte[] data, boolean[] forceResave) {
            try {
                var compound = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
                if (compound.getIntOr("id", -1) != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                var bsc = compound.getCompound("block_state").orElseThrow();
                var state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);
                if (state.isError()) {
                    Logger.info("Could not decode blockstate, attempting fixes, error: "+ state.error().get().message());
                    bsc = (CompoundTag) DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE,bsc),0, SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
                    state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);
                    if (state.isError()) {
                        Logger.error("Could not decode blockstate setting to air. id:" + id + " error: " + state.error().get().message());
                        return new StateEntry(id, Blocks.AIR.defaultBlockState());
                    } else {
                        Logger.info("Fixed blockstate to: " + state.getOrThrow());
                        forceResave[0] |= true;
                        return new StateEntry(id, state.getOrThrow());
                    }
                } else {
                    return new StateEntry(id, state.getOrThrow());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static final class BiomeEntry {
        public final int id;
        public final String biome;

        public BiomeEntry(int id, String biome) {
            this.id = id;
            this.biome = biome;
        }

        public byte[] serialize() {
            try {
                var serialized = new CompoundTag();
                serialized.putInt("id", this.id);
                serialized.putString("biome_id", this.biome);
                var out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static BiomeEntry deserialize(int id, byte[] data) {
            try {
                var compound = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
                if (compound.getIntOr("id", -1) != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                String biome = compound.getStringOr("biome_id", null);
                return new BiomeEntry(id, biome);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
