#!/usr/bin/env python3
"""Render the public authenticated zillfont XOR delta under texture hypotheses.

Only the public XOR delta is consumed. No retail font bytes are required or
written. The output is diagnostic PGM masks showing *which pixels changed* if
the 0x20000-byte tail of each verified PAR section is interpreted as a
512x512 4bpp texture, both linear and PSP-style 16-byte x 8-row unswizzled.

These are hypotheses, not format claims. The masks are useful because a nonzero
XOR nibble means that pixel changed even though the original retail pixel is
unknown.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import zlib
from pathlib import Path

EXPECTED_PATCH_SHA256 = "fcc46f805a970050d61b16ea00458731f1d56737fb04b0e04080f76c21465d89"
EXPECTED_XOR_SHA256 = "7a48a683e523c07f641b9a70396555ce16d69ecccccc6fc6edbea50edd622aac"
EXPECTED_SIZE = 525_424
PAR_SECTION_STARTS = (0x0000C0, 0x0201B0, 0x0402A0, 0x060390)
WIDTH = 512
HEIGHT = 512
WIDTH_BYTES_4BPP = WIDTH // 2
PAYLOAD_SIZE = WIDTH_BYTES_4BPP * HEIGHT  # 0x20000


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def save_pgm(path: Path, width: int, height: int, pixels: bytes) -> None:
    if len(pixels) != width * height:
        raise ValueError("pixel length mismatch")
    path.write_bytes(f"P5\n{width} {height}\n255\n".encode("ascii") + pixels)


def byte_mask_to_4bpp_pixel_mask(data: bytes) -> bytes:
    pixels = bytearray(len(data) * 2)
    j = 0
    for value in data:
        pixels[j] = 255 if (value >> 4) & 0x0F else 0
        pixels[j + 1] = 255 if value & 0x0F else 0
        j += 2
    return bytes(pixels)


def psp_unswizzle_bytes(swizzled: bytes, width_bytes: int, height: int) -> bytes:
    """Common PSP texture unswizzle: 16-byte-wide by 8-row blocks."""
    if width_bytes % 16:
        raise ValueError("width_bytes must be divisible by 16")
    if height % 8:
        raise ValueError("height must be divisible by 8")
    expected = width_bytes * height
    if len(swizzled) != expected:
        raise ValueError(f"swizzled payload size mismatch: {len(swizzled)} != {expected}")

    row_blocks = width_bytes // 16
    out = bytearray(expected)
    for y in range(height):
        block_y = y // 8
        in_y = y & 7
        for x_byte in range(width_bytes):
            block_x = x_byte // 16
            in_x = x_byte & 15
            src = ((block_y * row_blocks + block_x) * 128) + (in_y * 16) + in_x
            out[y * width_bytes + x_byte] = swizzled[src]
    return bytes(out)


def changed_coords(mask: bytes, width: int):
    return [(i % width, i // width) for i, value in enumerate(mask) if value]


def bounding_box_from_coords(coords):
    if not coords:
        return None
    xs = [x for x, _ in coords]
    ys = [y for _, y in coords]
    return [min(xs), min(ys), max(xs), max(ys)]


def cell_counts_from_coords(coords, cell: int, phase_x: int = 0, phase_y: int = 0):
    counts = {}
    for x, y in coords:
        cx = (x - phase_x) // cell
        cy = (y - phase_y) // cell
        key = (cx, cy)
        counts[key] = counts.get(key, 0) + 1
    return counts


def grid_phase_summary(coords, cell: int):
    best = None
    for py in range(cell):
        for px in range(cell):
            counts = cell_counts_from_coords(coords, cell, px, py)
            occupied = len(counts)
            # Prefer fewer occupied cells, then more concentration in the top cells.
            top_sum = sum(sorted(counts.values(), reverse=True)[:64])
            score = (occupied, -top_sum, py, px)
            if best is None or score < best[0]:
                best = (score, px, py, counts)
    assert best is not None
    _, px, py, counts = best
    top = sorted(counts.items(), key=lambda kv: kv[1], reverse=True)[:32]
    return {
        "cell": cell,
        "best_phase_x": px,
        "best_phase_y": py,
        "occupied_cells": len(counts),
        "top_cells": [
            {"cell_x": key[0], "cell_y": key[1], "changed_pixels": value}
            for key, value in top
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("zpatch", type=Path)
    parser.add_argument("--outdir", type=Path, default=Path("font-delta-atlas"))
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()

    compressed = args.zpatch.read_bytes()
    if sha256(compressed) != EXPECTED_PATCH_SHA256:
        raise SystemExit("compressed patch SHA-256 mismatch")
    expanded = zlib.decompress(compressed)
    if len(expanded) != EXPECTED_SIZE:
        raise SystemExit(f"expanded XOR size mismatch: {len(expanded)}")
    if sha256(expanded) != EXPECTED_XOR_SHA256:
        raise SystemExit("expanded XOR SHA-256 mismatch")

    args.outdir.mkdir(parents=True, exist_ok=True)
    report = {
        "authenticated": True,
        "hypothesis": "tail 0x20000 bytes of each verified PAR section are a 512x512 4bpp texture",
        "psp_unswizzle_hypothesis": "16-byte x 8-row blocks",
        "sections": [],
        "warnings": [
            "images are XOR change masks, not reconstructed retail or English glyph images",
            "512x512 4bpp and PSP swizzle interpretations remain hypotheses until validated against inner headers/runtime",
        ],
    }

    for index, start in enumerate(PAR_SECTION_STARTS):
        end = PAR_SECTION_STARTS[index + 1] if index + 1 < len(PAR_SECTION_STARTS) else len(expanded)
        section = expanded[start:end]
        if len(section) < PAYLOAD_SIZE:
            raise SystemExit(f"section {index} too small for 0x20000 candidate payload")
        prefix = len(section) - PAYLOAD_SIZE
        payload = section[prefix:]

        linear_mask = byte_mask_to_4bpp_pixel_mask(payload)
        unswizzled_bytes = psp_unswizzle_bytes(payload, WIDTH_BYTES_4BPP, HEIGHT)
        unswizzled_mask = byte_mask_to_4bpp_pixel_mask(unswizzled_bytes)
        coords = changed_coords(unswizzled_mask, WIDTH)

        linear_path = args.outdir / f"section{index}-linear-512x512-4bpp-mask.pgm"
        unswizzled_path = args.outdir / f"section{index}-unswizzled-512x512-4bpp-mask.pgm"
        save_pgm(linear_path, WIDTH, HEIGHT, linear_mask)
        save_pgm(unswizzled_path, WIDTH, HEIGHT, unswizzled_mask)

        changed_bytes = sum(1 for value in payload if value)
        changed_pixels = len(coords)
        grids = [grid_phase_summary(coords, cell) for cell in (8, 12, 16, 20, 24, 32)] if changed_pixels else []

        report["sections"].append({
            "index": index,
            "section_start": start,
            "section_end": end,
            "section_size": len(section),
            "candidate_prefix_size": prefix,
            "payload_size": len(payload),
            "changed_payload_bytes": changed_bytes,
            "changed_pixels_under_4bpp": changed_pixels,
            "unswizzled_changed_bbox": bounding_box_from_coords(coords),
            "grid_phase_hints": grids,
            "linear_mask": str(linear_path),
            "unswizzled_mask": str(unswizzled_path),
        })

    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.json:
        args.json.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
