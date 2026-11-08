#!/usr/bin/env python3
"""TERRADOCK control pack generator.

Reads the frozen reference geometry (`C:\\Tmobile\\terradock\\control-pack-ref.json`,
2400x1080 design canvas, owned by the mock teammate) and writes the launcher's bundled
control pack asset:

    RotatingartLauncher/app/src/main/assets/terradock/control-pack.json

Coordinate convention (must match the TMLMobileFeel mod exactly, agreed with the Lead):
  u = surfaceHeightPx / 1080
  Each element is anchored to one of the nine reference anchor points
    TL=(0,0) TC=(1200,0) TR=(2400,0) CL=(0,540) C=(1200,540) CR=(2400,540)
    BL=(0,1080) BC=(1200,1080) BR=(2400,1080)      (all in reference units)
  Anchor rule used here (deterministic): take the nearest anchor independently on each
  axis (x -> 0 / 1200 / 2400, y -> 0 / 540 / 1080), ties resolved towards the centre.
  Runtime position (both sides):
    px = anchorPointPx + (cx - anchorRefX, cy - anchorRefY) * u
  Sizes scale by u as well.

The launcher later converts the reference geometry into RAL `ControlData` normalized
coordinates (0..1 of the real surface) in `TerradockControlPack.kt`.

Usage:
    python tools/terradock/gen_control_pack.py
    python tools/terradock/gen_control_pack.py --ref C:\\Tmobile\\terradock\\control-pack-ref.json
"""
import argparse
import json
import os
import sys
import zlib

REF_W, REF_H = 2400, 1080
ANCHOR_X = [0, 1200, 2400]          # L, C, R
ANCHOR_Y = [0, 540, 1080]           # T, C, B
X_LETTER = ["L", "C", "R"]
Y_LETTER = ["T", "C", "B"]

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
DEFAULT_REF = r"C:\Tmobile\terradock\control-pack-ref.json"
DEFAULT_OUT = os.path.join(REPO, "app", "src", "main", "assets", "terradock", "control-pack.json")

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

# 12 hotbar cells; tap -> digit quick-select keys 1..9,0 (Lead's convention, not bridge fields)
HOTBAR_KEYS = ["KEYBOARD_1", "KEYBOARD_2", "KEYBOARD_3", "KEYBOARD_4", "KEYBOARD_5",
               "KEYBOARD_6", "KEYBOARD_7", "KEYBOARD_8", "KEYBOARD_9", "KEYBOARD_0", "", ""]

# Lead's authoritative geometry (2400x1080 ref units) — regression check so launcher touch
# zones and mod-drawn controls stay locked together. id -> (cx, cy, size) where size is the
# button diameter or the stick radius.
EXPECTED_GEOMETRY = {
    "move":      (272, 860, 120),
    "aim":       (2128, 860, 120),
    "interact":  (109, 749, 137),
    "heal":      (435, 749, 137),
    "autoselect": (88, 869, 103),
    "mana":      (456, 869, 103),
    "autofire":  (120, 965, 103),
    "buff":      (424, 965, 103),
    "jump":      (2291, 749, 137),
    "grapple":   (1965, 749, 75),
    "use":       (1944, 869, 103),
    "mount":     (2312, 869, 103),
    "smart":     (2280, 965, 103),
    "target":    (1976, 965, 103),
    "zoomout":   (735, 1010, 96),
    "zoomin":    (1665, 1010, 96),
    "map":       (56, 232, 72),
    "bag":       (56, 318, 72),
    "gear":      (56, 404, 72),
    "pause":     (56, 490, 72),
}


def nearest(value, candidates):
    """Nearest candidate index; ties pick the LOWER value (Lead's rule)."""
    best_i, best_d = 0, None
    for i, c in enumerate(candidates):
        d = abs(value - c)
        if best_d is None or d < best_d - 1e-6:
            best_i, best_d = i, d
    return best_i


def anchor_for(cx, cy):
    ax = nearest(cx, ANCHOR_X)
    ay = nearest(cy, ANCHOR_Y)
    return Y_LETTER[ay] + X_LETTER[ax]   # e.g. "B" + "L" = "BL"


def offsets_for(anchor, cx, cy):
    """(ox, oy) in reference units relative to the anchor point (for diffing with the mod)."""
    ax = ANCHOR_X[X_LETTER.index(anchor[-1])]
    ay = ANCHOR_Y[Y_LETTER.index(anchor[0])]
    return cx - ax, cy - ay


def check_geometry(pack, expected):
    """Diff generated (cx, cy, size) against the Lead's authoritative geometry."""
    problems = []
    actual = {}
    for s in pack["sticks"]:
        actual[s["id"]] = (s["cx"], s["cy"], s["radius"])
    for b in pack["buttons"]:
        actual[b["id"]] = (b["cx"], b["cy"], b["size"])
    for key, exp in expected.items():
        act = actual.get(key)
        if act is None:
            problems.append("%s: missing in generated pack" % key)
        elif any(abs(a - e) > 0.01 for a, e in zip(act, exp)):
            problems.append("%s: (cx,cy,size)=%s != expected %s" % (key, act, exp))
    return problems


def build(ref):
    out = {
        "format": "terradock-control-pack",
        "version": 2,
        "name": "Terradock touch v2",
        "generatedBy": "tools/terradock/gen_control_pack.py",
        "source": "terradock/control-pack-ref.json",
        "note": ("Reference canvas 2400x1080. Runtime mapping: u = surfaceHeightPx/1080; "
                 "pos_px = anchorPointPx + (cx-anchorRef)*u; sizes scale by u. "
                 "Anchor rule: X nearest of {0,1200,2400}, Y nearest of {0,540,1080} "
                 "(ties pick the lower value). Y anchoring is cosmetic: positions and "
                 "offsets both scale by u, so only X anchoring changes anything on "
                 "non-2.222:1 aspect ratios."),
        "reference": {"w": REF_W, "h": REF_H},
        "sticks": [],
        "buttons": [],
        "hotbar": None,
    }

    sticks = ref.get("sticks", {})
    for stick_id in ("move", "aim"):
        s = sticks.get(stick_id)
        if not s:
            continue
        anchor = anchor_for(s.get("cx", 0), s.get("cy", 0))
        ox, oy = offsets_for(anchor, s.get("cx", 0), s.get("cy", 0))
        out["sticks"].append({
            "id": stick_id,
            "mode": stick_id,                      # "move" -> WASD, "aim" -> relative mouse
            "anchor": anchor,
            "cx": s.get("cx", 0),
            "cy": s.get("cy", 0),
            "ox": ox,
            "oy": oy,
            "radius": s.get("radius", 150),
            "deadzone": s.get("deadzone", 0.12),
        })

    def add_buttons(items):
        for b in items:
            action = b.get("action", "")
            if action not in ACTION_KEYS:
                print("  skip (no action mapping): %s %s" % (b.get("id"), action), file=sys.stderr)
                continue
            keycode, bridge_only = ACTION_KEYS[action]
            cx, cy = b.get("cx", 0), b.get("cy", 0)
            anchor = anchor_for(cx, cy)
            ox, oy = offsets_for(anchor, cx, cy)
            out["buttons"].append({
                "id": b.get("id", ""),
                "action": action,
                "anchor": anchor,
                "cx": cx,
                "cy": cy,
                "ox": ox,
                "oy": oy,
                "size": b.get("size", 100),
                "keycode": keycode,
                "label": b.get("label", b.get("id", "")),
                "bridgeOnly": bridge_only,
            })

    add_buttons(ref.get("buttons", []))
    add_buttons(ref.get("system", []))

    hb = ref.get("hotbar")
    if hb:
        cols = hb.get("cols", 6)
        rows = hb.get("rows", 2)
        cell = hb.get("cell", 150)
        gap = hb.get("gap", 8)
        x0 = hb.get("x", 12)
        y0 = hb.get("y", 12)
        # anchor the whole block by its centre
        block_cx = x0 + (cols * cell + (cols - 1) * gap) / 2.0
        block_cy = y0 + (rows * cell + (rows - 1) * gap) / 2.0
        out["hotbar"] = {
            "anchor": anchor_for(block_cx, block_cy),
            "x": x0, "y": y0,
            "cols": cols, "rows": rows,
            "cell": cell, "gap": gap,
            "keys": HOTBAR_KEYS[: cols * rows],
        }

    # ---- group (radial) controls: Terraria mobile VirtualControlsGroupConfiguration ----
    out["groups"] = []
    for g in ref.get("groups", []):
        cx, cy = g.get("cx", 0), g.get("cy", 0)
        anchor = anchor_for(cx, cy)
        ox, oy = offsets_for(anchor, cx, cy)
        out["groups"].append({
            "id": g.get("id", ""),
            "action": g.get("action", ""),
            "anchor": anchor,
            "cx": cx,
            "cy": cy,
            "ox": ox,
            "oy": oy,
            "centre": g.get("centre", 96),
            "sectors": [
                {
                    "action": s.get("action", ""),
                    "keycode": s.get("keycode", "UNKNOWN"),
                    "label": s.get("label", ""),
                }
                for s in g.get("sectors", [])
            ],
        })

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


def build_template_pack(style, profile):
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
        "reference": {"w": REF_W, "h": REF_H},
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
                ox, oy = offsets_for(anchor, cx, cy)
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
            ox, oy = offsets_for(anchor, cx, cy)
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
            ox, oy = offsets_for(anchor, cx, cy)
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
        ox, oy = offsets_for(anchor, cx, cy)
        out["buttons"].append({
            "id": slot.get("slotId", "").lower(), "action": action, "anchor": anchor,
            "cx": cx, "cy": cy, "ox": ox, "oy": oy,
            "size": SIZE_BY_ACTION.get(action_mobile, 103),
            "keycode": key, "label": action, "bridgeOnly": bridge_only,
        })

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
        tpl = build_template_pack(style, by_hw.get(sid))
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
    args = ap.parse_args()

    if not os.path.isfile(args.ref):
        print("reference not found: %s" % args.ref, file=sys.stderr)
        return 2
    with open(args.ref, "r", encoding="utf-8-sig") as f:
        ref = json.load(f)

    pack = build(ref)
    # 内容哈希做版本号：几何/按键/分组/设置一变，安装器就会自动重装（无需手工 bump）
    payload = {k: pack[k] for k in ("reference", "sticks", "buttons", "hotbar", "groups", "settings")}
    pack["version"] = zlib.crc32(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ) & 0x7FFFFFFF
    pack["versionKind"] = "content-hash (crc32 of reference/sticks/buttons/hotbar/groups/settings)"

    print("\nMobile template packs (imported control customisation):")
    write_template_packs(pack)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print("wrote %s" % args.out)
    print("  version=%d (content hash)  sticks=%d buttons=%d groups=%d settings=%d hotbarCells=%s" % (
        pack["version"], len(pack["sticks"]), len(pack["buttons"]), len(pack.get("groups", [])),
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

    problems = check_geometry(pack, EXPECTED_GEOMETRY)
    if problems:
        print("\nGEOMETRY MISMATCH vs Lead's table:", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        return 1
    print("geometry matches Lead's table (%d elements)" % len(EXPECTED_GEOMETRY))
    return 0


if __name__ == "__main__":
    sys.exit(main())
