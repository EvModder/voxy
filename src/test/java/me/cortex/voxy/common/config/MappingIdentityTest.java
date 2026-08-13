package me.cortex.voxy.common.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MappingIdentityTest {
    @Test
    void ignoresTheStoredNumericId() throws Exception {
        assertArrayEquals(identityMapping(4), identityMapping(912));
    }

    @Test
    void rejectsNegativeCollectionLengths() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes); var output = new DataOutputStream(gzip)) {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(9);
            output.writeUTF("invalid");
            output.writeByte(1);
            output.writeInt(-1);
        }

        assertThrows(IllegalArgumentException.class,
                () -> MappingIdentity.fromSerializedMapping(bytes.toByteArray()));
    }

    private static byte[] identityMapping(int id) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes); var output = new DataOutputStream(gzip)) {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(3);
            output.writeUTF("id");
            output.writeInt(id);
            output.writeByte(8);
            output.writeUTF("biome_id");
            output.writeUTF("minecraft:plains");
            output.writeByte(0);
        }
        return MappingIdentity.fromSerializedMapping(bytes.toByteArray());
    }
}
