#!/usr/bin/env python3
"""
Local map rendering test — renders VMP4 tiles to PNG to validate decoder visually.
Run BEFORE pushing changes to Android.

Usage:
  python3 render_local.py [tile_path] [zoom x y]
  python3 render_local.py  # renders dump_14_8847_5550_s1.bin by default

Requires: pip3 install Pillow
"""

import struct, zlib, sys, os, math
from PIL import Image, ImageDraw, ImageFont

TILE_DIR = '/tmp/live_tiles2'
OUT_DIR  = '/tmp/vmp4_renders'
TILE_SIZE = 512  # pixels per tile


# ─── Section decompression / parsing ─────────────────────────────────────────

def decompress_section(raw):
    if not raw: return raw
    if raw[0] == 0: return raw[1:]
    dsize = struct.unpack_from('<I', raw, 1)[0]
    return zlib.decompress(raw[5:])

def parse_vmp4_sections(data):
    assert data[:4] == b'VMP4', f"Bad magic: {data[:4]}"
    num = struct.unpack_from('<H', data, 6)[0]
    sections = []
    pos = 8
    for _ in range(num):
        tid, off, size = struct.unpack_from('<HII', data, pos)
        raw = data[off:off + size]
        sections.append((tid, decompress_section(raw)))
        pos += 10
    return sections


# ─── Varint ───────────────────────────────────────────────────────────────────

def read_varint(data, pos):
    result = 0; shift = 0
    while pos < len(data):
        b = data[pos]; pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80): return result, pos
        shift += 7
    raise ValueError(f'EOF in varint at pos={pos}')


# ─── Vertex pool (confirmed correct) ─────────────────────────────────────────

def decode_vertex_pool(data):
    pos = 0
    shape_cap, pos = read_varint(data, pos)
    vtx_count, pos = read_varint(data, pos)
    if shape_cap == 0 or vtx_count == 0:
        return None

    bit_pos = pos * 8

    def read_bits(n):
        nonlocal bit_pos
        result = 0; rem = n
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
        if n > 0 and (v >> (n - 1)):
            v -= (1 << n)
        return v

    coord_bits = read_bits(6)
    run_bits   = read_bits(6)
    delta_bits = read_bits(4)
    has_curves = read_bits(1)
    scale = 1.0 / ((1 << coord_bits) - 1)

    verts = []; starts = []; lengths = []
    rem_deltas = 0; cx = 0; cy = 0

    for _ in range(vtx_count):
        try:
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
            if has_curves: read_bits(1)
            verts.append((cx * scale, cy * scale))
        except Exception:
            break

    return {'starts': starts, 'lengths': lengths, 'verts': verts}


# ─── Full tile parse ──────────────────────────────────────────────────────────

def parse_tile(data):
    """Returns {'poly': pool, 'line': pool, 'point': pool}"""
    sections = parse_vmp4_sections(data)

    result = {'poly': None, 'line': None, 'point': None, 'sections': {}}
    last_pool = None

    for tid, sdata in sections:
        result['sections'][f'0x{tid:02x}'] = len(sdata)
        if tid == 0x14:  # vertices
            last_pool = decode_vertex_pool(sdata)
        elif tid == 0x1e:  # points
            result['point'] = last_pool
        elif tid == 0x1f:  # lines
            result['line'] = last_pool
        elif tid == 0x20:  # polygons
            result['poly'] = last_pool
            last_pool = None

    return result


# ─── Rendering ───────────────────────────────────────────────────────────────

# Apple Maps color palette (from NativeMapView.kt)
COLORS = {
    'land':     (242, 239, 233),   # #F2EFE9
    'park':     (212, 232, 194),   # #D4E8C2
    'water':    (168, 212, 240),   # #A8D4F0
    'building': (224, 216, 204),   # #E0D8CC
    'road':     (200, 200, 200),   # road line
    'road_case':(136, 136, 136),   # #888888
    'bg':       (245, 245, 243),   # #F5F5F3 background
    'point':    (255, 87, 34),     # POI
}


def norm_to_px(nx, ny, tile_size=TILE_SIZE):
    """Tile-local [0,1] → pixel coords."""
    return (nx * tile_size, ny * tile_size)


def draw_polygon_pool(draw, pool, color, tile_size=TILE_SIZE):
    if pool is None: return
    starts   = pool['starts']
    lengths  = pool['lengths']
    verts    = pool['verts']
    for i, start in enumerate(starts):
        length = lengths[i]
        if length < 3: continue
        end = start + length
        if end > len(verts): continue
        # Clamp to tile bounds, convert to integer pixels (Pillow requires int or float but explicit cast is safer)
        pts = [(int(round(v[0] * tile_size)), int(round(v[1] * tile_size))) for v in verts[start:end]]
        if len(pts) >= 3:
            draw.polygon(pts, fill=color, outline=(180, 175, 168))


def draw_line_pool(draw, pool, color, width=2, tile_size=TILE_SIZE):
    if pool is None: return
    starts   = pool['starts']
    lengths  = pool['lengths']
    verts    = pool['verts']
    for i, start in enumerate(starts):
        length = lengths[i]
        if length < 2: continue
        end = start + length
        if end > len(verts): continue
        pts = [(int(round(v[0] * tile_size)), int(round(v[1] * tile_size))) for v in verts[start:end]]
        if len(pts) >= 2:
            draw.line(pts, fill=color, width=width)


def render_tile(tile_data, label='', tile_size=TILE_SIZE):
    """Render a single VMP4 tile to a PIL Image."""
    img = Image.new('RGB', (tile_size, tile_size), COLORS['bg'])
    draw = ImageDraw.Draw(img)

    parsed = parse_tile(tile_data)

    # Layer order: polygons (land/water), then lines (roads), then points
    # Since we don't have ft classification yet: ALL polys same land color
    # Water / park / building identified roughly by shape position (TODO: ft from 0x34)
    draw_polygon_pool(draw, parsed['poly'], COLORS['land'], tile_size)

    # Road casing (thicker)
    draw_line_pool(draw, parsed['line'], COLORS['road_case'], width=3, tile_size=tile_size)
    # Road fill
    draw_line_pool(draw, parsed['line'], (255, 255, 255), width=2, tile_size=tile_size)

    # Points
    if parsed['point']:
        for v in parsed['point']['verts']:
            px = int(round(v[0] * tile_size))
            py = int(round(v[1] * tile_size))
            r = 4
            draw.ellipse([px - r, py - r, px + r, py + r], fill=COLORS['point'])

    # Debug info
    poly_shapes  = len(parsed['poly']['starts'])  if parsed['poly']  else 0
    line_shapes  = len(parsed['line']['starts'])  if parsed['line']  else 0
    point_shapes = len(parsed['point']['starts']) if parsed['point'] else 0
    draw.text((4, 4), f"{label}\npoly={poly_shapes} line={line_shapes} pts={point_shapes}",
              fill=(0, 0, 0))

    return img, parsed


def render_tile_grid(paths, out_path, cols=4):
    """Render multiple tiles as a grid."""
    imgs = []
    for path in paths:
        with open(path, 'rb') as f:
            data = f.read()
        label = os.path.basename(path).replace('dump_', '').replace('.bin', '')
        img, parsed = render_tile(data, label=label)
        imgs.append(img)
        print(f"  {label}: sections={list(parsed['sections'].keys())}")

    if not imgs: return
    rows = math.ceil(len(imgs) / cols)
    ts = TILE_SIZE
    grid = Image.new('RGB', (cols * ts, rows * ts), (200, 200, 200))
    for idx, img in enumerate(imgs):
        row, col = divmod(idx, cols)
        grid.paste(img, (col * ts, row * ts))

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    grid.save(out_path)
    print(f"Saved grid: {out_path}")
    return grid


# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    import glob

    os.makedirs(OUT_DIR, exist_ok=True)

    # Single tile render
    test_tile = os.path.join(TILE_DIR, 'dump_14_8847_5550_s1.bin')
    if len(sys.argv) > 1:
        test_tile = sys.argv[1]

    print(f"Rendering single tile: {test_tile}")
    with open(test_tile, 'rb') as f:
        data = f.read()
    label = os.path.basename(test_tile).replace('.bin', '')
    img, parsed = render_tile(data, label=label)
    out = os.path.join(OUT_DIR, label + '.png')
    img.save(out)
    print(f"  → {out}")
    print(f"  sections: {parsed['sections']}")
    if parsed['poly']:
        print(f"  poly shapes={len(parsed['poly']['starts'])} verts={len(parsed['poly']['verts'])}")
    if parsed['line']:
        print(f"  line shapes={len(parsed['line']['starts'])} verts={len(parsed['line']['verts'])}")

    # Grid of z=14 Prague tiles (s1 = base map)
    print("\nRendering z=14 Prague grid (style 1)...")
    z14_s1 = sorted(glob.glob(os.path.join(TILE_DIR, 'dump_14_*_s1.bin')))
    render_tile_grid(z14_s1, os.path.join(OUT_DIR, 'grid_z14_s1.png'), cols=4)

    # Grid of z=14 Prague tiles (s20 = roads)
    print("\nRendering z=14 Prague grid (style 20)...")
    z14_s20 = sorted(glob.glob(os.path.join(TILE_DIR, 'dump_14_*_s20.bin')))
    render_tile_grid(z14_s20, os.path.join(OUT_DIR, 'grid_z14_s20.png'), cols=4)

    # 2x2 side-by-side of one tile: s1 base + s20 roads + composite overlay
    print("\nRendering s1+s20 side-by-side for tile 8847/5550...")
    s1_path  = os.path.join(TILE_DIR, 'dump_14_8847_5550_s1.bin')
    s20_path = os.path.join(TILE_DIR, 'dump_14_8847_5550_s20.bin')
    combo = Image.new('RGB', (TILE_SIZE * 3, TILE_SIZE), COLORS['bg'])
    with open(s1_path, 'rb') as f: d1 = f.read()
    with open(s20_path, 'rb') as f: d2 = f.read()
    img1, parsed1 = render_tile(d1, label='s1 base polygons')
    img2, parsed2 = render_tile(d2, label='s20 roads')

    # Composite: s1 polygons + s20 roads overlaid
    comp = Image.new('RGB', (TILE_SIZE, TILE_SIZE), COLORS['bg'])
    draw_c = ImageDraw.Draw(comp)
    draw_polygon_pool(draw_c, parsed1['poly'], COLORS['land'])
    draw_line_pool(draw_c, parsed1['line'], COLORS['road_case'], width=3)
    draw_line_pool(draw_c, parsed1['line'], (255,255,255), width=2)
    draw_line_pool(draw_c, parsed2['line'], COLORS['road_case'], width=3)
    draw_line_pool(draw_c, parsed2['line'], (255,255,255), width=2)
    draw_c.text((4, 4), 's1+s20 composite\ntile 14/8847/5550', fill=(0,0,0))

    combo.paste(img1, (0, 0))
    combo.paste(img2, (TILE_SIZE, 0))
    combo.paste(comp, (TILE_SIZE * 2, 0))
    out = os.path.join(OUT_DIR, 'side_by_side_8847_5550.png')
    combo.save(out)
    print(f"  → {out}")

    print(f"\nAll renders in: {OUT_DIR}")
    print("Open with: open /tmp/vmp4_renders/")


if __name__ == '__main__':
    main()
