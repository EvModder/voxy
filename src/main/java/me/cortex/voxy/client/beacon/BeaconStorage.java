package me.cortex.voxy.client.beacon;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A sidecar, independent of terrain serialization and its migration versions. */
public final class BeaconStorage {
    private static final Gson GSON = new Gson();

    public record Segment(int color, int height) {
        public Segment {
            if (height <= 0 || height > 4096) throw new IllegalArgumentException("Invalid beam height");
        }
    }

    public record Beam(long position, List<Segment> segments) {
        public Beam {
            segments = List.copyOf(segments);
            if (segments.isEmpty() || segments.size() > 4096) throw new IllegalArgumentException("Invalid beam sections");
        }
    }

    private record Data(int version, List<Beam> beams) { }

    // Null values are removals. Merge while locked rather than replacing another account's discoveries.
    public static Map<Long, Beam> merge(Path file, Map<Long, Beam> changes) throws IOException {
        Files.createDirectories(file.getParent());
        try (var channel = FileChannel.open(file.resolveSibling("beacons.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Beacon storage is busy");
            var beams = read(file);
            if (changes.isEmpty()) return beams;
            boolean changed = changes.entrySet().stream()
                    .anyMatch(entry -> !java.util.Objects.equals(beams.get(entry.getKey()), entry.getValue()));
            if (!changed) return beams;
            changes.forEach((position, beam) -> {
                if (beam == null) beams.remove(position);
                else beams.put(position, beam);
            });
            var temporary = Files.createTempFile(file.getParent(), "beacons-", ".tmp");
            try {
                Files.writeString(temporary, GSON.toJson(new Data(1, List.copyOf(beams.values()))));
                try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    output.force(true);
                }
                // If the filesystem cannot replace atomically, leave the original intact and retry later.
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return beams;
        }
    }

    private static Map<Long, Beam> read(Path file) throws IOException {
        var result = new HashMap<Long, Beam>();
        if (!Files.exists(file)) return result;
        try (var reader = Files.newBufferedReader(file)) {
            var data = GSON.fromJson(reader, Data.class);
            if (data == null || data.version != 1 || data.beams == null) throw new IOException("Invalid beacon storage version");
            for (var beam : data.beams) {
                if (beam == null || result.put(beam.position, beam) != null) throw new IOException("Invalid beacon storage entry");
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid beacon storage; original left untouched", e);
        }
        return result;
    }
}
