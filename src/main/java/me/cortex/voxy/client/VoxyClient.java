package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.RenderBackendSelector;
import me.cortex.voxy.client.core.model.bakery.BudgetBufferRenderer;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
// TODO: Debug screen API changed in MC 1.21.1 - disabled for now
// import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
// import net.minecraft.client.gui.components.debug.DebugScreenEntries;
// import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Client initialization for Voxy on NeoForge.
 * Uses NeoForge event bus for command registration.
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();

    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;

        // Backend / platform selection (Apple Silicon Metal port foundation).
        // See docs/MAC_METAL_PORT_DIFF_MAP.md. On Windows/Linux this is a no-op:
        // the selector reports OPENGL and the legacy capability check above is
        // authoritative. macOS Apple Silicon is handled explicitly because its
        // OpenGL driver is frozen at 4.1 (no compute, no indirect draws), so the
        // capability check fails even though the GPU is capable via Metal.
        BackendType backend = RenderBackendSelector.select();
        Logger.info("Voxy backend selection: " + RenderBackendSelector.describe());
        if (RenderBackendSelector.isAppleSilicon()) {
            systemSupported = handleAppleSilicon(backend);
        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();
            BudgetBufferRenderer.init();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        } else {
            Logger.error("Voxy is unsupported on your system.");
        }
    }

    /**
     * Decides whether Voxy can run on macOS Apple Silicon and emits a clear log
     * line either way. Until the native Metal render path is bundled and
     * implemented (see {@link RenderBackendSelector#isMetalRenderingImplemented()}
     * and docs/MAC_METAL_PORT_DIFF_MAP.md), Voxy disables itself cleanly on
     * macOS so Sodium keeps rendering normally — never a crash, never a blue
     * screen.
     *
     * @return whether Voxy's renderer should be initialised on this Mac.
     */
    private static boolean handleAppleSilicon(BackendType backend) {
        if (backend == BackendType.METAL) {
            // Reachable only once the native bridge ships AND the Metal renderer
            // is implemented; the safe path for the current build is the branches below.
            Logger.info("[VOXY_FORCE_METAL] Metal render backend selected on Apple Silicon.");
            return true;
        }

        if (RenderBackendSelector.isMetalRequested()) {
            String reason = !RenderBackendSelector.isMetalNativeAvailable()
                    ? "the native Metal bridge (libvoxy_metal.dylib) is not bundled in this build"
                    : "the Metal render path is not implemented in this build yet";
            Logger.warn("[VOXY_FORCE_METAL] Metal render path requested but " + reason
                    + ". Voxy will stay disabled and Sodium will render normally."
                    + " Track the Metal bring-up in docs/MAC_METAL_PORT_DIFF_MAP.md.");
        } else {
            Logger.info("Voxy is disabled on macOS Apple Silicon: OpenGL 4.1 lacks the "
                    + "compute / indirect-draw features Voxy needs. A Metal render path is "
                    + "in development; set " + RenderBackendSelector.FORCE_METAL_ENV
                    + "=1 to opt in once a Metal-enabled build is available.");
        }
        return false;
    }

    /**
     * NeoForge event handler for client command registration.
     * Replaces Fabric's ClientCommandRegistrationCallback.
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }

    // Note: FREX flawless frames integration disabled on NeoForge
    // (Fabric-specific entrypoint mechanism not available)

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}