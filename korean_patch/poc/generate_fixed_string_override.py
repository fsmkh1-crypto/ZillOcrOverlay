#!/usr/bin/env python3
"""Generate the first Korean fixed-string override for HK47196/zill.

This PoC keeps the upstream CP932 boundary intact. Hangul is represented by
unused CP932 surrogate glyphs; the font patch later redraws those glyph slots
as Hangul.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Glyph:
    hangul: str
    surrogate: str
    cp932_hex: str


GLYPHS = (
    Glyph("아", "纊", "ED40"),
    Glyph("이", "褜", "ED41"),
    Glyph("템", "鍈", "ED42"),
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
    print()
    print("Upstream eboot.toml PoC override:")
    print(
        f'{OFFSET} = {{ source = "{SOURCE}", replacement = "{surrogate}" }}'
    )
    return surrogate


if __name__ == "__main__":
    verify_mapping()
