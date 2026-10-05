package me.cortex.voxy.common.config.storage.lmdb;

import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.lmdb.MDBVal;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.util.lmdb.LMDB.*;

/** Copy-on-upgrade: the old environment is never renamed, deleted or committed. */
public final class LmdbFormatMigration {
    private static final String[] TABLES = {"world_sections", "id_mapping", "mapping_identity", "metadata"};
    private static final String MARKER = "lmdb1-complete";
    private static final String OWNER = "voxy-lmdb-format-migration-1";
    private static final int MAGIC = 0x56584C31;

    private LmdbFormatMigration() {}

    public static Path resolve(Path source) {
        if (!usesNewFormat()) return source;
        source = source.toAbsolutePath().normalize();
        Path destination = source.resolveSibling(source.getFileName() + ".lmdb1");
        if (!Files.exists(destination) && !isLegacy(source)) return source;
        Path staging = source.resolveSibling(source.getFileName() + ".lmdb1.migrating");
        Path lockPath = source.resolveSibling(source.getFileName() + ".format.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = lock(channel)) {
            if (Files.exists(destination)) {
                if (!Files.isRegularFile(destination.resolve(MARKER))
                        || !Files.readString(destination.resolve(MARKER)).matches("[0-9a-f]{64}")
                        || !Files.isRegularFile(destination.resolve("data.mdb"))) {
                    throw new IOException("Unverified migration destination: " + destination);
                }
                return destination;
            }
            resetStaging(staging);
            Logger.info("Migrating Voxy LMDB 0.9 to 1.0; LoDs will activate after validation");
            migrate(source, staging, destination);
            Logger.info("Voxy LMDB format migration complete; original database retained");
            return destination;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Voxy LMDB migration did not activate; original retained at " + source, e);
        }
    }

    private static FileLock lock(FileChannel channel) throws Exception {
        while (true) {
            interrupted();
            try {
                var lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException ignored) {}
            Thread.sleep(100);
        }
    }

    static boolean usesNewFormat() {
        try (var stack = MemoryStack.stackPush()) {
            var major = stack.mallocInt(1);
            mdb_version(major, stack.mallocInt(1), stack.mallocInt(1));
            return major.get(0) >= 1;
        }
    }

    private static boolean isLegacy(Path directory) {
        Path data = directory.resolve("data.mdb");
        if (!Files.isRegularFile(data)) return false;
        try (var input = FileChannel.open(data, StandardOpenOption.READ)) {
            var header = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
            while (header.hasRemaining()) if (input.read(header) < 0) return false;
            return header.getInt(16) == 0xBEEFC0DE && header.getInt(20) == 1;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void resetStaging(Path staging) throws IOException {
        if (Files.exists(staging)) {
            if (!Files.isRegularFile(staging.resolve(OWNER))) {
                try (var entries = Files.list(staging)) {
                    if (entries.findAny().isPresent()) throw new IOException("Unowned staging directory: " + staging);
                }
            }
            try (var paths = Files.walk(staging)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Files.createDirectory(staging);
        Files.createFile(staging.resolve(OWNER));
    }

    private static void migrate(Path source, Path staging, Path destination) throws Exception {
        Path database = Files.createDirectory(staging.resolve("database"));
        Path helper = staging.resolve("legacy-lmdb-reader.jar");
        try (var input = LmdbFormatMigration.class.getResourceAsStream("/voxy/migration/legacy-lmdb-reader.jar")) {
            if (input == null) throw new IOException("Missing isolated LMDB 0.9 reader");
            Files.copy(input, helper);
        }
        Path dump = staging.resolve("export.bin");
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "--enable-native-access=ALL-UNNAMED", "-jar",
                helper.toString(), source.toString(), dump.toString())
                .redirectOutput(staging.resolve("export.log").toFile())
                .redirectErrorStream(true).start();
        try {
            while (!Files.exists(staging.resolve("export-ready"))) {
                interrupted();
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    throw new IOException("Legacy reader exited " + process.exitValue() + "; see " + staging.resolve("export.log"));
                }
            }
            long mapSize = Math.max(16L << 30, Math.multiplyExact(Files.size(source.resolve("data.mdb")), 2));
            byte[] expected = importDump(dump, database, mapSize);
            byte[] actual = fingerprint(database);
            if (!MessageDigest.isEqual(expected, actual)) throw new IOException("Migrated database checksum mismatch");
            interrupted();
            Files.writeString(database.resolve(MARKER), java.util.HexFormat.of().formatHex(actual));
            try (var marker = FileChannel.open(database.resolve(MARKER), StandardOpenOption.WRITE)) { marker.force(true); }
            // Publish while the legacy helper still excludes source writers.
            if (!process.isAlive()) throw new IOException("Legacy reader exited before migration activation; source writer lock was lost");
            Files.move(database, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            // Close stdin to abort the helper's writer transaction and release its
            // source lock on success, failure, cancellation or parent shutdown.
            try {
                try { process.getOutputStream().close(); }
                catch (IOException e) { process.destroyForcibly(); }
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                process.onExit().join();
                Thread.currentThread().interrupt();
            }
        }
        try {
            Files.delete(helper);
            Files.delete(dump);
            Files.delete(staging.resolve("export-ready"));
            Files.delete(staging.resolve("export.log"));
            Files.delete(staging.resolve(OWNER));
            Files.delete(staging);
        } catch (IOException e) {
            Logger.warn("Voxy migration succeeded but temporary files could not be removed", e);
        }
    }

    private static byte[] importDump(Path dump, Path destination, long mapSize) throws Exception {
        // Staging is disposable until the final forced sync and validation.
        long environment = open(destination, mapSize, MDB_NOSYNC);
        long transaction = 0;
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(dump)));
             var stack = MemoryStack.stackPush()) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid legacy export");
            var pointer = stack.mallocPointer(1);
            for (String name : TABLES) {
                interrupted();
                if (!input.readUTF().equals(name)) throw new IOException("Unexpected legacy table");
                int flags = input.readInt();
                int expectedFlags = name.equals("mapping_identity") ? 0 : MDB_INTEGERKEY;
                if (flags != expectedFlags) throw new IOException("Unsupported legacy database flags");
                long count = input.readLong();
                if (count < 0) throw new IOException("Negative record count");
                check(mdb_txn_begin(environment, 0, 0, pointer));
                transaction = pointer.get(0);
                var db = stack.mallocInt(1);
                check(mdb_dbi_open(transaction, name, flags | MDB_CREATE, db));
                for (long index = 0; index < count; index++) {
                    interrupted();
                    int keySize = input.readInt(), valueSize = input.readInt();
                    if (keySize < 1 || keySize > 511 || valueSize < 0 || valueSize > (64 << 20)) {
                        throw new IOException("Invalid exported record length");
                    }
                    var keyBytes = MemoryUtil.memAlloc(keySize);
                    var valueBytes = MemoryUtil.memAlloc(valueSize);
                    try (var entryStack = MemoryStack.stackPush()) {
                        byte[] key = input.readNBytes(keySize), value = input.readNBytes(valueSize);
                        if (key.length != keySize || value.length != valueSize) throw new EOFException();
                        keyBytes.put(key).flip(); valueBytes.put(value).flip();
                        check(mdb_put(transaction, db.get(0), MDBVal.calloc(entryStack).mv_data(keyBytes),
                                MDBVal.calloc(entryStack).mv_data(valueBytes), MDB_NOOVERWRITE));
                    } finally { MemoryUtil.memFree(keyBytes); MemoryUtil.memFree(valueBytes); }
                    if ((index + 1) % 512 == 0 && index + 1 < count) {
                        long committing = transaction; transaction = 0;
                        check(mdb_txn_commit(committing));
                        check(mdb_txn_begin(environment, 0, 0, pointer)); transaction = pointer.get(0);
                    }
                }
                long committing = transaction; transaction = 0;
                check(mdb_txn_commit(committing));
            }
            byte[] digest = input.readNBytes(32);
            if (digest.length != 32 || input.read() != -1) throw new IOException("Incomplete legacy export");
            check(mdb_env_sync(environment, true));
            return digest;
        } finally {
            if (transaction != 0) mdb_txn_abort(transaction);
            mdb_env_close(environment);
        }
    }

    private static byte[] fingerprint(Path directory) throws Exception {
        long environment = open(directory, 0, MDB_RDONLY);
        long transaction = 0;
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stack = MemoryStack.stackPush();
             var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            var pointer = stack.mallocPointer(1);
            check(mdb_txn_begin(environment, 0, MDB_RDONLY, pointer)); transaction = pointer.get(0);
            output.writeInt(MAGIC);
            for (String name : TABLES) {
                var db = stack.mallocInt(1);
                var flags = stack.mallocInt(1);
                check(mdb_dbi_open(transaction, name, 0, db));
                check(mdb_dbi_flags(transaction, db.get(0), flags));
                var stat = org.lwjgl.util.lmdb.MDBStat.calloc(stack);
                check(mdb_stat(transaction, db.get(0), stat));
                output.writeUTF(name); output.writeInt(flags.get(0)); output.writeLong(stat.ms_entries());
                check(mdb_cursor_open(transaction, db.get(0), pointer));
                long cursor = pointer.get(0);
                try {
                    var key = MDBVal.calloc(stack); var value = MDBVal.calloc(stack);
                    int status = mdb_cursor_get(cursor, key, value, MDB_FIRST);
                    while (status == MDB_SUCCESS) {
                        interrupted();
                        byte[] k = new byte[Math.toIntExact(key.mv_size())], v = new byte[Math.toIntExact(value.mv_size())];
                        key.mv_data().get(k); value.mv_data().get(v);
                        output.writeInt(k.length); output.writeInt(v.length); output.write(k); output.write(v);
                        status = mdb_cursor_get(cursor, key, value, MDB_NEXT);
                    }
                    if (status != MDB_NOTFOUND) check(status);
                } finally { mdb_cursor_close(cursor); }
            }
            output.flush();
            return digest.digest();
        } finally {
            if (transaction != 0) mdb_txn_abort(transaction);
            mdb_env_close(environment);
        }
    }

    private static long open(Path path, long mapSize, int flags) {
        try (var stack = MemoryStack.stackPush()) {
            var pointer = stack.mallocPointer(1);
            check(mdb_env_create(pointer));
            long environment = pointer.get(0);
            try {
                check(mdb_env_set_maxdbs(environment, 4));
                check(mdb_env_set_maxreaders(environment, 512));
                if (mapSize != 0) check(mdb_env_set_mapsize(environment, mapSize));
                check(mdb_env_open(environment, path.toString(), flags | MDB_NOTLS, 0664));
                return environment;
            } catch (Throwable e) { mdb_env_close(environment); throw e; }
        }
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Voxy migration cancelled");
    }

    private static void check(int status) {
        if (status != MDB_SUCCESS) throw new IllegalStateException(mdb_strerror(status));
    }
}
