package me.cortex.voxy.common.world.other;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BlockStateMappingTest {
    @Test
    void currentAndLegacyStatesRetainTheirIdsAndProperties() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int id = 1;
        for (var state : Block.BLOCK_STATE_REGISTRY) {
            var current = new Mapper.StateEntry(id, state);
            boolean[] resave = {false};
            var decoded = Mapper.StateEntry.deserialize(id, current.serialize(), resave);
            assertSame(state, decoded.state);
            assertEquals(id, decoded.id);
            assertFalse(resave[0]);

            var legacy = new CompoundTag();
            legacy.putInt("id", id);
            legacy.put("block_state", NbtUtils.writeBlockState(state));
            var bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(legacy, bytes);
            decoded = Mapper.StateEntry.deserialize(id, bytes.toByteArray(), resave);
            assertSame(state, decoded.state, state.toString());
            assertEquals(id++, decoded.id);
        }
    }

    @Test
    void invalidStatesFailWithoutInventingReplacementBlocks() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var invalid = new CompoundTag();
        invalid.putInt("id", 1);
        invalid.putString("block_state", "voxy:missing_block");
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(invalid, bytes);
        assertThrows(IllegalStateException.class,
                () -> Mapper.StateEntry.deserialize(1, bytes.toByteArray(), new boolean[1]));
    }
}
