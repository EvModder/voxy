package me.cortex.voxy.common.config.storage.lmdb;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.MappingIdentity;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.lmdb.MDBEnvInfo;
import org.lwjgl.util.lmdb.MDBVal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;

import static org.lwjgl.util.lmdb.LMDB.*;

public final class LMDBStorageBackend extends StorageBackend {
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int BIOME_TYPE = 2;
    private static final int ID_MASK = (1 << 30) - 1;
    private static final int SCHEMA_VERSION = 1;
    private static final int SCHEMA_VERSION_KEY = 0;
    private static final int MAPPING_VERSION_KEY = 1;
    private static final int NEXT_BLOCK_STATE_ID_KEY = 2;
    private static final int NEXT_BIOME_ID_KEY = 3;
    private static final long DEFAULT_MAP_SIZE_BYTES = 16L << 30;
    private static final long MINIMUM_MAP_GROWTH_BYTES = 1L << 30;
    public static final String DEFAULT_DIRECTORY_NAME = "voxy-lmdb";
    private static final Object ENVIRONMENT_REGISTRY_LOCK = new Object();
    private static final Map<Path, SharedEnvironment> OPEN_ENVIRONMENTS = new HashMap<>();
    private static final ThreadLocal<MessageDigest> IDENTITY_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    });

    private final SharedEnvironment environment;
    private final AtomicBoolean closed = new AtomicBoolean();

    public LMDBStorageBackend(String path) {
        this(path, DEFAULT_MAP_SIZE_BYTES);
    }

    LMDBStorageBackend(String path, long initialMapSizeBytes) {
        if (initialMapSizeBytes <= 0) {
            throw new IllegalArgumentException("LMDB map size must be positive");
        }
        this.environment = acquireEnvironment(Path.of(path), initialMapSizeBytes);
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        ensureOpen();
        if (level < -1 || level > 15) {
            throw new IllegalArgumentException("Invalid Voxy LoD level " + level);
        }
        this.environment.read((transaction, stack) -> {
            long cursor = openCursor(transaction, this.environment.sectionDatabase, stack);
            try {
                MDBVal key = MDBVal.calloc(stack);
                MDBVal value = MDBVal.calloc(stack);
                int result;
                if (level == -1) {
                    result = mdb_cursor_get(cursor, key, value, MDB_FIRST);
                } else {
                    key.mv_data(longBuffer(stack, (long) level << 60));
                    result = mdb_cursor_get(cursor, key, value, MDB_SET_RANGE);
                }
                while (result == MDB_SUCCESS) {
                    long sectionKey = readLong(key.mv_data(), "section key");
                    if (level != -1 && WorldEngine.getLevel(sectionKey) != level) {
                        break;
                    }
                    consumer.accept(sectionKey);
                    result = mdb_cursor_get(cursor, key, value, MDB_NEXT);
                }
                checkNotFound(result);
                return null;
            } finally {
                mdb_cursor_close(cursor);
            }
        });
    }

    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        ensureOpen();
        return this.environment.read((transaction, stack) -> {
            ByteBuffer value = get(transaction, this.environment.sectionDatabase, longBuffer(stack, key), stack);
            if (value == null) {
                return null;
            }
            if (value.remaining() > scratch.size) {
                throw new IllegalStateException("Stored Voxy section exceeds scratch buffer: " + value.remaining());
            }
            UnsafeUtil.memcpy(MemoryUtil.memAddress(value), scratch.address, value.remaining());
            return scratch.subSize(value.remaining());
        });
    }

    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        ensureOpen();
        if (data.size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Voxy section exceeds LMDB value limits: " + data.size);
        }
        this.environment.write((transaction, stack) -> {
            put(transaction, this.environment.sectionDatabase, longBuffer(stack, key),
                    MemoryUtil.memByteBuffer(data.address, (int) data.size), stack);
            return null;
        });
    }

    @Override
    public void deleteSectionData(long key) {
        ensureOpen();
        this.environment.write((transaction, stack) -> {
            delete(transaction, this.environment.sectionDatabase, longBuffer(stack, key), stack);
            return null;
        });
    }

    @Override
    public void putIdMapping(int mappingKey, ByteBuffer data) {
        ensureOpen();
        int entryType = mappingKey >>> 30;
        int entryId = mappingKey & ID_MASK;
        validateEntryType(entryType);
        byte[] serialized = copy(data);
        byte[] identity = MappingIdentity.fromSerializedMapping(serialized);
        byte[] identityHash = hashIdentity(entryType, identity);

        this.environment.write((transaction, stack) -> {
            ByteBuffer mappingKeyBuffer = intBuffer(stack, mappingKey);
            ByteBuffer oldValue = get(transaction, this.environment.idMappingDatabase,
                    mappingKeyBuffer, stack);
            if (oldValue != null) {
                byte[] oldIdentity = MappingIdentity.fromSerializedMapping(copy(oldValue));
                if (!Arrays.equals(oldIdentity, identity)) {
                    byte[] oldHash = hashIdentity(entryType, oldIdentity);
                    Integer canonicalId = getCanonicalMappingId(transaction, oldHash, oldIdentity, stack);
                    if (canonicalId != null && canonicalId == entryId) {
                        delete(transaction, this.environment.mappingIdentityDatabase,
                                byteBuffer(stack, oldHash), stack);
                    }
                }
            }

            Integer canonicalId = getCanonicalMappingId(transaction, identityHash, identity, stack);
            if (canonicalId == null || canonicalId == entryId) {
                putCanonicalMapping(transaction, identityHash, identity, entryId, stack);
            }
            put(transaction, this.environment.idMappingDatabase, mappingKeyBuffer,
                    byteBuffer(stack, serialized), stack);
            advanceNextMappingId(transaction, entryType, entryId, stack);
            incrementMappingVersion(transaction, stack);
            return null;
        });
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        ensureOpen();
        return this.environment.read((transaction, stack) -> {
            var mappings = new Int2ObjectOpenHashMap<byte[]>();
            long cursor = openCursor(transaction, this.environment.idMappingDatabase, stack);
            try {
                MDBVal key = MDBVal.calloc(stack);
                MDBVal value = MDBVal.calloc(stack);
                int result = mdb_cursor_get(cursor, key, value, MDB_FIRST);
                while (result == MDB_SUCCESS) {
                    int mappingKey = readInt(key.mv_data(), "mapping key");
                    if (mappings.put(mappingKey, copy(value.mv_data())) != null) {
                        throw new IllegalStateException("Duplicate Voxy mapping key " + mappingKey);
                    }
                    result = mdb_cursor_get(cursor, key, value, MDB_NEXT);
                }
                checkNotFound(result);
                return mappings;
            } finally {
                mdb_cursor_close(cursor);
            }
        });
    }

    @Override
    public int getOrCreateIdMapping(
            int entryType, byte[] identity, IntFunction<byte[]> serializedMappingFactory) {
        ensureOpen();
        validateEntryType(entryType);
        byte[] identityHash = hashIdentity(entryType, identity);
        return this.environment.write((transaction, stack) -> {
            Integer existingId = getCanonicalMappingId(transaction, identityHash, identity, stack);
            if (existingId != null) {
                int mappingKey = existingId | (entryType << 30);
                if (get(transaction, this.environment.idMappingDatabase,
                        intBuffer(stack, mappingKey), stack) == null) {
                    throw new IllegalStateException("LMDB identity index references a missing Voxy mapping");
                }
                return existingId;
            }

            int nextIdKey = nextIdMetadataKey(entryType);
            long nextId = requireMetadata(transaction, this.environment.metadataDatabase, nextIdKey, stack);
            if (nextId > ID_MASK) {
                throw new IllegalStateException("Voxy mapping ID space exhausted for entry type " + entryType);
            }
            int allocatedId = (int) nextId;
            byte[] serialized = serializedMappingFactory.apply(allocatedId);
            byte[] serializedIdentity = MappingIdentity.fromSerializedMapping(serialized);
            if (!Arrays.equals(identity, serializedIdentity)) {
                throw new IllegalArgumentException("Serialized Voxy mapping does not match its identity");
            }

            put(transaction, this.environment.idMappingDatabase,
                    intBuffer(stack, allocatedId | (entryType << 30)), byteBuffer(stack, serialized), stack);
            putCanonicalMapping(transaction, identityHash, identity, allocatedId, stack);
            putMetadata(transaction, this.environment.metadataDatabase, nextIdKey, nextId + 1, stack);
            incrementMappingVersion(transaction, stack);
            return allocatedId;
        });
    }

    @Override
    public long getIdMappingVersion() {
        ensureOpen();
        return this.environment.read((transaction, stack) ->
                requireMetadata(transaction, this.environment.metadataDatabase, MAPPING_VERSION_KEY, stack));
    }

    @Override
    public void flush() {
        ensureOpen();
        this.environment.flush();
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            releaseEnvironment(this.environment);
        }
    }

    long getMapSize() {
        ensureOpen();
        return this.environment.getMapSize();
    }

    void importMappings(Map<Integer, byte[]> mappings) {
        ensureOpen();
        this.environment.write((transaction, stack) -> {
            long cursor = openCursor(transaction, this.environment.idMappingDatabase, stack);
            try {
                MDBVal key = MDBVal.calloc(stack);
                MDBVal value = MDBVal.calloc(stack);
                int status = mdb_cursor_get(cursor, key, value, MDB_FIRST);
                if (status != MDB_NOTFOUND) {
                    check(status);
                    throw new IllegalStateException("LMDB migration destination already contains mappings");
                }
            } finally {
                mdb_cursor_close(cursor);
            }

            long mappingVersion = requireMetadata(
                    transaction, this.environment.metadataDatabase, MAPPING_VERSION_KEY, stack);
            for (var entry : mappings.entrySet()) {
                int stackPointer = stack.getPointer();
                try {
                    int mappingKey = entry.getKey();
                    int entryType = mappingKey >>> 30;
                    int entryId = mappingKey & ID_MASK;
                    validateEntryType(entryType);
                    byte[] serialized = entry.getValue();
                    byte[] identity = MappingIdentity.fromSerializedMapping(serialized);
                    byte[] identityHash = hashIdentity(entryType, identity);
                    if (getCanonicalMappingId(transaction, identityHash, identity, stack) == null) {
                        putCanonicalMapping(transaction, identityHash, identity, entryId, stack);
                    }
                    put(transaction, this.environment.idMappingDatabase,
                            intBuffer(stack, mappingKey), byteBuffer(stack, serialized), stack);
                    advanceNextMappingId(transaction, entryType, entryId, stack);
                    mappingVersion++;
                } finally {
                    stack.setPointer(stackPointer);
                }
            }
            putMetadata(transaction, this.environment.metadataDatabase,
                    MAPPING_VERSION_KEY, mappingVersion, stack);
            return null;
        });
    }

    void importSectionBatch(List<SectionData> sections) {
        ensureOpen();
        if (sections.isEmpty()) {
            return;
        }
        int largestValue = sections.stream().mapToInt(section -> section.data.length).max().orElseThrow();
        ByteBuffer valueBuffer = MemoryUtil.memAlloc(largestValue);
        try {
            this.environment.write((transaction, stack) -> {
                for (SectionData section : sections) {
                    int stackPointer = stack.getPointer();
                    try {
                        valueBuffer.clear().put(section.data).flip();
                        put(transaction, this.environment.sectionDatabase,
                                longBuffer(stack, section.key), valueBuffer, stack);
                    } finally {
                        stack.setPointer(stackPointer);
                    }
                }
                return null;
            });
        } finally {
            MemoryUtil.memFree(valueBuffer);
        }
    }

    record SectionData(long key, byte[] data) {
    }

    private void ensureOpen() {
        if (this.closed.get()) {
            throw new IllegalStateException("LMDB Voxy storage is closed");
        }
    }

    private static SharedEnvironment acquireEnvironment(Path path, long initialMapSizeBytes) {
        Path normalizedPath = canonicalizeEnvironmentPath(path);
        synchronized (ENVIRONMENT_REGISTRY_LOCK) {
            SharedEnvironment environment = OPEN_ENVIRONMENTS.get(normalizedPath);
            if (environment == null) {
                environment = new SharedEnvironment(normalizedPath, initialMapSizeBytes);
                OPEN_ENVIRONMENTS.put(normalizedPath, environment);
            } else {
                environment.retain(initialMapSizeBytes);
            }
            return environment;
        }
    }

    private static Path canonicalizeEnvironmentPath(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalizedPath);
            return normalizedPath.toRealPath();
        } catch (IOException exception) {
            throw new RuntimeException("Unable to prepare shared Voxy LMDB storage at " + normalizedPath, exception);
        }
    }

    private static void releaseEnvironment(SharedEnvironment environment) {
        synchronized (ENVIRONMENT_REGISTRY_LOCK) {
            if (environment.release()) {
                OPEN_ENVIRONMENTS.remove(environment.path, environment);
                environment.close();
            }
        }
    }

    private static void validateEntryType(int entryType) {
        if (entryType != BLOCK_STATE_TYPE && entryType != BIOME_TYPE) {
            throw new IllegalArgumentException("Unsupported Voxy mapping entry type " + entryType);
        }
    }

    private static int nextIdMetadataKey(int entryType) {
        return entryType == BLOCK_STATE_TYPE ? NEXT_BLOCK_STATE_ID_KEY : NEXT_BIOME_ID_KEY;
    }

    private void advanceNextMappingId(
            long transaction, int entryType, int entryId, MemoryStack stack) {
        int metadataKey = nextIdMetadataKey(entryType);
        long current = requireMetadata(transaction, this.environment.metadataDatabase, metadataKey, stack);
        if (entryId >= current) {
            putMetadata(transaction, this.environment.metadataDatabase, metadataKey, (long) entryId + 1, stack);
        }
    }

    private void incrementMappingVersion(long transaction, MemoryStack stack) {
        long version = requireMetadata(
                transaction, this.environment.metadataDatabase, MAPPING_VERSION_KEY, stack);
        putMetadata(transaction, this.environment.metadataDatabase, MAPPING_VERSION_KEY, version + 1, stack);
    }

    private Integer getCanonicalMappingId(
            long transaction, byte[] identityHash, byte[] identity, MemoryStack stack) {
        ByteBuffer value = get(transaction, this.environment.mappingIdentityDatabase,
                byteBuffer(stack, identityHash), stack);
        if (value == null) {
            return null;
        }
        if (value.remaining() != Integer.BYTES + identity.length) {
            throw new IllegalStateException("Invalid LMDB Voxy mapping identity record");
        }
        int entryId = readInt(value, "mapping identity ID");
        for (int index = 0; index < identity.length; index++) {
            if (value.get(Integer.BYTES + index) != identity[index]) {
                throw new IllegalStateException("SHA-256 collision in the Voxy mapping identity index");
            }
        }
        return entryId;
    }

    private void putCanonicalMapping(
            long transaction, byte[] identityHash, byte[] identity, int entryId, MemoryStack stack) {
        ByteBuffer value = stack.malloc(Integer.BYTES + identity.length).order(ByteOrder.nativeOrder());
        value.putInt(entryId).put(identity).flip();
        put(transaction, this.environment.mappingIdentityDatabase,
                byteBuffer(stack, identityHash), value, stack);
    }

    private static byte[] hashIdentity(int entryType, byte[] identity) {
        MessageDigest digest = IDENTITY_DIGEST.get();
        digest.reset();
        digest.update((byte) entryType);
        return digest.digest(identity);
    }

    private static byte[] copy(ByteBuffer buffer) {
        ByteBuffer duplicate = buffer.duplicate();
        byte[] copy = new byte[duplicate.remaining()];
        duplicate.get(copy);
        return copy;
    }

    private static ByteBuffer byteBuffer(MemoryStack stack, byte[] value) {
        return stack.malloc(value.length).put(value).flip();
    }

    private static ByteBuffer intBuffer(MemoryStack stack, int value) {
        return stack.malloc(Integer.BYTES).order(ByteOrder.nativeOrder()).putInt(0, value);
    }

    private static ByteBuffer longBuffer(MemoryStack stack, long value) {
        return stack.malloc(Long.BYTES).order(ByteOrder.nativeOrder()).putLong(0, value);
    }

    private static int readInt(ByteBuffer value, String description) {
        if (value == null || value.remaining() < Integer.BYTES) {
            throw new IllegalStateException("Invalid LMDB " + description);
        }
        return value.order(ByteOrder.nativeOrder()).getInt(value.position());
    }

    private static long readLong(ByteBuffer value, String description) {
        if (value == null || value.remaining() != Long.BYTES) {
            throw new IllegalStateException("Invalid LMDB " + description);
        }
        return value.order(ByteOrder.nativeOrder()).getLong(value.position());
    }

    private static ByteBuffer get(
            long transaction, int database, ByteBuffer key, MemoryStack stack) {
        MDBVal keyValue = MDBVal.calloc(stack).mv_data(key);
        MDBVal result = MDBVal.calloc(stack);
        int status = mdb_get(transaction, database, keyValue, result);
        if (status == MDB_NOTFOUND) {
            return null;
        }
        check(status);
        return result.mv_data();
    }

    private static void put(
            long transaction, int database, ByteBuffer key, ByteBuffer value, MemoryStack stack) {
        check(mdb_put(transaction, database,
                MDBVal.calloc(stack).mv_data(key), MDBVal.calloc(stack).mv_data(value), 0));
    }

    private static void delete(long transaction, int database, ByteBuffer key, MemoryStack stack) {
        int status = mdb_del(transaction, database, MDBVal.calloc(stack).mv_data(key), null);
        if (status != MDB_NOTFOUND) {
            check(status);
        }
    }

    private static long openCursor(long transaction, int database, MemoryStack stack) {
        var cursorPointer = stack.mallocPointer(1);
        check(mdb_cursor_open(transaction, database, cursorPointer));
        return cursorPointer.get(0);
    }

    private static void checkNotFound(int status) {
        if (status != MDB_NOTFOUND) {
            check(status);
        }
    }

    private static void check(int status) {
        if (status != MDB_SUCCESS) {
            throw new LMDBFailure(status);
        }
    }

    private static Long getMetadata(
            long transaction, int metadataDatabase, int key, MemoryStack stack) {
        ByteBuffer value = get(transaction, metadataDatabase, intBuffer(stack, key), stack);
        return value == null ? null : readLong(value, "metadata value");
    }

    private static long requireMetadata(
            long transaction, int metadataDatabase, int key, MemoryStack stack) {
        Long value = getMetadata(transaction, metadataDatabase, key, stack);
        if (value == null) {
            throw new IllegalStateException("Missing LMDB Voxy metadata key " + key);
        }
        return value;
    }

    private static void putMetadata(
            long transaction, int metadataDatabase, int key, long value, MemoryStack stack) {
        put(transaction, metadataDatabase, intBuffer(stack, key), longBuffer(stack, value), stack);
    }

    private static final class LMDBFailure extends RuntimeException {
        private final int status;

        private LMDBFailure(int status) {
            super("LMDB error " + status + ": " + mdb_strerror(status));
            this.status = status;
        }
    }

    @FunctionalInterface
    private interface TransactionWork<T> {
        T execute(long transaction, MemoryStack stack);
    }

    private static final class SharedEnvironment {
        private final Path path;
        private final ReentrantReadWriteLock transactionLock = new ReentrantReadWriteLock();
        private FileChannel resizeLockChannel;
        private long handle;
        private int sectionDatabase;
        private int idMappingDatabase;
        private int mappingIdentityDatabase;
        private int metadataDatabase;
        private int references = 1;
        private boolean closed;

        private SharedEnvironment(Path path, long initialMapSizeBytes) {
            this.path = path;
            long openedHandle = 0;
            FileChannel openedResizeLock = null;
            try {
                Files.createDirectories(path);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var environmentPointer = stack.mallocPointer(1);
                    check(mdb_env_create(environmentPointer));
                    openedHandle = environmentPointer.get(0);
                }
                check(mdb_env_set_maxdbs(openedHandle, 4));
                check(mdb_env_set_maxreaders(openedHandle, 512));
                check(mdb_env_set_mapsize(openedHandle, initialMapSizeBytes));
                check(mdb_env_open(openedHandle, path.toString(), MDB_NOTLS, 0664));
                this.handle = openedHandle;
                openedHandle = 0;
                openedResizeLock = FileChannel.open(path.resolve("voxy-resize.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                this.resizeLockChannel = openedResizeLock;
                openedResizeLock = null;
                clearStaleReaders();

                int[] databases = write((transaction, stack) -> {
                    int sections = openDatabase(transaction, "world_sections", MDB_CREATE | MDB_INTEGERKEY, stack);
                    int mappings = openDatabase(transaction, "id_mapping", MDB_CREATE | MDB_INTEGERKEY, stack);
                    int identities = openDatabase(transaction, "mapping_identity", MDB_CREATE, stack);
                    int metadata = openDatabase(transaction, "metadata", MDB_CREATE | MDB_INTEGERKEY, stack);
                    initializeSchema(transaction, mappings, identities, metadata, stack);
                    return new int[]{sections, mappings, identities, metadata};
                });
                this.sectionDatabase = databases[0];
                this.idMappingDatabase = databases[1];
                this.mappingIdentityDatabase = databases[2];
                this.metadataDatabase = databases[3];
            } catch (Throwable throwable) {
                if (openedResizeLock != null) {
                    try {
                        openedResizeLock.close();
                    } catch (Exception closeException) {
                        throwable.addSuppressed(closeException);
                    }
                }
                if (openedHandle != 0) {
                    mdb_env_close(openedHandle);
                } else if (this.handle != 0) {
                    try {
                        if (this.resizeLockChannel != null) {
                            this.resizeLockChannel.close();
                        }
                    } catch (Exception closeException) {
                        throwable.addSuppressed(closeException);
                    }
                    mdb_env_close(this.handle);
                }
                throw new RuntimeException("Unable to open shared Voxy LMDB storage at " + path, throwable);
            }
        }

        private static int openDatabase(
                long transaction, String name, int flags, MemoryStack stack) {
            var database = stack.mallocInt(1);
            check(mdb_dbi_open(transaction, name, flags, database));
            return database.get(0);
        }

        private static void initializeSchema(
                long transaction, int mappings, int identities, int metadata, MemoryStack stack) {
            Long schemaVersion = getMetadata(transaction, metadata, SCHEMA_VERSION_KEY, stack);
            if (schemaVersion == null) {
                migrateExperimentalSchema(transaction, mappings, identities, metadata, stack);
                return;
            }
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalStateException("Unsupported LMDB Voxy schema version " + schemaVersion);
            }
            requireMetadata(transaction, metadata, MAPPING_VERSION_KEY, stack);
            requireMetadata(transaction, metadata, NEXT_BLOCK_STATE_ID_KEY, stack);
            requireMetadata(transaction, metadata, NEXT_BIOME_ID_KEY, stack);
        }

        private static void migrateExperimentalSchema(
                long transaction, int mappings, int identities, int metadata, MemoryStack stack) {
            long mappingVersion = 0;
            long nextBlockStateId = 1;
            long nextBiomeId = 0;
            long cursor = openCursor(transaction, mappings, stack);
            try {
                MDBVal key = MDBVal.calloc(stack);
                MDBVal value = MDBVal.calloc(stack);
                int result = mdb_cursor_get(cursor, key, value, MDB_FIRST);
                while (result == MDB_SUCCESS) {
                    int stackPointer = stack.getPointer();
                    try {
                        int mappingKey = readInt(key.mv_data(), "mapping key");
                        int entryType = mappingKey >>> 30;
                        int entryId = mappingKey & ID_MASK;
                        validateEntryType(entryType);
                        byte[] identity = MappingIdentity.fromSerializedMapping(copy(value.mv_data()));
                        byte[] identityHash = hashIdentity(entryType, identity);
                        ByteBuffer existing = get(transaction, identities,
                                byteBuffer(stack, identityHash), stack);
                        if (existing == null) {
                            ByteBuffer identityRecord = stack.malloc(Integer.BYTES + identity.length)
                                    .order(ByteOrder.nativeOrder());
                            identityRecord.putInt(entryId).put(identity).flip();
                            put(transaction, identities, byteBuffer(stack, identityHash), identityRecord, stack);
                        } else {
                            verifyIdentityRecord(existing, identity);
                        }
                        if (entryType == BLOCK_STATE_TYPE) {
                            nextBlockStateId = Math.max(nextBlockStateId, (long) entryId + 1);
                        } else {
                            nextBiomeId = Math.max(nextBiomeId, (long) entryId + 1);
                        }
                        mappingVersion++;
                    } finally {
                        stack.setPointer(stackPointer);
                    }
                    result = mdb_cursor_get(cursor, key, value, MDB_NEXT);
                }
                checkNotFound(result);
            } finally {
                mdb_cursor_close(cursor);
            }
            putMetadata(transaction, metadata, MAPPING_VERSION_KEY, mappingVersion, stack);
            putMetadata(transaction, metadata, NEXT_BLOCK_STATE_ID_KEY, nextBlockStateId, stack);
            putMetadata(transaction, metadata, NEXT_BIOME_ID_KEY, nextBiomeId, stack);
            putMetadata(transaction, metadata, SCHEMA_VERSION_KEY, SCHEMA_VERSION, stack);
        }

        private static void verifyIdentityRecord(ByteBuffer record, byte[] identity) {
            if (record.remaining() != Integer.BYTES + identity.length) {
                throw new IllegalStateException("Invalid LMDB Voxy mapping identity record");
            }
            for (int index = 0; index < identity.length; index++) {
                if (record.get(Integer.BYTES + index) != identity[index]) {
                    throw new IllegalStateException("SHA-256 collision in the Voxy mapping identity index");
                }
            }
        }

        private void clearStaleReaders() {
            int[] staleReaders = new int[1];
            check(mdb_reader_check(this.handle, staleReaders));
            if (staleReaders[0] != 0) {
                Logger.warn("Cleared " + staleReaders[0] + " stale Voxy LMDB reader slots");
            }
        }

        private synchronized void retain(long initialMapSizeBytes) {
            if (this.closed) {
                throw new IllegalStateException("LMDB Voxy environment is closed");
            }
            ensureMinimumMapSize(initialMapSizeBytes);
            this.references++;
        }

        private synchronized boolean release() {
            if (this.references <= 0) {
                throw new IllegalStateException("LMDB Voxy environment reference underflow");
            }
            return --this.references == 0;
        }

        private <T> T read(TransactionWork<T> work) {
            return transaction(true, work);
        }

        private <T> T write(TransactionWork<T> work) {
            return transaction(false, work);
        }

        private <T> T transaction(boolean readOnly, TransactionWork<T> work) {
            while (true) {
                try {
                    return transactionOnce(readOnly, work);
                } catch (LMDBFailure failure) {
                    if (failure.status == MDB_MAP_RESIZED) {
                        refreshMapSize();
                    } else if (!readOnly && failure.status == MDB_MAP_FULL) {
                        growMap();
                    } else {
                        throw failure;
                    }
                }
            }
        }

        private <T> T transactionOnce(boolean readOnly, TransactionWork<T> work) {
            ReentrantReadWriteLock.ReadLock lock = this.transactionLock.readLock();
            lock.lock();
            long transaction = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (this.closed) {
                    throw new IllegalStateException("LMDB Voxy environment is closed");
                }
                var transactionPointer = stack.mallocPointer(1);
                check(mdb_txn_begin(this.handle, 0, readOnly ? MDB_RDONLY : 0, transactionPointer));
                transaction = transactionPointer.get(0);
                T result = work.execute(transaction, stack);
                if (readOnly) {
                    mdb_txn_abort(transaction);
                    transaction = 0;
                } else {
                    int status = mdb_txn_commit(transaction);
                    transaction = 0;
                    check(status);
                }
                return result;
            } catch (RuntimeException | Error throwable) {
                if (transaction != 0) {
                    mdb_txn_abort(transaction);
                }
                throw throwable;
            } finally {
                lock.unlock();
            }
        }

        private void ensureMinimumMapSize(long minimumSize) {
            if (getMapSize() >= minimumSize) {
                return;
            }
            resizeMap(currentSize -> Math.max(currentSize, minimumSize));
        }

        private void growMap() {
            resizeMap(currentSize -> Math.max(Math.multiplyExact(currentSize, 2),
                    Math.addExact(currentSize, MINIMUM_MAP_GROWTH_BYTES)));
        }

        private void refreshMapSize() {
            ReentrantReadWriteLock.WriteLock lock = this.transactionLock.writeLock();
            lock.lock();
            try {
                check(mdb_env_set_mapsize(this.handle, 0));
            } finally {
                lock.unlock();
            }
        }

        private void resizeMap(java.util.function.LongUnaryOperator targetSize) {
            ReentrantReadWriteLock.WriteLock lock = this.transactionLock.writeLock();
            lock.lock();
            try (FileLock ignored = this.resizeLockChannel.lock()) {
                check(mdb_env_set_mapsize(this.handle, 0));
                long currentSize = readMapSize();
                long requestedSize = targetSize.applyAsLong(currentSize);
                if (requestedSize > currentSize) {
                    check(mdb_env_set_mapsize(this.handle, requestedSize));
                    Logger.info("Grew shared Voxy LMDB map from " + currentSize + " to " + requestedSize + " bytes");
                }
            } catch (LMDBFailure failure) {
                throw failure;
            } catch (Exception exception) {
                throw new RuntimeException("Unable to resize shared Voxy LMDB storage", exception);
            } finally {
                lock.unlock();
            }
        }

        private long getMapSize() {
            ReentrantReadWriteLock.ReadLock lock = this.transactionLock.readLock();
            lock.lock();
            try {
                return readMapSize();
            } finally {
                lock.unlock();
            }
        }

        private long readMapSize() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                MDBEnvInfo info = MDBEnvInfo.calloc(stack);
                check(mdb_env_info(this.handle, info));
                return info.me_mapsize();
            }
        }

        private void flush() {
            ReentrantReadWriteLock.ReadLock lock = this.transactionLock.readLock();
            lock.lock();
            try {
                if (this.closed) {
                    throw new IllegalStateException("LMDB Voxy environment is closed");
                }
                check(mdb_env_sync(this.handle, true));
            } finally {
                lock.unlock();
            }
        }

        private void close() {
            ReentrantReadWriteLock.WriteLock lock = this.transactionLock.writeLock();
            lock.lock();
            try {
                if (this.closed) {
                    return;
                }
                this.closed = true;
                RuntimeException failure = null;
                try {
                    check(mdb_env_sync(this.handle, true));
                } catch (RuntimeException exception) {
                    failure = exception;
                }
                mdb_dbi_close(this.handle, this.sectionDatabase);
                mdb_dbi_close(this.handle, this.idMappingDatabase);
                mdb_dbi_close(this.handle, this.mappingIdentityDatabase);
                mdb_dbi_close(this.handle, this.metadataDatabase);
                mdb_env_close(this.handle);
                try {
                    this.resizeLockChannel.close();
                } catch (Exception exception) {
                    if (failure == null) {
                        failure = new RuntimeException("Unable to close shared Voxy LMDB resize lock", exception);
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public static final class Config extends StorageConfig {
        public long initialMapSizeGiB = DEFAULT_MAP_SIZE_BYTES >> 30;
        public String directoryName;

        public Config() {
        }

        public Config(String directoryName) {
            this.directoryName = directoryName;
        }

        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            long initialMapSizeBytes;
            try {
                initialMapSizeBytes = Math.multiplyExact(this.initialMapSizeGiB, 1L << 30);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("LMDB map size is too large", exception);
            }
            Path storagePath = Path.of(ctx.substituteString(ctx.resolvePath()));
            if (this.directoryName != null && !this.directoryName.isBlank()) {
                storagePath = storagePath.resolve(this.directoryName);
            }
            return new LMDBStorageBackend(ctx.ensurePathExists(storagePath.toString()), initialMapSizeBytes);
        }

        public static String getConfigTypeName() {
            return "LMDB";
        }
    }
}
