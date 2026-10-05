package me.cortex.voxy.client;

import me.cortex.voxy.client.compat.FlashbackCompat;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
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
    private final AsyncStoragePreparation<WorldIdentifier> pendingStorage = new AsyncStoragePreparation<>();
    private final String playerId = Minecraft.getInstance().getUser().getProfileId().toString().replace(':', '-');
    private WorldIdentifier catchUpWorld;
    private int catchUpIndex = -1;

    public VoxyClientInstance() {
        {
            var path = FlashbackCompat.getReplayStoragePath();
            this.noIngestOverride = path != null;
            if (path == null) {
                path = getBasePath();
            }
            var basePath = this.basePath = path.normalize();
            this.config = StorageConfigUtil.getCreateStorageConfig(Config.class,
                    c -> c.version == 1 && c.sectionStorageConfig != null,
                    () -> DEFAULT_STORAGE_CONFIG,
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
        return this.pendingStorage.take(identifier);
    }

    @Override
    protected boolean prepareStorage(WorldIdentifier identifier) {
        return this.pendingStorage.prepare(identifier, () -> this.openStorage(identifier), () ->
                Minecraft.getInstance().execute(() -> {
                    var client = Minecraft.getInstance();
                    if (me.cortex.voxy.commonImpl.VoxyCommon.getInstance() != this
                            || !identifier.equals(WorldIdentifier.of(client.level))) return;
                    var holder = IVoxyRenderSystemHolder.getNullableHolder();
                    if (holder != null && holder.voxy$getRenderSystem() == null) holder.voxy$createRenderer();
                    this.catchUpWorld = identifier;
                    this.catchUpIndex = 0;
                }));
    }

    void tickStorageCatchUp() {
        if (this.catchUpIndex < 0) return;
        var level = Minecraft.getInstance().level;
        if (!this.catchUpWorld.equals(WorldIdentifier.of(level)) || !this.isIngestEnabled(this.catchUpWorld)) {
            this.catchUpIndex = -1;
            return;
        }
        // Revisit chunks received during migration, without one large main-thread ingest burst.
        this.catchUpIndex = ((ICheekyClientChunkCache) level.getChunkSource())
                .voxy$ingestLoadedChunks(this.catchUpIndex, 4);
    }

    private SectionStorage openStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, this.playerId);
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
        this.pendingStorage.close();
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
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
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
                    }
                }
            }
        }
        return basePath.toAbsolutePath();
    }
}
