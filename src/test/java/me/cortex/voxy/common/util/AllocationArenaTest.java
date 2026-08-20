package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AllocationArenaTest {
    @Test
    void shrinkingAllocationReleasesAndCoalescesItsTail() {
        var arena = new AllocationArena();
        arena.setLimit(80);

        long first = arena.alloc(30);
        long middle = arena.alloc(20);
        arena.alloc(10);
        arena.free(middle);

        assertEquals(10, arena.shrink(first, 20));
        assertEquals(30, arena.getLargestFreeBlockSize());
        assertEquals(20, arena.alloc(30));
    }

    @Test
    void shrinkingFinalAllocationLowersHeapTop() {
        var arena = new AllocationArena();
        arena.setLimit(80);
        long allocation = arena.alloc(60);

        assertEquals(40, arena.shrink(allocation, 20));
        assertEquals(20, arena.getSize());
        assertEquals(0, arena.numFreeBlocks());
    }
}
