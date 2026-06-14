# Mac (Apple Silicon) Metal Port — Diff Map & Plan

Work-plan step 1 ("Diff mapping") from `LLM_PROMPT_NEOFORGE_MAC_PORT.md`.

This document maps the upstream **`dodobird1/voxy-mseries-support`** reference
(Fabric, Minecraft **1.21.11**, Sodium **0.8.1**) onto **this** repository
(`dodobird1/voxy-neoforge`, NeoForge **21.1.x**, Minecraft **1.21.1**, Sodium
**0.6.13**), and classifies every file/subsystem the port touches as
**copy**, **adapt**, **rewrite**, or **ignore**.

It is the planning artifact for the multi-phase port. Only the foundation
(platform/backend detection + `VOXY_FORCE_METAL` opt-in + safe fallback) is
implemented in this PR; the native-bridge bring-up phases are scoped here.

---

## 1. Why a port is needed

Upstream Voxy targets **OpenGL 4.6** (compute shaders, multi-draw-indirect,
`glMultiDrawElementsIndirectCount`, persistent-mapped buffers). macOS ships a
frozen **OpenGL 4.1** — none of that exists. Minecraft + Sodium run on Apple's
GL 4.1; Voxy's LOD renderer cannot. The reference fork therefore runs **Voxy's
renderer on Metal** while Minecraft keeps rendering on GL, bridging the two per
frame through an **IOSurface**.

This repo is the **NeoForge 1.21.1** port. The Mac work must layer the Metal
backend on top of the NeoForge bootstrap **without** breaking the existing
Windows/Linux OpenGL path, and must remain **opt-in** (`VOXY_FORCE_METAL=1`).

---

## 2. Platform / version delta (the porting tax)

| Axis | Reference (mseries) | This repo (target) | Impact |
|---|---|---|---|
| Loader | Fabric Loader 0.18.2+ | NeoForge 21.1.217 | bootstrap/entrypoints differ — **do not copy** loader wiring |
| Minecraft | 1.21.11 | 1.21.1 | API/symbol drift (`Identifier`→`ResourceLocation`, debug-screen API, render-target/fog access) |
| Sodium | mc1.21.11-0.8.1 | mc1.21.1-0.6.13 | mixin targets + signatures differ — Sodium hooks must be **rewritten** to 0.6.13 |
| Fabric API | Fabric API 0.140.0+ | Forgified Fabric API 0.116.7 | use FFAPI shim; no raw Fabric entrypoints |
| Debug screen | `DebugScreenEntries` present | disabled in this port (1.21.1 API change) | keep disabled; do not reintroduce 1.21.11 API |

The reference's `VoxyClient` is a Fabric `ClientModInitializer` using
`net.minecraft.resources.Identifier`, `DebugScreenEntries`, and
`ClientCommandRegistrationCallback`. This repo already replaced all of that
with the NeoForge `@Mod` class (`me.cortex.voxy.Voxy`) +
`@EventBusSubscriber` (`VoxyClient`) + `RegisterClientCommandsEvent`. **The
backend-selection logic must be merged into the existing NeoForge `VoxyClient`,
not copied wholesale.**

---

## 3. File map

Paths are relative to `src/main/java/`. "Only in reference" = files the port
must introduce. Classification legend:

- **copy** — backend-agnostic; can be brought in with package-only changes.
- **adapt** — bring in, but edit for 1.21.1 / Sodium 0.6.13 / NeoForge.
- **rewrite** — must be re-authored against the target API.
- **ignore** — out of scope for this client-focused Mac port (per non-goals).

### 3a. Bootstrap / loader (already NeoForge here)

| File | Action | Notes |
|---|---|---|
| `me/cortex/voxy/Voxy.java` | keep | NeoForge `@Mod`; reference has no equivalent |
| `me/cortex/voxy/client/VoxyClient.java` | **adapt** | merge backend-selection + `VOXY_FORCE_METAL` gate into existing NeoForge class (done in this PR) |
| `me/cortex/voxy/client/VoxyClientEvents.java` | keep | NeoForge-only event glue |
| `me/cortex/voxy/client/config/VoxyNeoForgeConfig.java` | keep | NeoForge config |

### 3b. Cross-backend GPU abstraction — `client/core/gpu/` (28 files)

`BackendType`, `RenderBackend`, `RenderBackendFactory`, `ComputeEncoder`,
`RenderEncoder`, `IGpu{Buffer,Fence,Framebuffer,IndirectCommandBuffer,
PersistentBuffer,Pipeline,RenderBuffer,Resource,Sampler,Shader,Texture,
VertexArray}`, `ComputePipelineDesc`, `GraphicsPipelineDesc`, `RenderPassDesc`,
`SamplerDesc`, `PipelineState`, `VertexLayout`, `ComputeLocalSizeParser`,
`gpu/shader/RuntimeShaderCompiler`.

- **Action: copy → adapt.** These are backend-neutral interfaces and small
  value types. They compile without MC/Sodium symbols. They are the seam the
  whole renderer is migrated onto (raw GL call sites → encoder API).
- **Risk:** introducing them is inert until call sites are migrated; the real
  cost is migrating `MDICSectionRenderer`, `HierarchicalOcclusionTraverser`,
  `NodeCleaner`, `HiZBuffer2`, `ChunkBoundRenderer`, `AbstractRenderPipeline`,
  and the bakery off raw DSA GL onto the abstraction.

### 3c. OpenGL backend impls — `client/core/gl/` (9 new files)

`GLCompat`, `GlRenderBackend`, `GlComputeEncoder`, `GlComputePipeline`,
`GlGraphicsPipeline`, `GlIndirectCommandBuffer`, `GlPipelineStateApplier`,
`GlSampler`, `GlVertexFormatMap`.

- **Action: adapt.** These are the GL implementation of the `gpu/` interfaces;
  the existing GL renderer must be refactored onto them so the OpenGL path is
  expressed through the same abstraction the Metal backend implements. Keep
  them numerically/behaviourally identical to today's GL path (no regression).

### 3d. Metal backend — `client/core/metal/` (17 files)

`MetalNative`, `MetalRenderBackend`, `MetalBuffer`, `MetalPersistentBuffer`,
`MetalRenderBuffer`, `MetalTexture`, `MetalSampler`, `MetalFramebuffer`,
`MetalFence`, `MetalComputeEncoder`, `MetalComputePipeline`,
`MetalRenderEncoder`, `MetalGraphicsPipeline`, `MetalIndirectCommandBuffer`,
`MetalVertexDescriptor`, `MetalFormatUtil`, `MetalHandleMap`.

- **Action: copy (Java is platform-neutral) but gated.** Pure JNI binding
  layer over `native/metal/*.mm`. Compiles on any host (no Mac symbols in
  Java), but only *functions* with `libvoxy_metal.dylib` present.
  `MetalNative.load()` must fail soft (return `false`) when the dylib is
  absent so non-Mac builds and Mac-without-native both fall back cleanly.

### 3e. Native bridge — `native/metal/` (C++/Obj-C++)

`voxy_metal*.mm`/`.h`, `CMakeLists.txt`, `build.sh`, `README.md`. Builds
`libvoxy_metal.dylib` bundled at `src/main/resources/natives/macos-arm64/`.

- **Action: copy.** Build via a gradle `buildMetalNative` Exec task that
  **only runs on macOS aarch64** and **must not fail the overall build** on
  other hosts (jar simply ships without the dylib).
- **Cannot be compiled or verified on Linux/Windows CI** — requires Xcode +
  Metal toolchain. This is the hard boundary for this Linux agent.

### 3f. IOSurface interop — `client/core/interop/` (3 files)

`IOSurfaceBridge`, `IOSurfaceBridgeCompositor`, `IOSurfaceBridgeDemo`.

- **Action: adapt.** The compositor blits the Metal-rendered LOD color into
  MC's main render target at the head of Sodium's SOLID pass. The hook point
  is a Sodium mixin (`MixinDefaultChunkRenderer`) whose signature differs
  between Sodium 0.8.1 and 0.6.13 — **rewrite the hook**, adapt the bridge.

### 3g. Shader translation — `gpu/shader/RuntimeShaderCompiler` + LWJGL natives

Upstream GLSL 4.6 → SPIR-V (`shaderc -O`) → MSL 3.0 (SPIRV-Cross), with a
content-hash disk cache in `~/.voxy/shader-cache`.

- **Action: adapt.** Requires `lwjgl-shaderc` and `lwjgl-spvc` (macos-arm64
  natives). Add to `build.gradle` runtime deps, shipped only for macos-arm64.

### 3h. Model bakery on Metal — `client/core/model/bakery/`

`MetalBudgetBufferRenderer`, `MetalViewCapture` (+ adapt `ModelTextureBakery`,
`ModelFactory`). Upstream GL FBO readback bakes SIGBUS on Apple GL; the Metal
bakery renders each model's 6 faces into the atlas.

- **Action: adapt.** Depends on the Metal backend + atlas mirror.

### 3i. Per-frame mirrors — `client/core/rendering/util/`

`AtlasMirror` (block atlas → Metal at bake time), `DepthMirror`,
`LightMapHelper` (MC lightmap 16×16 → Metal each frame).

- **Action: adapt.** Tie-ins to MC's GL textures; `LightMapHelper` already
  exists here — diff for the mirror additions.

### 3j. Vulkan — `client/core/vulkan/VulkanLoader.java`

- **Action: ignore.** Non-goal ("Vulkan path completion"). Do not port.

### 3k. Smoke tests — `me/cortex/voxy/tools/*SmokeTest.java`

`Metal*`, `Vulkan*`, `IOSurfaceBridge`, `ShaderCompiler` smoke tests.

- **Action: copy (Metal/IOSurface/shader) / ignore (Vulkan).** Dev-only
  `JavaExec` harnesses; only runnable on a macOS aarch64 host with the dylib.

### 3l. Sodium mixins — `client/mixin/sodium/`

`MixinDefaultChunkRenderer` (compositor hook), `AccessorSharedQuadIndexBuffer`.

- **Action: rewrite.** Sodium 0.6.13 (this repo) vs 0.8.1 (reference) — verify
  every target class/method against the Sodium 0.6.13 sources in `.reference/`
  before changing (per `CLAUDE.md` reference-first rule).

---

## 4. Backend / platform guards (foundation — implemented in this PR)

- **`BackendType`** enum: `OPENGL`, `METAL` (Vulkan intentionally omitted —
  non-goal).
- **Platform detection**: macOS Apple Silicon = `os.name` contains `mac` AND
  `os.arch` contains `aarch64`.
- **Opt-in flag**: `VOXY_FORCE_METAL` env (`1`/`true`) or `-Dvoxy.forceMetal=true`.
- **Selection + fallback** (wired into `VoxyClient.initVoxyClient()`):
  - Non-Mac (Win/Linux): **OpenGL path unchanged** — zero behavioural change.
  - Mac aarch64, flag off: Voxy **disabled cleanly**, Sodium renders normally
    (clear log explaining how to opt in).
  - Mac aarch64, flag on, native bridge **not yet present**: log the requested
    Metal path and the missing-native reason, then **fall back safely**
    (disable Voxy, keep Sodium) — no crash.
  - Mac aarch64, flag on, native bridge present (future): select Metal.

This satisfies acceptance checks "macOS ARM without flag → safe fallback",
"Linux/Windows GL path still works", and "dedicated server unaffected"
(selection is client-init only).

---

## 5. Pipeline bring-up milestones (future phases, scoped not implemented)

Mirrors the reference's milestone ladder; each requires a macOS aarch64 host:

- **M1** backend init + capability selection (foundation here).
- **M2** IOSurface bridge clear pass visible in-game.
- **M3** compute passes execute without crash.
- **M4** opaque LOD draw (MDIC emulation + Metal model atlas bakery).
- **M5** translucent + chunk-bound depth mask + fog/boundary behavior.

Then multiplayer validation (per-server local cache path, fast-movement
ingest) and safety/fallback hardening.

---

## 6. Hard constraints on this (Linux) porting environment

- **No macOS / Metal toolchain** → `native/metal/*.mm` cannot be compiled, and
  Metal rendering cannot be exercised here. The `buildMetalNative` task is a
  no-op off-Mac by design.
- Therefore Metal **runtime** acceptance checks (LOD visible on M-series in
  multiplayer) must be validated on Apple Silicon hardware in a follow-up.
- What *is* verifiable off-Mac: the code compiles, the OpenGL path is
  unchanged, and the backend selector chooses OpenGL / safe-fallback on
  non-Mac and Mac-without-native respectively.
