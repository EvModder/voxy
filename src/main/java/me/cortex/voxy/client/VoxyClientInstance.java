package me.cortex.voxy.client;

import me.cortex.voxy.client.compat.FlashbackCompat;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    private final Config config;
    private final Path basePath;
    private final boolean noIngestOverride;

    public VoxyClientInstance() {
        {
            var path = FlashbackCompat.getReplayStoragePath();
            this.noIngestOverride = path != null;
            boolean sharedStorage = false;
            if (path == null) {
                var location = getStorageLocation();
                path = location.path();
                sharedStorage = location.sharedStorage();
            }
            var basePath = this.basePath = path.normalize();
            var defaultStorageConfig = sharedStorage ? DEFAULT_SHARED_STORAGE_CONFIG : DEFAULT_STORAGE_CONFIG;
            boolean requireSharedStorage = sharedStorage;
            this.config = StorageConfigUtil.getCreateStorageConfig(Config.class,
                    c -> c.version == 1 && c.sectionStorageConfig != null
                            && (!requireSharedStorage
                            || StorageConfigUtil.isSharedSerializer(c.sectionStorageConfig)),
                    () -> defaultStorageConfig,
                    basePath);
        }
        super();
        this.updateDedicatedThreads();
    }

    @Override
    protected boolean shouldCreateInstance() {
        return !this.config.disabled;
    }

    @Override
    public void updateDedicatedThreads() {
        int target = VoxyConfig.CONFIG.serviceThreads;
        if (!VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            var swr = SodiumWorldRenderer.instanceNullable();
            if (swr != null) {
                var rsm = ((AccessorSodiumWorldRenderer) swr).getRenderSectionManager();
                if (rsm != null) {
                    this.setNumThreads(Math.max(1, target - rsm.getBuilder().getTotalThreadCount()));
                    return;
                }
            }
        }
        this.setNumThreads(target);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, Minecraft.getInstance().getUser().getProfileId().toString().replace(':','-'));
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return (!this.noIngestOverride) && VoxyConfig.CONFIG.ingestEnabled;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        //Free the render resources cache since the entire instance is freed
        RenderResourceReuse.clearResources();
    }

    private static class Config {
        public int version = 1;
        public boolean disabled = false;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    private static final Config DEFAULT_SHARED_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;

        var sharedConfig = new Config();
        sharedConfig.sectionStorageConfig = StorageConfigUtil.createSharedSerializer();
        DEFAULT_SHARED_STORAGE_CONFIG = sharedConfig;
    }

    private record StorageLocation(Path path, boolean sharedStorage) {
    }

    private static StorageLocation getStorageLocation() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        boolean sharedStorage = false;
        var iserver = Minecraft.getInstance().getSingleplayerServer();
        if (iserver != null) {
            basePath = iserver.getWorldPath(LevelResource.ROOT).resolve("voxy");
        } else {
            var netHandle = Minecraft.getInstance().gameMode;
            if (netHandle == null) {
                Logger.error("Network handle null");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                var info = netHandle.connection.getServerData();
                if (info == null) {
                    Logger.error("Server info null");
                    basePath = basePath.resolve("UNKNOWN");
                } else {
                    if (info.isRealm()) {
                        basePath = basePath.resolve("realms");
                    } else {
                        var resolution = ServerStorageAliases.resolve(
                                Minecraft.getInstance().gameDirectory.toPath(), info.name, info.ip);
                        basePath = basePath.resolve(resolution.storageKey());
                        sharedStorage = resolution.sharedStorage();
                    }
                }
            }
        }
        return new StorageLocation(basePath.toAbsolutePath(), sharedStorage);
    }
}
