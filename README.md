# Voxy

A fork of [MCRcortex/voxy](https://github.com/MCRcortex/voxy) for Minecraft 26.2.

## Changes from upstream

- Adds a native Vulkan renderer that uses Minecraft's Vulkan device and command stream.
- Supports Vulkan on macOS through MoltenVK (preserves the existing OpenGL fallback).
- Prevents pending Vulkan readbacks from running after their consumers shut down.
- Bounds GPU-generated draw commands to their allocated buffer regions.
- Prevents partial-height block faces from incorrectly occluding adjacent terrain.
- Corrects clear-glass mipmapping and prevents translucent LoD face overlap.
- Keeps geometry-capacity pressure recoverable and reduces allocator fragmentation.
- Coalesces repeated renderer reloads within the same Minecraft frame.
- Preserves dirty section data for retry after failed storage writes.
- Keeps uniform LoD sections compact until per-voxel storage is required.
- Migrates from RocksDB to concurrent LMDB storage by default for LoD databases.
- Adds server name and proxy aliasing for stable shared storage of LoDs.
- Builds every pushed commit as a 90-day GitHub Actions artifact, and retains the latest successful `dev` build.

Voxy generates `.voxy/server_aliases.json` on first use.<br>
Legacy RocksDB and SQLiteShared saves migrate to LMDB automatically.

Build with `./gradlew build`
