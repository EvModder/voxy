package me.cortex.voxy.client.beacon;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DistantBeacons {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "Voxy beacon storage");
        thread.setDaemon(true);
        return thread;
    });

    public record Frame(List<BeaconStorage.Beam> beams, Set<Long> vanilla, float time) { }
    public static final Frame EMPTY = new Frame(List.of(), Set.of(), 0);

    public static Frame frame() {
        return ((IBeaconRenderState) Minecraft.getInstance()
                .gameRenderer.gameRenderState().levelRenderState).voxy$getBeacons();
    }

    private final Path basePath;
    private final Map<WorldIdentifier, World> worlds = new HashMap<>();
    private int ticks;

    public DistantBeacons(Path basePath) {
        this.basePath = basePath;
    }

    public void tick(ClientLevel level, boolean ingest) {
        if (level == null || ++this.ticks % 20 != 0) return;
        var id = WorldIdentifier.of(level);
        if (id == null) return;
        var world = this.worlds.computeIfAbsent(id,
                key -> new World(this.basePath.resolve(key.getWorldId()).resolve("beacons.json")));
        world.poll();
        if (ingest) {
            var loaded = new HashSet<Long>();
            for (var entity : level.getGloballyRenderedBlockEntities()) {
                if (!(entity instanceof BeaconBlockEntity beacon) || beacon.isRemoved()) continue;
                long pos = beacon.getBlockPos().asLong();
                loaded.add(pos);
                int seen = world.observed.computeIfAbsent(pos, key -> this.ticks);
                var sections = beacon.getBeamSections();
                // Newly loaded beacons need time to scan their column and receive their base level.
                if (sections.isEmpty()) {
                    if (this.ticks - seen >= 100) world.update(pos, null);
                } else {
                    var segments = new ArrayList<BeaconStorage.Segment>(sections.size());
                    for (int i = 0; i < sections.size(); i++) {
                        var section = sections.get(i);
                        segments.add(new BeaconStorage.Segment(section.getColor(),
                                i == sections.size() - 1 ? 2048 : section.getHeight()));
                    }
                    world.update(pos, new BeaconStorage.Beam(pos, segments));
                }
            }
            world.observed.keySet().retainAll(loaded);
            // A cached beacon can have been broken while this client was away. Never load chunks to check.
            for (long key : List.copyOf(world.beams.keySet())) {
                var pos = BlockPos.of(key);
                var chunk = ((ICheekyClientChunkCache) level.getChunkSource()).voxy$cheekyGetChunk(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk != null && !chunk.getBlockState(pos).is(Blocks.BEACON)) world.update(key, null);
            }
        }
        world.snapshot = List.copyOf(world.beams.values());
        // Also refresh discoveries made by another account, without touching the disk on the client thread.
        if (world.operation == null && (!world.dirty.isEmpty() || this.ticks % 200 == 0)) world.submit();
    }

    public static void extract(ClientLevel level, LevelRenderState state) {
        if (!VoxyConfig.CONFIG.distantBeaconBeams || level == null
                || !(VoxyCommon.getInstance() instanceof VoxyClientInstance instance)) {
            clearFrame(state);
            return;
        }
        var world = instance.beacons.worlds.get(WorldIdentifier.of(level));
        if (world == null) {
            clearFrame(state);
            return;
        }
        if (world.snapshot.isEmpty()) {
            clearFrame(state);
            return;
        }
        var vanilla = new HashSet<Long>();
        for (var entity : state.blockEntityRenderStates) {
            if (entity instanceof BeaconRenderState beacon && !beacon.sections.isEmpty()) vanilla.add(beacon.blockPos.asLong());
        }
        ((IBeaconRenderState) state).voxy$setBeacons(
                new Frame(world.snapshot, Set.copyOf(vanilla), (state.gameTime % 40) + state.worldPartialTicks));
    }

    public static void clearFrame(LevelRenderState state) {
        ((IBeaconRenderState) state).voxy$setBeacons(EMPTY);
    }

    public void close() {
        for (var world : this.worlds.values()) {
            // Queue the final delta behind any outstanding save; no foreground disk wait.
            var pending = world.operation;
            var inFlight = new HashMap<>(world.inFlight);
            var dirty = new HashMap<>(world.dirty);
            if (!inFlight.isEmpty() || !dirty.isEmpty()) IO.execute(() -> {
                var remaining = new HashMap<Long, BeaconStorage.Beam>();
                // The earlier operation has completed on this same worker. Only replay failures.
                if (pending != null && pending.isCompletedExceptionally()) remaining.putAll(inFlight);
                remaining.putAll(dirty);
                if (remaining.isEmpty()) return;
                try { BeaconStorage.merge(world.file, remaining); }
                catch (Exception e) { Logger.warn("Could not finish saving distant beacons", e); }
            });
        }
    }

    private static final class World {
        final Path file;
        final Map<Long, BeaconStorage.Beam> beams = new HashMap<>();
        final Map<Long, BeaconStorage.Beam> dirty = new HashMap<>();
        final Map<Long, Integer> observed = new HashMap<>();
        Map<Long, BeaconStorage.Beam> inFlight = Map.of();
        List<BeaconStorage.Beam> snapshot = List.of();
        CompletableFuture<Map<Long, BeaconStorage.Beam>> operation;
        boolean warned;

        World(Path file) {
            this.file = file;
            this.submit();
        }

        void update(long pos, BeaconStorage.Beam beam) {
            if (Objects.equals(this.beams.get(pos), beam)) return;
            if (beam == null) this.beams.remove(pos);
            else this.beams.put(pos, beam);
            this.dirty.put(pos, beam);
        }

        void submit() {
            this.inFlight = new HashMap<>(this.dirty);
            this.dirty.clear();
            var delta = this.inFlight;
            this.operation = CompletableFuture.supplyAsync(() -> {
                try { return BeaconStorage.merge(this.file, delta); }
                catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            }, IO);
        }

        void poll() {
            if (this.operation == null || !this.operation.isDone()) return;
            try {
                var loaded = this.operation.join();
                this.dirty.forEach((pos, beam) -> {
                    if (beam == null) loaded.remove(pos);
                    else loaded.put(pos, beam);
                });
                this.beams.clear();
                this.beams.putAll(loaded);
                this.warned = false;
            } catch (java.util.concurrent.CompletionException e) {
                this.inFlight.forEach((pos, beam) -> {
                    if (!this.dirty.containsKey(pos)) this.dirty.put(pos, beam);
                });
                if (!this.warned) Logger.warn("Could not load/save distant beacons; will retry without replacing invalid data", e.getCause());
                this.warned = true;
            }
            this.inFlight = Map.of();
            this.operation = null;
        }
    }
}
