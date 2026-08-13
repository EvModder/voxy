package me.cortex.voxy.common.config.storage.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SQLiteStorageBackendTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void allocatesMappingsAtomicallyAcrossIndependentConnections() throws Exception {
        Path database = this.temporaryDirectory.resolve("shared.sqlite");
        try (var first = new CloseableBackend(database); var second = new CloseableBackend(database)) {
            Map<String, Integer> assignedIds = new ConcurrentHashMap<>();
            List<Callable<Void>> work = new ArrayList<>();
            for (int index = 0; index < 1_000; index++) {
                int value = index % 200;
                SQLiteStorageBackend backend = (index & 1) == 0 ? first.backend : second.backend;
                work.add(() -> {
                    String state = "state-" + value;
                    byte[] identity = state.getBytes(StandardCharsets.UTF_8);
                    int id = backend.getOrCreateIdMapping(1, identity,
                            allocated -> (state + "=" + allocated).getBytes(StandardCharsets.UTF_8));
                    assignedIds.merge(state, id, (existing, candidate) -> {
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
        Path database = this.temporaryDirectory.resolve("multiprocess.sqlite");
        Process first = startWorker(database);
        Process second = startWorker(database);
        try {
            assertTrue(first.waitFor(Duration.ofSeconds(30)));
            assertTrue(second.waitFor(Duration.ofSeconds(30)));
            assertEquals(0, first.exitValue(),
                    new String(first.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(0, second.exitValue(),
                    new String(second.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            first.destroyForcibly();
            second.destroyForcibly();
        }

        try (var backend = new CloseableBackend(database)) {
            assertEquals(250, backend.backend.getIdMappingsData().size());
            assertEquals(250, backend.backend.getIdMappingVersion());
            var sectionCount = new AtomicInteger();
            backend.backend.iteratePositions(-1, ignored -> sectionCount.incrementAndGet());
            assertEquals(300, sectionCount.get());
        }
    }

    @Test
    void mappingUpdatesAdvanceTheSharedVersion() {
        Path database = this.temporaryDirectory.resolve("mapping-update.sqlite");
        try (var backend = new CloseableBackend(database)) {
            byte[] original = serializedBiome(0, "minecraft:plains");
            int id = backend.backend.getOrCreateIdMapping(2, mappingIdentity(original), ignored -> original);
            long version = backend.backend.getIdMappingVersion();
            byte[] updated = serializedBiome(id, "minecraft:forest");

            backend.backend.putIdMapping(id | (2 << 30), ByteBuffer.wrap(updated));

            assertEquals(version + 1, backend.backend.getIdMappingVersion());
            assertEquals(id, backend.backend.getOrCreateIdMapping(
                    2, mappingIdentity(updated), ignored -> updated));
            assertEquals(1, backend.backend.getIdMappingsData().size());
            assertEquals(version + 1, backend.backend.getIdMappingVersion());
        }
    }

    @Test
    void rejectsNewerDatabaseSchemas() throws Exception {
        Path database = this.temporaryDirectory.resolve("future.sqlite");
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=999");
        }

        assertThrows(RuntimeException.class, () -> new SQLiteStorageBackend(database.toString()));
        Files.delete(database);
    }

    @Test
    void upgradesMigratedDatabaseForConcurrentUse() throws Exception {
        Path database = this.temporaryDirectory.resolve("migrated.sqlite");
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE world_sections ("
                    + "section_key INTEGER PRIMARY KEY, data BLOB NOT NULL) WITHOUT ROWID");
            statement.execute("CREATE TABLE id_mappings ("
                    + "mapping_key INTEGER PRIMARY KEY, entry_type INTEGER NOT NULL, entry_id INTEGER NOT NULL, "
                    + "identity BLOB, data BLOB NOT NULL, UNIQUE(entry_type, entry_id), "
                    + "UNIQUE(entry_type, identity)) WITHOUT ROWID");
            statement.execute("CREATE TABLE storage_metadata ("
                    + "metadata_key TEXT PRIMARY KEY, value INTEGER NOT NULL) WITHOUT ROWID");
            statement.execute("INSERT INTO storage_metadata VALUES('mapping_version', 0)");
            statement.execute("CREATE TRIGGER increment_mapping_version AFTER INSERT ON id_mappings BEGIN "
                    + "UPDATE storage_metadata SET value = value + 1 WHERE metadata_key = 'mapping_version'; END");
            statement.execute("PRAGMA user_version=1");
        }

        try (var first = new CloseableBackend(database); var second = new CloseableBackend(database);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA journal_mode")) {
                assertTrue(result.next());
                assertEquals("wal", result.getString(1));
            }
            try (var result = statement.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='update_mapping_version'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void sectionWritesAreVisibleAcrossConnections() {
        Path database = this.temporaryDirectory.resolve("sections.sqlite");
        try (var first = new CloseableBackend(database); var second = new CloseableBackend(database)) {
            var source = new MemoryBuffer(4);
            MemoryBuffer scratch = new MemoryBuffer(16);
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
    void mappingVersionReadDoesNotWaitForBlockedSectionWriter() throws Exception {
        Path database = this.temporaryDirectory.resolve("nonblocking-version.sqlite");
        try (var backend = new CloseableBackend(database);
             var blocker = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = blocker.createStatement();
             var executor = Executors.newSingleThreadExecutor()) {
            long expectedVersion = backend.backend.getIdMappingVersion();
            statement.execute("BEGIN IMMEDIATE");

            var section = new MemoryBuffer(Long.BYTES);
            try {
                section.asByteBuffer().putLong(0, 42L);
                var blockedWrite = executor.submit(() -> backend.backend.setSectionData(42L, section));
                Thread.sleep(100);
                assertFalse(blockedWrite.isDone(), "section writer should be waiting for the external write lock");

                long started = System.nanoTime();
                assertEquals(expectedVersion, backend.backend.getIdMappingVersion());
                assertTrue(backend.backend.getIdMappingsData().isEmpty());
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsedMillis < 500, "mapping reads blocked for " + elapsedMillis + "ms");

                statement.execute("ROLLBACK");
                blockedWrite.get(5, TimeUnit.SECONDS);
            } finally {
                try {
                    statement.execute("ROLLBACK");
                } catch (Exception ignored) {
                }
                section.free();
            }
        }
    }

    public static void main(String[] args) {
        Path database = Path.of(args[0]);
        try (var backend = new CloseableBackend(database)) {
            for (int index = 0; index < 500; index++) {
                String state = "state-" + (index % 250);
                byte[] identity = state.getBytes(StandardCharsets.UTF_8);
                backend.backend.getOrCreateIdMapping(1, identity,
                        allocated -> (state + "=" + allocated).getBytes(StandardCharsets.UTF_8));
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
        }
    }

    private static Process startWorker(Path database) throws Exception {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                SQLiteStorageBackendTest.class.getName(), database.toString())
                .redirectErrorStream(true)
                .start();
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

    private static byte[] mappingIdentity(byte[] mapping) {
        return me.cortex.voxy.common.config.MappingIdentity.fromSerializedMapping(mapping);
    }

    private static final class CloseableBackend implements AutoCloseable {
        private final SQLiteStorageBackend backend;

        private CloseableBackend(Path database) {
            this.backend = new SQLiteStorageBackend(database.toString());
        }

        @Override
        public void close() {
            this.backend.close();
        }
    }
}
