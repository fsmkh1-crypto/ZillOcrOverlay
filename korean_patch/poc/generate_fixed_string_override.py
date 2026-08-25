#!/usr/bin/env python3
"""Generate the first Korean fixed-string override for HK47196/zill.

This PoC keeps the upstream CP932 boundary intact. Hangul is represented by
CP932 surrogate glyphs that are confirmed present in the upstream final font
metrics; the font patch later redraws those glyph slots as Hangul.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Glyph:
    hangul: str
    surrogate: str
    cp932_hex: str
    metric_key: str


GLYPHS = (
    Glyph("아", "腑", "E444", "0x44e4"),
    Glyph("이", "躙", "E757", "0x57e7"),
    Glyph("템", "綺", "E359", "0x59e3"),
)

SOURCE = "アイテム"
KOREAN = "아이템"
OFFSET = "0x246658"


def verify_mapping() -> str:
    mapping = {g.hangul: g for g in GLYPHS}
    surrogate = "".join(mapping[ch].surrogate for ch in KOREAN)

    for glyph in GLYPHS:
        actual = glyph.surrogate.encode("cp932").hex().upper()
        if actual != glyph.cp932_hex:
            raise SystemExit(
                f"CP932 mismatch for {glyph.surrogate}: {actual} != {glyph.cp932_hex}"
            )
        metric_key = "0x" + bytes.fromhex(actual)[::-1].hex()
        if metric_key != glyph.metric_key:
            raise SystemExit(
                f"metric key mismatch for {glyph.surrogate}: {metric_key} != {glyph.metric_key}"
            )

    source_bytes = SOURCE.encode("cp932")
    replacement_bytes = surrogate.encode("cp932")
    if len(replacement_bytes) > len(source_bytes):
        raise SystemExit(
            f"replacement too long: {len(replacement_bytes)} > {len(source_bytes)}"
        )

    print(f"source:       {SOURCE} ({len(source_bytes)} bytes)")
    print(f"korean:       {KOREAN}")
    print(f"surrogate:    {surrogate} ({len(replacement_bytes)} bytes)")
    print(f"replacement:  {replacement_bytes.hex().upper()}")
    print("font slots:   " + ", ".join(g.metric_key for g in GLYPHS))
    print()
    print("Upstream eboot.toml PoC override:")
    print(
        f'{OFFSET} = {{ source = "{SOURCE}", replacement = "{surrogate}" }}'
    )
    return surrogate


if __name__ == "__main__":
    verify_mapping()
