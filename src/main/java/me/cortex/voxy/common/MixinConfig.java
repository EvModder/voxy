package me.cortex.voxy.common;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class MixinConfig implements IMixinConfigPlugin {
    private final Predicate<String> modLoaded;
    private String prefix;

    public MixinConfig() {
        this(mod -> FabricLoader.getInstance().isModLoaded(mod));
    }

    MixinConfig(Predicate<String> modLoaded) {
        this.modLoaded = modLoaded;
    }

    @Override
    public void onLoad(String mixinPackage) {
        this.prefix = mixinPackage + ".";
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(this.prefix)) return true;
        String group = mixinClassName.substring(this.prefix.length()).split("\\.", 2)[0];
        // Only optional integrations are gated; vk is a backend, not a mod ID.
        return switch (group) {
            case "flashback", "iris", "nvidium", "chunky" -> this.modLoaded.test(group);
            default -> true;
        };
    }

    // Explicit defaults also support the older Mixin API used by 26.2.
    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
