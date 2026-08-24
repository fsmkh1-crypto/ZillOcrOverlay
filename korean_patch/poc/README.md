# Korean Patch PoC — deterministic `아이템` rendering path

## Target

Use the fixed executable string at ELF file offset `0x246658`:

- retail Japanese: `アイテム`
- upstream English: `Items`
- Korean target: `아이템`

The field is useful for the first rendering test because it is a fixed UI string
with an eight-byte CP932 capacity.

## What is verified

On the user's clean `ULJM-05410` v1.03 ISO the Android patcher has verified:

- `font/zillfont.par` PAA member index 13611
- member size 525,424 bytes
- retail SHA-256 `0d3d6d2648870e87a01636cdfc7cc7af8100ea40b71e5ed05f82ac197606584a`
- authenticated upstream XOR patch reconstruction
- reconstructed English SHA-256 `0f11ca53076e072408fb3eb9ffa29446b02fb97642f4173b559691c463a2fdb8`
- PAR container with four children
- all four children are real PSP GIM resources
- children 0..2 are 512x512 4bpp PSP-swizzled font-like atlases
- exact GIM descriptor, palette and PSP storage-offset decoding

The app and CI intentionally keep the source ISO read-only.

## Important mapping correction

`metrics.toml` keys are valid CP932-derived advance keys, but their textual or
numeric order is **not** a physical atlas-cell index.

A high-contrast device probe established recognizable retail cells:

- `0` at child 0 raw cell 16
- `A` at child 0 raw cell 31
- `a` at child 0 raw cell 60

These do not follow the previously assumed metrics ordinals 17 / 33 / 64.
Therefore the old direct physical-cell predictions for `腑`, `躙`, and `綺`
are rejected.

## OpenType source-font probe

The retained upstream `fs-tahoma-8px.otf` is authenticated before use. CI parses
its OpenType `cmap` directly rather than trusting Android fallback rendering.
For the three ASCII anchors its glyph IDs are:

- `0`: gid 17 -> physical-minus-gid = -1
- `A`: gid 34 -> physical-minus-gid = -3
- `a`: gid 66 -> physical-minus-gid = -6

There is no single constant offset, so `OTF glyph ID + constant == atlas cell`
is also rejected. The retained OTF cmap does not contain the Japanese or kanji
targets used for surrogate research, so it cannot directly locate CJK cells.

## Safe first write-path PoC

Before solving the complete CJK surrogate map, the writer path can be tested
independently with three already recognizable ASCII cells:

| Temporary byte | Verified raw cell | PoC glyph |
|---|---:|---|
| `0` / `0x30` | child 0 cell 16 | 아 |
| `A` / `0x41` | child 0 cell 31 | 이 |
| `a` / `0x61` | child 0 cell 60 | 템 |

The current Android PoC performs this replacement **in memory only** and decodes
the edited GIM again for a visual `아/이/템` preview. No ISO write occurs at this
stage. This isolates GIM nibble writes, PSP swizzle addressing, palette index
selection, and Hangul rasterization from the later ISO/executable patch logic.

Once that memory preview is confirmed, a disposable test ISO can replace the
fixed `アイテム` field with `0Aa` and the same three font cells with `아/이/템`.
This is only a temporary rendering-path proof; common ASCII cells will not be
used by the final Korean patch.

## Final surrogate strategy remains open

The long-term patch still needs hundreds or thousands of safe CP932-compatible
surrogate slots. The earlier candidates remain useful as encoding candidates:

| Hangul | Candidate surrogate | CP932 bytes | metrics key |
|---|---|---:|---:|
| 아 | 腑 | E444 | 0x44e4 |
| 이 | 躙 | E757 | 0x57e7 |
| 템 | 綺 | E359 | 0x59e3 |

But their **physical GIM cells are not known yet**. They must not be written until
an independent mapping method locates them and whole-game usage safety is checked.

## Android delivery requirement

The final user workflow remains phone-only:

1. Select a clean `ULJM-05410` v1.03 ISO through Android SAF.
2. Authenticate the retail inputs.
3. Apply the Korean patch locally.
4. Write a separate patched ISO; never overwrite the source ISO.
5. Open the patched ISO with PPSSPP for runtime QA.

No retail game data is committed or uploaded.
