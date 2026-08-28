package me.cortex.voxy.common.config.storage.lmdb;

import me.cortex.voxy.common.config.MappingIdentity;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.lmdb.MDBVal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.util.lmdb.LMDB.MDB_CREATE;
import static org.lwjgl.util.lmdb.LMDB.MDB_INTEGERKEY;
import static org.lwjgl.util.lmdb.LMDB.MDB_NOTLS;
import static org.lwjgl.util.lmdb.LMDB.MDB_SUCCESS;
import static org.lwjgl.util.lmdb.LMDB.mdb_dbi_close;
import static org.lwjgl.util.lmdb.LMDB.mdb_dbi_open;
import static org.lwjgl.util.lmdb.LMDB.mdb_env_close;
import static org.lwjgl.util.lmdb.LMDB.mdb_env_create;
import static org.lwjgl.util.lmdb.LMDB.mdb_env_open;
import static org.lwjgl.util.lmdb.LMDB.mdb_env_set_mapsize;
import static org.lwjgl.util.lmdb.LMDB.mdb_env_set_maxdbs;
import static org.lwjgl.util.lmdb.LMDB.mdb_put;
import static org.lwjgl.util.lmdb.LMDB.mdb_strerror;
import static org.lwjgl.util.lmdb.LMDB.mdb_txn_abort;
import static org.lwjgl.util.lmdb.LMDB.mdb_txn_begin;
import static org.lwjgl.util.lmdb.LMDB.mdb_txn_commit;

final class LMDBStorageBackendTest {
    private static final long SMALL_MAP_SIZE = 1L << 20;

    @TempDir
    Path temporaryDirectory;

    @Test
    void allocatesMappingsAtomicallyAcrossIndependentBackends() throws Exception {
        Path database = this.temporaryDirectory.resolve("shared.lmdb");
        try (var first = new CloseableBackend(database); var second = new CloseableBackend(database)) {
            Map<String, Integer> assignedIds = new ConcurrentHashMap<>();
            List<Callable<Void>> work = new ArrayList<>();
            for (int index = 0; index < 1_000; index++) {
                int value = index % 200;
                LMDBStorageBackend backend = (index & 1) == 0 ? first.backend : second.backend;
                work.add(() -> {
                    String biome = "test:biome_" + value;
                    int id = getOrCreateBiome(backend, biome);
                    assignedIds.merge(biome, id, (existing, candidate) -> {
                        assertEquals(existing, candidate);
                        return existing;
                    });
                    return null;
                });
            }

            try (var executor = Executors.newFixedThreadPool(8)) {
                for (var future : executor.invokeAll(work)) {
                    future.get();
                }
            }

            assertEquals(200, assignedIds.size());
            assertEquals(200, first.backend.getIdMappingsData().size());
            assertEquals(200, second.backend.getIdMappingVersion());
        }
    }

    @Test
    void allocatesMappingsAtomicallyAcrossProcesses() throws Exception {
        Path database = this.temporaryDirectory.resolve("multiprocess.lmdb");
        Process first = startWorker(database, "mappings", 0);
        Process second = startWorker(database, "mappings", 1);
        assertSuccessful(first, second);

        try (var backend = new CloseableBackend(database)) {
            assertEquals(250, backend.backend.getIdMappingsData().size());
            assertEquals(250, backend.backend.getIdMappingVersion());
            var sectionCount = new AtomicInteger();
            backend.backend.iteratePositions(-1, ignored -> sectionCount.incrementAndGet());
            assertEquals(300, sectionCount.get());
        }
    }

    @Test
    void growsMapSafelyAcrossProcesses() throws Exception {
        Path database = this.temporaryDirectory.resolve("growth.lmdb");
        Process first = startWorker(database, "growth", 0);
        Process second = startWorker(database, "growth", 1);
        assertSuccessful(first, second);

        try (var backend = new CloseableBackend(database, SMALL_MAP_SIZE)) {
            var sectionCount = new AtomicInteger();
            backend.backend.iteratePositions(-1, ignored -> sectionCount.incrementAndGet());
            assertEquals(128, sectionCount.get());
            assertTrue(backend.backend.getMapSize() > SMALL_MAP_SIZE);
        }
    }

    @Test
    void committedWritesSurviveAbruptProcessExit() throws Exception {
        Path database = this.temporaryDirectory.resolve("abrupt-exit.lmdb");
        Process process = startWorker(database, "abrupt", 0);
        assertSuccessful(process);

        try (var backend = new CloseableBackend(database)) {
            assertEquals(1, backend.backend.getIdMappingsData().size());
            var sections = new AtomicInteger();
            backend.backend.iteratePositions(-1, ignored -> sections.incrementAndGet());
            assertEquals(1, sections.get());
        }
    }

    @Test
    void upgradesExperimentalStorageSchemaInPlace() throws Exception {
        Path database = this.temporaryDirectory.resolve("experimental.lmdb");
        byte[] stone = serializedBiome(1, "test:stone");
        byte[] plains = serializedBiome(0, "minecraft:plains");
        createExperimentalDatabase(database, Map.of(
                (1 << 30) | 1, stone,
                2 << 30, plains));

        try (var backend = new CloseableBackend(database)) {
            assertEquals(2, backend.backend.getIdMappingsData().size());
            assertEquals(2, backend.backend.getIdMappingVersion());
            assertEquals(0, backend.backend.getOrCreateIdMapping(
                    2, MappingIdentity.fromSerializedMapping(plains), ignored -> plains));

            byte[] dirt = serializedBiome(0, "test:dirt");
            assertEquals(2, backend.backend.getOrCreateIdMapping(
                    1, MappingIdentity.fromSerializedMapping(dirt),
                    id -> serializedBiome(id, "test:dirt")));
            assertEquals(3, backend.backend.getIdMappingVersion());
        }
    }

    @Test
    void mappingUpdatesAdvanceSharedVersion() {
        Path database = this.temporaryDirectory.resolve("mapping-update.lmdb");
        try (var backend = new CloseableBackend(database)) {
            byte[] original = serializedBiome(0, "minecraft:plains");
            int id = backend.backend.getOrCreateIdMapping(
                    2, MappingIdentity.fromSerializedMapping(original), ignored -> original);
            long version = backend.backend.getIdMappingVersion();
            byte[] updated = serializedBiome(id, "minecraft:forest");

            backend.backend.putIdMapping(id | (2 << 30), ByteBuffer.wrap(updated));

            assertEquals(version + 1, backend.backend.getIdMappingVersion());
            assertEquals(id, backend.backend.getOrCreateIdMapping(
                    2, MappingIdentity.fromSerializedMapping(updated), ignored -> updated));
            assertEquals(1, backend.backend.getIdMappingsData().size());
        }
    }

    @Test
    void sectionWritesAreVisibleAcrossBackends() {
        Path database = this.temporaryDirectory.resolve("sections.lmdb");
        try (var first = new CloseableBackend(database); var second = new CloseableBackend(database)) {
            var source = new MemoryBuffer(4);
            var scratch = new MemoryBuffer(16);
            MemoryBuffer result = null;
            try {
                source.asByteBuffer().put(new byte[]{10, 20, 30, 40});
                first.backend.setSectionData(42L, source);
                result = second.backend.getSectionData(42L, scratch);

                byte[] actual = new byte[(int) result.size];
                result.asByteBuffer().get(actual);
                assertArrayEquals(new byte[]{10, 20, 30, 40}, actual);
            } finally {
                source.free();
                if (result == null) {
                    scratch.free();
                } else {
                    result.free();
                }
            }
        }
    }

    @Test
    void iteratesOnlyRequestedLodLevel() {
        Path database = this.temporaryDirectory.resolve("iteration.lmdb");
        try (var backend = new CloseableBackend(database)) {
            Set<Long> allKeys = Set.of(
                    WorldEngine.getWorldSectionId(0, -2, 3, 4),
                    WorldEngine.getWorldSectionId(2, 5, -6, 7),
                    WorldEngine.getWorldSectionId(2, 8, 9, -10),
                    WorldEngine.getWorldSectionId(4, 11, 12, 13));
            var data = new MemoryBuffer(1);
            try {
                data.asByteBuffer().put(0, (byte) 1);
                allKeys.forEach(key -> backend.backend.setSectionData(key, data));
            } finally {
                data.free();
            }

            Set<Long> levelTwo = new HashSet<>();
            backend.backend.iteratePositions(2, levelTwo::add);
            assertEquals(Set.of(
                    WorldEngine.getWorldSectionId(2, 5, -6, 7),
                    WorldEngine.getWorldSectionId(2, 8, 9, -10)), levelTwo);

            Set<Long> iteratedKeys = new HashSet<>();
            backend.backend.iteratePositions(-1, iteratedKeys::add);
            assertEquals(allKeys, iteratedKeys);
        }
    }

    public static void main(String[] args) {
        Path database = Path.of(args[0]);
        String mode = args[1];
        int workerId = Integer.parseInt(args[2]);
        if (mode.equals("abrupt")) {
            var backend = new LMDBStorageBackend(database.toString(), SMALL_MAP_SIZE);
            getOrCreateBiome(backend, "test:abrupt");
            var section = new MemoryBuffer(Long.BYTES);
            section.asByteBuffer().putLong(0, 42L);
            backend.setSectionData(42L, section);
            Runtime.getRuntime().halt(0);
        }

        long mapSize = mode.equals("growth") ? SMALL_MAP_SIZE : 16L << 20;
        try (var backend = new CloseableBackend(database, mapSize)) {
            if (mode.equals("mappings")) {
                for (int index = 0; index < 500; index++) {
                    getOrCreateBiome(backend.backend, "test:biome_" + (index % 250));
                }
                var section = new MemoryBuffer(Long.BYTES);
                try {
                    for (int index = 0; index < 1_000; index++) {
                        section.asByteBuffer().putLong(0, index);
                        backend.backend.setSectionData(index % 300, section);
                    }
                } finally {
                    section.free();
                }
            } else if (mode.equals("growth")) {
                var section = new MemoryBuffer(64 * 1024);
                try {
                    ByteBuffer buffer = section.asByteBuffer();
                    while (buffer.hasRemaining()) {
                        buffer.put((byte) workerId);
                    }
                    for (int index = 0; index < 64; index++) {
                        backend.backend.setSectionData(workerId * 1_000L + index, section);
                    }
                } finally {
                    section.free();
                }
            } else {
                throw new IllegalArgumentException("Unknown LMDB test worker mode " + mode);
            }
        }
    }

    private static int getOrCreateBiome(LMDBStorageBackend backend, String biome) {
        byte[] candidate = serializedBiome(0, biome);
        return backend.getOrCreateIdMapping(2, MappingIdentity.fromSerializedMapping(candidate),
                allocated -> serializedBiome(allocated, biome));
    }

    private static void createExperimentalDatabase(Path database, Map<Integer, byte[]> mappings) throws Exception {
        Files.createDirectories(database);
        long environment = 0;
        long transaction = 0;
        int sectionDatabase = 0;
        int mappingDatabase = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var environmentPointer = stack.mallocPointer(1);
            checkLmdb(mdb_env_create(environmentPointer));
            environment = environmentPointer.get(0);
            checkLmdb(mdb_env_set_maxdbs(environment, 2));
            checkLmdb(mdb_env_set_mapsize(environment, 16L << 20));
            checkLmdb(mdb_env_open(environment, database.toString(), MDB_NOTLS, 0664));

            var transactionPointer = stack.mallocPointer(1);
            checkLmdb(mdb_txn_begin(environment, 0, 0, transactionPointer));
            transaction = transactionPointer.get(0);
            var databasePointer = stack.mallocInt(1);
            checkLmdb(mdb_dbi_open(transaction, "world_sections",
                    MDB_CREATE | MDB_INTEGERKEY, databasePointer));
            sectionDatabase = databasePointer.get(0);
            checkLmdb(mdb_dbi_open(transaction, "id_mapping",
                    MDB_CREATE | MDB_INTEGERKEY, databasePointer));
            mappingDatabase = databasePointer.get(0);

            for (var entry : mappings.entrySet()) {
                ByteBuffer key = stack.malloc(Integer.BYTES).order(ByteOrder.nativeOrder());
                key.putInt(0, entry.getKey());
                ByteBuffer value = stack.malloc(entry.getValue().length).put(entry.getValue()).flip();
                checkLmdb(mdb_put(transaction, mappingDatabase,
                        MDBVal.calloc(stack).mv_data(key), MDBVal.calloc(stack).mv_data(value), 0));
            }
            long committedTransaction = transaction;
            transaction = 0;
            checkLmdb(mdb_txn_commit(committedTransaction));
        } finally {
            if (transaction != 0) {
                mdb_txn_abort(transaction);
            }
            if (environment != 0) {
                if (sectionDatabase != 0) {
                    mdb_dbi_close(environment, sectionDatabase);
                }
                if (mappingDatabase != 0) {
                    mdb_dbi_close(environment, mappingDatabase);
                }
                mdb_env_close(environment);
            }
        }
    }

    private static void checkLmdb(int status) {
        if (status != MDB_SUCCESS) {
            throw new IllegalStateException("LMDB test setup failed: " + mdb_strerror(status));
        }
    }

    private static Process startWorker(Path database, String mode, int workerId) throws Exception {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Dorg.lwjgl.system.SharedLibraryExtractPath="
                        + database.getParent().resolve("lwjgl-" + mode + '-' + workerId),
                "-cp", System.getProperty("java.class.path"),
                LMDBStorageBackendTest.class.getName(), database.toString(), mode,
                Integer.toString(workerId))
                .redirectErrorStream(true)
                .start();
    }

    private static void assertSuccessful(Process... processes) throws Exception {
        try {
            for (Process process : processes) {
                assertTrue(process.waitFor(Duration.ofSeconds(45)));
                assertEquals(0, process.exitValue(),
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            for (Process process : processes) {
                process.destroyForcibly();
            }
        }
    }

    private static byte[] serializedBiome(int id, String biome) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(bytes); var output = new DataOutputStream(gzip)) {
                output.writeByte(10);
                output.writeUTF("");
                output.writeByte(3);
                output.writeUTF("id");
                output.writeInt(id);
                output.writeByte(8);
                output.writeUTF("biome_id");
                output.writeUTF(biome);
                output.writeByte(0);
            }
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static final class CloseableBackend implements AutoCloseable {
        private final LMDBStorageBackend backend;

        private CloseableBackend(Path database) {
            this.backend = new LMDBStorageBackend(database.toString());
        }

        private CloseableBackend(Path database, long mapSize) {
            this.backend = new LMDBStorageBackend(database.toString(), mapSize);
        }

        @Override
        public void close() {
            this.backend.close();
        }
    }
}
