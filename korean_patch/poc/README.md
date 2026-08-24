# Korean Patch PoC 1 — deterministic `아이템` test

## Target

Use the fixed executable string at `0x246658`:

- retail Japanese: `アイテム`
- upstream English: `Items`
- Korean target: `아이템`

This field is suitable for the first rendering test because it is a fixed UI
string rather than random dialogue.

## Encoding strategy

HK47196/zill validates replacement text through CP932/Shift-JIS. Direct Hangul
therefore fails before build time. PoC 1 keeps that boundary intact and uses
three CP932 extension glyphs as surrogate codes:

| Hangul | Surrogate | CP932 bytes | Width |
|---|---|---:|---:|
| 아 | 纊 | ED40 | 12 |
| 이 | 褜 | ED41 | 12 |
| 템 | 鍈 | ED42 | 12 |

The upstream repository was searched for the three surrogate characters before
selection and no translation-data occurrence was found.

The executable replacement becomes `纊褜鍈`. It is six bytes in CP932, so it
fits the original eight-byte `アイテム` field. At runtime the font patch must
redraw those three CP932 glyph slots as `아`, `이`, and `템`.

## Upstream fixed-string override

```toml
0x246658 = { source = "アイテム", replacement = "纊褜鍈" }
```

`generate_fixed_string_override.py` validates the surrogate CP932 values and
capacity before printing this override.

## Remaining blocker for an executable PoC

The released English project freezes `font/zillfont.par` as an authenticated
XOR delta and exposes the final 2,637-glyph metrics table, but does not publish
raw retail font members. We still need a deterministic way to replace the
three glyph bitmaps.

Preferred implementation path:

1. Port/reuse the upstream game archive/font parser if a glyph writer exists.
2. If the final font atlas layout is fixed and documented, patch only the three
   glyph cells on the user's phone after reading the retail member from the ISO.
3. Preserve all upstream retail hash guards and output a new ISO rather than
   changing the user's source ISO in place.

## Android delivery requirement

The user must not need a PC. The final workflow is:

1. Select clean `ULJM-05410` v1.03 ISO with Android SAF.
2. Apply Korean patch/build logic locally on the phone.
3. Write a separate patched ISO.
4. Launch/open that ISO with PPSSPP.

The source ISO is never committed or uploaded.
