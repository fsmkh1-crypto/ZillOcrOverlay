#!/usr/bin/env python3
"""Test a 15x16 metric-slot atlas geometry against device-observed ASCII anchors.

The previous 16x16/32-column interpretation treated each metrics.toml ordinal as a
16-pixel cell and appeared inconsistent. The user's exact GIM screenshot instead
shows a drift pattern that is explained exactly by 15-pixel-wide glyph slots:
512 / 15 = 34 full slots (510 px) per row, 32 rows at 16 px height.

This probe is deliberately narrow. It does not claim that textual metrics order is
physical order merely because ordinals exist. It requires several independently
observed ASCII glyphs to land in the same 16x16 pseudo-cells shown by the earlier
device probe before it emits non-ASCII slot predictions.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

WIDTH = 512
HEIGHT = 512
SLOT_W = 15
SLOT_H = 16
COLS = WIDTH // SLOT_W          # 34; 2 pixels unused at row end
ROWS = HEIGHT // SLOT_H        # 32
SLOTS_PER_PAGE = COLS * ROWS   # 1088
KEY_RE = re.compile(r'^"(0x[0-9A-Fa-f]{4})"\s*=')

# Empirical anchors read directly from the user's PoC 0.6 screenshot.  These are
# the labels of the old 16x16/32-column diagnostic crops in which the glyph is
# visibly centered/contained. They are evidence only, not generated assumptions.
OBSERVED_PSEUDO_CELLS = {
    0x0029: 9,   # ')'
    0x0030: 16,  # '0'
    0x003A: 25,  # ':'
    0x0041: 31,  # 'A'
    0x005C: 56,  # '\\'
    0x0061: 60,  # 'a'
}

TARGETS = {
    "0": 0x0030,
    "A": 0x0041,
    "a": 0x0061,
    "ア": 0x4183,
    "イ": 0x4383,
    "テ": 0x6583,
    "ム": 0x8083,
    "surrogate_아_腑": 0x44E4,
    "surrogate_이_躙": 0x57E7,
    "surrogate_템_綺": 0x59E3,
}


def parse_metric_order(path: Path) -> list[int]:
    result: list[int] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = KEY_RE.match(line.strip())
        if m:
            result.append(int(m.group(1), 16))
    if len(result) != 2637:
        raise SystemExit(f"unexpected metrics glyph count: {len(result)}")
    if len(set(result)) != len(result):
        raise SystemExit("duplicate metrics glyph keys")
    return result


def slot_for_ordinal(ordinal: int) -> dict[str, int]:
    page = ordinal // SLOTS_PER_PAGE
    local = ordinal % SLOTS_PER_PAGE
    row = local // COLS
    col = local % COLS
    x = col * SLOT_W
    y = row * SLOT_H
    # Map the center of the proposed 15x16 slot back into the old 16x16/32-col
    # diagnostic coordinate system, which is what the screenshots label.
    center_x = x + SLOT_W // 2
    center_y = y + SLOT_H // 2
    pseudo_col = center_x // 16
    pseudo_row = center_y // 16
    pseudo_cell = pseudo_row * 32 + pseudo_col
    return {
        "page": page,
        "local_slot": local,
        "row": row,
        "col": col,
        "x": x,
        "y": y,
        "pseudo_16x16_cell_by_center": pseudo_cell,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("metrics", type=Path)
    ap.add_argument("--json", type=Path)
    args = ap.parse_args()

    order = parse_metric_order(args.metrics)
    positions = {key: i for i, key in enumerate(order)}

    anchors = []
    for key, observed in OBSERVED_PSEUDO_CELLS.items():
        ordinal = positions.get(key)
        if ordinal is None:
            anchors.append({"key": f"0x{key:04x}", "present": False, "observed": observed})
            continue
        slot = slot_for_ordinal(ordinal)
        anchors.append({
            "key": f"0x{key:04x}",
            "char": bytes([key]).decode("ascii") if key < 0x80 else None,
            "present": True,
            "metrics_ordinal": ordinal,
            "observed_pseudo_cell": observed,
            "predicted_pseudo_cell": slot["pseudo_16x16_cell_by_center"],
            "match": slot["pseudo_16x16_cell_by_center"] == observed,
            "slot": slot,
        })

    passed = len(anchors) == len(OBSERVED_PSEUDO_CELLS) and all(a.get("match") for a in anchors)

    targets = {}
    for label, key in TARGETS.items():
        ordinal = positions.get(key)
        if ordinal is None:
            targets[label] = {"key": f"0x{key:04x}", "present": False}
            continue
        targets[label] = {
            "key": f"0x{key:04x}",
            "present": True,
            "metrics_ordinal": ordinal,
            "slot_prediction": slot_for_ordinal(ordinal) if passed else None,
        }

    report = {
        "metrics_glyphs": len(order),
        "geometry_hypothesis": {
            "slot_width": SLOT_W,
            "slot_height": SLOT_H,
            "texture_width": WIDTH,
            "texture_height": HEIGHT,
            "columns": COLS,
            "rows": ROWS,
            "slots_per_page": SLOTS_PER_PAGE,
            "unused_right_edge_pixels": WIDTH - COLS * SLOT_W,
        },
        "device_anchor_validation_pass": passed,
        "anchors": anchors,
        "targets": targets,
        "interpretation": (
            "PASS means the 15x16/34-column geometry reproduces every recorded ASCII screenshot anchor. "
            "It supports, but does not by itself prove, that metrics textual order is the complete physical glyph order; "
            "the next on-device probe must crop the exact 15x16 predicted slots and visually validate 0/A/a before surrogate writes are enabled."
        ),
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.json:
        args.json.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
