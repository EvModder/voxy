package me.cortex.voxy.common;

import com.google.gson.JsonParser;
import me.cortex.voxy.common.config.storage.lmdb.LMDBStorageBackend;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
}
