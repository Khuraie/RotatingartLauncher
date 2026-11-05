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
}

# 12 hotbar cells; tap -> digit quick-select keys 1..9,0 (Lead's convention, not bridge fields)
HOTBAR_KEYS = ["KEYBOARD_1", "KEYBOARD_2", "KEYBOARD_3", "KEYBOARD_4", "KEYBOARD_5",
               "KEYBOARD_6", "KEYBOARD_7", "KEYBOARD_8", "KEYBOARD_9", "KEYBOARD_0", "", ""]


def nearest(value, candidates):
    """Nearest candidate index; ties resolve towards the centre (index 1)."""
    best_i, best_d = 0, None
    for i, c in enumerate(candidates):
        d = abs(value - c)
        if best_d is None or d < best_d - 1e-6 or (abs(d - best_d) <= 1e-6 and i == 1):
            best_i, best_d = i, d
    return best_i


def anchor_for(cx, cy):
    ax = nearest(cx, ANCHOR_X)
    ay = nearest(cy, ANCHOR_Y)
    return Y_LETTER[ay] + X_LETTER[ax]   # e.g. "B" + "L" = "BL"


def build(ref):
    out = {
        "format": "terradock-control-pack",
        "version": 2,
        "name": "Terradock touch v2",
        "generatedBy": "tools/terradock/gen_control_pack.py",
        "source": "terradock/control-pack-ref.json",
        "note": ("Reference canvas 2400x1080. Runtime mapping: u = surfaceHeightPx/1080; "
                 "pos_px = anchorPointPx + (cx-anchorRef)*u; sizes scale by u. "
                 "Anchor rule: nearest of the 9 anchor points per axis."),
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
        out["sticks"].append({
            "id": stick_id,
            "mode": stick_id,                      # "move" -> WASD, "aim" -> relative mouse
            "anchor": anchor_for(s.get("cx", 0), s.get("cy", 0)),
            "cx": s.get("cx", 0),
            "cy": s.get("cy", 0),
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
            out["buttons"].append({
                "id": b.get("id", ""),
                "action": action,
                "anchor": anchor_for(b.get("cx", 0), b.get("cy", 0)),
                "cx": b.get("cx", 0),
                "cy": b.get("cy", 0),
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

    return out


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
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(pack, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print("wrote %s" % args.out)
    print("  sticks=%d buttons=%d hotbarCells=%s" % (
        len(pack["sticks"]), len(pack["buttons"]),
        len(pack["hotbar"]["keys"]) if pack["hotbar"] else 0))
    for s in pack["sticks"]:
        print("  stick %-4s anchor=%-2s cx=%s cy=%s r=%s" % (s["id"], s["anchor"], s["cx"], s["cy"], s["radius"]))
    for b in pack["buttons"]:
        print("  button %-8s %-12s anchor=%-2s key=%-16s bridgeOnly=%s" % (
            b["id"], b["action"], b["anchor"], b["keycode"], b["bridgeOnly"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
