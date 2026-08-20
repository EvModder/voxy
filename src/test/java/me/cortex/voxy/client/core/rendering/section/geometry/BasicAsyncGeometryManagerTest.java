package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BasicAsyncGeometryManagerTest {
    private static final int ELEMENT_SIZE = 8;

    @Test
    void replacementReusesAndShrinksAllocation() {
        var manager = new BasicAsyncGeometryManager(16, 512L*ELEMENT_SIZE);
        int id = manager.uploadSection(section(200));

        assertEquals(id, manager.uploadReplaceSection(id, section(60)));
        assertEquals(128L*ELEMENT_SIZE, manager.getGeometryUsedBytes());

        manager.removeSection(id);
        assertEquals(0, manager.getGeometryUsedBytes());
    }

    @Test
    void fragmentedArenaRejectsOnlyNewAllocation() {
        var manager = new BasicAsyncGeometryManager(16, 512L*ELEMENT_SIZE);
        int first = manager.uploadSection(section(128));
        int hole = manager.uploadSection(section(128));
        int third = manager.uploadSection(section(128));
        int tail = manager.uploadSection(section(128));
        manager.removeSection(hole);
        manager.removeSection(tail);

        var rejected = section(256);
        assertEquals(IGeometryManager.OUT_OF_CAPACITY, manager.uploadSection(rejected));
        rejected.free();
        assertEquals(2, manager.getSectionCount());
        assertEquals(256L*ELEMENT_SIZE, manager.getGeometryUsedBytes());

        manager.removeSection(first);
        manager.removeSection(third);
    }

    @Test
    void failedReplacementPreservesExistingAllocation() {
        var manager = new BasicAsyncGeometryManager(16, 384L*ELEMENT_SIZE);
        int first = manager.uploadSection(section(128));
        int second = manager.uploadSection(section(128));
        int third = manager.uploadSection(section(128));

        var rejected = section(256);
        assertEquals(IGeometryManager.OUT_OF_CAPACITY, manager.uploadReplaceSection(first, rejected));
        rejected.free();
        assertEquals(3, manager.getSectionCount());
        assertEquals(384L*ELEMENT_SIZE, manager.getGeometryUsedBytes());

        manager.removeSection(first);
        manager.removeSection(second);
        manager.removeSection(third);
    }

    @Test
    void growingReplacementKeepsSectionIdAfterRelocation() {
        var manager = new BasicAsyncGeometryManager(16, 640L*ELEMENT_SIZE);
        int first = manager.uploadSection(section(128));
        int second = manager.uploadSection(section(128));
        int third = manager.uploadSection(section(128));

        assertEquals(first, manager.uploadReplaceSection(first, section(256)));
        assertEquals(512L*ELEMENT_SIZE, manager.getGeometryUsedBytes());

        manager.removeSection(first);
        manager.removeSection(second);
        manager.removeSection(third);
    }

    private static BuiltSection section(int elements) {
        return new BuiltSection(0, (byte) 0, 0, new MemoryBuffer((long) elements*ELEMENT_SIZE), new int[8], null);
    }
}
