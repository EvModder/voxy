package me.cortex.voxy.client;

import com.mojang.blaze3d.platform.InputConstants;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Unbound by default. Like travel mods that share their key (ebounce stops when you leave), a pause ends on
 * disconnect or restart, so one shared press after rejoining starts both again in step.
 */
public final class VoxyKeybinds {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.parse("voxy:main"));
    private static final KeyMapping INGEST = register("key.voxy.toggle_ingest");
    private static final KeyMapping RENDERING = register("key.voxy.toggle_rendering");

    private static KeyMapping register(String name) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(name, InputConstants.UNKNOWN.getValue(), CATEGORY));
    }

    private static boolean rebuildShaderPack;

    public static void init() {
        // The renderer is torn down with the level, so the next world starts with both resumed.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            var config = VoxyConfig.CONFIG;
            rebuildShaderPack |= config.isRenderingPaused();
            config.setIngestPaused(false);
            config.setRenderingPaused(false);
        });
        // A shader pack built while paused lacks Voxy's patches.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (rebuildShaderPack) IrisUtil.reload();
            rebuildShaderPack = false;
        });
    }

    public static void tick(Minecraft client) {
        while (INGEST.consumeClick()) toggleIngest(client);
        while (RENDERING.consumeClick()) toggleRendering(client);
    }

    private static void toggleIngest(Minecraft client) {
        var config = VoxyConfig.CONFIG;
        if (!blockedBySettings(client, "voxy.toggle.ingest", config.ingestEnabled)) {
            config.setIngestPaused(!config.isIngestPaused());
            report(client, "voxy.toggle.ingest", config.isIngestEnabled());
        }
    }

    private static void toggleRendering(Minecraft client) {
        var config = VoxyConfig.CONFIG;
        if (blockedBySettings(client, "voxy.toggle.rendering", config.enableRendering)) return;
        boolean pause = !config.isRenderingPaused();
        config.setRenderingPaused(pause);
        var holder = IVoxyRenderSystemHolder.getNullableHolder();
        if (pause) {
            holder.voxy$shutdownRenderer();
            IrisUtil.reload();// Rebuild any shader pack without Voxy's patches
        } else {
            // A shader pack must be rebuilt with Voxy's patches before the renderer picks its pipeline.
            IrisUtil.reload();
            if (holder.voxy$getRenderSystem() == null) holder.voxy$createRenderer();
            // Sodium only re-lists visible sections when its graph changes; the new renderer needs them now.
            var sodium = SodiumWorldRenderer.instanceNullable();
            if (sodium != null) sodium.scheduleTerrainUpdate();
        }
        report(client, "voxy.toggle.rendering", config.isRenderingEnabled());
    }

    // A key cannot override Voxy or the setting itself being off.
    private static boolean blockedBySettings(Minecraft client, String label, boolean settingEnabled) {
        if (VoxyCommon.isAvailable() && VoxyConfig.CONFIG.enabled && settingEnabled) return false;
        if (client.player != null) client.player.sendOverlayMessage(Component.translatable(label,
                Component.translatable("voxy.toggle.disabled_in_settings")));
        return true;
    }

    private static void report(Minecraft client, String label, boolean on) {
        if (client.player != null) client.player.sendOverlayMessage(Component.translatable(label,
                Component.translatable(on ? "voxy.toggle.on" : "voxy.toggle.off")));
    }
}
