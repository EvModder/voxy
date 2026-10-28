package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryShaderContractTest {
    @Test
    void opaqueCommandMergingCompilesAlongsideTheUnmodifiedPath() {
        for (int variant = 0; variant < 4; variant++) {
            var defines = VkShaderSource.defs()
                    .defIf("MERGE_OPAQUE_DRAWS", (variant & 1) != 0)
                    .defIf("HAS_STATISTICS", (variant & 2) != 0)
                    .def("STATISTICS_BUFFER_BINDING", 8)
                    .def("TRANSLUCENT_WRITE_BASE", 1024).def("TEMPORAL_OFFSET", 500000)
                    .def("MAX_OPAQUE_DRAWS", 400000).def("MAX_TRANSLUCENT_DRAWS", 100000)
                    .def("MAX_TEMPORAL_DRAWS", 100000).def("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7);
            var source = VkShaderSource.load("voxy:lod/gl46/cmdgen.comp", defines.build());
            assertTrue(ShadercCompiler.compile(source, ShaderType.COMPUTE, "cmdgen").remaining() > 20);
        }
    }

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
