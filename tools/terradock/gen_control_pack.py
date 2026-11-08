#!/usr/bin/env python3
"""TERRADOCK control pack generator.

Reads the frozen reference geometry (`C:\\Tmobile\\terradock\\control-pack-ref.json`, schema v3,
1920x1080 reference frame = the real Terraria Mobile 1.4.5 HUD as measured at 1080p) and writes
the launcher's bundled control pack asset:

    RotatingartLauncher/app/src/main/assets/terradock/control-pack.json

Coordinate convention (must match the TMLMobileFeel mod exactly):
  u = surfaceHeightPx / 1080
  Each element is anchored to one of the nine reference anchor points
    TL=(0,0) TC=(960,0) TR=(1920,0) CL=(0,540) C=(960,540) CR=(1920,540)
    BL=(0,1080) BC=(960,1080) BR=(1920,1080)      (all in reference units)
  Runtime position (both sides):
    px = anchorPointPx + (cx - anchorRefX, cy - anchorRefY) * u
  Sizes scale by u as well.

VISUAL OWNERSHIP (2026-11-11): the TERRADOCK default pack installs INVISIBLE touch zones
(`opacityOverride: 0`); the TMLMobileFeel mod draws the native mobile HUD/controls from the same
reference geometry. That removes double-drawing (translucent stick discs would darken otherwise) and
guarantees the launcher hit-areas coincide with what the mod draws. Set `--visible` to install the
legacy visible RAL-styled controls instead (debug only).

Usage:
    python tools/terradock/gen_control_pack.py
    python tools/terradock/gen_control_pack.py --ref C:\\Tmobile\\terradock\\control-pack-ref.json
"""
import argparse
import json
import os
import sys
import zlib

# Default pack reference frame: the measured 1920x1080 (16:9) mobile HUD frame == 1080p units.
REF_W, REF_H = 1920, 1080
# Mobile template packs (decoded from the APK) keep the original 2400x1080 calibration canvas.
TEMPLATE_REF_W, TEMPLATE_REF_H = 2400, 1080
ANCHOR_X = [0, 1200, 2400]          # L, C, R   (template canvas)
ANCHOR_Y = [0, 540, 1080]           # T, C, B
X_LETTER = ["L", "C", "R"]
Y_LETTER = ["T", "C", "B"]

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
DEFAULT_REF = r"C:\Tmobile\terradock\control-pack-ref.json"
DEFAULT_OUT = os.path.join(REPO, "app", "src", "main", "assets", "terradock", "control-pack.json")

# Anchor name (reference JSON uses long names) -> RAL short code used by TerradockControlPack.mapPoint
ANCHOR_CODES = {
    "topleft": "TL", "topcenter": "TC", "topright": "TR",
    "centerleft": "CL", "center": "C", "centerright": "CR",
    "bottomleft": "BL", "bottomcenter": "BC", "bottomright": "BR",
    "tl": "TL", "tc": "TC", "tr": "TR", "cl": "CL", "c": "C", "cr": "CR",
    "bl": "BL", "bc": "BC", "br": "BR",
}


def anchor_code(anchor, cx=None, cy=None):
    """Reference anchor -> RAL short code (falls back to nearest-anchor rule on the 1920 frame)."""
    if isinstance(anchor, str) and anchor.strip().lower() in ANCHOR_CODES:
        return ANCHOR_CODES[anchor.strip().lower()]
    if cx is None or cy is None:
        return "BL"
    # nearest of 0 / REF_W/2 / REF_W and 0 / REF_H/2 / REF_H
    ax = min((0.0, REF_W / 2.0, float(REF_W)), key=lambda v: abs(cx - v))
    ay = min((0.0, REF_H / 2.0, float(REF_H)), key=lambda v: abs(cy - v))
    xl = "L" if ax == 0.0 else ("C" if ax == REF_W / 2.0 else "R")
    yl = "T" if ay == 0.0 else ("C" if ay == REF_H / 2.0 else "B")
    return yl + xl

# action -> desktop key synthesized by the launcher (bridge-spec.md action table)
#   keycode   = ControlData.KeyCode name forwarded to SDL
#   bridgeOnly= launcher-only action: reported on the bridge, not forwarded to the desktop game
ACTION_KEYS = {
    "JUMP":         ("KEYBOARD_SPACE",    False),
    "INTERACT":     ("MOUSE_RIGHT",       False),
    "FIRE":         ("MOUSE_LEFT",        False),
    "HEAL":         ("KEYBOARD_H",        False),
    "MANA":         ("KEYBOARD_J",        False),
    "BUFF":         ("KEYBOARD_B",        False),
    "MOUNT":        ("KEYBOARD_R",        False),
    "GRAPPLE":      ("KEYBOARD_E",        False),
    "SMART_CURSOR": ("KEYBOARD_LSHIFT",   False),
    "MAP":          ("KEYBOARD_M",        False),
    "INVENTORY":    ("KEYBOARD_ESCAPE",   False),
    "MENU":         ("KEYBOARD_ESCAPE",   False),
    # no desktop binding in Terraria desktop -> mod applies camera zoom from the bridge
    "ZOOM_IN":      ("KEYBOARD_PAGEUP",   True),
    "ZOOM_OUT":     ("KEYBOARD_PAGEDOWN", True),
    "TARGET_LOCK":  ("KEYBOARD_TAB",      True),
    # launcher-only synthetic keys (swallowed, never forwarded to the desktop game):
    #   F1 -> bridge bit 9  AUTO_FIRE   (hold = launcher holds LMB; mod reads AIM_MODE as ACTION 9)
    #   F2 -> bridge bit 10 AUTO_SELECT (auto-select hotbar item; mod-side behaviour)
    "AIM_MODE":     ("KEYBOARD_F1",       True),
    "AUTO_SELECT":  ("KEYBOARD_F2",       True),
    # ---- imported from Terraria mobile (terradock/control-system/mobile-actions.json) ----
    # bridge v2 bits 23..30. Keys marked True are swallowed launcher-side (reported on the
    # bridge only); CHAT is forwarded because Enter opens the game's chat.
    "HOTBAR_NEXT":  ("MOUSE_WHEEL_DOWN",  False),
    "HOTBAR_PREV":  ("MOUSE_WHEEL_UP",    False),
    "PAGE_TOGGLE":  ("KEYBOARD_F3",       True),
    "CHAT":         ("KEYBOARD_RETURN",   False),
    # mobile Settings == the game's pause/settings overlay on desktop -> forward Escape
    "SETTINGS":     ("KEYBOARD_ESCAPE",   False),
    "MAP_ZOOM_IN":  ("KEYBOARD_EQUALS",   True),
    "MAP_ZOOM_OUT": ("KEYBOARD_MINUS",    True),
    "LOADOUT_CYCLE":("KEYBOARD_F5",       True),
    "EMOTE":        ("KEYBOARD_F6",       True),
}

# 10 hotbar touch cells (2 cols x 5 rows, row-major) -> digit quick-select keys 1..9,0
HOTBAR_KEYS = ["KEYBOARD_1", "KEYBOARD_2", "KEYBOARD_3", "KEYBOARD_4", "KEYBOARD_5",
               "KEYBOARD_6", "KEYBOARD_7", "KEYBOARD_8", "KEYBOARD_9", "KEYBOARD_0"]


def nearest(value, candidates):
    """Nearest candidate index; ties pick the LOWER value (Lead's rule)."""
    best_i, best_d = 0, None
    for i, c in enumerate(candidates):
        d = abs(value - c)
        if best_d is None or d < best_d - 1e-6:
            best_i, best_d = i, d
    return best_i


def anchor_for(cx, cy):
    """Template (2400x1080) nearest-anchor rule."""
    ax = nearest(cx, ANCHOR_X)
    ay = nearest(cy, ANCHOR_Y)
    return Y_LETTER[ay] + X_LETTER[ax]   # e.g. "B" + "L" = "BL"


def offsets_for(anchor, cx, cy, ref_w=REF_W, ref_h=REF_H):
    """(ox, oy) in reference units relative to the anchor point (for diffing with the mod)."""
    ax = 0.0 if anchor.endswith("L") else (ref_w if anchor.endswith("R") else ref_w / 2.0)
    ay = 0.0 if anchor.startswith("T") else (ref_h if anchor.startswith("B") else ref_h / 2.0)
    return cx - ax, cy - ay


def check_generated(pack, ref):
    """Sanity: generated control set/geometry equals the reference JSON (no silent drift)."""
    problems = []
    want = {}
    for group in ("system", "buttons", "bottom"):
        for b in ref.get(group, []):
            want[b["id"]] = (float(b["cx"]), float(b["cy"]), float(b["size"]))
    got = {b["id"]: (b["cx"], b["cy"], b["size"]) for b in pack["buttons"]}
    for key, exp in want.items():
        act = got.get(key)
        if act is None:
            problems.append("%s: missing in generated pack" % key)
        elif any(abs(a - e) > 0.01 for a, e in zip(act, exp)):
            problems.append("%s: (cx,cy,size)=%s != ref %s" % (key, act, exp))
    for sid, spec in ref.get("sticks", {}).items():
        s = next((s for s in pack["sticks"] if s["id"] == sid), None)
        if s is None:
            problems.append("stick %s missing" % sid)
        elif abs(s["radius"] - float(spec["radius"])) > 0.01:
            problems.append("stick %s radius %s != ref %s" % (sid, s["radius"], spec["radius"]))
    return problems


def build(ref, visible=False):
    overlay_opacity = -1.0 if visible else 0.0
    out = {
        "format": "terradock-control-pack",
        "version": 3,
        "name": "Terradock touch v3 (Terraria Mobile HUD)",
        "generatedBy": "tools/terradock/gen_control_pack.py",
        "source": "terradock/control-pack-ref.json (schema v3, 1920x1080 mobile HUD frame)",
        "note": ("Reference frame 1920x1080 (= Terraria Mobile HUD as measured at 1080p). "
                 "Runtime mapping: u = surfaceHeightPx/1080; pos_px = anchorPointPx + "
                 "(cx-anchorRef)*u; sizes scale by u. Visuals are owned by the TMLMobileFeel "
                 "mod (native mobile art); this pack installs transparent touch zones that "
                 "coincide with the mod's drawing."),
        "overlay": "visible" if visible else "invisible (mod draws the HUD)",
        "reference": {"w": REF_W, "h": REF_H},
        "sticks": [],
        "buttons": [],
        "hotbar": None,
    }

    for stick_id in ("move", "aim"):
        s = (ref.get("sticks") or {}).get(stick_id)
        if not s:
            continue
        cx, cy = float(s.get("cx", 0)), float(s.get("cy", 0))
        anchor = anchor_code(s.get("anchor"), cx, cy)
        ox, oy = offsets_for(anchor, cx, cy)
        out["sticks"].append({
            "id": stick_id,
            "mode": stick_id,                      # "move" -> WASD, "aim" -> relative mouse
            "anchor": anchor,
            "cx": cx, "cy": cy, "ox": ox, "oy": oy,
            "radius": float(s.get("radius", 152)),
            "deadzone": float(s.get("deadzone", 0.12)),
            "opacityOverride": overlay_opacity,
        })

    def add_buttons(items):
        for b in items:
            action = b.get("action", "")
            if action not in ACTION_KEYS:
                print("  skip (no action mapping): %s %s" % (b.get("id"), action), file=sys.stderr)
                continue
            keycode, bridge_only = ACTION_KEYS[action]
            cx, cy = float(b.get("cx", 0)), float(b.get("cy", 0))
            anchor = anchor_code(b.get("anchor"), cx, cy)
            ox, oy = offsets_for(anchor, cx, cy)
            shape = "RECTANGLE" if str(b.get("shape", "")).upper().startswith("RECT") else "CIRCLE"
            out["buttons"].append({
                "id": b.get("id", ""),
                "action": action,
                "anchor": anchor,
                "cx": cx, "cy": cy, "ox": ox, "oy": oy,
                "size": float(b.get("size", 100)),
                "keycode": keycode,
                "label": b.get("label") or str(b.get("id", "")).upper(),
                "bridgeOnly": bridge_only,
                "shape": shape,
                "opacityOverride": overlay_opacity,
            })

    add_buttons(ref.get("system", []))
    add_buttons(ref.get("buttons", []))
    add_buttons(ref.get("bottom", []))

    hb = ref.get("hotbar")
    if hb:
        cols = int(hb.get("cols", 2))
        rows = int(hb.get("rows", 5))
        cell = float(hb.get("cell", 84))
        gap = float(hb.get("gap", 12))
        x0 = float(hb.get("x", 102))
        y0 = float(hb.get("y", 6))
        block_cx = x0 + (cols * cell + (cols - 1) * gap) / 2.0
        block_cy = y0 + (rows * cell + (rows - 1) * gap) / 2.0
        # Hotbar tapping is independent of the visual hotbar: transparent hit zones synthesizing
        # digit keys 1..0 (the mobile hotbar itself is drawn by the mod).
        out["hotbar"] = {
            "anchor": anchor_code(hb.get("anchor"), block_cx, block_cy),
            "x": x0, "y": y0,
            "cols": cols, "rows": rows,
            "cell": cell, "gap": gap,
            "keys": HOTBAR_KEYS[: cols * rows],
        }

    # ---- group (radial) controls: real mobile HUD has none by default ----
    out["groups"] = []

    # ---- mobile settings defaults imported from the APK (see terradock/control-system) ----
    out["settings"] = load_settings_defaults()
    out["templates"] = load_templates()

    return out


SETTINGS_JSON = r"C:\Tmobile\terradock\control-system\mobile-settings.json"
ACTIONS_JSON = r"C:\Tmobile\terradock\control-system\mobile-actions.json"


def load_settings_defaults():
    """{settingId: default} for rows whose shipped default was recovered from the APK."""
    if not os.path.isfile(SETTINGS_JSON):
        return {}
    try:
        with open(SETTINGS_JSON, "r", encoding="utf-8-sig") as f:
            data = json.load(f)
    except Exception as e:
        print("  warn: cannot read %s: %s" % (SETTINGS_JSON, e), file=sys.stderr)
        return {}
    return {
        row["id"]: row["default"]
        for row in data.get("settings", [])
        if row.get("default") is not None
    }


def load_templates():
    path = r"C:\Tmobile\terradock\control-system\mobile-settings.json"
    if not os.path.isfile(path):
        return []
    try:
        with open(path, "r", encoding="utf-8-sig") as f:
            data = json.load(f)
    except Exception:
        return []
    return data.get("presets", {}).get("templates", [])


# =====================================================================================
# Template (hardware style) packs — imported from Terraria mobile
# `VirtualControlsStyles_Layout`: 12 HardwareStyles + 7 Profiles.
#
# Coordinate model, calibrated against the decoded default layout (error <= 0.5 px):
#     ref_canvas_pos = stick_centre_ref + slot.Location * 1.875
# where stick_centre_ref is (272,860) for LeftStick and (2128,860) for RightStick and
# 1.875 is the mobile-UI -> 2400x1080 reference scale used everywhere else.
# =====================================================================================
STYLES_JSON = r"C:\Tmobile\terradock\control-system\mobile-styles.json"
STYLES_RAW = r"C:\Tmobile\terradock\control-system\_styles_decoded_raw.json"
STICK_CENTRES = {"LeftStick": (272.0, 860.0), "RightStick": (2128.0, 860.0)}
LOCATION_SCALE = 1.875
SIZE_BY_ACTION = {"Interact": 137, "Jump": 137, "Fire": 103, "Heal": 137, "Mana": 103,
                  "Buff": 103, "Mount": 103, "SmartCursor": 103, "CombatTargetting": 103,
                  "AutoFire": 103, "AutoSelect": 103, "GrappleFire": 75,
                  "ZoomIn": 96, "ZoomOut": 96}
TEMPLATE_OUT_DIR = os.path.join(REPO, "app", "src", "main", "assets", "terradock")


def load_styles():
    """Return (hardwareStyles, profiles) decoded from the APK UILayout asset, or (None, None)."""
    profiles_path = r"C:\Tmobile\terradock\control-system\mobile-profiles.json"
    if os.path.isfile(STYLES_JSON):
        try:
            with open(STYLES_JSON, "r", encoding="utf-8-sig") as f:
                d = json.load(f)
            if d.get("hardwareStyles"):
                profiles = d.get("profiles", [])
                if not profiles and os.path.isfile(profiles_path):
                    with open(profiles_path, "r", encoding="utf-8-sig") as pf:
                        profiles = json.load(pf).get("profiles", [])
                return d["hardwareStyles"], profiles
        except Exception:
            pass
    if os.path.isfile(STYLES_RAW):
        try:
            with open(STYLES_RAW, "r", encoding="utf-8-sig") as f:
                d = json.load(f).get("data", {})
            hw = [
                {
                    "id": h.get("Id", ""),
                    "name": h.get("EditedName") or h.get("Id", ""),
                    "slots": [
                        {
                            "slotId": s.get("SlotId", ""),
                            "controlType": (s.get("Style") or {}).get("ControlType", ""),
                            "styleId": (s.get("Style") or {}).get("ControlTypeId", ""),
                            "defaultControl": s.get("DefaultControl", ""),
                            "defaultGroupControl": s.get("DefaultGroupControl", []),
                            "anchorControl": s.get("AnchorControl", ""),
                            "anchor": s.get("Anchor", ""),
                            "location": s.get("Location", [0, 0]),
                            "multiTouch": bool(s.get("MultiTouch")),
                        }
                        for s in h.get("Slots", [])
                    ],
                }
                for h in d.get("HardwareStyles", [])
            ]
            pf = [
                {
                    "id": p.get("Id", ""),
                    "name": p.get("EditedName") or p.get("Id", ""),
                    "hardwareStyle": p.get("Hardware", ""),
                    "bindings": [
                        {
                            "slotId": c.get("SlotId", ""),
                            "action": c.get("Action", ""),
                            "groupActions": c.get("GroupActions", []),
                        }
                        for c in p.get("Controls", [])
                    ],
                }
                for p in d.get("Profiles", [])
            ]
            return hw, pf
        except Exception as e:
            print("  warn: cannot read %s: %s" % (STYLES_RAW, e), file=sys.stderr)
    return None, None


def ref_point(anchor_control, loc):
    base = STICK_CENTRES.get(anchor_control)
    if not base or not loc:
        return None
    return base[0] + loc[0] * LOCATION_SCALE, base[1] + loc[1] * LOCATION_SCALE


def build_template_pack(style, profile, default_hotbar=None):
    """One pack per mobile hardware style, with profile bindings applied."""
    bind = {}
    group_bind = {}
    if profile:
        for b in profile.get("bindings", []):
            bind[b.get("slotId", "")] = b.get("action", "")
            if b.get("groupActions"):
                group_bind[b.get("slotId", "")] = b["groupActions"]

    out = {
        "format": "terradock-control-pack",
        "version": 2,
        "name": "Terradock " + (style.get("name") or style.get("id", "")),
        "generatedBy": "tools/terradock/gen_control_pack.py --templates",
        "source": "terradock/control-system (mobile UILayout VirtualControlsStyles_Layout)",
        "reference": {"w": TEMPLATE_REF_W, "h": TEMPLATE_REF_H},
        "templateId": style.get("id", ""),
        "hardwareStyle": style.get("id", ""),
        "profile": profile.get("id", "") if profile else "",
        "sticks": [],
        "buttons": [],
        "groups": [],
        "hotbar": None,
    }

    for slot in style.get("slots", []):
        ct = slot.get("controlType", "")
        action_mobile = bind.get(slot.get("slotId", "")) or slot.get("defaultControl", "")
        loc = slot.get("location") or [0, 0]
        pt = ref_point(slot.get("anchorControl", ""), loc)
        if pt is None:
            continue
        cx, cy = pt

        if ct == "Axis":
            if action_mobile in ("Move", "Aim"):
                role = "move" if action_mobile == "Move" else "aim"
                anchor = anchor_for(cx, cy)
                ox, oy = offsets_for(anchor, cx, cy, TEMPLATE_REF_W, TEMPLATE_REF_H)
                out["sticks"].append({
                    "id": role, "mode": role, "anchor": anchor, "cx": cx, "cy": cy,
                    "ox": ox, "oy": oy, "radius": 120, "deadzone": 0.12,
                })
                continue
            # other axes are drawn as a compact button in the port
            action = ACTION_ALIASES.get(action_mobile)
            if not action:
                continue
            key, bridge_only = ACTION_KEYS.get(action, ("UNKNOWN", True))
            anchor = anchor_for(cx, cy)
            ox, oy = offsets_for(anchor, cx, cy, TEMPLATE_REF_W, TEMPLATE_REF_H)
            out["buttons"].append({
                "id": "axis_" + slot.get("slotId", ""), "action": action, "anchor": anchor,
                "cx": cx, "cy": cy, "ox": ox, "oy": oy, "size": SIZE_BY_ACTION.get(action_mobile, 75),
                "keycode": key, "label": action, "bridgeOnly": bridge_only,
            })
            continue

        if ct == "Group":
            sectors = group_bind.get(slot.get("slotId", "")) or slot.get("defaultGroupControl", [])
            secs = []
            for s in sectors[:4]:
                if not s or s == "Count":
                    continue
                act = ACTION_ALIASES.get(s)
                if not act:
                    continue
                key, _ = ACTION_KEYS.get(act, ("UNKNOWN", True))
                secs.append({"action": act, "keycode": key, "label": act})
            if not secs:
                continue
            anchor = anchor_for(cx, cy)
            ox, oy = offsets_for(anchor, cx, cy, TEMPLATE_REF_W, TEMPLATE_REF_H)
            out["groups"].append({
                "id": "group_" + slot.get("slotId", ""), "action": "", "anchor": anchor,
                "cx": cx, "cy": cy, "ox": ox, "oy": oy, "centre": 96, "sectors": secs,
            })
            continue

        # Button
        action = ACTION_ALIASES.get(action_mobile)
        if not action:
            continue
        key, bridge_only = ACTION_KEYS.get(action, ("UNKNOWN", True))
        if key == "UNKNOWN":
            continue
        anchor = anchor_for(cx, cy)
        ox, oy = offsets_for(anchor, cx, cy, TEMPLATE_REF_W, TEMPLATE_REF_H)
        out["buttons"].append({
            "id": slot.get("slotId", "").lower(), "action": action, "anchor": anchor,
            "cx": cx, "cy": cy, "ox": ox, "oy": oy,
            "size": SIZE_BY_ACTION.get(action_mobile, 103),
            "keycode": key, "label": action, "bridgeOnly": bridge_only,
        })

    # Hotbar touch zones are independent of the mobile hardware style (the mobile client draws the
    # hotbar as normal UI, not as a virtual control). Without inheriting them a template pack would
    # silently lose hotbar tapping in the port, so every template gets the same zones as the default.
    if default_hotbar:
        out["hotbar"] = default_hotbar

    # Port fallback: a hardware style without an Aim axis (e.g. Simple, Angryhippo) would leave the
    # player unable to move the cursor. Mobile covers that with tap-to-aim; desktop cannot, so add
    # the standard aim stick and say so in the pack.
    have_aim = any(s.get("mode") == "aim" for s in out["sticks"])
    have_move = any(s.get("mode") == "move" for s in out["sticks"])
    if not have_move:
        out["sticks"].append({"id": "move", "mode": "move", "anchor": "BL", "cx": 272, "cy": 860,
                              "ox": 272, "oy": -220, "radius": 120, "deadzone": 0.12})
    if not have_aim:
        out["sticks"].append({"id": "aim", "mode": "aim", "anchor": "BR", "cx": 2128, "cy": 860,
                              "ox": -272, "oy": -220, "radius": 120, "deadzone": 0.12})
        out["note"] = ("This mobile hardware style ships without an Aim axis; TERRADOCK adds the "
                       "standard aim stick so the cursor can be moved on desktop.")
    return out


def write_template_packs(pack):
    """Emit one `control-pack-tpl-<style>.json` per mobile hardware style."""
    styles, profiles = load_styles()
    if not styles:
        print("  (no decoded mobile styles found; skipping template packs)")
        return 0
    by_hw = {p.get("hardwareStyle", ""): p for p in profiles}
    written = 0
    for style in styles:
        sid = style.get("id", "")
        if not sid:
            continue
        tpl = build_template_pack(style, by_hw.get(sid), pack.get("hotbar"))
        payload = {k: tpl[k] for k in ("reference", "sticks", "buttons", "groups")}
        tpl["version"] = zlib.crc32(
            json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ) & 0x7FFFFFFF
        tpl["versionKind"] = "content-hash (crc32 of reference/sticks/buttons/groups)"
        path = os.path.join(TEMPLATE_OUT_DIR, "control-pack-tpl-%s.json" % sid.lower())
        with open(path, "w", encoding="utf-8") as f:
            json.dump(tpl, f, indent=2, ensure_ascii=False)
            f.write("\n")
        written += 1
        print("  template %-10s -> %s (%d buttons, %d groups, %d sticks)" % (
            sid, os.path.basename(path), len(tpl["buttons"]), len(tpl["groups"]), len(tpl["sticks"])))
    # expose the profile/template catalogue in the main pack
    pack["profiles"] = [
        {"id": p.get("id", ""), "name": p.get("name", ""), "hardwareStyle": p.get("hardwareStyle", "")}
        for p in profiles
    ]
    pack["hardwareStyles"] = [s.get("id", "") for s in styles]
    print("  catalogue: %d hardware styles, %d profiles" % (len(styles), len(profiles)))
    return written


# Terraria mobile ContolType (serialized layout enum) -> pack action id
ACTION_ALIASES = {
    "Jump": "JUMP", "Heal": "HEAL", "Mana": "MANA", "Buff": "BUFF", "Mount": "MOUNT",
    "Interact": "INTERACT", "InteractOnly": "INTERACT", "Fire": "FIRE",
    "GrappleFire": "GRAPPLE", "AutoSelect": "AUTO_SELECT", "ZoomIn": "ZOOM_IN",
    "ZoomOut": "ZOOM_OUT", "SmartCursor": "SMART_CURSOR", "AutoFire": "AIM_MODE",
    "CombatTargetting": "TARGET_LOCK", "ProfileSwitch": "PROFILE_SWITCH",
    "InventoryToggle": "INVENTORY", "Settings": "SETTINGS", "Loadout1": "LOADOUT",
    "Loadout2": "LOADOUT", "Loadout3": "LOADOUT", "LoadoutCycle": "LOADOUT_CYCLE",
    "ToggleFireInteract": "FIRE",
}


def main():
    ap = argparse.ArgumentParser(description="Generate the TERRADOCK launcher control pack asset")
    ap.add_argument("--ref", default=DEFAULT_REF, help="control-pack-ref.json path")
    ap.add_argument("--out", default=DEFAULT_OUT, help="output control-pack.json path")
    ap.add_argument("--visible", action="store_true",
                    help="install the legacy VISIBLE RAL-styled controls (debug); default = transparent "
                         "touch layer because the mod draws the native mobile HUD")
    args = ap.parse_args()

    if not os.path.isfile(args.ref):
        print("reference not found: %s" % args.ref, file=sys.stderr)
        return 2
    with open(args.ref, "r", encoding="utf-8-sig") as f:
        ref = json.load(f)

    pack = build(ref, visible=args.visible)
    # 内容哈希做版本号：几何/按键/分组/设置/不透明度一变，安装器就会自动重装（无需手工 bump）
    payload = {k: pack.get(k) for k in ("reference", "sticks", "buttons", "hotbar", "groups", "settings", "overlay")}
    pack["version"] = zlib.crc32(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ) & 0x7FFFFFFF
    pack["versionKind"] = "content-hash (crc32 of reference/sticks/buttons/hotbar/groups/settings/overlay)"

    print("\nMobile template packs (imported control customisation):")
    write_template_packs(pack)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print("wrote %s" % args.out)
    print("  overlay=%s version=%d (content hash)  sticks=%d buttons=%d groups=%d settings=%d hotbarCells=%s" % (
        pack.get("overlay"), pack["version"], len(pack["sticks"]), len(pack["buttons"]), len(pack.get("groups", [])),
        len(pack.get("settings", {})),
        len(pack["hotbar"]["keys"]) if pack["hotbar"] else 0))
    for g in pack.get("groups", []):
        print("  group  %-8s %-14s anchor=%-2s cx=%-4s cy=%-4s sectors=%d [%s]" % (
            g["id"], g["action"], g["anchor"], g["cx"], g["cy"], len(g["sectors"]),
            ", ".join(s["action"] or "?" for s in g["sectors"])))
    if pack.get("settings"):
        print("  settings %s" % ", ".join("%s=%s" % (k, v) for k, v in sorted(pack["settings"].items())))
    for s in pack["sticks"]:
        print("  stick %-5s anchor=%-2s cx=%-4s cy=%-4s off=(%5s,%5s) r=%s" % (
            s["id"], s["anchor"], s["cx"], s["cy"], s["ox"], s["oy"], s["radius"]))
    for b in pack["buttons"]:
        print("  button %-10s %-12s anchor=%-2s cx=%-4s cy=%-4s off=(%5s,%5s) size=%-3s key=%-16s bridgeOnly=%s" % (
            b["id"], b["action"], b["anchor"], b["cx"], b["cy"], b["ox"], b["oy"],
            b["size"], b["keycode"], b["bridgeOnly"]))
    if pack["hotbar"]:
        hb = pack["hotbar"]
        print("  hotbar anchor=%s rect=(%s,%s) cols=%s rows=%s cell=%s gap=%s keys=%d" % (
            hb["anchor"], hb["x"], hb["y"], hb["cols"], hb["rows"], hb["cell"], hb["gap"],
            len([k for k in hb["keys"] if k])))

    problems = check_generated(pack, ref)
    if problems:
        print("\nGEOMETRY MISMATCH vs control-pack-ref.json:", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        return 1
    print("geometry matches control-pack-ref.json (%d buttons, %d sticks)" % (
        len(pack["buttons"]), len(pack["sticks"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
