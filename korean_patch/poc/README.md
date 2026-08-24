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
three existing final-font glyph slots as surrogate codes:

| Hangul | Surrogate | CP932 bytes | metrics.toml key | Width |
|---|---|---:|---:|---:|
| 아 | 腑 | E444 | 0x44e4 | 12 |
| 이 | 躙 | E757 | 0x57e7 | 12 |
| 템 | 綺 | E359 | 0x59e3 | 12 |

All three metrics keys were confirmed in upstream `release/font/metrics.toml`.
The upstream repository was also searched for the three surrogate characters
before selection and no translation-data occurrence was found.

The executable replacement becomes `腑躙綺`. It is six bytes in CP932, so it
fits the original eight-byte `アイテム` field. At runtime the font patch must
redraw those three existing glyph slots as `아`, `이`, and `템`.

## Upstream fixed-string override

```toml
0x246658 = { source = "アイテム", replacement = "腑躙綺" }
```

`generate_fixed_string_override.py` validates the surrogate CP932 values,
metrics key byte order, and field capacity before printing this override.

## Remaining blocker for an executable PoC

The released English project freezes `font/zillfont.par` as an authenticated
XOR delta and exposes the final 2,637-glyph metrics table, but does not publish
a visible glyph authoring/writer path for replacing individual glyph bitmaps.

Preferred implementation path:

1. Reconstruct or locate the `zillfont.paf` glyph-cell layout.
2. Patch only the three confirmed existing cells (`0x44e4`, `0x57e7`,
   `0x59e3`) using Hangul bitmaps.
3. Apply that modification on the user's phone after reading the required
   retail member from the ISO, preserving the original ISO untouched.
4. Keep upstream validation and produce a separate patched ISO.

## Android delivery requirement

The user must not need a PC. The final workflow is:

1. Select clean `ULJM-05410` v1.03 ISO with Android SAF.
2. Apply Korean patch/build logic locally on the phone.
3. Write a separate patched ISO.
4. Launch/open that ISO with PPSSPP.

The source ISO is never committed or uploaded.
