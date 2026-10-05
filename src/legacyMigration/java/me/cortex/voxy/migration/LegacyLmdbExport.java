package me.cortex.voxy.migration;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.lmdb.MDBStat;
import org.lwjgl.util.lmdb.MDBVal;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;

import static org.lwjgl.util.lmdb.LMDB.*;

/** Isolated LMDB 0.9 reader. Its write transaction is always aborted. */
public final class LegacyLmdbExport {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]);
        Path dump = Path.of(args[1]);
        try (var stack = MemoryStack.stackPush()) {
            var major = stack.mallocInt(1);
            var minor = stack.mallocInt(1);
            mdb_version(major, minor, stack.mallocInt(1));
            if (major.get(0) != 0 || minor.get(0) != 9) throw new IllegalStateException("Expected isolated LMDB 0.9 reader");
            var pointer = stack.mallocPointer(1);
            check(mdb_env_create(pointer));
            long environment = pointer.get(0);
            long transaction = 0;
            try {
                check(mdb_env_set_maxdbs(environment, 4));
                check(mdb_env_open(environment, source.toString(), MDB_NOTLS, 0664));
                // Exclude old clients' writers for the entire conversion, including
                // validation and activation in the parent. Never commit this txn.
                check(mdb_txn_begin(environment, 0, 0, pointer));
                transaction = pointer.get(0);
                var main = stack.mallocInt(1);
                check(mdb_dbi_open(transaction, (CharSequence) null, 0, main));
                var tables = MDBStat.calloc(stack);
                check(mdb_stat(transaction, main.get(0), tables));
                if (tables.ms_entries() != 4) throw new IllegalStateException("Unsupported legacy Voxy database schema");
                var digest = MessageDigest.getInstance("SHA-256");
                try (var hash = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(dump)), digest);
                     var output = new DataOutputStream(hash)) {
                    output.writeInt(0x56584C31);
                    for (String name : new String[]{"world_sections", "id_mapping", "mapping_identity", "metadata"}) {
                        exportTable(transaction, name, output);
                    }
                    output.flush();
                    hash.on(false);
                    output.write(digest.digest());
                }
                try (var file = FileChannel.open(dump, StandardOpenOption.WRITE)) { file.force(true); }
                Files.createFile(dump.resolveSibling("export-ready"));
                // EOF also releases the lock if the parent exits unexpectedly.
                System.in.read();
            } finally {
                if (transaction != 0) mdb_txn_abort(transaction);
                mdb_env_close(environment);
            }
        }
    }

    private static void exportTable(long transaction, String name, DataOutputStream output) throws Exception {
        try (var stack = MemoryStack.stackPush()) {
            var db = stack.mallocInt(1);
            check(mdb_dbi_open(transaction, name, 0, db));
            var flags = stack.mallocInt(1);
            check(mdb_dbi_flags(transaction, db.get(0), flags));
            var stat = MDBStat.calloc(stack);
            check(mdb_stat(transaction, db.get(0), stat));
            output.writeUTF(name);
            output.writeInt(flags.get(0));
            output.writeLong(stat.ms_entries());
            var pointer = stack.mallocPointer(1);
            check(mdb_cursor_open(transaction, db.get(0), pointer));
            long cursor = pointer.get(0);
            try {
                var key = MDBVal.calloc(stack);
                var value = MDBVal.calloc(stack);
                long count = 0;
                int status = mdb_cursor_get(cursor, key, value, MDB_FIRST);
                while (status == MDB_SUCCESS) {
                    byte[] k = new byte[Math.toIntExact(key.mv_size())];
                    byte[] v = new byte[Math.toIntExact(value.mv_size())];
                    key.mv_data().get(k);
                    value.mv_data().get(v);
                    output.writeInt(k.length);
                    output.writeInt(v.length);
                    output.write(k);
                    output.write(v);
                    count++;
                    status = mdb_cursor_get(cursor, key, value, MDB_NEXT);
                }
                if (status != MDB_NOTFOUND) check(status);
                if (count != stat.ms_entries()) throw new IllegalStateException("Source count changed");
            } finally { mdb_cursor_close(cursor); }
        }
    }

    private static void check(int status) {
        if (status != MDB_SUCCESS) throw new IllegalStateException(mdb_strerror(status));
    }
}
