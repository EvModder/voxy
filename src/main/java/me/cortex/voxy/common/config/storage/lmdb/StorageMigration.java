package me.cortex.voxy.common.config.storage.lmdb;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class StorageMigration {
    private static final String STAGING_DIRECTORY_NAME = LMDBStorageBackend.DEFAULT_DIRECTORY_NAME + ".migrating";
    private static final String COMPLETION_MARKER_NAME = "migration-complete.properties";
    private static final int MAX_BATCH_SECTIONS = 1_024;
    private static final long MAX_BATCH_BYTES = 32L << 20;

    private StorageMigration() {
    }

    public enum LegacyStorage {
        ROCKS_DB("RocksDB"),
        SQLITE_SHARED("SQLiteShared");

        private final String configType;

        LegacyStorage(String configType) {
            this.configType = configType;
        }

        public String configType() {
            return this.configType;
        }
    }

    public static void migrateLegacyStorage(
            Path basePath, LegacyStorage sourceType, String sqliteFileName) {
        for (Path storageDirectory : findStorageDirectories(basePath)) {
            migrateStorageDirectory(storageDirectory, sourceType, sqliteFileName);
        }
    }

    private static List<Path> findStorageDirectories(Path basePath) {
        if (!Files.isDirectory(basePath)) {
            return List.of();
        }
        try (var paths = Files.find(basePath, 2,
                (path, attributes) -> attributes.isDirectory()
                        && path.getFileName() != null
                        && path.getFileName().toString().equals("storage"))) {
            return paths.sorted().toList();
        } catch (Exception exception) {
            throw new RuntimeException("Unable to find Voxy storage directories under " + basePath, exception);
        }
    }

    private static void migrateStorageDirectory(
            Path storageDirectory, LegacyStorage sourceType, String sqliteFileName) {
        Path destination = storageDirectory.resolve(LMDBStorageBackend.DEFAULT_DIRECTORY_NAME);
        Path staging = storageDirectory.resolve(STAGING_DIRECTORY_NAME);
        boolean sourceExists = sourceExists(storageDirectory, sourceType, sqliteFileName);
        Marker marker = readMarker(destination);

        if (!sourceExists) {
            if (marker != null) {
                requireMatchingSource(marker, sourceType, sqliteFileName);
            } else if (Files.exists(destination) || Files.exists(staging)) {
                throw new IllegalStateException("Incomplete LMDB migration without legacy source at "
                        + storageDirectory);
            }
            return;
        }

        if (Files.exists(destination)) {
            if (marker == null) {
                throw new IllegalStateException("Refusing to replace unmarked LMDB storage at " + destination);
            }
            requireMatchingSource(marker, sourceType, sqliteFileName);
            validateCompletedMigration(storageDirectory, destination, sourceType, sqliteFileName, marker);
            Logger.info("Reusing verified Voxy LMDB migration at " + destination);
            return;
        }

        if (Files.exists(staging)) {
            deleteStagingDirectory(staging);
        }

        Logger.info("Migrating Voxy " + sourceType.configType() + " storage at " + storageDirectory + " to LMDB");
        MigrationStats stats;
        try (MigrationSource source = openSource(storageDirectory, sourceType, sqliteFileName)) {
            var target = new LMDBStorageBackend(staging.toString());
            try {
                stats = copyAndVerify(source, target, storageDirectory);
                target.flush();
            } finally {
                target.close();
            }
        }

        writeMarker(staging, new Marker(sourceKey(sourceType, sqliteFileName),
                stats.sectionCount(), stats.mappingCount()));
        moveAtomically(staging, destination);
        Logger.info("Migrated " + stats.sectionCount() + " Voxy sections and " + stats.mappingCount()
                + " mappings to " + destination);
    }

    private static MigrationStats copyAndVerify(
            MigrationSource source, LMDBStorageBackend destination, Path storageDirectory) {
        Map<Integer, byte[]> mappings = source.readMappings();
        destination.importMappings(mappings);
        verifyMappings(mappings, destination.getIdMappingsData(), storageDirectory);

        long sectionCount;
        try (var copier = new SectionCopier(destination, storageDirectory)) {
            source.forEachSection(copier::accept);
            sectionCount = copier.finish();
        }
        source.ensureStable();
        long destinationCount = countSections(destination);
        if (destinationCount != sectionCount) {
            throw new IllegalStateException("LMDB migration section count mismatch at " + storageDirectory
                    + ": copied " + sectionCount + ", stored " + destinationCount);
        }
        return new MigrationStats(sectionCount, mappings.size());
    }

    private static void validateCompletedMigration(
            Path storageDirectory, Path destination, LegacyStorage sourceType,
            String sqliteFileName, Marker marker) {
        try (MigrationSource source = openSource(storageDirectory, sourceType, sqliteFileName)) {
            var target = new LMDBStorageBackend(destination.toString());
            try {
                Map<Integer, byte[]> mappings = source.readMappings();
                verifyMappings(mappings, target.getIdMappingsData(), storageDirectory);
                long sectionCount;
                try (var verifier = new SectionVerifier(target, storageDirectory)) {
                    source.forEachSection(verifier::accept);
                    sectionCount = verifier.count;
                }
                source.ensureStable();
                long destinationCount = countSections(target);
                if (sectionCount != marker.sectionCount() || destinationCount != marker.sectionCount()
                        || mappings.size() != marker.mappingCount()) {
                    throw new IllegalStateException("Completed LMDB migration no longer matches its source at "
                            + storageDirectory);
                }
            } finally {
                target.close();
            }
        }
    }

    private static void verifyMappings(
            Map<Integer, byte[]> expected, Map<Integer, byte[]> actual, Path storageDirectory) {
        if (expected.size() != actual.size()) {
            throw new IllegalStateException("LMDB migration mapping count mismatch at " + storageDirectory);
        }
        for (var entry : expected.entrySet()) {
            if (!Arrays.equals(entry.getValue(), actual.get(entry.getKey()))) {
                throw new IllegalStateException("LMDB migration mapping mismatch at " + storageDirectory
                        + " for key " + entry.getKey());
            }
        }
    }

    private static long countSections(LMDBStorageBackend storage) {
        long[] count = new long[1];
        storage.iteratePositions(-1, ignored -> count[0]++);
        return count[0];
    }

    private static boolean sourceExists(
            Path storageDirectory, LegacyStorage sourceType, String sqliteFileName) {
        return switch (sourceType) {
            case ROCKS_DB -> Files.isRegularFile(storageDirectory.resolve("CURRENT"));
            case SQLITE_SHARED -> Files.isRegularFile(resolveSqlitePath(storageDirectory, sqliteFileName));
        };
    }

    private static MigrationSource openSource(
            Path storageDirectory, LegacyStorage sourceType, String sqliteFileName) {
        return switch (sourceType) {
            case ROCKS_DB -> new RocksMigrationSource(storageDirectory);
            case SQLITE_SHARED -> new SQLiteMigrationSource(resolveSqlitePath(storageDirectory, sqliteFileName));
        };
    }

    private static Path resolveSqlitePath(Path storageDirectory, String sqliteFileName) {
        String fileName = normalizedSqliteFileName(sqliteFileName);
        Path normalizedStorage = storageDirectory.toAbsolutePath().normalize();
        Path sqlitePath = normalizedStorage.resolve(fileName).normalize();
        if (!sqlitePath.getParent().equals(normalizedStorage)) {
            throw new IllegalArgumentException("Automatic SQLite migration requires a file directly under "
                    + storageDirectory);
        }
        return sqlitePath;
    }

    private static String sourceKey(LegacyStorage sourceType, String sqliteFileName) {
        return sourceType == LegacyStorage.SQLITE_SHARED
                ? sourceType.configType() + ':' + normalizedSqliteFileName(sqliteFileName)
                : sourceType.configType();
    }

    private static String normalizedSqliteFileName(String sqliteFileName) {
        return sqliteFileName == null || sqliteFileName.isBlank()
                ? "voxy-shared.sqlite"
                : sqliteFileName;
    }

    private static void requireMatchingSource(
            Marker marker, LegacyStorage sourceType, String sqliteFileName) {
        String sourceKey = sourceKey(sourceType, sqliteFileName);
        if (!sourceKey.equals(marker.source())) {
            throw new IllegalStateException("LMDB migration source mismatch: expected " + sourceKey
                    + ", found " + marker.source());
        }
    }

    private static Marker readMarker(Path destination) {
        Path markerPath = destination.resolve(COMPLETION_MARKER_NAME);
        if (!Files.isRegularFile(markerPath)) {
            return null;
        }
        var properties = new Properties();
        try (var input = Files.newInputStream(markerPath)) {
            properties.load(input);
            return new Marker(
                    properties.getProperty("source"),
                    Long.parseLong(properties.getProperty("sections")),
                    Integer.parseInt(properties.getProperty("mappings")));
        } catch (Exception exception) {
            throw new RuntimeException("Unable to read Voxy migration marker at " + markerPath, exception);
        }
    }

    private static void writeMarker(Path destination, Marker marker) {
        var properties = new Properties();
        properties.setProperty("source", marker.source());
        properties.setProperty("sections", Long.toString(marker.sectionCount()));
        properties.setProperty("mappings", Integer.toString(marker.mappingCount()));
        Path markerPath = destination.resolve(COMPLETION_MARKER_NAME);
        try (var output = Files.newOutputStream(markerPath)) {
            properties.store(output, "Completed Voxy storage migration");
        } catch (Exception exception) {
            throw new RuntimeException("Unable to write Voxy migration marker at " + markerPath, exception);
        }
    }

    private static void moveAtomically(Path source, Path destination) {
        try {
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(source, destination);
            }
        } catch (Exception exception) {
            throw new RuntimeException("Unable to activate migrated Voxy LMDB storage at " + destination, exception);
        }
    }

    private static void deleteStagingDirectory(Path staging) {
        if (!staging.getFileName().toString().equals(STAGING_DIRECTORY_NAME)) {
            throw new IllegalArgumentException("Refusing to delete unexpected migration path " + staging);
        }
        try (var paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception exception) {
            throw new RuntimeException("Unable to remove interrupted Voxy migration at " + staging, exception);
        }
    }

    @FunctionalInterface
    private interface SectionConsumer {
        void accept(long key, byte[] value);
    }

    private interface MigrationSource extends AutoCloseable {
        Map<Integer, byte[]> readMappings();

        void forEachSection(SectionConsumer consumer);

        void ensureStable();

        @Override
        void close();
    }

    private static final class SectionCopier implements AutoCloseable {
        private final LMDBStorageBackend destination;
        private final Path storageDirectory;
        private final List<LMDBStorageBackend.SectionData> batch = new ArrayList<>();
        private final SectionVerifier verifier;
        private long batchBytes;
        private long count;

        private SectionCopier(LMDBStorageBackend destination, Path storageDirectory) {
            this.destination = destination;
            this.storageDirectory = storageDirectory;
            this.verifier = new SectionVerifier(destination, storageDirectory);
        }

        private void accept(long key, byte[] value) {
            this.batch.add(new LMDBStorageBackend.SectionData(key, value));
            this.batchBytes += value.length;
            if (this.batch.size() >= MAX_BATCH_SECTIONS || this.batchBytes >= MAX_BATCH_BYTES) {
                flush();
            }
        }

        private long finish() {
            flush();
            return this.count;
        }

        private void flush() {
            if (this.batch.isEmpty()) {
                return;
            }
            this.destination.importSectionBatch(this.batch);
            this.batch.forEach(section -> this.verifier.accept(section.key(), section.data()));
            this.count += this.batch.size();
            if (this.count % 100_000 < this.batch.size()) {
                Logger.info("Migrated " + this.count + " Voxy sections from " + this.storageDirectory);
            }
            this.batch.clear();
            this.batchBytes = 0;
        }

        @Override
        public void close() {
            this.verifier.close();
        }
    }

    private static final class SectionVerifier implements AutoCloseable {
        private final LMDBStorageBackend destination;
        private final Path storageDirectory;
        private MemoryBuffer scratch = new MemoryBuffer(1L << 20);
        private long count;

        private SectionVerifier(LMDBStorageBackend destination, Path storageDirectory) {
            this.destination = destination;
            this.storageDirectory = storageDirectory;
        }

        private void accept(long key, byte[] expected) {
            ensureCapacity(expected.length);
            MemoryBuffer actual = this.destination.getSectionData(
                    key, this.scratch.createUntrackedUnfreeableReference());
            if (actual == null || actual.size != expected.length
                    || ByteBuffer.wrap(expected).mismatch(actual.asByteBuffer()) != -1) {
                throw new IllegalStateException("LMDB migration section mismatch at "
                        + this.storageDirectory + " for key " + key);
            }
            this.count++;
        }

        private void ensureCapacity(int required) {
            if (required <= this.scratch.size) {
                return;
            }
            long expandedSize = Math.multiplyExact(this.scratch.size, 2);
            this.scratch.free();
            this.scratch = new MemoryBuffer(Math.max(required, expandedSize));
        }

        @Override
        public void close() {
            this.scratch.free();
        }
    }

    private static final class RocksMigrationSource implements MigrationSource {
        private final List<ColumnFamilyOptions> columnOptions = new ArrayList<>();
        private final List<ColumnFamilyHandle> handles = new ArrayList<>();
        private DBOptions databaseOptions;
        private RocksDB database;
        private Snapshot snapshot;
        private ReadOptions readOptions;
        private ColumnFamilyHandle sections;
        private ColumnFamilyHandle mappings;
        private long snapshotSequence;

        private RocksMigrationSource(Path path) {
            RocksDB.loadLibrary();
            try {
                this.databaseOptions = new DBOptions();
                var descriptors = new ArrayList<ColumnFamilyDescriptor>();
                for (byte[] name : List.of(
                        RocksDB.DEFAULT_COLUMN_FAMILY,
                        "world_sections".getBytes(StandardCharsets.UTF_8),
                        "id_mappings".getBytes(StandardCharsets.UTF_8))) {
                    var options = new ColumnFamilyOptions();
                    this.columnOptions.add(options);
                    descriptors.add(new ColumnFamilyDescriptor(name, options));
                }
                this.database = RocksDB.openReadOnly(
                        this.databaseOptions, path.toString(), descriptors, this.handles);
                this.sections = this.handles.get(1);
                this.mappings = this.handles.get(2);
                this.snapshot = this.database.getSnapshot();
                this.snapshotSequence = this.snapshot.getSequenceNumber();
                this.readOptions = new ReadOptions()
                        .setSnapshot(this.snapshot)
                        .setFillCache(false)
                        .setVerifyChecksums(true);
            } catch (Exception exception) {
                close();
                throw new RuntimeException("Unable to open RocksDB migration source at " + path, exception);
            }
        }

        @Override
        public Map<Integer, byte[]> readMappings() {
            var result = new HashMap<Integer, byte[]>();
            try (RocksIterator iterator = this.database.newIterator(this.mappings, this.readOptions)) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                    byte[] key = iterator.key();
                    if (key.length != Integer.BYTES) {
                        throw new IllegalStateException("Invalid RocksDB Voxy mapping key length " + key.length);
                    }
                    result.put(ByteBuffer.wrap(key).getInt(), iterator.value());
                }
                iterator.status();
                return result;
            } catch (Exception exception) {
                throw new RuntimeException("Unable to read RocksDB Voxy mappings", exception);
            }
        }

        @Override
        public void forEachSection(SectionConsumer consumer) {
            try (RocksIterator iterator = this.database.newIterator(this.sections, this.readOptions)) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                    byte[] key = iterator.key();
                    if (key.length != Long.BYTES) {
                        throw new IllegalStateException("Invalid RocksDB Voxy section key length " + key.length);
                    }
                    consumer.accept(ByteBuffer.wrap(key).getLong(), iterator.value());
                }
                iterator.status();
            } catch (Exception exception) {
                throw new RuntimeException("Unable to read RocksDB Voxy sections", exception);
            }
        }

        @Override
        public void ensureStable() {
            long currentSequence = this.database.getLatestSequenceNumber();
            if (currentSequence != this.snapshotSequence) {
                throw new IllegalStateException("RocksDB Voxy storage changed during migration; retry when no client is using it");
            }
        }

        @Override
        public void close() {
            if (this.readOptions != null) {
                this.readOptions.close();
                this.readOptions = null;
            }
            if (this.database != null && this.snapshot != null) {
                this.database.releaseSnapshot(this.snapshot);
                this.snapshot = null;
            }
            this.handles.forEach(ColumnFamilyHandle::close);
            this.handles.clear();
            if (this.database != null) {
                this.database.close();
                this.database = null;
            }
            this.columnOptions.forEach(ColumnFamilyOptions::close);
            this.columnOptions.clear();
            if (this.databaseOptions != null) {
                this.databaseOptions.close();
                this.databaseOptions = null;
            }
        }
    }

    private static final class SQLiteMigrationSource implements MigrationSource {
        private Connection connection;

        private SQLiteMigrationSource(Path path) {
            try {
                Class.forName("org.sqlite.JDBC");
                this.connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                try (Statement statement = this.connection.createStatement()) {
                    statement.execute("PRAGMA busy_timeout=30000");
                    statement.execute("BEGIN IMMEDIATE");
                    requireTable(statement, "world_sections");
                    requireTable(statement, "id_mappings");
                }
            } catch (Exception exception) {
                try {
                    close();
                } catch (RuntimeException closeException) {
                    exception.addSuppressed(closeException);
                }
                throw new RuntimeException("Unable to open SQLite Voxy migration source at " + path, exception);
            }
        }

        private static void requireTable(Statement statement, String table) throws Exception {
            try (ResultSet result = statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                if (!result.next()) {
                    throw new IllegalStateException("SQLite Voxy migration source is missing table " + table);
                }
            }
        }

        @Override
        public Map<Integer, byte[]> readMappings() {
            var mappings = new HashMap<Integer, byte[]>();
            try (Statement statement = this.connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT mapping_key, data FROM id_mappings ORDER BY mapping_key")) {
                while (result.next()) {
                    mappings.put(result.getInt(1), result.getBytes(2));
                }
                return mappings;
            } catch (Exception exception) {
                throw new RuntimeException("Unable to read SQLite Voxy mappings", exception);
            }
        }

        @Override
        public void forEachSection(SectionConsumer consumer) {
            try (Statement statement = this.connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT section_key, data FROM world_sections ORDER BY section_key")) {
                while (result.next()) {
                    consumer.accept(result.getLong(1), result.getBytes(2));
                }
            } catch (Exception exception) {
                throw new RuntimeException("Unable to read SQLite Voxy sections", exception);
            }
        }

        @Override
        public void ensureStable() {
            // BEGIN IMMEDIATE holds the source write lock for the migration snapshot.
        }

        @Override
        public void close() {
            if (this.connection == null) {
                return;
            }
            try (Statement statement = this.connection.createStatement()) {
                statement.execute("ROLLBACK");
            } catch (Exception ignored) {
            }
            try {
                this.connection.close();
            } catch (Exception exception) {
                throw new RuntimeException("Unable to close SQLite Voxy migration source", exception);
            } finally {
                this.connection = null;
            }
        }
    }

    private record MigrationStats(long sectionCount, int mappingCount) {
    }

    private record Marker(String source, long sectionCount, int mappingCount) {
    }
}
