package me.cortex.voxy.client.beacon;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.core.BlockPos;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

public final class BeaconGeometry {
    public record Segment(float x, float y, float z, float height, int color, float radius) { }

    public static List<Segment> collect(Viewport<?> viewport, DistantBeacons.Frame frame) {
        if (!VoxyConfig.CONFIG.distantBeaconBeams) return List.of();
        var result = new ArrayList<Segment>();
        double range = VoxyConfig.CONFIG.sectionRenderDistance * 512.0;
        for (var beam : frame.beams()) {
            if (frame.vanilla().contains(beam.position())) continue;
            var pos = BlockPos.of(beam.position());
            double dx = pos.getX() + 0.5 - viewport.cameraX;
            double dz = pos.getZ() + 0.5 - viewport.cameraZ;
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared > range * range) continue;
            double distance = Math.sqrt(distanceSquared);
            float x = (float) dx, z = (float) dz;
            float y = (float) (pos.getY() - viewport.cameraY);
            float radius = 0.2f * (float) Math.max(1, distance / 96);
            for (var segment : beam.segments()) {
                if (viewport.frustum.testAab(x - radius * 1.5f, y, z - radius * 1.5f,
                        x + radius * 1.5f, y + segment.height(), z + radius * 1.5f)) {
                    result.add(new Segment(x, y, z, segment.height(), segment.color(), radius));
                }
                y += segment.height();
            }
        }
        return result;
    }

    public static void write(long address, List<Segment> segments) {
        for (var segment : segments) {
            MemoryUtil.memPutFloat(address, segment.x);
            MemoryUtil.memPutFloat(address + 4, segment.y);
            MemoryUtil.memPutFloat(address + 8, segment.z);
            MemoryUtil.memPutFloat(address + 12, segment.height);
            MemoryUtil.memPutFloat(address + 16, ((segment.color >> 16) & 255) / 255f);
            MemoryUtil.memPutFloat(address + 20, ((segment.color >> 8) & 255) / 255f);
            MemoryUtil.memPutFloat(address + 24, (segment.color & 255) / 255f);
            MemoryUtil.memPutFloat(address + 28, segment.radius);
            address += 32;
        }
    }

    public static void writeUniform(long address, Viewport<?> viewport, float time) {
        viewport.MVP.getToAddress(address);
        MemoryUtil.memPutFloat(address + 64, time);
    }
}
