package me.cortex.voxy.common;

import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.config.storage.sqlite.SQLiteStorageBackend;

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
                try {
                    config = Serialization.GSON.fromJson(Files.readString(json), configClass);
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
        return createSerializer(new RocksDBStorageBackend.Config());
    }

    public static SectionSerializationStorage.Config createSharedSerializer() {
        return createSerializer(new SQLiteStorageBackend.Config());
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

    public static boolean isSharedSerializer(SectionStorageConfig config) {
        return config instanceof SectionSerializationStorage.Config serializer
                && serializer.storage instanceof CompressionStorageAdaptor.Config compression
                && compression.delegate instanceof SQLiteStorageBackend.Config;
    }
}
