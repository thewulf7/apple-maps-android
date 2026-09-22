# VMP4 Reverse Engineering Notes

**Project**: Apple Maps Android — native VMP4 vector tile RE  
**Last updated**: Sep 22 2026  

---

## What We Know (High Confidence — Verified via Disassembly)

### Container Format
- Magic: `VMP4` (4 bytes)
- Skip 2 bytes
- `uint16_le` → num_sections
- Sections: each `[type:uint16_le][offset:uint32_le][size:uint32_le]` (10 bytes/entry)
- Section data: `[flag:byte]` prefix — `0x00` = uncompressed (skip flag byte), `0x01` = zlib-deflate (`[flag][decompressed_size:uint32_le][deflate_payload...]`)

### Section Types (confirmed)
| ID | Type | Notes |
|----|------|-------|
| `0x01` | Global | Tile metadata |
| `0x0A` | Labels | UTF-8 label strings |
| `0x0B` | Label languages | Language codes |
| `0x0D` | Label localizations | Locale-specific strings |
| `0x14` | **Vertices** | Bitstream-encoded vertex pool |
| `0x1E` | Point features | `[count][vs][ft][si]` per feature |
| `0x1F` | **Line features** | `[count][vs][vc][ft][si]` per feature |
| `0x20` | **Polygon features** | `[count][vs][oc][hc][ft][si]` per feature — **BROKEN, see below** |
| `0x33` | Unknown | Present in style-20 road tiles |
| `0x8D` | Unknown | Present in style-20 road tiles |
| `0x98` | Unknown | Present in style-20 road tiles |

**A tile can have MULTIPLE `0x14` sections.** Each `0x14` immediately precedes the feature section that uses it. Order: `[0x14 vertices] [feature section] [0x14 vertices] [feature section] ...`

### Vertex Pool Format (`0x14`)
```
varint   shapeCount     (number of geometric shapes/rings in this pool)
varint   vertexCount    (total vertices across all shapes)
--- bitstream (MSB-first within each byte) ---
6 bits   coordBits      (bits per coordinate component, typically 10 or 13)
6 bits   deltaBits      (bits per delta, typically 5-9)
4 bits   runBits        (bits per run-length per shape, typically 4)
1 bit    hasCurves      (if set, one extra bit per vertex = curve flag)
--- for each shape (shapeCount total) ---
  runBits bits → runLen  (number of vertices in this shape)
  for each vertex:
    if first vertex:  coordBits×2 bits → (cx, cy)  absolute
    else:             deltaBits×2 bits → (dx, dy)  signed delta, added to (cx, cy)
    if hasCurves:     1 bit            → curve flag (skip)
```

**Bit order**: **MSB-first within each byte** — confirmed via `geo::ibitstream::readUIntBits` disassembly in VectorKit.framework. Bit offset 0 = MSB (bit 7 of byte).

**Coordinate system**: normalized 0..1 within tile. `scale = 1.0 / ((1 << coordBits) - 1)`

**WGS84 conversion** (confirmed via VectorKit `_VKLocationCoordinate2DForVKPoint` disassembly):
```
lon = (tileX + normX) / 2^zoom * 360 - 180
lat = atan(sinh(π × (1 - 2 × (tileY + normY) / 2^zoom))) × 180/π
```
Standard Web Mercator (EPSG:3857). Matches Apple's binary exactly.

### Line Feature Format (`0x1F`) — 4 varints per entry
```
varint count
for each feature:
  varint vertex_start   ← flat vertex index into pool (NOT shape index)
  varint vertex_count   ← number of VERTICES (not shapes) 
  varint feature_type
  varint style_index
```
**IMPORTANT**: `vertex_start` and `vertex_count` are in **flat vertex space**, not shape space. You must walk the shape run-lengths to find which shapes contain vertices `[vertex_start .. vertex_start+vertex_count)`.

**ALSO IMPORTANT**: Section `0x1F` appears to contain **multiple sub-lists** after the initial feature count. After reading `count` features, the remaining bytes hold additional entries (possibly per-zoom or per-style variants). This needs further investigation.

### Polygon Feature Format (`0x20`) — **BROKEN / UNCRACKED**
Nominal 5-varint format `[vs][oc][hc][ft][si]`:
- Works for classes 0, 5, 6, 7 (small `vs` values)
- Breaks for classes 1–4: the `vs` varint reads a **9-byte hash** embedded between class entries, producing values like `26045921473397720`
- Root cause: each polygon class entry has a **9-byte opaque hash** (fingerprint/UUID) embedded before the varint fields for some entries
- The hash is a varint itself (8 bytes with MSB continuation bits + 1 terminating byte)
- Workaround: skip any entry where `ft > 127` or `vs > totalShapeCount` after clamping

**Area analysis confirms**: despite the broken parsing, the geometry IS being correctly decoded and colored (ft=0 land, ft=64 park, ft=94 water) because valid classes happen to survive the corruption. But the mapping is approximate.

### Tile Endpoints
```
Style 1 (polygons/land-use/buildings):
  https://gspe19-ssl.ls.apple.com/tile.vf?flags=8&style=1&size=2&scale=0&v=20028707&z=Z&x=X&y=Y

Style 20 (road network):
  https://gspe19-ssl.ls.apple.com/tile.vf?style=20&size=2&scale=0&v=20027943&z=Z&x=X&y=Y
```
Auth: tokenP1 hardcoded in manifest, tokenP2 from manifest protobuf, tokenP3 = random UUID, signed via SHA-256 + AES-CBC URL signing (see `NativeAuth.kt`).

---

## Known Feature Types (polygon `ft` values)

| ft | Meaning | Color |
|----|---------|-------|
| 0 | Land / default ground | `#F2EFE9` beige |
| 40 | Recreation / park variant | `#D4E8C2` green |
| 64 | Park | `#D4E8C2` green |
| 65 | Park variant (garbage-clamped class) | `#D4E8C2` green |
| 94 | Water | `#A8D4F0` blue |
| 97 | Water (river, confirmed = Vltava Prague) | `#A8D4F0` blue |

---

## What Is Still Broken

### 1. Line feature vertex iteration (HIGH PRIORITY — causes "scribble" roads)
**Bug**: We iterate over ALL `shapeCount` vertex pool shapes and emit each as a separate GeoJSON LineString. This is wrong.  
**Fix needed**: Each line feature defines `[vs, vc]` in flat vertex space. Walk shape run-lengths to find the shapes spanning `[vs..vs+vc)`. Emit those shapes as a multi-segment polyline (connected) per feature.

### 2. Polygon feature class parsing (MEDIUM)
**Bug**: 5-varint decode hits embedded 9-byte hash values for some classes.  
**Fix needed**: Detect/skip the hash before each class entry. Pattern: any varint > some threshold (perhaps > 2^32) = hash, skip it and re-read the actual fields.

### 3. Section `0x1F` sub-list parsing (UNKNOWN)
After the `count` features in the line section, there are additional bytes (observed: 3176 bytes after 4 features in a 3196-byte section). Purpose unknown — possibly per-road-type style sub-entries or zoom-range variants.

### 4. Section `0x33` / `0x8D` (UNKNOWN)
Present in style-20 tiles. Not yet decoded.

---

## VectorKit RE Findings (VectorKit.framework, macOS 27 / iOS 20 shared cache)

### Key symbols located in dyld shared cache
- `geo::ibitstream::readUIntBits(uint8_t)` — at `0x1C22BFEF0` (cache addr)
- `geo::ibitstream::readVarInt()` — at `0x1C28AC594` (standard LEB128/protobuf varint)
- `geo::codec::vertexPoolForFeature(GeoCodecsMultiSectionFeature const*)` — at `0x1C341E8A8`
  - Uses pointer-range dispatch against 9 distinct vertex pool slots at fixed offsets in the tile object
  - Pool offsets: `0x320, 0x328, 0x330, 0x338, 0x340, 0x348, 0x350, 0x358, 0xdd8`
- `VKLocationCoordinate2DForVKPoint` — exported C function, disassembled to confirm Mercator math

### Bitstream analysis
- `readUIntBits`: MSB-first bitstream confirmed. `bit_offset=0` = MSB of byte (bit 7). Cross-byte combines: `(cur_byte_high_bits << old_offset) | (next_byte >> (8-old_offset))`.
- Struct layout of `geo::ibitstream` (`x1` register in callers):
  - `[+0x00]` = pointer to byte array base
  - `[+0x08]` = end pointer
  - `[+0x10]` = current byte position (updated per read)
  - `[+0x18]` = current bit offset within byte (0-7, 0=MSB)
- `readVarInt`: standard LEB128 (byte[7] = continuation, bytes[6:0] = 7-bit value chunk, little-endian)

### Tile object structure (partial, from `vertexPoolForFeature`)
The tile object (type `geo::codec::VectorTile` or similar) has feature-array ranges at offsets `0x20, 0x28, 0x40, 0x48, 0x60, 0x68, 0x98, 0xa0, 0xb8, 0xc0, 0xd8, 0xe0, 0x118, 0x120, 0x178, 0x180, 0x198, 0x1a0, 0xdb8, 0xdc0` — each pair `[begin, end)` pointers to feature subarrays. The function returns a pointer to one of 9 vertex pools based on which subarray the input feature pointer falls into.

---

## Decompiler Setup

Ghidra 12.1.4 downloaded to `~/tools/ghidra_12.1.4_PUBLIC`.  
DYLD Cache import fails with default heap (OOM). Use:
```bash
export GHIDRA_HEADLESS_MAXMEM=10G
~/tools/ghidra_12.1.4_PUBLIC/support/analyzeHeadless ~/ghidra_project VmpRE \
  -import /System/Volumes/Preboot/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e \
  -noanalysis
```
**Problem**: Ghidra's DYLD Cache loader parses ALL dylib symbol tables on import (thousands of dylibs), running OOM even at 10G. 

**Alternative (TODO)**: Extract just VectorKit bytes using `dyld_info -section_bytes __TEXT __text` and create a minimal Mach-O wrapper, then import that standalone binary.

**Workaround used**: `dyld_info -disassemble` on the framework path reads directly from the shared cache and produces full disassembly (4.8M lines, `/tmp/vectorkit_disasm.txt`). Sufficient for symbol lookup and manual function tracing.

---

## Live Tile Corpus

49 real live tiles captured from device across zoom levels 3–14, styles 1 and 20, stored in `/tmp/live_tiles2/`. Useful for validating decoder changes.

Tiles cover:
- z=3–9: continental Europe
- z=11–12: Czech Republic / Prague region  
- z=14: Prague city center (tiles 8847/5550 area)

---

## Next Steps (Priority Order)

1. **Fix line rendering** — implement `vertex_start/vertex_count → shape range` mapping, emit per-feature polylines not per-shape fragments
2. **Decode `0x33` section** — likely road name/label associations or road graph connectivity
3. **Fix polygon class hash skip** — detect and skip the embedded hash bytes in `0x20` section class entries
4. **Decode section `0x1F` sub-list** — understand what follows the initial feature array
5. **Ghidra decompile** — extract VectorKit binary cleanly and decompile `readUIntBits`, `vertexPoolForFeature`, and the main tile-parse entry point to get ground truth on all above
