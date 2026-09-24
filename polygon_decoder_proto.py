#!/usr/bin/env python3
"""
Prototype polygon feature decoder for VMP4 section 0x20 / 0x21.
Based on full trace of _readPolygonsofType @ 0x19AF3A7E0 in GeoServices.

Key findings from disasm:
1. Function reads shapeCount + featureCount from the VERTEX chapter (0x14), not the polygon section
   Wait — actually: reads from 0x19AF3A8C4 chapterReadVarUint32 twice → featureCount + second count
   But the polygon chapter (0x20) is the input chapter (x19). Let's trace properly.

Actual structure of 0x20 polygon chapter:
  - varint featureCount (at 0x19AF3AD88 → stored at [x29-0xac])
  - varint labelCount  (at 0x19AF3ADB4 → stored at [x29-0xb0])
  - vector<PolygonFeature> resized to featureCount @ VectorTile+0x60
  - then per-feature loop (x10 counter 0..featureCount):
    feature ptr = VectorTile+0x60 + x10*0x80 (0x19AF3AF64: x28 = base[x10 * 0x80])
    
    Per-feature reads (bit-reads from BYTE-aligned position within chapter):
    
    The function uses a "bitstream reader" stored at x19 (the VMP4Chapter struct).
    At 0x19AF3AFC8 it reads the byte-position frontier and increments it,
    then enters a block reading 5 (or 6 for 0x21) individual BITS:
    
    0x19AF3B010: bit_pos=0 → reads bit0  (bit of byte[0]) → w9=bit0 → flag@feature+0x54
    0x19AF3B028: bit_pos=1 → reads bit1               → w11                
    0x19AF3B040: bit_pos=2 → reads bit2               → w0                 
    0x19AF3B058: bit_pos=3 → reads bit3               → w17                
    0x19AF3B06C: bit_pos=4 → reads bit4               → w13 @ [sp+0x54]    
    0x19AF3B090: bit_pos=5 → reads bit5               → w14 @ [sp+0x50]    
    if sectionType==0x21:
    0x19AF3B0AC: bit_pos=6 → reads bit6               → feature+0x80
    then bit_pos advances to 8 (next byte boundary):
    0x19AF3B0D0+: cmp x12(=9),x10 → bit_pos 8  → byte-align at bit 8

    After re-align (0x19AF3B100-0x19AF3B110):
      byte_pos = ceil(bit_pos / 8) = 1 byte consumed from chapter
    
    0x19AF3B120: tbz w9, #0 → if bit0 set: call chapterReadVarUint32 → outerCount
                              else outerCount = 1 (default)
    
    0x19AF3B140: feature+0x5c = [shapeIndex_running, outerCount]
                  (shapeIndex_running accumulated with each feature's outerCount)
    
    Then for 0x21 (building footprints): additional per-outer-ring data
    0x19AF3B160-0x19AF3B1A4: loop outerCount times:
      chapterReadVarUint32 → height_raw
      height = float(height_raw) * scale_factor (s8, loaded from data at 0x19AF3AF38)
      stored as float32 in a separate array (x25 = index, array @ sp+0x90)
    
    Back in main path (0x19AF3B1A8):
    0x19AF3B1C4: tbz bit1, #0 → if set: chapterReadVarUint32 → featureId (u64, 8 bytes) stored @feature+0x28
    0x19AF3B1F8: tbz bit2, #0 → if set: chapterReadVarUint32 → small_u32 stored @feature+0x44 (float area?)
    0x19AF3B250: tbz bit3, #0 → if set: call accumulateStyleAttributes → style attributes
    
    Then outer polygon holes:
    0x19AF3B288: shapeIndex_running += outerCount  (accumulate shape index)
    
    MOST IMPORTANT: feature.vertexStart = shapeIndex_running (before add)
    feature.outerCount = outerCount
    feature.featureType = from attributesForFeatureType (uses bit4/bit5 to select)
    
SIMPLIFIED MODEL (what we need for rendering):
  - featureCount from varint
  - per feature: read 1 byte of flags, re-align
    flag bit 0 → if set, read varint = outerCount, else outerCount = 1
    flag bit 1 → if set, skip varint (feature_id)  
    flag bit 2 → if set, skip float32 (area/elevation)
    flag bit 3 → if set, skip style attributes varint
  - running shapeIndex += outerCount each feature
  - We get: vertexStart=running, outerCount

WAIT. Re-read disasm more carefully.

At 0x19AF3AF64: feature ptr = VectorTile.polygons[x10] where x10 = loop counter 0..featureCount
At 0x19AF3AFC0: hp_readLabel(feature, chapter, label_len_ptr, label_data, label_count, VectorTile)
  → This reads a LABEL association for the feature
  → Returns bool; if false → jump to 0x19AF3B530

The ACTUAL per-feature bit-flag read starts AT 0x19AF3AFC8 after hp_readLabel returns.

So order per feature:
1. hp_readLabel (reads some bytes from chapter for label association)
2. Then reads byte-position from chapter state
3. Read 5-6 bits (flag header)
4. Re-align to next byte
5. Conditionally read varints based on flags

The hp_readLabel function is complex. Let me check what it reads.
For now: the key insight from the disasm is that the bits are read from the
BYTE-stream position stored in x19 (chapter struct field [+0x30]).

ALTERNATIVE simpler approach: Since Apple's actual calling convention reuses
the chapter struct for BOTH the bitstream AND byte reads, and the bit cursor
lives at [x19+0x18] while the byte cursor lives at [x19+0x30]:

The flag bits (0-5) at 0x19AF3B010-0x19AF3B0B4 use [x19+0x18] = bit_pos.
They start from bit_pos=x9 (loaded from x19+0x18 at entry to this block).

The initial x9 = 0 for first feature means the bits start at the BEGINNING
of the current byte in the byte-aligned position. This is a separate bitstream
from the main varint stream!

Actually looking more carefully:
0x19AF3AFC8: ldp x8,x9 from [x19, #0x30] → x8=byte_pos_of_bitstream, x9=byte_limit
0x19AF3AFCC: add x10, x8, #1  → x10 = byte_pos + 1
0x19AF3AFD0: cmp x10, x9 → check limit
... if ok:
0x19AF3AFE8: x9 = 0  (bit offset within byte = 0, start of byte)
0x19AF3AFEC: x10 = 8 (total bits available = 8 = 1 byte)

So: reads exactly 1 BYTE at [x19+0x30], extracts bits from it MSB-first.
x10=8 is the bit_limit (8 bits total in this 1 byte).

The bit reads use: bit_index x9 increments 0→1→2→3→4→5→6→8
Pattern: lsr byte_value, (7 - bit_index) = MSB-first extraction

After reading bits, recalculate byte position:
0x19AF3B104: tst x12, #7 → x12 = final bit_index = 8
0x19AF3B108: add x8, x8, x12>>3 = x8 + 1 = byte_pos + 1
0x19AF3B10C: cinc x8, x8, ne → +1 if bits didn't align: 8 bits = exact 1 byte, no extra
0x19AF3B110: str x8, [x19+0x30] → advance byte position by 1

SUMMARY of per-feature bit layout (1 byte, MSB first):
  bit7 (MSB) = flag0: if set → read varint outerCount, else outerCount=1
  bit6       = flag1: if set → read varint featureId (u64)  
  bit5       = flag2: if set → read float (area or similar)
  bit4       = flag3: if set → read style attributes
  bit3       = flag4: stored @ feature+0x54 (second copy)
  bit2       = flag5: stored @ feature+0x50 (auxiliary)
  bit1       = (for 0x21 only) → feature+0x80 (building extra flag)
  bit0 (LSB) = not used / padding?

Wait, re-check bit extraction:
  x9 starts = 0 (bit position from MSB)
  bit0: lsr w11, w11[byte], (7-0) = bit7 of byte → extracted via `bic w9,w14,w9; lsr w9,w11,w9; tbz w9,#0`
  
  Actually: bic w9, w14(=7), w9(=0) = 7-0=7; lsr w11, w11, 7 = bit7 → MSB first ✓
  
bit_pos=0: extracts bit7 of byte → flag0 (outerCount present)
bit_pos=1: extracts bit6 → flag1 (featureId present)
bit_pos=2: extracts bit5 → flag2 (float present)
bit_pos=3: extracts bit4 → flag3 (style attrs present)
bit_pos=4: extracts bit3 → stored to [sp+0x54]
bit_pos=5: extracts bit2 → stored to [sp+0x50]
[if 0x21:
bit_pos=6: extracts bit1 → feature+0x80]
bit_pos advances to 8 → consume 1 byte

So the flag byte layout (MSB-first):
  [bit7][bit6][bit5][bit4][bit3][bit2][bit1][bit0]
  flag0  flag1  flag2 flag3  aux1   aux2   bldg   -
"""

import struct, zlib, sys

# ─── low-level helpers ────────────────────────────────────────────────────────

def decompress_section(raw):
    if not raw: return raw
    if raw[0] == 0: return raw[1:]
    dsize = struct.unpack_from('<I', raw, 1)[0]
    return zlib.decompress(raw[5:])

def parse_vmp4_sections(data):
    assert data[:4] == b'VMP4', f"not VMP4: {data[:4]}"
    num = struct.unpack_from('<H', data, 6)[0]
    sections = []
    pos = 8
    for _ in range(num):
        tid, off, size = struct.unpack_from('<HII', data, pos)
        raw = data[off:off+size]
        sections.append((tid, decompress_section(raw)))
        pos += 10
    return sections

def read_varint(data, pos):
    result = 0; shift = 0
    while pos < len(data):
        b = data[pos]; pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7
        if shift >= 35: raise ValueError("varint overflow")
    raise ValueError("EOF in varint")


# ─── vertex pool decoder (confirmed correct) ─────────────────────────────────

def decode_vertex_pool(data):
    """Returns (shape_starts[], shape_lengths[], vertices[]) or None."""
    pos = 0
    shape_cap, pos = read_varint(data, pos)
    vtx_count, pos = read_varint(data, pos)
    if shape_cap == 0 or vtx_count == 0:
        return None

    # bitstream from pos (MSB-first)
    bit_pos = pos * 8

    def read_bits(n):
        nonlocal bit_pos
        result = 0
        rem = n
        while rem > 0:
            byte_off = bit_pos >> 3
            bit_off  = bit_pos & 7
            avail = 8 - bit_off
            take  = min(rem, avail)
            shift = 8 - bit_off - take
            bits  = (data[byte_off] >> shift) & ((1 << take) - 1)
            result = (result << take) | bits
            bit_pos += take
            rem -= take
        return result

    def read_bits_signed(n):
        v = read_bits(n)
        if n > 0 and (v >> (n-1)):
            v -= (1 << n)
        return v

    coord_bits = read_bits(6)
    run_bits   = read_bits(6)
    delta_bits = read_bits(4)
    has_curves = read_bits(1)
    scale = 1.0 / ((1 << coord_bits) - 1)

    verts = []; starts = []; lengths = []
    rem_deltas = 0; cx = 0; cy = 0

    for vi in range(vtx_count):
        if rem_deltas == 0:
            raw_rl = read_bits(run_bits) if run_bits > 0 else 0
            rem_deltas = raw_rl
            cx = read_bits(coord_bits)
            cy = read_bits(coord_bits)
            starts.append(len(verts))
            lengths.append(raw_rl + 1)
        else:
            if delta_bits > 0:
                cx += read_bits_signed(delta_bits)
                cy += read_bits_signed(delta_bits)
            rem_deltas -= 1
        if has_curves:
            read_bits(1)
        verts.append((cx * scale, cy * scale))

    return starts, lengths, verts


# ─── NEW polygon feature decoder (corrected) ─────────────────────────────────

def decode_polygon_features_new(data, section_type=0x20, debug=False):
    """
    Decode polygon features from section 0x20 or 0x21.
    
    Corrected model from full disasm trace of _readPolygonsofType.
    
    Section layout:
      varint featureCount
      varint labelCount
      per feature:
        1 byte flags (MSB-first bit extraction):
          bit7 = has_outer_count   → if 1, read varint outerCount; else outerCount=1
          bit6 = has_feature_id    → if 1, skip varint (u64 featureId)
          bit5 = has_float_field   → if 1, skip float32
          bit4 = has_style_attrs   → if 1, skip style varint
          bit3 = aux flag 1
          bit2 = aux flag 2
          bit1 = (0x21 only) building extra flag
          bit0 = unused
        [varint outerCount if bit7 set]
        [varint featureId  if bit6 set] -- actually a u64 so could be 9 bytes but we just skip
        [float32 area      if bit5 set]
        [varint style_attrs if bit4 set]
      
      cumulative shapeIndex: start=0, += outerCount each feature
      → feature.vertexStart = shapeIndex BEFORE adding outerCount
    
    Returns list of (vertexStart, outerCount, flags_byte) tuples.
    """
    pos = 0
    feature_count, pos = read_varint(data, pos)
    label_count,   pos = read_varint(data, pos)
    
    if debug:
        print(f"  featureCount={feature_count} labelCount={label_count} (data_len={len(data)})")
    
    features = []
    shape_index = 0  # running cumulative vertex/shape start

    for fi in range(feature_count):
        if pos >= len(data):
            if debug: print(f"  EOF at feature {fi}")
            break
        
        # Read 1 flag byte
        flag_byte = data[pos]; pos += 1
        
        # Extract flags MSB-first
        has_outer_count  = (flag_byte >> 7) & 1
        has_feature_id   = (flag_byte >> 6) & 1
        has_float_field  = (flag_byte >> 5) & 1
        has_style_attrs  = (flag_byte >> 4) & 1
        aux1             = (flag_byte >> 3) & 1
        aux2             = (flag_byte >> 2) & 1
        bldg_extra       = (flag_byte >> 1) & 1  # 0x21 only
        
        if debug:
            print(f"  feat[{fi}] pos={pos-1} flag=0x{flag_byte:02x} "
                  f"oc={has_outer_count} fid={has_feature_id} "
                  f"fl={has_float_field} sa={has_style_attrs} "
                  f"aux={aux1}{aux2} bld={bldg_extra}")
        
        # Read outerCount
        outer_count = 1
        if has_outer_count:
            outer_count, pos = read_varint(data, pos)
        
        vs = shape_index
        shape_index += outer_count
        
        # Skip optional fields
        feature_id = None
        if has_feature_id:
            feature_id, pos = read_varint(data, pos)  # may be u64 (up to 9 bytes)
        
        float_val = None
        if has_float_field:
            if pos + 4 <= len(data):
                float_val = struct.unpack_from('<f', data, pos)[0]
                pos += 4
        
        style_val = None
        if has_style_attrs:
            style_val, pos = read_varint(data, pos)
        
        features.append({
            'vs': vs,
            'outer_count': outer_count,
            'flags': flag_byte,
            'feature_id': feature_id,
            'float_val': float_val,
            'style_val': style_val,
        })
        
        if debug and fi < 10:
            print(f"    → vs={vs} outer_count={outer_count} fid={feature_id} "
                  f"float={float_val} style={style_val} pos_after={pos}")
    
    return features, pos


# ─── validation against live tiles ────────────────────────────────────────────

import os, glob

TILE_DIR = '/tmp/live_tiles2'

def validate_tile(path, debug=False):
    fname = os.path.basename(path)
    with open(path, 'rb') as f:
        data = f.read()
    
    sections = parse_vmp4_sections(data)
    
    # Build section pairing: each vertex 0x14 precedes its feature section
    vertex_pools = {}
    last_verts = None
    
    for tid, sdata in sections:
        if tid == 0x14:
            last_verts = decode_vertex_pool(sdata)
        elif tid in (0x1e, 0x1f, 0x20, 0x21):
            if last_verts is not None:
                vertex_pools[tid] = last_verts
            last_verts = None
    
    # Now decode polygon features
    results = {}
    last_verts = None
    for tid, sdata in sections:
        if tid == 0x14:
            last_verts = decode_vertex_pool(sdata)
        elif tid == 0x20:
            if last_verts is not None:
                starts, lengths, verts = last_verts
                shape_count = len(starts)
                feats, end_pos = decode_polygon_features_new(sdata, section_type=0x20, debug=debug)
                results['0x20'] = {
                    'shape_count': shape_count,
                    'feat_count': len(feats),
                    'end_pos': end_pos,
                    'data_len': len(sdata),
                    'features': feats[:5],  # sample
                }
        elif tid == 0x21:
            if last_verts is not None:
                starts, lengths, verts = last_verts
                shape_count = len(starts)
                feats, end_pos = decode_polygon_features_new(sdata, section_type=0x21, debug=debug)
                results['0x21'] = {
                    'shape_count': shape_count,
                    'feat_count': len(feats),
                    'end_pos': end_pos,
                    'data_len': len(sdata),
                    'features': feats[:5],
                }
    
    return fname, results


def main():
    tiles = sorted(glob.glob(os.path.join(TILE_DIR, '*.bin')))
    s1_tiles = [t for t in tiles if '_s1.bin' in t]
    
    print(f"Testing {len(s1_tiles)} style-1 tiles...\n")
    
    for path in s1_tiles:
        fname, results = validate_tile(path, debug=False)
        if not results:
            print(f"  {fname}: no polygon sections found")
            continue
        
        for sec_type, r in results.items():
            sc = r['shape_count']
            fc = r['feat_count']
            ep = r['end_pos']
            dl = r['data_len']
            slack = dl - ep
            
            # Check: max vs+outer_count should not exceed shape_count
            max_vs_end = max((f['vs'] + f['outer_count'] for f in r['features']), default=0)
            # Get all features for full check
            with open(path, 'rb') as f:
                data = f.read()
            sections = parse_vmp4_sections(data)
            last_v = None
            all_feats = []
            for tid, sdata in sections:
                if tid == 0x14:
                    last_v = decode_vertex_pool(sdata)
                elif tid == int(sec_type, 16) and last_v:
                    all_feats, _ = decode_polygon_features_new(sdata, int(sec_type, 16))
                    break
            
            vs_max = max((f['vs'] + f['outer_count'] for f in all_feats), default=0) if all_feats else 0
            ok = "✓" if vs_max <= sc else f"OVERFLOW(vs_end={vs_max}>sc={sc})"
            
            ft_set = set()
            for f in all_feats:
                if f['style_val'] is not None:
                    ft_set.add(f['style_val'])
            
            print(f"  {fname} {sec_type}: shapes={sc} feats={fc} slack={slack}b {ok} "
                  f"style_vals={sorted(ft_set)[:8]}")
        
    # Detailed debug on one tile
    print("\n=== DETAILED: dump_14_8847_5550_s1.bin ===")
    validate_tile(os.path.join(TILE_DIR, 'dump_14_8847_5550_s1.bin'), debug=True)


if __name__ == '__main__':
    # If specific tile given
    if len(sys.argv) > 1:
        fname, results = validate_tile(sys.argv[1], debug=True)
        print(f"\nResults for {fname}:")
        for sec, r in results.items():
            print(f"  {sec}: shapes={r['shape_count']} feats={r['feat_count']} "
                  f"slack={r['data_len']-r['end_pos']}b")
    else:
        main()
