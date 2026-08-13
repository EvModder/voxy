package me.cortex.voxy.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StorageConfigUtilTest {
    @Test
    void distinguishesSharedAndExclusiveStorageDefaults() {
        assertTrue(StorageConfigUtil.isSharedSerializer(StorageConfigUtil.createSharedSerializer()));
        assertFalse(StorageConfigUtil.isSharedSerializer(StorageConfigUtil.createDefaultSerializer()));
    }
}
