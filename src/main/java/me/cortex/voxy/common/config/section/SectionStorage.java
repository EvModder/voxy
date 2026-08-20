package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.world.WorldSection;

public abstract class SectionStorage implements IMappingStorage, IStoredSectionPositionIterator {
    /**
     * @return 0 when loaded, 1 when absent, or a negative value when stored data is invalid
     */
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);
}
