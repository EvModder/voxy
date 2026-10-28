// Merge only adjacent, visible groups from the same section. Never bridge culled geometry.
uint buildOpaqueRanges(uvec4 counts, uint mask, uint base, out uvec2 ranges[7]) {
    uint length = 0;
    uint offset = base + (counts.x & 0xFFFFu); // Skip translucent geometry.
    for (uint group = 1; group < 8; group++) {
        uint count = (counts[group >> 1] >> ((group & 1u) * 16)) & 0xFFFFu;
        uint bit = group == 1 ? 6 : group - 2;
        if (count != 0 && (mask & (1u << bit)) != 0) {
            bool merge = false;
            if (length != 0) {
                uvec2 previous = ranges[length - 1];
                merge = previous.x + previous.y == offset && previous.y + count <= 0xFFFFu;
            }
            if (merge) ranges[length - 1].y += count;
            else ranges[length++] = uvec2(offset, count);
        }
        offset += count;
    }
    return length;
}
