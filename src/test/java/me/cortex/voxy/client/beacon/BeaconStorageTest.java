package me.cortex.voxy.client.beacon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BeaconStorageTest {
    @TempDir Path directory;

    private static BeaconStorage.Beam beam(long position) {
        return new BeaconStorage.Beam(position, List.of(
                new BeaconStorage.Segment(0xFF0000, 10), new BeaconStorage.Segment(0xFF00FF, 2048)));
    }

    @Test
    void mergesAccountDiscoveriesAndDeletionsAcrossReopens() throws IOException {
        var file = directory.resolve("world/beacons.json");
        BeaconStorage.merge(file, Map.of(1L, beam(1)));
        BeaconStorage.merge(file, Map.of(2L, beam(2)));
        assertEquals(Map.of(1L, beam(1), 2L, beam(2)), BeaconStorage.merge(file, Map.of()));
        var deletion = new HashMap<Long, BeaconStorage.Beam>();
        deletion.put(1L, null);
        BeaconStorage.merge(file, deletion);
        assertEquals(Map.of(2L, beam(2)), BeaconStorage.merge(file, Map.of()));
        assertTrue(BeaconStorage.merge(directory.resolve("other/beacons.json"), Map.of()).isEmpty());
    }

    @Test
    void unchangedDiscoveriesDoNotRewriteTheFile() throws IOException {
        var file = directory.resolve("beacons.json");
        BeaconStorage.merge(file, Map.of(1L, beam(1)));
        var timestamp = java.nio.file.attribute.FileTime.fromMillis(1000);
        Files.setLastModifiedTime(file, timestamp);
        assertEquals(Map.of(1L, beam(1)), BeaconStorage.merge(file, Map.of(1L, beam(1))));
        assertEquals(timestamp, Files.getLastModifiedTime(file));
    }

    @Test
    void invalidOrFutureDataIsNeverOverwritten() throws IOException {
        var file = directory.resolve("beacons.json");
        for (var invalid : List.of("{", "{\"version\":2,\"beams\":[]}",
                "{\"version\":1,\"beams\":[{\"position\":1,\"segments\":[{\"color\":0,\"height\":-1}]}]}")) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> BeaconStorage.merge(file, Map.of(1L, beam(1))));
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test
    void lockContentionDoesNotReplaceExistingData() throws IOException {
        var file = directory.resolve("beacons.json");
        BeaconStorage.merge(file, Map.of(1L, beam(1)));
        var original = Files.readString(file);
        try (var channel = FileChannel.open(directory.resolve("beacons.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class,
                    () -> BeaconStorage.merge(file, Map.of(2L, beam(2))));
            assertEquals(original, Files.readString(file));
        }
    }
}
