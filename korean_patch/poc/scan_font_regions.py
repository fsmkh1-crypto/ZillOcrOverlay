#!/usr/bin/env python3
"""Visualize candidate bitmap regions in authenticated zillfont.par pairs.

Inputs are the exact retail zillfont.par and the reconstructed English result.
This scanner does not map CP932 keys to physical indices. Instead it searches
regions that actually changed in the English font migration and emits PGM
views under several packing hypotheses so recognizable ASCII glyph structure
can be found before any surrogate slot is touched.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

EXPECTED_SIZE = 525_424
EXPECTED_SOURCE_SHA256 = "0d3d6d2648870e87a01636cdfc7cc7af8100ea40b71e5ed05f82ac197606584a"
EXPECTED_RESULT_SHA256 = "0f11ca53076e072408fb3eb9ffa29446b02fb97642f4173b559691c463a2fdb8"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def save_pgm(path: Path, width: int, height: int, pixels: bytes) -> None:
    path.write_bytes(f"P5\n{width} {height}\n255\n".encode("ascii") + pixels)


def decode_1bpp(data: bytes, width: int, height: int, lsb_first: bool) -> bytes:
    out = bytearray(width * height)
    for p in range(width * height):
        bi = p >> 3
        if bi >= len(data):
            break
        bit = p & 7
        shift = bit if lsb_first else 7 - bit
        out[p] = 255 if ((data[bi] >> shift) & 1) else 0
    return bytes(out)


def decode_2bpp(data: bytes, width: int, height: int, lsb_first: bool) -> bytes:
    out = bytearray(width * height)
    for p in range(width * height):
        bi = p >> 2
        if bi >= len(data):
            break
        q = p & 3
        shift = (q * 2) if lsb_first else ((3 - q) * 2)
        out[p] = ((data[bi] >> shift) & 3) * 85
    return bytes(out)


def decode_4bpp(data: bytes, width: int, height: int, low_nibble_first: bool) -> bytes:
    out = bytearray(width * height)
    for p in range(width * height):
        bi = p >> 1
        if bi >= len(data):
            break
        low = (p & 1) == 0
        if not low_nibble_first:
            low = not low
        value = (data[bi] & 0x0F) if low else ((data[bi] >> 4) & 0x0F)
        out[p] = value * 17
    return bytes(out)


def changed_runs(a: bytes, b: bytes, merge_gap: int = 16):
    indices = [i for i, (x, y) in enumerate(zip(a, b)) if x != y]
    if not indices:
        return []
    runs = []
    start = prev = indices[0]
    for i in indices[1:]:
        if i - prev > merge_gap:
            runs.append((start, prev + 1))
            start = i
        prev = i
    runs.append((start, prev + 1))
    return runs


def specs():
    for w, h in ((8, 8), (8, 12), (8, 16), (12, 12), (12, 16), (16, 16), (16, 24), (20, 20), (24, 24), (32, 32)):
        for bpp in (1, 2, 4):
            bits = w * h * bpp
            if bits % 8 == 0:
                yield w, h, bpp, bits // 8


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("retail", type=Path)
    p.add_argument("english", type=Path)
    p.add_argument("--outdir", type=Path, default=Path("font-region-scan"))
    p.add_argument("--max-regions", type=int, default=40)
    p.add_argument("--context", type=int, default=256)
    args = p.parse_args()

    retail = args.retail.read_bytes()
    english = args.english.read_bytes()
    if len(retail) != EXPECTED_SIZE or len(english) != EXPECTED_SIZE:
        raise SystemExit("unexpected zillfont.par size")
    if sha256(retail) != EXPECTED_SOURCE_SHA256:
        raise SystemExit("retail SHA-256 mismatch")
    if sha256(english) != EXPECTED_RESULT_SHA256:
        raise SystemExit("English result SHA-256 mismatch")

    args.outdir.mkdir(parents=True, exist_ok=True)
    runs = changed_runs(retail, english)
    runs.sort(key=lambda r: r[1] - r[0], reverse=True)

    index_lines = [
        "# zillfont changed-region visual scan",
        "# No CP932->physical-index assumption is made.",
        f"changed regions (merged gap<=16): {len(runs)}",
        "",
    ]

    for ri, (start, end) in enumerate(runs[: args.max_regions]):
        lo = max(0, start - args.context)
        hi = min(len(english), end + args.context)
        region = english[lo:hi]
        index_lines.append(f"region {ri:03d}: changed=0x{start:X}-0x{end:X} context=0x{lo:X}-0x{hi:X}")

        for w, h, bpp, cell_bytes in specs():
            # Test every alignment within one cell. This is intentionally
            # brute-force for tiny changed regions; visual confirmation is the gate.
            for align in range(min(cell_bytes, 32)):
                pos = lo + align
                if pos + cell_bytes > hi:
                    break
                chunk = english[pos : pos + cell_bytes]
                if bpp == 1:
                    variants = (("msb", decode_1bpp(chunk, w, h, False)), ("lsb", decode_1bpp(chunk, w, h, True)))
                elif bpp == 2:
                    variants = (("msb", decode_2bpp(chunk, w, h, False)), ("lsb", decode_2bpp(chunk, w, h, True)))
                else:
                    variants = (("high", decode_4bpp(chunk, w, h, False)), ("low", decode_4bpp(chunk, w, h, True)))
                for order, pixels in variants:
                    # Skip completely blank/solid guesses.
                    unique = len(set(pixels))
                    if unique < 2:
                        continue
                    name = f"r{ri:03d}_o{pos:06X}_{w}x{h}_{bpp}bpp_{order}.pgm"
                    save_pgm(args.outdir / name, w, h, pixels)

    (args.outdir / "INDEX.txt").write_text("\n".join(index_lines) + "\n", encoding="utf-8")
    print(f"wrote scan to {args.outdir}")
    print("Open PGM files and look for recognizable Latin glyph fragments or repeated cell structure.")


if __name__ == "__main__":
    main()
