#!/usr/bin/env python3
"""Validate 15x16 atlas geometry and CP932 encoded-byte physical ordering.

The 15x16 geometry is already supported by device screenshots. The previous
non-ASCII ordinal prediction incorrectly used metrics.toml's textual/numeric key
order. Two-byte metric keys are byte-reversed identifiers (E4 44 -> 0x44e4), so
numeric key order groups by the second CP932 byte and cannot be assumed to be the
atlas order.

This probe reconstructs each key's actual CP932 byte sequence, sorts by those
encoded bytes, checks the known ASCII anchors, then emits CJK slot candidates.
CJK still requires an on-device visual crop check before any write is enabled.
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

EXPECTED_ASCII_ORDINALS = {
    0x0030: 17,  # 0
    0x0041: 33,  # A
    0x0061: 64,  # a
}

TARGETS = {
    "0": 0x0030,
    "A": 0x0041,
    "a": 0x0061,
    "ア": 0x4183,  # CP932 83 41
    "イ": 0x4383,  # CP932 83 43
    "テ": 0x6583,  # CP932 83 65
    "ム": 0x8083,  # CP932 83 80
    "surrogate_아_腑": 0x44E4,  # CP932 E4 44
    "surrogate_이_躙": 0x57E7,  # CP932 E7 57
    "surrogate_템_綺": 0x59E3,  # CP932 E3 59
}


def parse_keys(path: Path) -> list[int]:
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


def cp932_bytes_for_key(key: int) -> bytes:
    if key <= 0xFF:
        return bytes([key])
    return bytes([key & 0xFF, (key >> 8) & 0xFF])


def slot_for_ordinal(ordinal: int) -> dict[str, int]:
    page = ordinal // SLOTS_PER_PAGE
    local = ordinal % SLOTS_PER_PAGE
    row = local // COLS
    col = local % COLS
    return {
        "page": page,
        "local_slot": local,
        "row": row,
        "col": col,
        "x": col * SLOT_W,
        "y": row * SLOT_H,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("metrics", type=Path)
    ap.add_argument("--json", type=Path)
    args = ap.parse_args()

    keys = parse_keys(args.metrics)
    ordered = sorted(keys, key=cp932_bytes_for_key)
    positions = {key: i for i, key in enumerate(ordered)}

    anchors = []
    for key, expected in EXPECTED_ASCII_ORDINALS.items():
        ordinal = positions.get(key)
        anchors.append({
            "key": f"0x{key:04x}",
            "char": chr(key),
            "encoded_hex": cp932_bytes_for_key(key).hex(" ").upper(),
            "ordinal": ordinal,
            "expected_ordinal": expected,
            "match": ordinal == expected,
            "slot": slot_for_ordinal(ordinal) if ordinal is not None else None,
        })
    passed = all(a["match"] for a in anchors)

    targets = {}
    for label, key in TARGETS.items():
        ordinal = positions.get(key)
        targets[label] = {
            "key": f"0x{key:04x}",
            "encoded_hex": cp932_bytes_for_key(key).hex(" ").upper(),
            "present": ordinal is not None,
            "cp932_order_ordinal": ordinal,
            "slot_prediction": slot_for_ordinal(ordinal) if passed and ordinal is not None else None,
        }

    report = {
        "metrics_glyphs": len(keys),
        "ordering": "actual CP932 encoded bytes, lexicographic unsigned order",
        "geometry": {
            "slot_width": SLOT_W,
            "slot_height": SLOT_H,
            "texture_width": WIDTH,
            "texture_height": HEIGHT,
            "columns": COLS,
            "rows": ROWS,
            "slots_per_page": SLOTS_PER_PAGE,
            "unused_right_edge_pixels": WIDTH - COLS * SLOT_W,
        },
        "ascii_anchor_validation_pass": passed,
        "anchors": anchors,
        "targets": targets,
        "interpretation": (
            "ASCII PASS validates the ordering rule only for the known anchors. "
            "Kana and surrogate kanji crops must visibly match on device before physical CJK slots are trusted."
        ),
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.json:
        args.json.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
