#!/usr/bin/env python3
"""Analyze HK47196/zill's frozen zillfont XOR delta without retail assets.

The .zpatch is zlib-compressed and expands to an XOR stream the same size as
font/zillfont.par. This script reports non-zero runs and alignment patterns so
we can infer likely font-atlas/table regions before touching a retail ISO.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import zlib
from pathlib import Path

EXPECTED_EXPANDED_SIZE = 525_424
EXPECTED_PATCH_SHA256 = "fcc46f805a970050d61b16ea00458731f1d56737fb04b0e04080f76c21465d89"
EXPECTED_XOR_SHA256 = "7a48a683e523c07f641b9a70396555ce16d69ecccccc6fc6edbea50edd622aac"


def runs(data: bytes):
    start = None
    for i, value in enumerate(data):
        if value and start is None:
            start = i
        elif not value and start is not None:
            yield start, i
            start = None
    if start is not None:
        yield start, len(data)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("zpatch", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()

    compressed = args.zpatch.read_bytes()
    patch_sha = hashlib.sha256(compressed).hexdigest()
    if patch_sha != EXPECTED_PATCH_SHA256:
        raise SystemExit(f"unexpected compressed patch sha256: {patch_sha}")

    expanded = zlib.decompress(compressed)
    xor_sha = hashlib.sha256(expanded).hexdigest()
    if len(expanded) != EXPECTED_EXPANDED_SIZE:
        raise SystemExit(f"unexpected expanded size: {len(expanded)}")
    if xor_sha != EXPECTED_XOR_SHA256:
        raise SystemExit(f"unexpected XOR sha256: {xor_sha}")

    nonzero_runs = list(runs(expanded))
    changed = sum(end - start for start, end in nonzero_runs)
    longest = sorted(nonzero_runs, key=lambda r: r[1] - r[0], reverse=True)[:40]

    block_stats = {}
    for block_size in (4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 256, 512, 1024, 2048, 4096):
        touched = {
            i // block_size
            for i, value in enumerate(expanded)
            if value
        }
        block_stats[str(block_size)] = {
            "touched_blocks": len(touched),
            "total_blocks": (len(expanded) + block_size - 1) // block_size,
        }

    report = {
        "compressed_size": len(compressed),
        "expanded_size": len(expanded),
        "changed_bytes": changed,
        "changed_ratio": changed / len(expanded),
        "run_count": len(nonzero_runs),
        "longest_runs": [
            {"start": start, "end": end, "length": end - start}
            for start, end in longest
        ],
        "block_stats": block_stats,
    }

    print(json.dumps(report, indent=2))
    if args.json:
        args.json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
