#!/usr/bin/env python3
"""Test whether metrics.toml textual order plausibly matches 16x16 atlas cell order.

Consumes only public upstream files: metrics.toml and the authenticated zillfont XOR delta.
No retail font bytes are read. The check is deliberately conservative: it reports
ordinals for known keys and measures XOR activity in the corresponding 16x16 cells.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import zlib
from pathlib import Path

EXPECTED_PATCH_SHA256 = "fcc46f805a970050d61b16ea00458731f1d56737fb04b0e04080f76c21465d89"
EXPECTED_XOR_SHA256 = "7a48a683e523c07f641b9a70396555ce16d69ecccccc6fc6edbea50edd622aac"
EXPECTED_SIZE = 525_424
SECTION_STARTS = (0x0000C0, 0x0201B0, 0x0402A0, 0x060390)
WIDTH = HEIGHT = 512
CELL = 16
CELLS_PER_ROW = WIDTH // CELL
CELLS_PER_PAGE = CELLS_PER_ROW * (HEIGHT // CELL)
PAYLOAD_SIZE = (WIDTH // 2) * HEIGHT
KEY_RE = re.compile(r'^"(0x[0-9A-Fa-f]{4})"\s*=')

TARGETS = {
    "space": 0x0020,
    "0": 0x0030,
    "A": 0x0041,
    "a": 0x0061,
    "surrogate_아": 0x44E4,
    "surrogate_이": 0x57E7,
    "surrogate_템": 0x59E3,
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_metric_order(path: Path) -> list[int]:
    result: list[int] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = KEY_RE.match(line.strip())
        if m:
            result.append(int(m.group(1), 16))
    if len(result) != 2637:
        raise SystemExit(f"unexpected metrics glyph count: {len(result)}")
    return result


def unswizzle_4bpp_mask(payload: bytes) -> bytes:
    width_bytes = WIDTH // 2
    out = bytearray(len(payload))
    row_blocks = width_bytes // 16
    for y in range(HEIGHT):
        block_y = y // 8
        in_y = y & 7
        for x_byte in range(width_bytes):
            block_x = x_byte // 16
            in_x = x_byte & 15
            src = ((block_y * row_blocks + block_x) * 128) + (in_y * 16) + in_x
            out[y * width_bytes + x_byte] = payload[src]
    pixels = bytearray(WIDTH * HEIGHT)
    p = 0
    for value in out:
        pixels[p] = 1 if (value & 0x0F) else 0
        pixels[p + 1] = 1 if ((value >> 4) & 0x0F) else 0
        p += 2
    return bytes(pixels)


def cell_activity(mask: bytes, cell_index: int) -> int:
    x0 = (cell_index % CELLS_PER_ROW) * CELL
    y0 = (cell_index // CELLS_PER_ROW) * CELL
    total = 0
    for y in range(y0, y0 + CELL):
        row = y * WIDTH
        total += sum(mask[row + x0: row + x0 + CELL])
    return total


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("metrics", type=Path)
    ap.add_argument("zpatch", type=Path)
    ap.add_argument("--json", type=Path)
    args = ap.parse_args()

    order = parse_metric_order(args.metrics)
    compressed = args.zpatch.read_bytes()
    if sha256(compressed) != EXPECTED_PATCH_SHA256:
        raise SystemExit("compressed patch SHA-256 mismatch")
    expanded = zlib.decompress(compressed)
    if len(expanded) != EXPECTED_SIZE or sha256(expanded) != EXPECTED_XOR_SHA256:
        raise SystemExit("expanded XOR authentication failed")

    masks = []
    for i, start in enumerate(SECTION_STARTS):
        end = SECTION_STARTS[i + 1] if i + 1 < len(SECTION_STARTS) else len(expanded)
        section = expanded[start:end]
        if len(section) < PAYLOAD_SIZE:
            raise SystemExit(f"section {i} too small")
        masks.append(unswizzle_4bpp_mask(section[-PAYLOAD_SIZE:]))

    positions = {key: i for i, key in enumerate(order)}
    targets = {}
    for label, key in TARGETS.items():
        ordinal = positions.get(key)
        if ordinal is None:
            targets[label] = {"key": f"0x{key:04x}", "present": False}
            continue
        page = ordinal // CELLS_PER_PAGE
        cell = ordinal % CELLS_PER_PAGE
        targets[label] = {
            "key": f"0x{key:04x}",
            "present": True,
            "ordinal": ordinal,
            "page_if_text_order_is_physical": page,
            "cell_index": cell,
            "cell_x": cell % CELLS_PER_ROW,
            "cell_y": cell // CELLS_PER_ROW,
            "xor_changed_pixels_in_that_cell": cell_activity(masks[page], cell) if page < len(masks) else None,
        }

    # A simple sanity score: under the hypothesis, printable ASCII entries should
    # occupy early cells and many should be touched by the English font patch.
    ascii_rows = []
    touched = 0
    total = 0
    for key in range(0x20, 0x7F):
        ordinal = positions.get(key)
        if ordinal is None:
            continue
        page = ordinal // CELLS_PER_PAGE
        cell = ordinal % CELLS_PER_PAGE
        activity = cell_activity(masks[page], cell) if page < len(masks) else 0
        ascii_rows.append({"key": f"0x{key:04x}", "ordinal": ordinal, "page": page, "cell": cell, "activity": activity})
        total += 1
        if activity:
            touched += 1

    report = {
        "metrics_glyphs": len(order),
        "grid_hypothesis": "metrics.toml textual ordinal -> 16x16 row-major cells, 1024 cells per 512x512 page",
        "printable_ascii_present": total,
        "printable_ascii_cells_touched_by_english_xor": touched,
        "printable_ascii_touch_ratio": (touched / total) if total else 0.0,
        "targets": targets,
        "ascii": ascii_rows,
        "warning": "A strong ASCII correlation supports but does not alone prove physical ordering; candidate crops must be visually verified from retail on-device.",
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.json:
        args.json.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
