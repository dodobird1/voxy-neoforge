## Prompt: NeoForge 1.21.1 + Mac (Apple Silicon) Voxy Port

You are working in a NeoForge 1.21.1 Voxy fork. Goal: add Mac M-series support (Metal backend) for **client-side multiplayer use** on a heavy NeoForge 1.21.1 modpack (Create-based), without breaking Windows/Linux OpenGL path.

### Constraints
- Keep target at Minecraft **1.21.1** and NeoForge **21.1.x**.
- Treat this as client-focused; server-side Voxy sync/worldgen is out of scope.
- Preserve current NeoForge bootstrapping (`@Mod`, neoforge mods toml, configs).
- No Fabric loader wiring in final result.
- Metal path must be opt-in via env flag (ex: `VOXY_FORCE_METAL=1`).

### Source Repos
- Base (edit here): `dodobird1/voxy-neoforge` (`neoforge-1.21.1` lineage)
- Reference to port from: `dodobird1/voxy-mseries-support`

### Primary Deliverable
Run on macOS Apple Silicon client:
1) game starts,
2) Voxy initializes,
3) LOD renders (not just clear screen),
4) connects to NeoForge 1.21.1 multiplayer server,
5) no regression on non-mac clients.

### Work Plan (ordered)
1. **Diff mapping**
   - Build a file map: loader/bootstrap files vs rendering/backend files.
   - Mark files as: copy, adapt, rewrite, ignore.
2. **Backend substrate import**
   - Port minimal `gpu/`, `metal/`, `interop/` interfaces/classes needed for pipeline bring-up.
   - Add platform/backend guards so non-mac still uses OpenGL path.
3. **Native bridge integration**
   - Bring `native/metal` build pieces into NeoForge gradle flow.
   - Bundle `libvoxy_metal.dylib` and required macOS LWJGL natives.
4. **1.21.1 + Sodium 0.6.13 adaptation**
   - Rewrite mseries Sodium mixin hooks to 0.6.13 signatures.
   - Adjust MC API differences (1.21.11 -> 1.21.1 symbols/flow).
5. **Pipeline bring-up milestones**
   - M1: backend init + capability selection.
   - M2: bridge clear pass visible in-game.
   - M3: compute passes execute without crash.
   - M4: opaque LOD draw.
   - M5: translucent + boundary/depth behavior.
6. **Multiplayer validation**
   - Join dedicated NeoForge server.
   - Verify per-server local cache path behavior.
   - Confirm stable streaming/ingest while moving fast (elytra/nether portals optional stress checks).
7. **Safety + fallback**
   - If Metal path fails, disable Voxy cleanly and keep normal Sodium rendering.
   - Add clear logs for backend selected and failure reason.

### Non-goals (for now)
- Iris parity on Mac
- server-side shared LOD protocol
- Vulkan path completion
- perfect visual parity; prioritize stability + usable LOD

### Acceptance Checks
- macOS ARM: with `VOXY_FORCE_METAL=1`, LOD visible in multiplayer world.
- macOS ARM: without flag, safe fallback behavior.
- Linux/Windows: existing GL path still works.
- Dedicated server startup unaffected by client rendering code.

### Output Format Required From You
For each commit, include:
1) what changed,
2) why,
3) proof (log line / screenshot / run result),
4) risk left.

