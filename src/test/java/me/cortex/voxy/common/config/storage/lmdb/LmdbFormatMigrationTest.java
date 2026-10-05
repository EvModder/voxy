package me.cortex.voxy.common.config.storage.lmdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.lmdb.MDBVal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.util.lmdb.LMDB.*;

class LmdbFormatMigrationTest {
    @TempDir Path temporary;

    @Test
    void freshStoreDoesNotLoadLegacyLibrariesOrMigrationCode() throws Exception {
        Path source = temporary.resolve("fresh");
        Path classes = temporary.resolve("classes.log");
        Path output = temporary.resolve("worker.log");
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "--enable-native-access=ALL-UNNAMED",
                "-Xlog:class+load=info:file=classes.log", "-cp", System.getProperty("java.class.path"),
                FreshStoreWorker.class.getName(), source.toString())
                .directory(temporary.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(output));
            String loaded = Files.readString(classes);
            for (String legacy : new String[]{"LmdbFormatMigration source:", "StorageMigration source:",
                    "org.rocksdb.", "org.sqlite.", "me.cortex.voxy.migration."}) {
                assertFalse(loaded.contains(legacy), legacy);
            }
            assertTrue(Files.isRegularFile(source.resolve("data.mdb")));
            assertFalse(Files.exists(temporary.resolve("fresh.format.lock")));
            assertFalse(Files.exists(temporary.resolve("fresh.lmdb1.migrating")));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static class FreshStoreWorker {
        public static void main(String[] args) {
            me.cortex.voxy.common.StorageConfigUtil.createDefaultSerializer();
            var storage = new LMDBStorageBackend(args[0]);
            try {
                if (!storage.getIdMappingsData().isEmpty()) throw new AssertionError("Fresh store is not empty");
            } finally { storage.close(); }
        }
    }

    @Test
    void concurrentUpgradePreservesEveryTableAndSourceAndRetriesInterruptedStaging() throws Exception {
        Path source = legacyDatabase();
        byte[] before = hash(source.resolve("data.mdb"));
        if (!LmdbFormatMigration.usesNewFormat()) {
            assertEquals(source, LmdbFormatMigration.resolve(source));
            return;
        }
        Path staging = Files.createDirectory(source.resolveSibling("old.lmdb1.migrating"));
        Files.createFile(staging.resolve("voxy-lmdb-format-migration-1"));
        Files.writeString(staging.resolve("incomplete"), "interrupted attempt");
        Path destination;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> LmdbFormatMigration.resolve(source));
            var second = workers.submit(() -> LmdbFormatMigration.resolve(source));
            destination = first.get(60, TimeUnit.SECONDS);
            assertEquals(destination, second.get(60, TimeUnit.SECONDS));
        }
        assertArrayEquals(before, hash(source.resolve("data.mdb")));
        assertFalse(Files.exists(staging));
        assertEquals(destination, LmdbFormatMigration.resolve(source));
        try (var stack = MemoryStack.stackPush()) {
            var pointer = stack.mallocPointer(1);
            check(mdb_env_create(pointer));
            long env = pointer.get(0), txn = 0;
            try {
                check(mdb_env_set_maxdbs(env, 4));
                check(mdb_env_open(env, destination.toString(), MDB_RDONLY | MDB_NOTLS, 0664));
                check(mdb_txn_begin(env, 0, MDB_RDONLY, pointer)); txn = pointer.get(0);
                for (String name : LegacyFixture.TABLES) {
                    var db = stack.mallocInt(1);
                    check(mdb_dbi_open(txn, name, 0, db));
                    var key = MDBVal.calloc(stack);
                    var value = MDBVal.calloc(stack);
                    var bytes = stack.malloc(8);
                    for (int i = 0; i < 700; i++) {
                        bytes.putLong(0, i);
                        check(mdb_get(txn, db.get(0), key.mv_data(bytes), value));
                        assertEquals(1024, value.mv_size());
                        assertEquals(i, value.mv_data().order(java.nio.ByteOrder.nativeOrder()).getLong(0));
                    }
                }
            } finally {
                if (txn != 0) mdb_txn_abort(txn);
                mdb_env_close(env);
            }
        }
    }

    @Test
    void rejectsUnownedStagingAndUnverifiedDestinationWithoutChangingSource() throws Exception {
        assumeTrue(LmdbFormatMigration.usesNewFormat());
        Path source = legacyDatabase();
        byte[] before = hash(source.resolve("data.mdb"));
        Path staging = Files.createDirectory(source.resolveSibling("old.lmdb1.migrating"));
        Path unrelated = Files.writeString(staging.resolve("unrelated"), "keep");
        assertThrows(IllegalStateException.class, () -> LmdbFormatMigration.resolve(source));
        assertEquals("keep", Files.readString(unrelated));
        Files.createDirectory(source.resolveSibling("old.lmdb1"));
        assertThrows(IllegalStateException.class, () -> LmdbFormatMigration.resolve(source));
        assertArrayEquals(before, hash(source.resolve("data.mdb")));
    }

    @Test
    void cancellationBeforeMigrationDoesNotActivateDestination() throws Exception {
        assumeTrue(LmdbFormatMigration.usesNewFormat());
        Path source = legacyDatabase();
        Thread.currentThread().interrupt();
        try {
            assertThrows(RuntimeException.class, () -> LmdbFormatMigration.resolve(source));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(source.resolveSibling("old.lmdb1")));
        } finally { Thread.interrupted(); }
    }

    @Test
    void failedExportNeverActivatesOrChangesSource() throws Exception {
        assumeTrue(LmdbFormatMigration.usesNewFormat());
        Path source = legacyDatabase(true);
        byte[] before = hash(source.resolve("data.mdb"));
        assertThrows(IllegalStateException.class, () -> LmdbFormatMigration.resolve(source));
        assertThrows(IllegalStateException.class, () -> LmdbFormatMigration.resolve(source));
        assertFalse(Files.exists(source.resolveSibling("old.lmdb1")));
        assertArrayEquals(before, hash(source.resolve("data.mdb")));
    }

    @Test
    void anotherProcessWaitsForTheMigrationLock() throws Exception {
        assumeTrue(LmdbFormatMigration.usesNewFormat());
        Path source = legacyDatabase();
        Path started = temporary.resolve("worker-started");
        Process process = null;
        try {
            try (var channel = java.nio.channels.FileChannel.open(source.resolveSibling("old.format.lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                        MigrationWorker.class.getName(), source.toString(), started.toString())
                        .redirectErrorStream(true).redirectOutput(temporary.resolve("worker.log").toFile()).start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!Files.exists(started) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.exists(started));
                assertFalse(process.waitFor(300, TimeUnit.MILLISECONDS));
                assertFalse(Files.exists(source.resolveSibling("old.lmdb1")));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(temporary.resolve("worker.log")));
            assertTrue(Files.isRegularFile(source.resolveSibling("old.lmdb1").resolve("lmdb1-complete")));
        } finally { if (process != null) process.destroyForcibly(); }
    }

    public static class MigrationWorker {
        public static void main(String[] args) throws Exception {
            Files.createFile(Path.of(args[1]));
            LmdbFormatMigration.resolve(Path.of(args[0]));
        }
    }

    private Path legacyDatabase() throws Exception {
        return legacyDatabase(false);
    }

    private Path legacyDatabase(boolean incomplete) throws Exception {
        Path helper = temporary.resolve("legacy.jar");
        try (var input = getClass().getResourceAsStream("/voxy/migration/legacy-lmdb-reader.jar")) {
            assertNotNull(input);
            Files.copy(input, helper);
        }
        Path source = Files.createDirectory(temporary.resolve("old"));
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes = Path.of(LegacyFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(executable, "--enable-native-access=ALL-UNNAMED", "-cp",
                helper + java.io.File.pathSeparator + classes, LegacyFixture.class.getName(), source.toString(), Boolean.toString(incomplete))
                .redirectErrorStream(true).redirectOutput(temporary.resolve("fixture.log").toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(temporary.resolve("fixture.log")); }
                catch (Exception e) { return e.toString(); }
            });
        } finally { process.destroyForcibly(); }
        return source;
    }

    private static byte[] hash(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }

    private static void check(int status) { assertEquals(MDB_SUCCESS, status, () -> mdb_strerror(status)); }

    /** Runs with only the helper's LMDB 0.9 libraries, never the test JVM's native library. */
    public static class LegacyFixture {
        static final String[] TABLES = {"world_sections", "id_mapping", "mapping_identity", "metadata"};

        public static void main(String[] args) {
            try (var stack = MemoryStack.stackPush()) {
                var major = stack.mallocInt(1);
                mdb_version(major, stack.mallocInt(1), stack.mallocInt(1));
                if (major.get(0) != 0) throw new IllegalStateException("Fixture must use LMDB 0.9");
                var pointer = stack.mallocPointer(1);
                check(mdb_env_create(pointer));
                long env = pointer.get(0), txn = 0;
                try {
                    check(mdb_env_set_maxdbs(env, 4));
                    check(mdb_env_set_mapsize(env, 16L << 20));
                    check(mdb_env_open(env, args[0], MDB_NOTLS, 0664));
                    check(mdb_txn_begin(env, 0, 0, pointer)); txn = pointer.get(0);
                    var bytes = stack.calloc(1024);
                    var keyBytes = stack.malloc(8);
                    var key = MDBVal.calloc(stack);
                    var value = MDBVal.calloc(stack);
                    for (String name : TABLES) {
                        if (Boolean.parseBoolean(args[1]) && name.equals("metadata")) continue;
                        var db = stack.mallocInt(1);
                        check(mdb_dbi_open(txn, name, MDB_CREATE | (name.equals("mapping_identity") ? 0 : MDB_INTEGERKEY), db));
                        for (int i = 0; i < 700; i++) {
                            bytes.putLong(0, i); keyBytes.putLong(0, i);
                            check(mdb_put(txn, db.get(0), key.mv_data(keyBytes), value.mv_data(bytes), 0));
                        }
                    }
                    long committing = txn; txn = 0;
                    check(mdb_txn_commit(committing));
                } finally {
                    if (txn != 0) mdb_txn_abort(txn);
                    mdb_env_close(env);
                }
            }
        }

        private static void check(int status) {
            if (status != MDB_SUCCESS) throw new IllegalStateException(mdb_strerror(status));
        }
    }
}
