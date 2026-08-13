package me.cortex.voxy.common.config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

public final class MappingIdentity {
    private MappingIdentity() {
    }

    public static byte[] fromSerializedMapping(byte[] serialized) {
        try (var input = new GZIPInputStream(new ByteArrayInputStream(serialized));
             var output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            byte[] uncompressed = output.toByteArray();
            zeroRootInteger(uncompressed, "id");
            return uncompressed;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to decode Voxy mapping", exception);
        }
    }

    private static void zeroRootInteger(byte[] nbt, String targetName) {
        Cursor cursor = new Cursor(nbt);
        if (cursor.readUnsignedByte() != 10) {
            throw new IllegalArgumentException("Voxy mapping root is not an NBT compound");
        }
        cursor.skipString();
        while (true) {
            int type = cursor.readUnsignedByte();
            if (type == 0) {
                break;
            }
            String name = cursor.readString();
            if (type == 3 && name.equals(targetName)) {
                cursor.writeZeroes(4);
                return;
            }
            cursor.skipPayload(type);
        }
        throw new IllegalArgumentException("Voxy mapping does not contain a root integer named " + targetName);
    }

    private static final class Cursor {
        private final byte[] data;
        private int offset;

        private Cursor(byte[] data) {
            this.data = data;
        }

        private int readUnsignedByte() {
            require(1);
            return Byte.toUnsignedInt(this.data[this.offset++]);
        }

        private int readUnsignedShort() {
            require(2);
            return (Byte.toUnsignedInt(this.data[this.offset++]) << 8)
                    | Byte.toUnsignedInt(this.data[this.offset++]);
        }

        private int readInt() {
            require(4);
            return (Byte.toUnsignedInt(this.data[this.offset++]) << 24)
                    | (Byte.toUnsignedInt(this.data[this.offset++]) << 16)
                    | (Byte.toUnsignedInt(this.data[this.offset++]) << 8)
                    | Byte.toUnsignedInt(this.data[this.offset++]);
        }

        private String readString() {
            int length = readUnsignedShort();
            require(length);
            String value = new String(this.data, this.offset, length, StandardCharsets.UTF_8);
            this.offset += length;
            return value;
        }

        private void skipString() {
            int length = readUnsignedShort();
            skip(length);
        }

        private void skipPayload(int type) {
            switch (type) {
                case 1 -> skip(1);
                case 2 -> skip(2);
                case 3, 5 -> skip(4);
                case 4, 6 -> skip(8);
                case 7 -> skip(readLength("byte array"));
                case 8 -> skipString();
                case 9 -> {
                    int itemType = readUnsignedByte();
                    int length = readLength("list");
                    for (int index = 0; index < length; index++) {
                        skipPayload(itemType);
                    }
                }
                case 10 -> {
                    while (true) {
                        int childType = readUnsignedByte();
                        if (childType == 0) {
                            break;
                        }
                        skipString();
                        skipPayload(childType);
                    }
                }
                case 11 -> skip(Math.multiplyExact(readLength("integer array"), Integer.BYTES));
                case 12 -> skip(Math.multiplyExact(readLength("long array"), Long.BYTES));
                default -> throw new IllegalArgumentException("Unsupported NBT tag type " + type);
            }
        }

        private int readLength(String type) {
            int length = readInt();
            if (length < 0) {
                throw new IllegalArgumentException("Negative NBT " + type + " length");
            }
            return length;
        }

        private void writeZeroes(int count) {
            require(count);
            Arrays.fill(this.data, this.offset, this.offset + count, (byte) 0);
            this.offset += count;
        }

        private void skip(int count) {
            if (count < 0) {
                throw new IllegalArgumentException("Negative NBT payload length");
            }
            require(count);
            this.offset += count;
        }

        private void require(int count) {
            if (count < 0 || this.offset > this.data.length - count) {
                throw new IllegalArgumentException("Truncated Voxy mapping NBT");
            }
        }
    }
}
