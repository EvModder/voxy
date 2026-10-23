package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryShaderContractTest {
    @Test
    void cullingOffsetMatchesTheExpandedTerrainIndexBuffer() {
        var source = VkShaderSource.load("voxy:lod/gl46/prep.comp", VkShaderSource.defs()
                .def("CUBE_INDEX_OFFSET", SharedIndexBuffer.CUBE_INDEX_OFFSET).build());
        assertTrue(source.contains("firstIndex = CUBE_INDEX_OFFSET / 2"));
        assertTrue(ShadercCompiler.compile(source, ShaderType.COMPUTE, "prep").remaining() > 20);
    }

    @Test
    void cleanerMasksExternalIdsBeforeDereferencingAndStopsAtSentinel() {
        var source = VkShaderSource.load("voxy:lod/hierarchical/cleaner/sort_visibility.comp", VkShaderSource.defs()
                .def("WORK_SIZE", 64).def("ELEMS_PER_THREAD", 8).def("OUTPUT_SIZE", 256)
                .def("VISIBILITY_BUFFER_BINDING", 1).def("OUTPUT_BUFFER_BINDING", 2).def("NODE_DATA_BINDING", 3).build());
        assertFalse(source.contains("const uint value = visibility[id];"));
        assertTrue(source.contains("visibility[initalSort[(OUTPUT_SIZE-1)>>1]&((1u<<31)-1)]"));
        assertTrue(source.contains("id = atomicDerefMaxExchangeLocal(i, id);\n        if (id == uint(-1)) break;"));
        assertTrue(ShadercCompiler.compile(source, ShaderType.COMPUTE, "sort_visibility").remaining() > 20);
    }
}
