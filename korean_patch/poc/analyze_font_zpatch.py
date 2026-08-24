#!/usr/bin/env python3
"""Analyze HK47196/zill's frozen zillfont XOR delta conservatively.

This tool deliberately does *not* assume that metrics.toml sort order equals
physical glyph order, nor that bitmap data begins at EOF - glyph_count*stride.
It authenticates the upstream frozen XOR patch, reports changed regions and
alignment/periodicity hints, and (when a retail zillfont.par is supplied) also
reconstructs the authenticated English result for downstream structural scans.

The PAR section starts below were verified on-device from the authenticated
ULJM05410 v1.03 retail font header. They are used only to partition the public
XOR delta; they are not treated as glyph or bitmap boundaries.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
import zlib
from pathlib import Path

EXPECTED_SIZE = 525_424
EXPECTED_SOURCE_SHA256 = "0d3d6d2648870e87a01636cdfc7cc7af8100ea40b71e5ed05f82ac197606584a"
EXPECTED_RESULT_SHA256 = "0f11ca53076e072408fb3eb9ffa29446b02fb97642f4173b559691c463a2fdb8"
EXPECTED_PATCH_SHA256 = "fcc46f805a970050d61b16ea00458731f1d56737fb04b0e04080f76c21465d89"
EXPECTED_XOR_SHA256 = "7a48a683e523c07f641b9a70396555ce16d69ecccccc6fc6edbea50edd622aac"

# Verified from the authenticated retail PAR header:
# PAR\0, version=2, count=4, unknown=1, starts below.
PAR_SECTION_STARTS = (0x0000C0, 0x0201B0, 0x0402A0, 0x060390)
SHIFT_CANDIDATES = (8, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96, 128, 160, 192, 256, 320, 384, 512)
CANONICAL_PAYLOAD = 0x20000
CANONICAL_PAYLOAD_GEOMETRIES = [
    {"width": 512, "height": 512, "bpp": 4},
    {"width": 512, "height": 256, "bpp": 8},
    {"width": 256, "height": 512, "bpp": 8},
    {"width": 256, "height": 256, "bpp": 16},
]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def nonzero_runs(data: bytes):
    start = None
    for i, value in enumerate(data):
        if value and start is None:
            start = i
        elif not value and start is not None:
            yield start, i
            start = None
    if start is not None:
        yield start, len(data)


def gcd_of_deltas(starts: list[int]) -> int:
    if len(starts) < 2:
        return 0
    g = 0
    for a, b in zip(starts, starts[1:]):
        d = b - a
        if d:
            g = math.gcd(g, d)
    return g


def alignment_histogram(starts: list[int], moduli=(2, 4, 8, 16, 32, 64, 128, 256)):
    result = {}
    for m in moduli:
        counts = [0] * m
        for s in starts:
            counts[s % m] += 1
        ranked = sorted(enumerate(counts), key=lambda x: x[1], reverse=True)[:8]
        result[str(m)] = [{"remainder": r, "count": c} for r, c in ranked if c]
    return result


def block_touch_stats(data: bytes, sizes=(4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256, 512, 1024, 2048, 4096)):
    changed_indices = [i for i, v in enumerate(data) if v]
    out = {}
    for size in sizes:
        touched = {i // size for i in changed_indices}
        total = (len(data) + size - 1) // size
        out[str(size)] = {
            "touched_blocks": len(touched),
            "total_blocks": total,
            "coverage": len(touched) / total,
        }
    return out


def section_analysis(data: bytes):
    sections = []
    relative_changed_sets = []

    for index, start in enumerate(PAR_SECTION_STARTS):
        end = PAR_SECTION_STARTS[index + 1] if index + 1 < len(PAR_SECTION_STARTS) else len(data)
        section = data[start:end]
        runs = list(nonzero_runs(section))
        changed_positions = {i for i, value in enumerate(section) if value}
        relative_changed_sets.append(changed_positions)
        lengths = [e - s for s, e in runs]

        prefix_candidate = len(section) - CANONICAL_PAYLOAD if len(section) >= CANONICAL_PAYLOAD else None
        prefix_100_changed = sum(1 for pos in changed_positions if pos < 0x100)
        prefix_candidate_changed = (
            sum(1 for pos in changed_positions if pos < prefix_candidate)
            if prefix_candidate is not None and prefix_candidate >= 0
            else None
        )
        payload_candidate_changed = (
            sum(1 for pos in changed_positions if pos >= prefix_candidate)
            if prefix_candidate is not None and prefix_candidate >= 0
            else None
        )

        sections.append({
            "index": index,
            "start": start,
            "end": end,
            "size": end - start,
            "changed_bytes": len(changed_positions),
            "changed_ratio": len(changed_positions) / len(section),
            "run_count": len(runs),
            "max_run": max(lengths) if lengths else 0,
            "median_run": statistics.median(lengths) if lengths else 0,
            "first_changed_relative": min(changed_positions) if changed_positions else None,
            "last_changed_relative": max(changed_positions) if changed_positions else None,
            "changed_in_first_0x100": prefix_100_changed,
            "canonical_0x20000_payload_hypothesis": {
                "payload_bytes": CANONICAL_PAYLOAD,
                "prefix_bytes_if_payload_is_tail": prefix_candidate,
                "changed_in_prefix": prefix_candidate_changed,
                "changed_in_payload": payload_candidate_changed,
                "candidate_geometries": CANONICAL_PAYLOAD_GEOMETRIES,
                "status": "heuristic only; must be validated from inner section header/pixels",
            },
            "start_alignment": alignment_histogram([s for s, _ in runs], moduli=(8, 16, 32, 64)),
            "relative_mod16_changed_bytes": [
                sum(1 for pos in changed_positions if pos % 16 == remainder)
                for remainder in range(16)
            ],
        })

    pairwise = []
    for i in range(len(relative_changed_sets)):
        for j in range(i + 1, len(relative_changed_sets)):
            a = relative_changed_sets[i]
            b = relative_changed_sets[j]
            intersection = len(a & b)
            union = len(a | b)
            pairwise.append({
                "left": i,
                "right": j,
                "intersection": intersection,
                "union": union,
                "jaccard": intersection / union if union else 0.0,
            })

    common = set(relative_changed_sets[0])
    for positions in relative_changed_sets[1:]:
        common &= positions

    shift_scores = []
    for index, positions in enumerate(relative_changed_sets):
        scores = []
        if positions:
            for shift in SHIFT_CANDIDATES:
                hits = sum(1 for pos in positions if pos + shift in positions)
                scores.append({
                    "shift": shift,
                    "hits": hits,
                    "changed_bytes": len(positions),
                    "ratio": hits / len(positions),
                })
        shift_scores.append({
            "section": index,
            "top": sorted(scores, key=lambda x: x["ratio"], reverse=True)[:10],
        })

    return {
        "verified_par_section_starts": list(PAR_SECTION_STARTS),
        "section_size_observation": {
            "first_three": [PAR_SECTION_STARTS[i + 1] - PAR_SECTION_STARTS[i] for i in range(3)],
            "last": len(data) - PAR_SECTION_STARTS[-1],
            "notable": "0x200F0 = 0x20000 + 0xF0 for first three sections; last is 0x200E0",
            "interpretation": "strong texture-sized payload hint, not proof of atlas format",
        },
        "sections": sections,
        "pairwise_same_relative_offset_overlap": pairwise,
        "same_relative_changed_in_all_sections": {
            "count": len(common),
            "first_offsets": sorted(common)[:128],
        },
        "shifted_change_overlap": shift_scores,
        "warning": "section partitioning is verified; glyph/page semantics remain unproven",
    }


def xor_bytes(a: bytes, b: bytes) -> bytes:
    if len(a) != len(b):
        raise ValueError("xor inputs differ in length")
    return bytes(x ^ y for x, y in zip(a, b))


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("zpatch", type=Path)
    p.add_argument("--retail-par", type=Path,
                   help="optional exact retail font/zillfont.par; never modified")
    p.add_argument("--reconstructed", type=Path,
                   help="optional path to write reconstructed English result")
    p.add_argument("--json", type=Path)
    args = p.parse_args()

    compressed = args.zpatch.read_bytes()
    if sha256(compressed) != EXPECTED_PATCH_SHA256:
        raise SystemExit("compressed patch SHA-256 mismatch")

    expanded = zlib.decompress(compressed)
    if len(expanded) != EXPECTED_SIZE:
        raise SystemExit(f"unexpected XOR size: {len(expanded)}")
    if sha256(expanded) != EXPECTED_XOR_SHA256:
        raise SystemExit("expanded XOR SHA-256 mismatch")

    runs = list(nonzero_runs(expanded))
    starts = [s for s, _ in runs]
    lengths = [e - s for s, e in runs]
    changed = sum(lengths)

    report = {
        "authenticated": True,
        "compressed_size": len(compressed),
        "expanded_size": len(expanded),
        "changed_bytes": changed,
        "changed_ratio": changed / len(expanded),
        "run_count": len(runs),
        "run_length": {
            "min": min(lengths) if lengths else 0,
            "max": max(lengths) if lengths else 0,
            "median": statistics.median(lengths) if lengths else 0,
            "mean": statistics.mean(lengths) if lengths else 0,
        },
        "start_delta_gcd": gcd_of_deltas(starts),
        "alignment_histogram": alignment_histogram(starts),
        "block_stats": block_touch_stats(expanded),
        "section_analysis": section_analysis(expanded),
        "longest_runs": [
            {"start": s, "end": e, "length": e - s}
            for s, e in sorted(runs, key=lambda r: r[1] - r[0], reverse=True)[:80]
        ],
        "warnings": [
            "metrics key order is NOT treated as physical glyph order",
            "no bitmap-start or fixed-stride assumption is made",
            "PAR section starts are verified from the authenticated retail header, but section semantics remain unproven",
            "0x20000 payload interpretation is a geometry heuristic only",
            "candidate layout must be validated against recognizable glyph imagery or renderer lookup",
        ],
    }

    if args.retail_par:
        retail = args.retail_par.read_bytes()
        if len(retail) != EXPECTED_SIZE:
            raise SystemExit(f"retail zillfont.par size mismatch: {len(retail)}")
        source_sha = sha256(retail)
        if source_sha != EXPECTED_SOURCE_SHA256:
            raise SystemExit(f"retail zillfont.par SHA-256 mismatch: {source_sha}")
        english = xor_bytes(retail, expanded)
        result_sha = sha256(english)
        if result_sha != EXPECTED_RESULT_SHA256:
            raise SystemExit(f"reconstructed English font SHA-256 mismatch: {result_sha}")
        report["retail_source_sha256"] = source_sha
        report["reconstructed_result_sha256"] = result_sha
        report["reconstructed_result_verified"] = True
        if args.reconstructed:
            args.reconstructed.write_bytes(english)
            report["reconstructed_path"] = str(args.reconstructed)

    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.json:
        args.json.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
