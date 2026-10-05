package me.cortex.voxy.common;

import com.google.gson.JsonParser;
import me.cortex.voxy.common.config.storage.lmdb.LMDBStorageBackend;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StorageConfigUtilTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void usesNamedLmdbDirectoryByDefault() {
        var serializer = StorageConfigUtil.createDefaultSerializer();

        var compression = assertInstanceOf(CompressionStorageAdaptor.Config.class, serializer.storage);
        var lmdb = assertInstanceOf(LMDBStorageBackend.Config.class, compression.delegate);
        assertEquals(LMDBStorageBackend.DEFAULT_DIRECTORY_NAME, lmdb.directoryName);
    }

    @Test
    void rewritesLegacyStorageBeforeTypedDeserialization() {
        String migrated = StorageConfigUtil.migrateLegacyStorageConfig("""
                {
                  "version": 1,
                  "disabled": false,
                  "sectionStorageConfig": {
                    "TYPE": "Serializer",
                    "storage": {
                      "TYPE": "CompressionAdaptor",
                      "compressor": {"TYPE": "ZSTD", "compressionLevel": 1},
                      "delegate": {"TYPE": "SQLiteShared", "fileName": "legacy.sqlite"}
                    }
                  }
                }
                """, this.temporaryDirectory);

        var root = JsonParser.parseString(migrated).getAsJsonObject();
        var delegate = root.getAsJsonObject("sectionStorageConfig")
                .getAsJsonObject("storage")
                .getAsJsonObject("delegate");
        assertEquals("LMDB", delegate.get("TYPE").getAsString());
        assertEquals(LMDBStorageBackend.DEFAULT_DIRECTORY_NAME,
                delegate.get("directoryName").getAsString());
    }

    @Test
    void upstreamConfigMigratesTerrainAndReopensWithoutReimporting() throws Exception {
        Path storage = this.temporaryDirectory.resolve("dimension/storage");
        Files.createDirectories(storage);
        var bytes = new MemoryBuffer(3);
        try {
            bytes.asByteBuffer().put(new byte[]{1, 2, 3});
            var rocks = new RocksDBStorageBackend(storage.toString());
            try { rocks.setSectionData(42, bytes); }
            finally { rocks.close(); }
            String migrated = StorageConfigUtil.migrateLegacyStorageConfig("""
                    {"version":1,"disabled":false,"sectionStorageConfig":{
                      "TYPE":"Serializer","storage":{"TYPE":"CompressionAdaptor",
                      "compressor":{"TYPE":"ZSTD","compressionLevel":1},
                      "delegate":{"TYPE":"RocksDB"}}}}
                    """, this.temporaryDirectory);
            assertTrue(migrated.contains("\"LMDB\""));
            assertTrue(Files.isRegularFile(storage.resolve("CURRENT")));
            var lmdb = new LMDBStorageBackend(storage.resolve(LMDBStorageBackend.DEFAULT_DIRECTORY_NAME).toString());
            try {
                var read = lmdb.getSectionData(42, bytes.createUntrackedUnfreeableReference());
                byte[] actual = new byte[3];
                read.asByteBuffer().get(actual);
                assertArrayEquals(new byte[]{1, 2, 3}, actual);
                lmdb.setSectionData(43, bytes);
            } finally { lmdb.close(); }
            assertEquals(migrated, StorageConfigUtil.migrateLegacyStorageConfig(migrated, this.temporaryDirectory));
        } finally { bytes.free(); }
    }
}
