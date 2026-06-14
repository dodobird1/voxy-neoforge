package me.cortex.voxy.client.core.gpu;

import java.util.Locale;

/**
 * Decides which GPU backend Voxy's LOD renderer should use, and exposes the
 * reasoning so the choice can be logged at startup.
 *
 * <p>This is the foundation for the Apple Silicon (Metal) port described in
 * {@code docs/MAC_METAL_PORT_DIFF_MAP.md}. It is deliberately self-contained
 * and has no dependency on Minecraft, Sodium, or any native code, so it
 * compiles and behaves identically on every platform.</p>
 *
 * <h2>Selection rules</h2>
 * <ul>
 *   <li>Windows / Linux: always {@link BackendType#OPENGL} — the existing path
 *       is untouched (no regression).</li>
 *   <li>macOS Apple Silicon: {@link BackendType#METAL} is selected only when
 *       the user opts in via {@code VOXY_FORCE_METAL} <em>and</em> the native
 *       Metal bridge ({@code libvoxy_metal.dylib}) is bundled and the Metal
 *       render path is implemented. Otherwise the selector reports
 *       {@link BackendType#OPENGL} (which, on Apple's frozen GL 4.1, is not
 *       usable for Voxy — callers fall back to Sodium-only rendering).</li>
 * </ul>
 *
 * <p>The Metal render path itself (encoder API, IOSurface bridge, MSL shader
 * translation, model bakery) is not part of this build yet, so
 * {@link #isMetalRenderingImplemented()} is {@code false}. When the native
 * bring-up lands it becomes the single switch that enables the Metal backend.</p>
 */
public final class RenderBackendSelector {
    /** Env var / system property that opts in to the Metal render path on macOS. */
    public static final String FORCE_METAL_ENV = "VOXY_FORCE_METAL";
    public static final String FORCE_METAL_PROPERTY = "voxy.forceMetal";

    /** Resource path of the bundled native Metal bridge, when present. */
    private static final String METAL_NATIVE_RESOURCE = "/natives/macos-arm64/libvoxy_metal.dylib";

    private RenderBackendSelector() {}

    /** {@code true} on macOS running on an Apple Silicon (M-series, aarch64) CPU. */
    public static boolean isAppleSilicon() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64"));
    }

    /** {@code true} when the user has opted in to the Metal render path. */
    public static boolean isMetalRequested() {
        String env = System.getenv(FORCE_METAL_ENV);
        if ("1".equals(env) || "true".equalsIgnoreCase(env)) {
            return true;
        }
        return Boolean.parseBoolean(System.getProperty(FORCE_METAL_PROPERTY, "false"));
    }

    /**
     * {@code true} when the native Metal bridge ({@code libvoxy_metal.dylib}) is
     * bundled in this build. It is shipped only for macos-arm64 and only once
     * the native toolchain has produced it; absent in OpenGL-only builds.
     */
    public static boolean isMetalNativeAvailable() {
        return RenderBackendSelector.class.getResource(METAL_NATIVE_RESOURCE) != null;
    }

    /**
     * Whether Voxy's renderer has been migrated to the Metal backend yet.
     *
     * <p>This is the transitional switch for the staged port: until the encoder
     * API, IOSurface bridge, and Metal model bakery land (milestones M2–M5 in
     * the diff map), Voxy's render path is raw OpenGL DSA and would abort on
     * Apple's GL 4.1. Keeping this {@code false} guarantees a clean
     * Sodium-only fallback on macOS even when {@code VOXY_FORCE_METAL=1}.</p>
     */
    public static boolean isMetalRenderingImplemented() {
        return false;
    }

    /** The backend Voxy will actually use given the current platform and opt-in state. */
    public static BackendType select() {
        if (isAppleSilicon()
                && isMetalRequested()
                && isMetalNativeAvailable()
                && isMetalRenderingImplemented()) {
            return BackendType.METAL;
        }
        return BackendType.OPENGL;
    }

    /** Human-readable explanation of the current selection, for startup logs. */
    public static String describe() {
        if (!isAppleSilicon()) {
            return "OPENGL (platform=" + System.getProperty("os.name") + "/"
                    + System.getProperty("os.arch") + ")";
        }
        StringBuilder sb = new StringBuilder("Apple Silicon detected; ");
        sb.append(FORCE_METAL_ENV).append(isMetalRequested() ? "=set" : "=unset")
                .append(", nativeBridge=").append(isMetalNativeAvailable() ? "present" : "absent")
                .append(", metalRenderer=").append(isMetalRenderingImplemented() ? "implemented" : "pending")
                .append(" -> ").append(select());
        return sb.toString();
    }
}
