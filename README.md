# Voxy

A fork of [MCRcortex/voxy](https://github.com/MCRcortex/voxy) for Minecraft 26.2.

## Changes from upstream

- Adds a native Vulkan renderer that uses Minecraft's Vulkan device and command stream.
- Supports Vulkan on macOS through MoltenVK while preserving the existing OpenGL renderer.
- Hardens Vulkan synchronization and first-frame draw handling on MoltenVK.
- Prevents pending Vulkan readbacks from running after their consumers shut down.
- Prevents partial-height block faces from incorrectly occluding adjacent terrain.
- Adds a concurrent SQLite storage backend for sharing one LoD database across client processes.
- Maps saved server names and proxy addresses to stable storage aliases.
- Builds every pushed commit as a 90-day GitHub Actions artifact, and retains the latest successful `dev` build.

Voxy generates `.voxy/server_aliases.json` on first use. Existing RocksDB data is not converted automatically and should be backed up before migration.

Build with `./gradlew build`.
