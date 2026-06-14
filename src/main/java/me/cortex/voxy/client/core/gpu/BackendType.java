package me.cortex.voxy.client.core.gpu;

/**
 * The GPU backend Voxy's LOD renderer runs on.
 *
 * <p>Windows/Linux use {@link #OPENGL} (OpenGL 4.6: compute, multi-draw-indirect,
 * persistent-mapped buffers). macOS ships a frozen OpenGL 4.1 that has none of
 * those, so Apple Silicon uses {@link #METAL} via a native bridge while
 * Minecraft itself keeps rendering on GL.</p>
 *
 * <p>Vulkan is intentionally omitted: completing the Vulkan path is an explicit
 * non-goal of the Mac client port (see {@code docs/MAC_METAL_PORT_DIFF_MAP.md}).</p>
 */
public enum BackendType {
    OPENGL,
    METAL
}
