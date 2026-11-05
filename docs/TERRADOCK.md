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
  Re-installed when the asset `version` increases; a user's own pack is never overridden.
- Coordinate mapping (identical to the mod, agreed with Lead):
  `u = surfaceHeightPx / 1080`; each element is anchored to one of 9 reference anchor points;
  `pos_px = anchorPointPx + (ref - anchorRef) * u`; sizes scale by `u`.
  Anchor per element is stored explicitly in the asset (`"anchor": "BL"` …) and computed by the
  generator with a nearest-anchor-per-axis rule. X-anchoring matters on aspect ratios ≠ 2.222:1
  (right-anchored controls stick to the right edge); Y-anchoring is mathematically identical to
  plain scaling because both positions and offsets use `u = sh/1080`.
- Hotbar: 10 invisible touch cells over the 6×2 hotbar area synthesize digit keys `1..9,0`
  (tap-to-select, per Lead convention — hotbar state is **not** in the bridge). Cells 10–11 are
  inert (no control created, taps fall through).
- Pack contents: 2 sticks (move→WASD, aim→relative mouse), 12 quick buttons, 4 system buttons,
  10 hotbar cells = 28 controls. Bars/buffs/cursor are mod-drawn only.

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
| UP / DOWN | bridge bits 18/19 | derived from move stick (W/S) |
| AUTO_FIRE | bridge bit 9 + `flags.bit5` | aim stick beyond 0.65 in Aim & Use mode |

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
- This change: see the run URL in the task/PR comment (recorded in the commit message region).
  Unit tests (`TerradockBridgeFormatTest`) are **not** executed by that workflow (it only runs
  `assembleDebug`); they are compile-checked only if someone runs `testDebugUnitTest`.

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

1. **Anchor assignment (X axis)** — the reference JSON has no anchor field; this repo computes it
   with "nearest of 0/1200/2400 per axis". If the Lead's mod generator uses a different rule,
   right/centre-side controls will be offset by up to ~30–70 px on the phone's aspect ratio.
   Fixable in `tools/terradock/gen_control_pack.py` (one rule) + regenerate + reinstall.
2. Escape is shared by INVENTORY and MENU (one desktop key), so the bridge reports only the
   INVENTORY bit for Escape presses.
3. ZOOM/TARGET_LOCK keys (PageUp/PageDown/Tab) are swallowed while the bridge runs so they are
   not forwarded to the desktop game. A user pack that binds those keys for real desktop use
   would lose them during a TERRADOCK session.
4. Relative-mouse aim assumes FNA/SDL consumes relative motion for `Mouse.GetState`; if Terraria
   reads absolute position only, aim may need the absolute-position path (already implemented in
   RAL, one-line switch).
5. Bridge writes happen on the main thread each frame; measured cost should be well under 0.1 ms
   (256 B pwrite), but it is not measured on device yet.
6. Hotbar cells are invisible RAL buttons (opacity 0, `borderOpacity` 0.02 to dodge the legacy
   opacity-migration heuristic); they should be visually indistinguishable but exist in the
   editor if the user opens it.
