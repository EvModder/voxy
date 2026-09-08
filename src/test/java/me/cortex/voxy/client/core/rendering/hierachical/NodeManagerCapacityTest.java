package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NodeManagerCapacityTest {
    @Test
    void rejectedTopLevelMeshStaysPendingUntilSpaceIsFreed() {
        var geometry = new BasicAsyncGeometryManager(16, 1024);
        int occupied = geometry.uploadSection(mesh(0));
        var router = new SectionUpdateRouter();
        router.setCallbacks(pos -> {}, pos -> {}, section -> {});
        var nodes = new NodeManager(16, geometry, router);
        long pos = WorldEngine.getWorldSectionId(4, 1, 0, 1);
        nodes.insertTopLevelNode(pos);
        var pending = mesh(pos);
        assertFalse(nodes.processGeometryResult(pending));
        assertTrue(nodes.getTopLevelNodeIds().isEmpty());
        geometry.removeSection(occupied);
        assertTrue(nodes.processGeometryResult(pending));
        assertEquals(1, nodes.getTopLevelNodeIds().size());
        nodes.removeTopLevelNode(pos);
        assertEquals(0, geometry.getSectionCount());
    }

    private static BuiltSection mesh(long pos) {
        return new BuiltSection(pos, (byte) 0, 0, new MemoryBuffer(1024), new int[8], null);
    }

    @Test
    void canceledPendingMeshIsDiscardedWithoutAllocating() {
        var geometry = new BasicAsyncGeometryManager(16, 1024);
        int occupied = geometry.uploadSection(mesh(0));
        var router = new SectionUpdateRouter();
        router.setCallbacks(pos -> {}, pos -> {}, section -> {});
        var nodes = new NodeManager(16, geometry, router);
        long pos = WorldEngine.getWorldSectionId(4, 1, 0, 1);
        nodes.insertTopLevelNode(pos);
        var pending = mesh(pos);
        assertFalse(nodes.processGeometryResult(pending));
        nodes.removeTopLevelNode(pos);
        assertTrue(nodes.processGeometryResult(pending));
        assertTrue(nodes.getTopLevelNodeIds().isEmpty());
        assertEquals(1, geometry.getSectionCount());
        geometry.removeSection(occupied);
    }

    @Test
    void failedReplacementKeepsTheNodeAndCanBeRetried() {
        var geometry = new BasicAsyncGeometryManager(16, 3072);
        var router = new SectionUpdateRouter();
        router.setCallbacks(pos -> {}, pos -> {}, section -> {});
        var nodes = new NodeManager(16, geometry, router);
        long pos = WorldEngine.getWorldSectionId(4, 1, 0, 1);
        nodes.insertTopLevelNode(pos);
        assertTrue(nodes.processGeometryResult(mesh(pos)));
        int occupied = geometry.uploadSection(mesh(0));
        var replacement = new BuiltSection(pos, (byte) 0, 0, new MemoryBuffer(2048), new int[8], null);
        assertFalse(nodes.processGeometryResult(replacement));
        assertEquals(1, nodes.getTopLevelNodeIds().size());
        assertEquals(2048, geometry.getGeometryUsedBytes());
        geometry.removeSection(occupied);
        assertTrue(nodes.processGeometryResult(replacement));
        assertEquals(2048, geometry.getGeometryUsedBytes());
        nodes.removeTopLevelNode(pos);
        assertEquals(0, geometry.getSectionCount());
    }
}
