package me.cortex.voxy.client.core.rendering.building;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RenderDataFactoryCapacityTest {
    @Test
    void pooledQuadCountsRemainRepresentableAfterOverflow() throws Exception {
        var factory = new RenderDataFactory(null, null, false);
        try {
            var mesherField = RenderDataFactory.class.getDeclaredField("blockMesher");
            mesherField.setAccessible(true);
            var mesher = mesherField.get(factory);
            var emit = mesher.getClass().getDeclaredMethod("emitQuad", int.class, int.class, int.class, int.class, long.class);
            emit.setAccessible(true);
            for (int i = 0; i < 65537; i++) emit.invoke(mesher, 0, 0, 1, 1, 0L);
            for (int i = 0; i < 65537; i++) emit.invoke(mesher, 0, 0, 1, 1, 2L);

            var countersField = RenderDataFactory.class.getDeclaredField("quadCounters");
            countersField.setAccessible(true);
            int[] counters = (int[]) countersField.get(factory);
            assertEquals(65535, counters[0]);
            assertEquals(65535, counters[1]);
            var countField = RenderDataFactory.class.getDeclaredField("quadCount");
            countField.setAccessible(true);
            assertEquals(131070, countField.getInt(factory));
            for (int i = 2; i < 8; i++) assertEquals(0, counters[i]);
        } finally {
            factory.free();
        }
    }
}
