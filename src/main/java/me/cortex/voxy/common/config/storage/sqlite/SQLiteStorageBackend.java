package me.cortex.voxy.common.config.storage.sqlite;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.MappingIdentity;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;

public final class SQLiteStorageBackend extends StorageBackend {
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int MAPPING_READ_BUSY_TIMEOUT_MS = 50;
    private static final int MAX_TRANSACTION_ATTEMPTS = 8;
    private static final int SCHEMA_VERSION = 1;

    private final Connection connection;
    private final Connection mappingReadConnection;
    private final PreparedStatement getSection;
    private final PreparedStatement setSection;
    private final PreparedStatement deleteSection;
    private final PreparedStatement putMapping;
    private final PreparedStatement getMappings;
    private final PreparedStatement getMappingVersion;
    private final ReentrantLock mappingReadLock = new ReentrantLock();
    private volatile long lastMappingVersion = -1;
    private volatile boolean closed;

    public SQLiteStorageBackend(String file) {
        Connection openedConnection = null;
        Connection openedMappingReadConnection = null;
        try {
            Path path = Path.of(file).toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            Class.forName("org.sqlite.JDBC");
            openedConnection = DriverManager.getConnection("jdbc:sqlite:" + path);
            this.connection = openedConnection;
            configureConnection(this.connection);
            createSchema(this.connection);
            openedMappingReadConnection = DriverManager.getConnection("jdbc:sqlite:" + path);
            this.mappingReadConnection = openedMappingReadConnection;
            configureMappingReadConnection(this.mappingReadConnection);
            this.getSection = this.connection.prepareStatement(
                    "SELECT data FROM world_sections WHERE section_key = ?");
            this.setSection = this.connection.prepareStatement(
                    "INSERT INTO world_sections(section_key, data) VALUES(?, ?) "
                            + "ON CONFLICT(section_key) DO UPDATE SET data = excluded.data");
            this.deleteSection = this.connection.prepareStatement(
                    "DELETE FROM world_sections WHERE section_key = ?");
            this.putMapping = this.connection.prepareStatement(
                    "INSERT INTO id_mappings(mapping_key, entry_type, entry_id, identity, data) "
                            + "VALUES(?, ?, ?, ?, ?) ON CONFLICT(mapping_key) DO UPDATE SET "
                            + "identity = excluded.identity, data = excluded.data");
            this.getMappings = this.mappingReadConnection.prepareStatement(
                    "SELECT mapping_key, data FROM id_mappings ORDER BY mapping_key");
            this.getMappingVersion = this.mappingReadConnection.prepareStatement(
                    "SELECT value FROM storage_metadata WHERE metadata_key = 'mapping_version'");
            this.lastMappingVersion = readMappingVersion();
        } catch (Exception exception) {
            if (openedMappingReadConnection != null) {
                try {
                    openedMappingReadConnection.close();
                } catch (SQLException closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            if (openedConnection != null) {
                try {
                    openedConnection.close();
                } catch (SQLException closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            throw new RuntimeException("Unable to open shared Voxy SQLite storage at " + file, exception);
        }
    }

    private static void configureMappingReadConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + MAPPING_READ_BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA query_only=ON");
        }
    }

    private static void configureConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=30000");
            try (ResultSet result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("Shared Voxy storage requires SQLite WAL journal mode");
                }
            }
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA temp_store=MEMORY");
            statement.execute("PRAGMA cache_size=-65536");
            statement.execute("PRAGMA mmap_size=268435456");
            statement.execute("PRAGMA wal_autocheckpoint=2000");
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                if (!result.next()) {
                    throw new SQLException("Unable to read shared Voxy schema version");
                }
                int version = result.getInt(1);
                if (version > SCHEMA_VERSION) {
                    throw new SQLException("Shared Voxy database schema " + version
                            + " is newer than supported schema " + SCHEMA_VERSION);
                }
            }
            statement.execute("CREATE TABLE IF NOT EXISTS world_sections ("
                    + "section_key INTEGER PRIMARY KEY, data BLOB NOT NULL) WITHOUT ROWID");
            statement.execute("CREATE TABLE IF NOT EXISTS id_mappings ("
                    + "mapping_key INTEGER PRIMARY KEY,"
                    + "entry_type INTEGER NOT NULL,"
                    + "entry_id INTEGER NOT NULL,"
                    + "identity BLOB,"
                    + "data BLOB NOT NULL,"
                    + "UNIQUE(entry_type, entry_id),"
                    + "UNIQUE(entry_type, identity)) WITHOUT ROWID");
            statement.execute("CREATE TABLE IF NOT EXISTS storage_metadata ("
                    + "metadata_key TEXT PRIMARY KEY, value INTEGER NOT NULL) WITHOUT ROWID");
            statement.execute("INSERT OR IGNORE INTO storage_metadata(metadata_key, value) "
                    + "VALUES('mapping_version', 0)");
            statement.execute("CREATE TRIGGER IF NOT EXISTS increment_mapping_version "
                    + "AFTER INSERT ON id_mappings BEGIN "
                    + "UPDATE storage_metadata SET value = value + 1 WHERE metadata_key = 'mapping_version'; END");
            statement.execute("CREATE TRIGGER IF NOT EXISTS update_mapping_version "
                    + "AFTER UPDATE OF data ON id_mappings BEGIN "
                    + "UPDATE storage_metadata SET value = value + 1 WHERE metadata_key = 'mapping_version'; END");
            statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        }
    }

    @Override
    public synchronized void iteratePositions(int level, LongConsumer consumer) {
        ensureOpen();
        try (Statement statement = this.connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT section_key FROM world_sections")) {
            while (result.next()) {
                long key = result.getLong(1);
                if (level == -1 || WorldEngine.getLevel(key) == level) {
                    consumer.accept(key);
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Unable to iterate shared Voxy sections", exception);
        }
    }

    @Override
    public synchronized MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        ensureOpen();
        try {
            this.getSection.setLong(1, key);
            try (ResultSet result = this.getSection.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                byte[] data = result.getBytes(1);
                if (data.length > scratch.size) {
                    throw new IllegalStateException("Stored Voxy section exceeds scratch buffer: " + data.length);
                }
                UnsafeUtil.memcpy(data, scratch.address);
                return scratch.subSize(data.length);
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Unable to read shared Voxy section", exception);
        }
    }

    @Override
    public synchronized void setSectionData(long key, MemoryBuffer data) {
        ensureOpen();
        byte[] bytes = new byte[(int) data.size];
        UnsafeUtil.memcpy(data.address, bytes);
        try {
            this.setSection.setLong(1, key);
            this.setSection.setBytes(2, bytes);
            this.setSection.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Unable to write shared Voxy section", exception);
        }
    }

    @Override
    public synchronized void deleteSectionData(long key) {
        ensureOpen();
        try {
            this.deleteSection.setLong(1, key);
            this.deleteSection.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Unable to delete shared Voxy section", exception);
        }
    }

    @Override
    public synchronized void putIdMapping(int mappingKey, ByteBuffer data) {
        ensureOpen();
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        data.rewind();
        byte[] identity = MappingIdentity.fromSerializedMapping(bytes);
        int entryType = mappingKey >>> 30;
        int entryId = mappingKey & ((1 << 30) - 1);
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                beginImmediate();
                byte[] storedIdentity = hasOtherMappingWithIdentity(mappingKey, entryType, identity)
                        ? null
                        : identity;
                this.putMapping.setInt(1, mappingKey);
                this.putMapping.setInt(2, entryType);
                this.putMapping.setInt(3, entryId);
                this.putMapping.setBytes(4, storedIdentity);
                this.putMapping.setBytes(5, bytes);
                this.putMapping.executeUpdate();
                commit();
                return;
            } catch (SQLException exception) {
                rollbackQuietly();
                if (attempt == MAX_TRANSACTION_ATTEMPTS || !isBusy(exception)) {
                    throw new RuntimeException("Unable to write shared Voxy ID mapping", exception);
                }
                waitBeforeRetry(attempt, "writing shared Voxy ID mapping");
            } catch (RuntimeException exception) {
                rollbackQuietly();
                throw exception;
            }
        }
        throw new IllegalStateException("Unreachable mapping write failure");
    }

    @Override
    public synchronized int getOrCreateIdMapping(
            int entryType, byte[] identity, IntFunction<byte[]> serializedMappingFactory) {
        ensureOpen();
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                beginImmediate();
                Integer existing = findMappingId(entryType, identity);
                if (existing != null) {
                    commit();
                    return existing;
                }

                int entryId = nextMappingId(entryType);
                int mappingKey = entryId | (entryType << 30);
                try (PreparedStatement insert = this.connection.prepareStatement(
                        "INSERT INTO id_mappings(mapping_key, entry_type, entry_id, identity, data) "
                                + "VALUES(?, ?, ?, ?, ?)")) {
                    insert.setInt(1, mappingKey);
                    insert.setInt(2, entryType);
                    insert.setInt(3, entryId);
                    insert.setBytes(4, identity);
                    insert.setBytes(5, serializedMappingFactory.apply(entryId));
                    insert.executeUpdate();
                }
                commit();
                return entryId;
            } catch (SQLException exception) {
                rollbackQuietly();
                if (attempt == MAX_TRANSACTION_ATTEMPTS || !isBusy(exception)) {
                    throw new RuntimeException("Unable to allocate shared Voxy ID mapping", exception);
                }
                waitBeforeRetry(attempt, "allocating shared Voxy ID mapping");
            } catch (RuntimeException exception) {
                rollbackQuietly();
                throw exception;
            }
        }
        throw new IllegalStateException("Unreachable mapping allocation failure");
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        ensureOpen();
        this.mappingReadLock.lock();
        try {
            ensureOpen();
            Int2ObjectOpenHashMap<byte[]> mappings = new Int2ObjectOpenHashMap<>();
            try (ResultSet result = this.getMappings.executeQuery()) {
                while (result.next()) {
                    mappings.put(result.getInt(1), result.getBytes(2));
                }
                return mappings;
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Unable to read shared Voxy ID mappings", exception);
        } finally {
            this.mappingReadLock.unlock();
        }
    }

    @Override
    public long getIdMappingVersion() {
        ensureOpen();
        if (!this.mappingReadLock.tryLock()) {
            return this.lastMappingVersion;
        }
        try {
            ensureOpen();
            try (ResultSet result = this.getMappingVersion.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Missing mapping version metadata");
                }
                return this.lastMappingVersion = result.getLong(1);
            }
        } catch (SQLException exception) {
            if (isBusy(exception)) {
                return this.lastMappingVersion;
            }
            throw new RuntimeException("Unable to read shared Voxy mapping version", exception);
        } finally {
            this.mappingReadLock.unlock();
        }
    }

    private long readMappingVersion() throws SQLException {
        try (ResultSet result = this.getMappingVersion.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("Missing mapping version metadata");
            }
            return result.getLong(1);
        }
    }

    private void beginImmediate() throws SQLException {
        try (Statement statement = this.connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
        }
    }

    private void commit() throws SQLException {
        try (Statement statement = this.connection.createStatement()) {
            statement.execute("COMMIT");
        }
    }

    private void rollbackQuietly() {
        try (Statement statement = this.connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException ignored) {
        }
    }

    private Integer findMappingId(int entryType, byte[] identity) throws SQLException {
        try (PreparedStatement query = this.connection.prepareStatement(
                "SELECT entry_id FROM id_mappings WHERE entry_type = ? AND identity = ?")) {
            query.setInt(1, entryType);
            query.setBytes(2, identity);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getInt(1) : null;
            }
        }
    }

    private boolean hasOtherMappingWithIdentity(int mappingKey, int entryType, byte[] identity) throws SQLException {
        try (PreparedStatement query = this.connection.prepareStatement(
                "SELECT 1 FROM id_mappings WHERE entry_type = ? AND identity = ? AND mapping_key != ? LIMIT 1")) {
            query.setInt(1, entryType);
            query.setBytes(2, identity);
            query.setInt(3, mappingKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private int nextMappingId(int entryType) throws SQLException {
        int emptyValue = entryType == BLOCK_STATE_TYPE ? 0 : -1;
        try (PreparedStatement query = this.connection.prepareStatement(
                "SELECT COALESCE(MAX(entry_id), ?) + 1 FROM id_mappings WHERE entry_type = ?")) {
            query.setInt(1, emptyValue);
            query.setInt(2, entryType);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Unable to select next mapping ID");
                }
                return result.getInt(1);
            }
        }
    }

    private static boolean isBusy(SQLException exception) {
        return exception.getErrorCode() == 5 || exception.getErrorCode() == 6
                || (exception.getMessage() != null && exception.getMessage().contains("SQLITE_BUSY"));
    }

    private static void waitBeforeRetry(int attempt, String operation) {
        try {
            Thread.sleep(5L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while " + operation, interrupted);
        }
    }

    @Override
    public synchronized void flush() {
        ensureOpen();
        try (Statement statement = this.connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(PASSIVE)");
        } catch (SQLException exception) {
            throw new RuntimeException("Unable to checkpoint shared Voxy storage", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.mappingReadLock.lock();
        try {
            this.getSection.close();
            this.setSection.close();
            this.deleteSection.close();
            this.putMapping.close();
            this.getMappings.close();
            this.getMappingVersion.close();
            this.mappingReadConnection.close();
            this.connection.close();
        } catch (SQLException exception) {
            throw new RuntimeException("Unable to close shared Voxy storage", exception);
        } finally {
            this.mappingReadLock.unlock();
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Shared Voxy storage is closed");
        }
    }

    public static final class Config extends StorageConfig {
        public String fileName = "voxy-shared.sqlite";

        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            Path storageDirectory = Path.of(ctx.substituteString(ctx.resolvePath()));
            return new SQLiteStorageBackend(storageDirectory.resolve(this.fileName).toString());
        }

        public static String getConfigTypeName() {
            return "SQLiteShared";
        }
    }
}
