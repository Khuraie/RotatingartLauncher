# TERRADOCK — launcher half (RotatingartLauncher fork)

Status: **P0/P1 implemented, CI build green required** (see Build status below).
Scope owner: launcher (Kotlin/Android). Contract owner: Lead (`C:\Tmobile\terradock\bridge-spec.md`, FROZEN v1).

## What this adds

The launcher is the only side that can see raw touch input, so it synthesizes the desktop
game's keyboard/mouse from the on-screen controls **and** publishes that exact input state at
60 Hz to a binary file that the `TMLMobileFeel` tModLoader mod reads (the mod cannot receive
touch input itself).

Three pieces:

| Piece | File | Role |
|---|---|---|
| Bridge writer | `feature/terradock/TerradockBridgeWriter.kt` + `TerradockBridgeFormat.kt` | 512-byte / 2-slot `state.bin`, zero-allocation, CRC32, seq wrap |
| State + synthesis | `TerradockInputState.kt`, `TerradockRecordingBridge.kt`, `TerradockBridgeService.kt` | records what was actually sent to SDL; move stick→WASD, aim stick→relative mouse, Aim&Use auto-fire, action bitfields |
| Control pack | `assets/terradock/control-pack.json` (generated), `TerradockControlPack.kt`, `tools/terradock/gen_control_pack.py` | Terraria-mobile geometry from `control-pack-ref.json` (2400×1080 canvas) applied as the default RAL control profile |

Minimal edits to existing code (no refactors, editor/other packs untouched):

- `feature/controls/ControlData.kt` — additive optional fields: `Joystick.terradockStick`,
  `Button.terradockAction`, `Button.terradockBridgeOnly` (JSON backward compatible).
- `feature/controls/bridges/ControlInputBridge.kt` — default no-op `sendStick(stick, x, y)`.
- `feature/controls/ui/VirtualJoystick.kt` — when `terradockStick` is set, report analog values
  instead of the legacy discrete-key/absolute-mouse paths (2 early-return blocks).
- `feature/controls/ui/ControlLayout.kt` — snapshot real pointers into the bridge state (≤10).
- `feature/game/GameVirtualControlsManager.kt` — wrap the SDL bridge with the recording bridge,
  install the default pack, start/stop the 60 Hz service with the game session.
- `core/model/Settings.kt` + `core/common/SettingsAccess.kt` — 3 new settings.
- `feature/controls/packs/ControlPackManager.kt` — `hasUserSelectedPack()` so the built-in pack
  never overrides a user-configured pack.
- `app/src/test/.../TerradockBridgeFormatTest.kt` — layout/CRC/seq-wrap unit tests.

## Bridge file

- Path: `<RAL HOME>/ral_controls/state.bin`, where RAL HOME = `/storage/emulated/0/RALauncher`
  (same root as `ControlPacks/`; see `GameLauncher.prepareDataDirectory`).
- Env override: `TERRADOCK_BRIDGE` = full file path (both sides honor it).
- Resolution order in `TerradockBridgeWriter.resolveBridgeFile()`:
  1. `TERRADOCK_BRIDGE`
  2. `<externalStorage>/RALauncher` (RAL default)
  3. `HOME` env (set by RAL when launching the .NET game)
  4. app-private `filesDir` (last resort; the mod normally cannot read this)
- Format: exactly `bridge-spec.md` v1 — 512 B, 2 slots × 256 B, magic `TRDK`, version 1,
  CRC-32 (IEEE) over bytes 0..251, little-endian, packed.
- Cadence: one write per `Choreographer` frame (≈60 Hz at 60 Hz displays). Only the target
  256-byte slot is written per frame (no fsync — page-cache visible; avoids 60 Hz fsync stalls).
- Session end: both slots are zeroed (magic/CRC invalid → mod immediately marks the bridge
  stale and falls back to mouse), then the file and `ral_controls/` (if empty) are deleted.
- Self-test: `TerradockBridgeFormat.selfTest()` runs once on open (layout offsets, CRC,
  unused-touch zeroing, seq wrap) and is logged as `TERRADOCK bridge format self-test: PASS/FAIL`.

## Control pack

- Generated: `python tools/terradock/gen_control_pack.py` (re-reads
  `C:\Tmobile\terradock\control-pack-ref.json`; re-run when the mock geometry changes).
- Installed to `ControlPacks/installed/terradock_pack/` on game start; selected automatically
  only when the user has never configured a control pack (`hasUserSelectedPack() == false`).
  Re-installed whenever the asset `version` changes — the version is a content hash (CRC-32 of
  reference/sticks/buttons/hotbar), so any geometry or key-mapping tweak regenerates and
  re-installs automatically; a user's own pack is never overridden.
- Coordinate mapping (identical to the mod, agreed with Lead):
  `u = surfaceHeightPx / 1080`; each element is anchored to one of 9 reference anchor points;
  `pos_px = anchorPointPx + (ref - anchorRef) * u`; sizes scale by `u`.
  Anchor per element is stored explicitly in the asset (`"anchor": "BL"` …). Generator rule
  (Lead's, unchanged): X = nearest of `{0, 1200, 2400}` to `cx`, Y = nearest of
  `{0, 540, 1080}`, ties picking the lower value. X-anchoring matters on aspect ratios
  ≠ 2.222:1 (right-anchored controls stick to the right edge); Y-anchoring is cosmetic —
  positions and offsets both scale by `u = sh/1080`, so the runtime Y is identical for any
  anchor choice.
  The generator also emits `ox/oy` and **regression-checks all 20 element geometries
  (cx, cy, size/radius) against the Lead's authoritative table** (exit 1 on any mismatch).
- Current geometry (2400×1080 reference units, from the finalised
  `control-pack-ref.json` / mobile-feel v3 mock):
  - sticks: `move (272,860) r120`, `aim (2128,860) r120`, deadzone 0.12 → anchors BL / BR.
  - quick buttons (cx,cy,size): `interact 109,749,137` · `heal 435,749,137` ·
    `autoselect 88,869,103` · `mana 456,869,103` · `autofire 120,965,103` ·
    `buff 424,965,103` · `jump 2291,749,137` · `grapple 1965,749,75` · `use 1944,869,103` ·
    `mount 2312,869,103` · `smart 2280,965,103` · `target 1976,965,103` ·
    `zoomout 735,1010,96` · `zoomin 1665,1010,96` (anchors CL/CR/BL/BR/BC per the rule).
  - system buttons on the **left edge**, cx=56, size 72: `map cy232` · `bag cy318` ·
    `gear cy404` · `pause cy490` → anchors TL, CL, CL, CL (X all = 56 → left).
  - offsets (ref units from anchor): move (272,−220) · aim (−272,−220) ·
    interact (109,209) · heal (435,209) · autoselect (88,−211) · mana (456,−211) ·
    autofire (120,−115) · buff (424,−115) · jump (−109,209) · grapple (−435,209) ·
    use (−456,−211) · mount (−88,−211) · smart (−120,−115) · target (−424,−115) ·
    zoomout (−465,−70) · zoomin (465,−70) · map (56,232) · bag (56,−222) ·
    gear (56,−136) · pause (56,−50).
- Hotbar rect is the same in both halves: `x=22, y=18, cols=6, rows=2, cell=78, gap=7` in
  2400×1080 reference units (block anchor TL), so the invisible launcher tap cells sit exactly
  under the mod's drawn slots.
- Hotbar: 12 cells; cells 0–9 are the hotbar and synthesize digit keys `1..9,0`
  (tap-to-select, per Lead convention — hotbar state is **not** in the bridge); cells 10–11 are
  the mod's native quick potion slots and are inert (no launcher control created).
- Pack contents: 2 sticks (move→WASD, aim→relative mouse), 14 quick buttons, 4 left-edge system
  buttons, 10 hotbar cells = 30 controls. Bars/buffs/cursor are mod-drawn only.

## Action → desktop input (bridge-spec table)

| Action | Synthesized | Notes |
|---|---|---|
| JUMP | Space | hold = longer jump (key held while touched) |
| INTERACT | right mouse | |
| FIRE | left mouse | explicit USE button; held while touched |
| HEAL / MANA / BUFF | H / J / B | |
| MOUNT / GRAPPLE | R / E | |
| SMART_CURSOR | LeftShift | also sets `smartCursor` byte |
| INVENTORY / MENU | Escape | both map to Escape (Terraria uses one key) |
| MAP | M | |
| ZOOM_IN / ZOOM_OUT | **launcher-only** (bridge bits 12/13) | no desktop binding; mod applies camera zoom |
| TARGET_LOCK | **launcher-only** (bridge bit 11) | |
| AUTO_FIRE (`AIM_MODE` button) | **launcher-only** (bridge bit 9) | hold = launcher holds LMB + `flags.bit5`; mod reads AIM_MODE as ACTION 9 |
| AUTO_SELECT (`AUTO_SELECT` button) | **launcher-only** (bridge bit 10) | hotbar auto-select handled mod-side |
| UP / DOWN | bridge bits 18/19 | derived from move stick (W/S) |
| AUTO_FIRE from aim stick | bridge bit 9 + `flags.bit5` | aim stick beyond 0.65 in Aim & Use mode |

Sticks are analog on the bridge (`stickLX/LY/RX/RY`, radius-normalized), dead zones are
launcher-side: move 0.12, aim 0.12, auto-fire threshold 0.65. Aim sensitivity reuses the
existing RAL setting `mouseRightStickSpeed` (60–200 → 540–1800 px/s at full deflection).

## Settings

`AppSettings` (`core/model/Settings.kt`), JSON-persisted, defaults preserve old behavior:

- `terradockBridgeEnabled` (default `true`) — write the bridge file at all.
- `terradockControlPackEnabled` (default `true`) — use the built-in pack as default; turning it
  off while it is selected hands selection back to another installed pack.
- `terradockAimMode` (default `1`) — `0` = Aim Only, `1` = Aim & Use (auto-fire when the aim
  stick passes the threshold).

Enable/disable:
- Bridge only: set `terradockBridgeEnabled=false` (no bridge file is created; controls still work).
- Pack only: set `terradockControlPackEnabled=false` and pick your own pack in Controls.
- Env override for a one-off path: `TERRADOCK_BRIDGE=/sdcard/.../state.bin`.

## Build status

- Local build: **not possible on this machine** — `ANDROID_HOME` points to a non-existent
  directory and no Android SDK/NDK 29 is installed (`C:\Program Files\Android\Android Studio`
  has no bundled SDK). JDK 22 + Gradle 9.1 wrapper are present.
- Verification path used: push to `fork main` → GitHub Actions `build-debug-apk.yml`
  (`./gradlew assembleDebug`, NDK 29 / CMake 3.22.1, preinstalled SDK).
- Baseline before this work: CI run `41987830725` built `app-debug.apk` successfully.
- This work (**green**): run
  [`42037567076`](https://github.com/Khuraie/RotatingartLauncher/actions/runs/42037567076)
  on `904d4476` — all steps ✓ (`Build debug APK` 6m48s), run
  [`42038730476`](https://github.com/Khuraie/RotatingartLauncher/actions/runs/42038730476)
  on `176a4da3` (anchor/version alignment) — **success** (`Build debug APK` ✓,
  `Upload debug APK artifact` ✓), and run
  [`42041691604`](https://github.com/Khuraie/RotatingartLauncher/actions/runs/42041691604)
  on `f7bb903f` (finalised mobile-feel v3 geometry: new hotbar/sticks/buttons, AIM_MODE &
  AUTO_SELECT bridge actions, left-edge system buttons) — **success**.
- Unit tests (`TerradockBridgeFormatTest`) are **not** executed by that workflow (it only runs
  `assembleDebug`); they are compile-checked only if someone runs `testDebugUnitTest`.
- Note: `build-debug-apk.yml` only triggers on `app/**`, `shared/**`, `gradle/**`, `gradlew`,
  `settings.gradle*`, `build.gradle*`; docs/tooling-only commits do not schedule a build.

## Verified vs unverified

Verified (by construction / unit-testable):
- Bridge byte layout, CRC-32 and slot/seq logic are unit tested
  (`TerradockBridgeFormatTest`: offsets table, CRC vs `java.util.zip.CRC32`, single-byte CRC
  sensitivity across 0..251, slot alternation, 2^32 seq wrap, zeroed unused touch records).
- Pack generation is deterministic and reproducible from `control-pack-ref.json`.
- App compiles and packages (CI `assembleDebug`) — see build status.

**Not verified on device** (needs the Honor Magic 5 Pro + installed tModLoader):
- That SDL/Terraria accepts the synthesized WASD/mouse/keys in-game (existing RAL paths, but not
  exercised for Terraria here).
- Real 60 Hz cadence under game load and the 250 ms staleness threshold from the mod's side.
- That `/storage/emulated/0/RALauncher/ral_controls/state.bin` is readable by the mod process
  (same app UID / storage permission — RAL already writes `ControlPacks/` there, so expected OK).
- C#-side parsing of the file (Lead's `TMLMobileFeel`) — cross-checked against `bridge-spec.md`
  only, not against a running mod.
- Touch-vs-mod-drawing alignment on the real device aspect ratio (2848×1312 → u≈1.215); the
  anchor rule matches the Lead's described convention, but the per-element anchor assignment
  must be confirmed with the Lead's generated mod layout.
- Auto-fire feel (threshold 0.65), aim sensitivity mapping, hotbar tap-to-select.

## Open risks

1. **Anchor assignment** — resolved with the Lead: generator rule is now X nearest of
   `{0,1200,2400}` / Y nearest of `{0,540,1080}` (ties lower, no group overrides) and the 20
   authoritative `(cx, cy, size)` tuples are encoded as a regression check, so a ref-JSON drift
   or rule change fails the generator loudly instead of silently misaligning touch zones.
2. Escape is shared by INVENTORY and MENU (one desktop key), so the bridge reports only the
   INVENTORY bit for Escape presses.
3. Launcher-only synthetic keys are swallowed while the bridge runs: `PageUp`/`PageDown`
   (zoom), `Tab` (target lock), `F1` (`AIM_MODE` → bridge bit 9) and `F2` (`AUTO_SELECT` →
   bridge bit 10). A user pack that binds those keys for real desktop use would lose them during
   a TERRADOCK session.
4. Relative-mouse aim assumes FNA/SDL consumes relative motion for `Mouse.GetState`; if Terraria
   reads absolute position only, aim may need the absolute-position path (already implemented in
   RAL, one-line switch).
5. Bridge writes happen on the main thread each frame; measured cost should be well under 0.1 ms
   (256 B pwrite), but it is not measured on device yet.
6. Hotbar cells are invisible RAL buttons (opacity 0, `borderOpacity` 0.02 to dodge the legacy
   opacity-migration heuristic); they should be visually indistinguishable but exist in the
   editor if the user opens it.
