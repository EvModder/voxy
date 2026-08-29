package me.cortex.voxy.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.config.storage.lmdb.LMDBStorageBackend;
import me.cortex.voxy.common.config.storage.lmdb.StorageMigration;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;

import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class StorageConfigUtil {
    private static final Object CONFIG_LOCK = new Object();
    private static final Gson CONFIG_JSON = new GsonBuilder().setPrettyPrinting().create();

    public static <T> T getCreateStorageConfig(
            Class<T> configClass, Predicate<T> verifier, Supplier<T> defaultConfig, Path path) {
        synchronized (CONFIG_LOCK) {
            return getCreateStorageConfigLocked(configClass, verifier, defaultConfig, path);
        }
    }

    private static <T> T getCreateStorageConfigLocked(
            Class<T> configClass, Predicate<T> verifier, Supplier<T> defaultConfig, Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to create the storage config directory", exception);
        }
        var json = path.resolve("config.json");
        var lockPath = path.resolve("config.json.lock");
        try (var lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = lockChannel.lock()) {
            T config = null;
            if (Files.exists(json)) {
                String contents = null;
                try {
                    contents = Files.readString(json);
                } catch (Exception exception) {
                    Logger.error("Failed to read the storage configuration file, resetting it to default", exception);
                }
                if (contents != null) {
                    contents = migrateLegacyStorageConfig(contents, path);
                    try {
                        config = Serialization.GSON.fromJson(contents, configClass);
                        if (config == null) {
                            Logger.error("Config deserialization null, reverting to default");
                        } else if (!verifier.test(config)) {
                            Logger.error("Invalid storage config, reverting to default");
                            config = null;
                        }
                    } catch (Exception exception) {
                        Logger.error("Failed to load the storage configuration file, resetting it to default, this will probably break your save if you used a custom storage config", exception);
                    }
                }
            }

            if (config == null) {
                config = defaultConfig.get();
            }
            if (config == null) {
                throw new IllegalStateException("Default storage config supplier returned null");
            }
            writeAtomically(json, Serialization.GSON.toJson(config));
            return config;
        } catch (Exception exception) {
            throw new RuntimeException("Failed to load or write the storage config", exception);
        }
    }

    private static void writeAtomically(Path destination, String contents) throws Exception {
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, contents);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static SectionSerializationStorage.Config createDefaultSerializer() {
        return createSerializer(new LMDBStorageBackend.Config(LMDBStorageBackend.DEFAULT_DIRECTORY_NAME));
    }

    private static SectionSerializationStorage.Config createSerializer(StorageConfig storage) {
        var compressor = new ZSTDCompressor.Config();
        compressor.compressionLevel = 1;

        var compression = new CompressionStorageAdaptor.Config();
        compression.delegate = storage;
        compression.compressor = compressor;

        var serializer = new SectionSerializationStorage.Config();
        serializer.storage = compression;
        return serializer;
    }

    static String migrateLegacyStorageConfig(String contents, Path path) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(contents);
        } catch (RuntimeException ignored) {
            return contents;
        }
        if (!parsed.isJsonObject()) {
            return contents;
        }

        JsonObject root = parsed.getAsJsonObject();
        JsonObject sectionStorage = getObject(root, "sectionStorageConfig");
        JsonObject compression = getObject(sectionStorage, "storage");
        JsonObject delegate = getObject(compression, "delegate");
        String type = getString(delegate, "TYPE");
        StorageMigration.LegacyStorage legacyStorage = switch (type == null ? "" : type) {
            case "RocksDB" -> StorageMigration.LegacyStorage.ROCKS_DB;
            case "SQLiteShared" -> StorageMigration.LegacyStorage.SQLITE_SHARED;
            default -> null;
        };
        if (legacyStorage == null) {
            return contents;
        }

        String sqliteFileName = legacyStorage == StorageMigration.LegacyStorage.SQLITE_SHARED
                ? getString(delegate, "fileName")
                : null;
        StorageMigration.migrateLegacyStorage(path, legacyStorage, sqliteFileName);

        var lmdb = new JsonObject();
        lmdb.addProperty("TYPE", LMDBStorageBackend.Config.getConfigTypeName());
        var lmdbConfig = new LMDBStorageBackend.Config(LMDBStorageBackend.DEFAULT_DIRECTORY_NAME);
        lmdb.addProperty("initialMapSizeGiB", lmdbConfig.initialMapSizeGiB);
        lmdb.addProperty("directoryName", lmdbConfig.directoryName);
        compression.add("delegate", lmdb);
        return CONFIG_JSON.toJson(root);
    }

    private static JsonObject getObject(JsonObject parent, String property) {
        if (parent == null) {
            return null;
        }
        JsonElement value = parent.get(property);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String getString(JsonObject parent, String property) {
        if (parent == null) {
            return null;
        }
        JsonElement value = parent.get(property);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }
}
