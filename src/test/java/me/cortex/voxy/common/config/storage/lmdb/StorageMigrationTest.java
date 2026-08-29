package me.cortex.voxy.common.config.storage.lmdb;

import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StorageMigrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesRocksDbAndRetainsSource() throws Exception {
        Path basePath = this.temporaryDirectory.resolve("rocks");
        Path storagePath = basePath.resolve("dimension").resolve("storage");
        Files.createDirectories(storagePath);
        byte[] mapping = serializedBiome(0, "test:plains");
        Map<Long, byte[]> sections = Map.of(
                42L, new byte[]{1, 2, 3},
                99L, new byte[]{4, 5, 6, 7});

        var source = new RocksDBStorageBackend(storagePath.toString());
        try {
            source.putIdMapping(2 << 30, ByteBuffer.wrap(mapping));
            sections.forEach((key, value) -> putSection(source, key, value));
        } finally {
            source.close();
        }

        StorageMigration.migrateLegacyStorage(
                basePath, StorageMigration.LegacyStorage.ROCKS_DB, null);
        verifyMigration(storagePath, mapping, sections);
        assertTrue(Files.isRegularFile(storagePath.resolve("CURRENT")));

        StorageMigration.migrateLegacyStorage(
                basePath, StorageMigration.LegacyStorage.ROCKS_DB, null);
        verifyMigration(storagePath, mapping, sections);
    }

    @Test
    void migratesSQLiteSharedAndRetainsSource() throws Exception {
        Path basePath = this.temporaryDirectory.resolve("sqlite");
        Path storagePath = basePath.resolve("dimension").resolve("storage");
        Files.createDirectories(storagePath);
        Path sqlitePath = storagePath.resolve("legacy.sqlite");
        byte[] mapping = serializedBiome(0, "test:forest");
        Map<Long, byte[]> sections = Map.of(
                7L, new byte[]{10, 20},
                8L, new byte[]{30, 40, 50});

        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE world_sections (section_key INTEGER PRIMARY KEY, data BLOB NOT NULL) WITHOUT ROWID");
            statement.execute("CREATE TABLE id_mappings (mapping_key INTEGER PRIMARY KEY, data BLOB NOT NULL) WITHOUT ROWID");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             var mappingInsert = connection.prepareStatement(
                     "INSERT INTO id_mappings(mapping_key, data) VALUES(?, ?)");
             var sectionInsert = connection.prepareStatement(
                     "INSERT INTO world_sections(section_key, data) VALUES(?, ?)")) {
            mappingInsert.setInt(1, 2 << 30);
            mappingInsert.setBytes(2, mapping);
            mappingInsert.executeUpdate();
            for (var entry : sections.entrySet()) {
                sectionInsert.setLong(1, entry.getKey());
                sectionInsert.setBytes(2, entry.getValue());
                sectionInsert.executeUpdate();
            }
        }

        StorageMigration.migrateLegacyStorage(
                basePath, StorageMigration.LegacyStorage.SQLITE_SHARED, "legacy.sqlite");
        verifyMigration(storagePath, mapping, sections);
        assertTrue(Files.isRegularFile(sqlitePath));

        StorageMigration.migrateLegacyStorage(
                basePath, StorageMigration.LegacyStorage.SQLITE_SHARED, "legacy.sqlite");
        verifyMigration(storagePath, mapping, sections);
    }

    private static void verifyMigration(
            Path storagePath, byte[] mapping, Map<Long, byte[]> sections) {
        Path destination = storagePath.resolve(LMDBStorageBackend.DEFAULT_DIRECTORY_NAME);
        assertTrue(Files.isRegularFile(destination.resolve("migration-complete.properties")));
        var backend = new LMDBStorageBackend(destination.toString());
        try {
            assertArrayEquals(mapping, backend.getIdMappingsData().get(2 << 30));
            var positions = new java.util.HashSet<Long>();
            backend.iteratePositions(-1, positions::add);
            assertEquals(Set.copyOf(sections.keySet()), positions);
            sections.forEach((key, value) -> assertArrayEquals(value, readSection(backend, key)));
        } finally {
            backend.close();
        }
    }

    private static void putSection(RocksDBStorageBackend backend, long key, byte[] value) {
        var data = new MemoryBuffer(value.length);
        try {
            data.asByteBuffer().put(value);
            backend.setSectionData(key, data);
        } finally {
            data.free();
        }
    }

    private static byte[] readSection(LMDBStorageBackend backend, long key) {
        var scratch = new MemoryBuffer(1L << 20);
        MemoryBuffer result = null;
        try {
            result = backend.getSectionData(key, scratch);
            byte[] bytes = new byte[(int) result.size];
            result.asByteBuffer().get(bytes);
            return bytes;
        } finally {
            if (result == null) {
                scratch.free();
            } else {
                result.free();
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
}
