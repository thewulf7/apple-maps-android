package dev.appcabin.applemaps.vmp4

import android.util.Log
import java.io.ByteArrayInputStream
import java.util.zip.Inflater
import kotlin.math.atan
import kotlin.math.sinh
import kotlin.math.PI

private const val TAG = "Vmp4Decoder"

// ─── Public data model ────────────────────────────────────────────────────────

data class LatLon(val lat: Double, val lon: Double)

data class VectorTileFeature(
    val featureType: Byte,           // attributesForFeatureType constants
    val geometry: List<List<LatLon>>, // one inner list per polyline/polygon ring
    val labels: List<String>,
    val shields: List<Pair<String, Long>>, // (text, identifier)
    val roadWidth: Int = 0,          // native stroke width from chapter 0x33 (tile-relative)
)

data class VectorTile(
    val tileZ: Int,
    val tileX: Int,
    val tileY: Int,
    val lineFeatures: List<VectorTileFeature>,
    val polygonFeatures: Map<Int, List<VectorTileFeature>>, // chapterType → features
    val pointFeatures: List<VectorTileFeature> = emptyList(), // POI labels (chapter 0x1e)
)

// Feature type constants from attributesForFeatureType decompile
object FeatureType {
    const val LAND: Byte           = 0x01
    const val WATER: Byte          = 0x02
    const val PARK: Byte           = 0x03
    const val BUILDING: Byte       = 0x04
    const val ROAD_POLYGON: Byte   = 0x05
    const val FREEWAY: Byte        = 0x10
    const val PRIMARY: Byte        = 0x11
    const val SECONDARY: Byte      = 0x12
    const val LOCAL: Byte          = 0x13
    const val PEDESTRIAN: Byte     = 0x14
    const val RAILWAY: Byte        = 0x20
    const val TUNNEL: Byte         = 0x30
    const val BRIDGE: Byte         = 0x31
    const val RAMP: Byte           = 0x40
}

// ─── Chapter IDs (from decompiled GeoCodecs) ─────────────────────────────────

private const val CH_GLOBAL           = 0x01
private const val CH_STRING_POOL      = 0x0a
private const val CH_LABEL_LOCALES    = 0x0b
private const val CH_LABEL_META       = 0x0d
private const val CH_VERTICES         = 0x14
private const val CH_POINT_FEATURES   = 0x1e
private const val CH_LINE_FEATURES    = 0x1f
// Chapter type constants confirmed from _readPolygons.c decompile (Oct 20 2026):
// 0x20 = land + all area features (primary polygon chapter, always present)
// 0x21 = legacy water/park polygons (only present when 0x68 modern buildings is ABSENT)
// 0x22 = coastline new format
// 0x26 = coastline old format
// 0x68 = modern buildings (when present, tile uses 0x20 only; 0x21 absent)
// In _readPolygons: always decode 0x20; if 0x68 present → modern buildings; else decode 0x21
private const val CH_POLY_LAND        = 0x20   // always: land + area features
private const val CH_POLY_LEGACY_WATER_PARK = 0x21  // only when no 0x68: water/park
private const val CH_POLY_COASTLINE_NEW = 0x22
private const val CH_POLY_BUILDING    = 0x24   // (rare/unused in observed tiles)
private const val CH_MODERN_BUILDINGS_VADDR = 0x68  // modern buildings (replaces 0x21 path)
private const val CH_COASTLINE        = 0x26
private const val CH_POLY_CHAR_LINE   = 0x33
private const val CH_POLY_CHAR_POLY   = 0x34
private const val CH_LABEL_META2      = 0x8d
private const val CH_MODERN_BUILDINGS = 0x68
private const val CH_ROAD_NETWORK     = 0x87

// ─── Low-level readers ───────────────────────────────────────────────────────

private fun readVarUint32(buf: ByteArray, pos: Int): Pair<Long, Int> {
    var result = 0L; var shift = 0; var p = pos
    while (true) {
        val b = buf[p++].toLong() and 0xff
        result = result or ((b and 0x7f) shl shift)
        if ((b and 0x80) == 0L) return Pair(result, p)
        shift += 7
        if (shift > 35) throw IllegalStateException("varint overflow at $pos")
    }
}

private fun readVarUint64(buf: ByteArray, pos: Int): Pair<Long, Int> {
    var result = 0L; var shift = 0; var p = pos
    while (true) {
        val b = buf[p++].toLong() and 0xff
        result = result or ((b and 0x7f) shl shift)
        if ((b and 0x80) == 0L) return Pair(result, p)
        shift += 7
        if (shift > 63) throw IllegalStateException("u64 varint overflow at $pos")
    }
}

private fun readNullTermStr(pool: ByteArray, off: Int): String {
    var end = off
    while (end < pool.size && pool[end] != 0.toByte()) end++
    return String(pool, off, end - off, Charsets.UTF_8)
}

/** MSB-first bit reader over a byte array segment */
private class BitReader(private val data: ByteArray, bitStart: Int = 0) {
    var bitPos = bitStart

    fun read(n: Int): Int {
        if (n == 0) return 0
        var v = 0
        repeat(n) {
            val byteIdx = bitPos ushr 3
            val bitIdx  = 7 - (bitPos and 7)
            v = (v shl 1) or ((data[byteIdx].toInt() ushr bitIdx) and 1)
            bitPos++
        }
        return v
    }

    fun readSigned(n: Int): Int {
        if (n == 0) return 0
        val v = read(n)
        return if (v >= (1 shl (n - 1))) v - (1 shl n) else v
    }

    fun bytePos(): Int = bitPos ushr 3
}

// ─── Section / chapter parsing ───────────────────────────────────────────────

private data class Section(val type: Int, val data: ByteArray)

private fun decompressSection(raw: ByteArray): ByteArray {
    if (raw.isEmpty()) return raw
    return when (raw[0].toInt() and 0xff) {
        0 -> raw.copyOfRange(1, raw.size)
        1 -> {
            val decompSize = ((raw[4].toLong() and 0xff) shl 24 or
                             ((raw[3].toLong() and 0xff) shl 16) or
                             ((raw[2].toLong() and 0xff) shl 8) or
                              (raw[1].toLong() and 0xff)).toInt()
            val inflater = Inflater() // standard zlib with header (0x78 0x9c)
            inflater.setInput(raw, 5, raw.size - 5)
            val out = ByteArray(decompSize)
            inflater.inflate(out)
            inflater.end()
            out
        }
        else -> raw.copyOfRange(1, raw.size)
    }
}

private fun parseSections(data: ByteArray): List<Section> {
    // VMP4 header: magic(4) + unknown(2) + sectionCount(2LE) + [type(2LE) + offset(4LE) + size(4LE)]*N
    val magic = String(data, 0, 4, Charsets.US_ASCII)
    check(magic == "VMP4") { "Not a VMP4 tile (magic=$magic)" }
    val numSections = ((data[7].toInt() and 0xff) shl 8) or (data[6].toInt() and 0xff)
    val sections = mutableListOf<Section>()
    for (i in 0 until numSections) {
        val off = 8 + i * 10
        val type   = ((data[off+1].toInt() and 0xff) shl 8) or (data[off].toInt() and 0xff)
        val chOff  = (data[off+2].toLong() and 0xff) or
                     ((data[off+3].toLong() and 0xff) shl 8) or
                     ((data[off+4].toLong() and 0xff) shl 16) or
                     ((data[off+5].toLong() and 0xff) shl 24)
        val chSize = (data[off+6].toLong() and 0xff) or
                     ((data[off+7].toLong() and 0xff) shl 8) or
                     ((data[off+8].toLong() and 0xff) shl 16) or
                     ((data[off+9].toLong() and 0xff) shl 24)
        val raw = data.copyOfRange(chOff.toInt(), (chOff + chSize).toInt())
        sections.add(Section(type, decompressSection(raw)))
    }
    return sections
}

// ─── Coordinate conversion ────────────────────────────────────────────────────

private fun tileNormToLatLon(tileX: Int, tileY: Int, tileZ: Int,
                              normX: Double, normY: Double): LatLon {
    val n = (1 shl tileZ).toDouble()
    val lon = (tileX + normX) * 360.0 / n - 180.0
    val latRad = atan(sinh(PI * (1.0 - 2.0 * (tileY + normY) / n)))
    return LatLon(Math.toDegrees(latRad), lon)
}

// ─── Vertex chapter decoder (geo_codec_decodeVertices + AfterPreamble) ────────

private data class VertexRange(val start: Int, val count: Int)
private data class VtxPool(val vertices: List<Pair<Int,Int>>, val ranges: List<VertexRange>,
                               val coordBits: Int, val normBits: Int)

private fun decodeVertexChapter(ch: ByteArray): VtxPool {
    var pos = 0
    val (rangeCount, p1) = readVarUint32(ch, pos);  pos = p1
    val (vertexCount, p2) = readVarUint32(ch, pos); pos = p2

    // Preamble: MSB-first bitstream, 17 bits total.
    // Layout: coordBits(6) + rlenBits(6) + deltaBitsBits(4) + vertexType(1)
    // normBits = coordBits (copy — NOT a separate bitstream field).
    // From decodeVertices.c: reads 6+6+4+1 = 17 bits, then sets repeatedFirstSixBitValue = firstSixBitValue.
    val bs = BitReader(ch, pos * 8)
    val coordBits  = bs.read(6)   // coordinate bits (absolute x/y)
    val rlenBits   = bs.read(6)   // range_length_bits (extra-vertex count per range)
    val dbBits     = bs.read(4)   // delta_bits_bits (bit width selector per range)
    val vertexType = bs.read(1)   // 0=polyline, 1=curve
    // normBits = coordBits (normalization denominator = (1 << coordBits) - 1)
    // preamble = 17 bits always

    val normDenom = ((1L shl coordBits) - 1L).coerceAtLeast(1L)

    val vertices = mutableListOf<Pair<Int,Int>>()
    val ranges   = mutableListOf<VertexRange>()
    var x = 0; var y = 0; var remaining = 0; var deltaB = 0; var ri = 0

    for (vi in 0 until vertexCount.toInt()) {
        if (bs.bytePos() >= ch.size) break  // guard against reading past end
        if (remaining > 0) {
            val dx = bs.readSigned(deltaB)
            val dy = bs.readSigned(deltaB)
            x += dx; y += dy
            remaining--
            vertices.add(Pair(x, y))
        } else {
            val ax = bs.read(coordBits)
            val ay = bs.read(coordBits)
            val rlenM1 = bs.read(rlenBits)
            val nextDb = bs.read(4)  // 4-bit field specifying delta bit width for this range
            x = ax; y = ay
            remaining = rlenM1
            deltaB = nextDb
            ranges.add(VertexRange(vi, rlenM1 + 1))
            vertices.add(Pair(x, y))
            ri++
        }
    }

    return VtxPool(vertices, ranges, coordBits, coordBits) // normBits = coordBits (confirmed by decodeVertices.c)
}

// ─── POI vertex pool (geo_codec_readPois, chapter 0x14 referenced by section idx) ─
// Plain format: varint pointCount, varint coordCount, then bitstream
// 6b coordBits | 6b yBits(==0) | 4b reserved(==0) | 1b reserved(==0),
// then per point rawX(coordBits) rawY(coordBits). No ranges, no deltas.

private fun decodePoiVertexPool(ch: ByteArray): List<Pair<Int,Int>>? {
    val (pointCount, p1) = readVarUint32(ch, 0)
    val (coordCount, p2) = readVarUint32(ch, p1)
    if (pointCount == 0L || pointCount != coordCount || pointCount > 65536) return null
    val bs = BitReader(ch, p2 * 8)
    val coordBits = bs.read(6)
    if (bs.read(6) != 0 || bs.read(4) != 0 || bs.read(1) != 0) return null
    if (coordBits == 0 || coordBits > 24) return null
    val pts = ArrayList<Pair<Int,Int>>(pointCount.toInt())
    repeat(pointCount.toInt()) {
        val x = bs.read(coordBits)
        val y = bs.read(coordBits)
        pts.add(Pair(x, y))
    }
    return pts
}

// ─── POI feature decoder (geo_codec_readPois, chapter 0x1e) ──────────────────
// Header: vertexChapterIndex | firstPointIndex | labelSpaceNeeded | featureCount.
// Per feature (hp_readLabel + readFeatureOptionalFields):
//   varint labelCount; per label: varint strOff + varint ignored
//   flags byte (6 bits MSB-first): f0 u64, f1 u64, f2 float(priority),
//   f3 varint, f4 varint(styleRecIdx), f5 stylePairs(addN pairs + remN keys)
// Geometry is POSITIONAL: feature i uses vertexPool[firstPointIndex + i].

private fun decodePointFeatures(
    poiCh: ByteArray,
    sections: List<Section>,
    stringPool: ByteArray,
    tileX: Int, tileY: Int, tileZ: Int,
): List<VectorTileFeature> {
    var pos = 0
    val (vtxIdx, p1)       = readVarUint32(poiCh, pos); pos = p1
    val (firstPoint, p2)   = readVarUint32(poiCh, pos); pos = p2
    val (_, p3)            = readVarUint32(poiCh, pos); pos = p3 // labelSpaceNeeded
    val (featureCount, p4) = readVarUint32(poiCh, pos); pos = p4

    val vtxData = sections.getOrNull(vtxIdx.toInt())?.data ?: return emptyList()
    val pool = decodePoiVertexPool(vtxData) ?: return emptyList()
    if (pool.size - firstPoint.toInt() < featureCount.toInt()) return emptyList()

    val feats = mutableListOf<VectorTileFeature>()
    var i = 0
    try {
        while (i < featureCount.toInt()) {
            val (labelCount, p5) = readVarUint32(poiCh, pos); pos = p5
            val labels = mutableListOf<String>()
            repeat(labelCount.toInt()) {
                val (strOff, p6) = readVarUint32(poiCh, pos); pos = p6
                val (_, p7)      = readVarUint32(poiCh, pos); pos = p7 // ignored
                if (strOff in 1 until stringPool.size.toLong()) {
                    labels.add(readNullTermStr(stringPool, strOff.toInt()))
                } else if (strOff == 0L && stringPool.isNotEmpty()) {
                    labels.add(readNullTermStr(stringPool, 0))
                }
            }
            val flags = poiCh[pos].toInt() and 0xff; pos += 1
            // f0: u64, f1: u64, f2: float priority, f3: varint, f4: styleRecIdx, f5: style pairs
            if (flags and 0x80 != 0) pos += 8
            if (flags and 0x40 != 0) pos += 8
            if (flags and 0x20 != 0) pos += 4
            if (flags and 0x10 != 0) { val (_, p) = readVarUint32(poiCh, pos); pos = p }
            if (flags and 0x08 != 0) { val (_, p) = readVarUint32(poiCh, pos); pos = p }
            if (flags and 0x04 != 0) {
                val (addN, pa) = readVarUint32(poiCh, pos); pos = pa
                repeat(addN.toInt()) {
                    val (_, pk) = readVarUint32(poiCh, pos); pos = pk
                    val (_, pv) = readVarUint32(poiCh, pos); pos = pv
                }
                val (remN, pr) = readVarUint32(poiCh, pos); pos = pr
                repeat(remN.toInt()) { val (_, pk) = readVarUint32(poiCh, pos); pos = pk }
            }
            val (rx, ry) = pool[firstPoint.toInt() + i]
            val denom = poiNormDenom(vtxData)
            // POI pool y is inverted vs. the polygon/line vertex convention
            // (verified against hand-known Prague landmarks: Karlův most,
            // Petřínská rozhledna, Pražský hrad, Katedrála sv. Víta all land
            // within ~10m of ground truth only with this flip).
            feats.add(VectorTileFeature(
                featureType = FeatureType.LAND, // POI label anchor
                geometry = listOf(listOf(tileNormToLatLon(tileX, tileY, tileZ,
                    rx.toDouble() / denom, 1.0 - (ry.toDouble() / denom)))),
                labels = labels,
                shields = emptyList(),
            ))
            i++
        }
    } catch (e: Exception) {
        Log.w(TAG, "POI decode desync at $i/$featureCount: ${e.message}")
    }
    return feats
}

private fun poiNormDenom(vtxData: ByteArray): Long {
    // coordBits lives after the two varints in the pool header
    val (_, p1) = readVarUint32(vtxData, 0)
    val (_, p2) = readVarUint32(vtxData, p1)
    val bs = BitReader(vtxData, p2 * 8)
    return ((1L shl bs.read(6)) - 1L).coerceAtLeast(1L)
}

// ─── Modern building footprints (geo_codec_readModernBuildings, chapter 0x68) ──
// Format (from decompile, byte-exact, no guessing needed):
//   1. decodeSectionEncoding: a generic ranges+deltas vertex-pool header+body.
//      Buildings only need vertexCount/coordPrecision from its header — the
//      pool's own vertex data is NOT the building geometry, but MUST be
//      consumed bit-for-bit to keep the cursor aligned.
//   2. auxWidth(5)+1 -> auxMarker(auxWidth) -> hasAuxRecords = marker != 0
//   3. featureCountWidth(5)+1 -> featureCount(featureCountWidth), cap 0x10000
//   4. 5x 4-bit widths: auxRecordCountWidth, xValueWidth, yValueWidth,
//      vertexCountWidth, auxRecordValueWidth
//   5. 4x 1-bit flags: hasXValues, hasYValues, hasFeatureFlag, hasFeatureId
//   6. Per feature: optional aux records (skipped), vertexCount(vertexCountWidth),
//      x values (vertexCount x xValueWidth, raw/coordScale), y values (vertexCount x
//      [1-bit present + yValueWidth if present], raw/coordScale), optional 1-bit
//      flag, optional 1-bit id-present + 64-bit id.
// No trailing style-attribute decode needed — this chapter's bytes are already
// isolated by VMP4's own section framing, so we can stop once geometry is read.

private fun readVarWidth5(bs: BitReader): Int {
    val w = bs.read(5) + 1
    return bs.read(w)
}

private data class SectionVertexPoolHeader(val vertexCount: Int, val coordPrecision: Int)

private fun decodeSectionEncodingConsume(bs: BitReader): SectionVertexPoolHeader {
    val rangeCount = readVarWidth5(bs)
    val vertexCount = readVarWidth5(bs)
    val coordPrecision = bs.read(5)
    val pointCountBitCount = bs.read(5)
    val deltaBitCountBitCount = bs.read(4)
    val coordBitCount = bs.read(5)
    val curveFlag = bs.read(1) == 1
    if (vertexCount > 0 && rangeCount > 0) {
        var vertexIndex = 0
        repeat(rangeCount) {
            if (vertexIndex >= vertexCount) return@repeat
            bs.readSigned(coordBitCount) // range base x (discarded)
            bs.readSigned(coordBitCount) // range base y (discarded)
            vertexIndex++
            val additionalPointCount = bs.read(pointCountBitCount)
            val deltaBitCount = bs.read(deltaBitCountBitCount)
            repeat(additionalPointCount) {
                bs.readSigned(deltaBitCount) // dx (discarded)
                bs.readSigned(deltaBitCount) // dy (discarded)
                vertexIndex++
                if (curveFlag) bs.read(1)
            }
        }
    }
    return SectionVertexPoolHeader(vertexCount, coordPrecision)
}

private fun decodeModernBuildings(
    ch: ByteArray, tileX: Int, tileY: Int, tileZ: Int,
): List<VectorTileFeature> {
    val bs = BitReader(ch, 0)
    val vph = decodeSectionEncodingConsume(bs)

    val auxWidth = bs.read(5) + 1
    val auxMarker = bs.read(auxWidth)
    val hasAuxRecords = auxMarker != 0

    val featureCountWidth = bs.read(5) + 1
    val featureCount = bs.read(featureCountWidth)
    if (featureCount >= 0x10000) return emptyList()

    val auxRecordCountWidth = bs.read(4)
    val xValueWidth = bs.read(4)
    val yValueWidth = bs.read(4)
    val vertexCountWidth = bs.read(4)
    val auxRecordValueWidth = bs.read(4)
    val hasXValues = bs.read(1) == 1
    val hasYValues = bs.read(1) == 1
    val hasFeatureFlag = bs.read(1) == 1
    val hasFeatureId = bs.read(1) == 1

    val coordScale = ((1L shl vph.coordPrecision) - 1L).toDouble().coerceAtLeast(1.0)
    val feats = mutableListOf<VectorTileFeature>()
    try {
        for (i in 0 until featureCount) {
            if (hasAuxRecords) {
                val auxCount = bs.read(auxRecordCountWidth)
                repeat(auxCount) { bs.read(auxRecordValueWidth) }
            }
            val vertexCount = bs.read(vertexCountWidth)
            val xs = DoubleArray(vertexCount)
            val ys = DoubleArray(vertexCount)
            if (hasXValues) {
                for (j in 0 until vertexCount) xs[j] = bs.read(xValueWidth).toDouble() / coordScale
            }
            if (hasYValues) {
                for (j in 0 until vertexCount) {
                    val present = bs.read(1) == 1
                    val v = if (present) bs.read(yValueWidth) else 0
                    ys[j] = v.toDouble() / coordScale
                }
            }
            if (hasFeatureFlag) bs.read(1)
            if (hasFeatureId) {
                val idPresent = bs.read(1) == 1
                if (idPresent) { bs.read(32); bs.read(32) } // skip 64-bit id
            }
            if (vertexCount >= 3) {
                // Building y uses the same inverted convention as POI (verified
                // empirically below) — not the raw polygon/line convention.
                val ring = (0 until vertexCount).map { k ->
                    tileNormToLatLon(tileX, tileY, tileZ, xs[k], 1.0 - ys[k])
                }
                feats.add(VectorTileFeature(FeatureType.BUILDING, listOf(ring), emptyList(), emptyList()))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Building decode error at feature ${feats.size}/$featureCount: ${e.message}")
    }
    return feats
}

// ─── Line feature decoder (_readLines) ───────────────────────────────────────

private fun decodeLineFeatures(
    lineCh: ByteArray,
    vtxByIndex: Map<Int, ByteArray>,
    stringPool: ByteArray,
    tileX: Int, tileY: Int, tileZ: Int,
): List<VectorTileFeature> {
    var pos = 0
    val (vtxIdx, p1) = readVarUint32(lineCh, pos); pos = p1
    val (_, p2)      = readVarUint32(lineCh, pos); pos = p2 // lineCount
    val (_, p3)      = readVarUint32(lineCh, pos); pos = p3 // labelCount
    val (_, p4)      = readVarUint32(lineCh, pos); pos = p4 // shieldCount
    val (_, p5)      = readVarUint32(lineCh, pos); pos = p5 // laneGeometryCount
    val (roadCount, p6) = readVarUint32(lineCh, pos); pos = p6

    // Vertex pool — vtxIdx is the global section index for the vertex chapter
    val vtxPool = vtxByIndex[vtxIdx.toInt()]?.let {
        try { decodeVertexChapter(it) } catch (e: Exception) {
            Log.w(TAG, "Line vertex decode failed: $e"); null
        }
    }

    val normDenom = vtxPool?.let { ((1L shl it.normBits) - 1L).coerceAtLeast(1L) } ?: 8191L

    val features = mutableListOf<VectorTileFeature>()

    for (i in 0 until roadCount.toInt()) {
        // Per-feature: 1 byte bitstream (4b labelCount | 4b shieldCount), byte-aligned
        // From _readLines decompile: bitstreamInitFromChapter + BitstreamUnpack(4) + BitstreamUnpack(4) + byteAlign
        // Net result = same as reading 1 raw byte. Guard EOF on both the byte and subsequent varints.
        if (pos >= lineCh.size) break
        val byte0 = lineCh[pos++].toInt() and 0xff
        val featLabels  = (byte0 ushr 4) and 0xf
        val featShields = byte0 and 0xf

        val labels = mutableListOf<String>()
        val shields = mutableListOf<Pair<String, Long>>()

        for (j in 0 until featLabels) {
            if (pos >= lineCh.size) break
            val (strOff, p7) = readVarUint32(lineCh, pos); pos = p7
            if (pos >= lineCh.size) break
            val (strLen, p8) = readVarUint32(lineCh, pos); pos = p8
            val text = if (strOff > 0L && strOff < stringPool.size) {
                if (strLen == 0L) readNullTermStr(stringPool, strOff.toInt())
                else String(stringPool, strOff.toInt(), strLen.toInt().coerceAtMost(stringPool.size - strOff.toInt()), Charsets.UTF_8)
            } else ""
            if (text.isNotEmpty()) labels.add(text)
        }

        for (j in 0 until featShields) {
            if (pos >= lineCh.size) break
            val (strOff, p7) = readVarUint32(lineCh, pos); pos = p7
            if (pos >= lineCh.size) break
            val (shieldId, p8) = readVarUint64(lineCh, pos); pos = p8
            val text = if (strOff > 0L && strOff < stringPool.size) readNullTermStr(stringPool, strOff.toInt()) else ""
            shields.add(Pair(text, shieldId))
        }

        // Geometry from vertex pool range i
        val geometry = mutableListOf<List<LatLon>>()
        vtxPool?.let { pool ->
            if (i < pool.ranges.size) {
                val range = pool.ranges[i]
                val ring = (range.start until range.start + range.count)
                    .map { vi ->
                        val (vx, vy) = pool.vertices[vi]
                        val nx = vx.toDouble() / normDenom
                        val ny = vy.toDouble() / normDenom
                        tileNormToLatLon(tileX, tileY, tileZ, nx, ny)
                    }
                if (ring.isNotEmpty()) geometry.add(ring)
            }
        }

        features.add(VectorTileFeature(
            featureType = FeatureType.LOCAL, // default; overridden by materials if present
            geometry = geometry,
            labels = labels,
            shields = shields,
        ))
    }
    return features
}

// ─── Polygon feature decoder (_readPolygonsofType) ───────────────────────────

private fun decodePolygonFeatures(
    polyCh: ByteArray,
    vtxByIndex: Map<Int, ByteArray>,
    stringPool: ByteArray,
    featureType: Byte,
    tileX: Int, tileY: Int, tileZ: Int,
): List<VectorTileFeature> {
    var pos = 0

    // Header: vtxChapterIndex + vtxChapterDetail + labelSpaceNeeded + featureCount (4 varints)
    val (vtxIdx, p1)       = readVarUint32(polyCh, pos); pos = p1
    val (_, p2)            = readVarUint32(polyCh, pos); pos = p2 // vtxChapterDetail
    val (_, p3)            = readVarUint32(polyCh, pos); pos = p3 // labelSpaceNeeded
    val (featureCount, p4) = readVarUint32(polyCh, pos); pos = p4

    val vtxPool = vtxByIndex[vtxIdx.toInt()]?.let {
        try { decodeVertexChapter(it) } catch (e: Exception) {
            Log.w(TAG, "Poly vertex decode failed idx=${vtxIdx}: $e"); null
        }
    }

    val normDenom = vtxPool?.let { ((1L shl it.normBits) - 1L).coerceAtLeast(1L) } ?: 8191L

    val features = mutableListOf<VectorTileFeature>()

    // Per-feature geometry: flags byte has BIT-REVERSED constants vs. C decompile.
    // Empirically confirmed: 0x40=hasFeatureId(uint64), 0x08=hasInlineAttrs, etc.
    // Bit mapping (actual byte bit → logical field):
    //   bit7(0x80) = hasPointCount    (decompile: 0x01)
    //   bit6(0x40) = hasFeatureId     (decompile: 0x02, uint64 8B)
    //   bit5(0x20) = hasSortKey       (decompile: 0x04, varint)
    //   bit4(0x10) = hasDispPriority  (decompile: 0x08, 4B float)
    //   bit3(0x08) = hasInlineAttrs   (decompile: 0x10, accumulateStyleAttributes)
    //   bit2(0x04) = hasBuildingFlag  (decompile: 0x20, varint)
    var rp = 0  // running range pointer into vtxPool.ranges

    for (i in 0 until featureCount.toInt()) {
        if (pos >= polyCh.size) break
        var inlineFeatureType = featureType  // overridden per-feature by style attrs

        // hp_readLabel: encodedLabelCount (LEB128) + N×(textOffset + unusedValue) LEB128 pairs
        val (encodedLabelCount, p5) = readVarUint32(polyCh, pos); pos = p5
        val labelCount = encodedLabelCount.toInt()
        val labels = mutableListOf<String>()
        for (j in 0 until labelCount) {
            val (strOff, p6) = readVarUint32(polyCh, pos); pos = p6
            val (_, p7)      = readVarUint32(polyCh, pos); pos = p7 // unusedValue
            val labelText = if (strOff > 0L && strOff < stringPool.size) {
                readNullTermStr(stringPool, strOff.toInt())
            } else ""
            if (labelText.isNotEmpty()) labels.add(labelText)
        }

        if (pos >= polyCh.size) break
        val flags = polyCh[pos++].toInt() and 0xff

        // pointCount: how many consecutive vtxPool ranges belong to this feature
        var pc = 1
        if (flags and 0x80 != 0) {
            val (pcV, p8) = readVarUint32(polyCh, pos); pos = p8; pc = pcV.toInt().coerceAtLeast(1)
        }
        if (flags and 0x40 != 0) { pos += 8 }  // featureId (uint64, 8 bytes)
        if (flags and 0x20 != 0) { val (_, px) = readVarUint32(polyCh, pos); pos = px } // sortKey
        if (flags and 0x10 != 0) { pos += 4 }  // dispPriority (float, 4 bytes)
        if (flags and 0x08 != 0) {              // inlineStyleAttributes (accumulateStyleAttributes)
            val (addCnt, p9) = readVarUint32(polyCh, pos); pos = p9
            // Collect style attributes — key 71 = area feature type discriminator:
            //   71=2 → WATER (Vltava river, confirmed centroid lat=50.079 lon=14.407)
            //   71=1 or 71=5 → PARK (Stromovka/Letná parks and river islands)
            //   everything else → LAND (default urban/terrain polygons)
            // Validated empirically against 27 real Prague z14 style=1 tiles (Oct 20 2026)
            var styleKey71: Long? = null
            repeat(addCnt.toInt()) {
                val (styleKey, pa) = readVarUint32(polyCh, pos); pos = pa
                val (styleVal, pb) = readVarUint32(polyCh, pos); pos = pb
                if (styleKey == 71L) styleKey71 = styleVal
            }
            // Override featureType based on native style attribute
            if (styleKey71 != null) {
                inlineFeatureType = when (styleKey71) {
                    2L    -> FeatureType.WATER
                    1L, 5L -> FeatureType.PARK
                    else  -> featureType  // keep caller-supplied default (LAND)
                }
            }
            val (remCnt, pc2) = readVarUint32(polyCh, pos); pos = pc2
            repeat(remCnt.toInt()) { val (_, pd) = readVarUint32(polyCh, pos); pos = pd }
        }
        if (flags and 0x04 != 0) { val (_, pe) = readVarUint32(polyCh, pos); pos = pe } // buildingFlag

        // Geometry: pc consecutive ranges from vtxPool (outer ring + optional inner rings)
        val geometry = mutableListOf<List<LatLon>>()
        vtxPool?.let { pool ->
            for (ri in rp until (rp + pc)) {
                if (ri >= pool.ranges.size) break
                val range = pool.ranges[ri]
                if (range.count < 2) continue
                val ring = (range.start until range.start + range.count).map { vi ->
                    val (vx, vy) = pool.vertices[vi]
                    tileNormToLatLon(tileX, tileY, tileZ, vx.toDouble() / normDenom, vy.toDouble() / normDenom)
                }
                if (ring.size >= 2) geometry.add(ring)
            }
        }
        rp += pc

        features.add(VectorTileFeature(
            featureType = inlineFeatureType,
            geometry    = geometry,
            labels      = labels,
            shields     = emptyList(),
        ))
    }
    Log.d(TAG, "decodePolyFeatures: fc=$featureCount got=${features.size} rp=$rp pos=$pos/${polyCh.size}")
    return features
}

// ─── Main entry point ─────────────────────────────────────────────────────────

// ─── GeoJSON emission ─────────────────────────────────────────────────────────

fun VectorTile.toGeoJson(): Pair<String, String> {
    // Returns (polygonGeoJson, lineGeoJson)
    val polySb = StringBuilder("""{"type":"FeatureCollection","features":[""")
    val lineSb = StringBuilder("""{"type":"FeatureCollection","features":[""")
    var pf = true; var lf = true

    fun appendCoord(sb: StringBuilder, ll: LatLon) {
        sb.append("[%.6f,%.6f]".format(java.util.Locale.US, ll.lon, ll.lat))
    }

    // Polygons — land/water/park/building
    for ((chType, feats) in polygonFeatures) {
        for (feat in feats) {
            if (feat.geometry.isEmpty()) continue
            val ring = feat.geometry.first()
            if (ring.size < 3) continue
            if (!pf) polySb.append(','); pf = false
            val ftTag = feat.featureType.toInt() and 0xff
            val label = feat.labels.firstOrNull() ?: ""
            polySb.append("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[""")
            ring.forEachIndexed { i, ll ->
                if (i > 0) polySb.append(',')
                appendCoord(polySb, ll)
            }
            // Close ring
            polySb.append(','); appendCoord(polySb, ring.first())
            polySb.append("""]]},""")
            polySb.append(""""properties":{"ft":$ftTag,"label":"${label.jsonStr()}","ch":$chType}}""")
        }
    }
    polySb.append("]}")

    // Lines — road network
    // Native class from Apple's own stroke widths (chapter 0x33). Widths are encoded
    // against the tile vertex grid, so absolute scale varies per tile/zoom; classify
    // by percentile rank within the tile's width distribution. Fall back to the
    // shield/label heuristic when 0x33 is absent or all-zero (e.g. z13 tiles).
    val wSorted = lineFeatures.map { it.roadWidth }.filter { it > 0 }.sorted()
    val hasWidths = wSorted.isNotEmpty()
    fun widthRank(w: Int): Double =
        if (!hasWidths || w <= 0) -1.0 else java.util.Collections.binarySearch(
            wSorted, w).let { idx -> (if (idx < 0) -idx - 1 else idx).toDouble() / (wSorted.size - 1).coerceAtLeast(1) }
    for (feat in lineFeatures) {
        if (feat.geometry.isEmpty()) continue
        for (ring in feat.geometry) {
            if (ring.size < 2) continue
            if (!lf) lineSb.append(','); lf = false
            val ftTag = feat.featureType.toInt() and 0xff
            val label = feat.labels.firstOrNull() ?: ""
            val shieldText = feat.shields.firstOrNull()?.first ?: ""
            lineSb.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
            ring.forEachIndexed { i, ll ->
                if (i > 0) lineSb.append(',')
                appendCoord(lineSb, ll)
            }
            lineSb.append("""]},""")
            val rank = widthRank(feat.roadWidth)
            val cls = when {
                rank >= 0.85 || shieldText.isNotEmpty() -> "major"
                rank >= 0.45 || label.isNotEmpty()      -> "named"
                else                                    -> "minor"
            }
            lineSb.append(""""properties":{"ft":$ftTag,"label":"${label.jsonStr()}","shield":"${shieldText.jsonStr()}","cls":"$cls","highway":"road"}}""")
        }
    }
    lineSb.append("]}")

    return Pair(polySb.toString(), lineSb.toString())
}

// POI labels → point GeoJSON for SRC_POINT (chapter 0x1e, decoded positionally)
fun VectorTile.toPointGeoJson(): String {
    val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
    var first = true
    for (feat in pointFeatures) {
        val name = feat.labels.firstOrNull()?.jsonStr() ?: continue
        if (name.isEmpty()) continue
        val pt = feat.geometry.firstOrNull()?.firstOrNull() ?: continue
        if (!first) sb.append(','); first = false
        sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
        sb.append("[%.6f,%.6f]".format(java.util.Locale.US, pt.lon, pt.lat))
        sb.append("""]},"properties":{"label":"$name"}}""")
    }
    sb.append("]}")
    return sb.toString()
}

// Sanitize a raw VMP4 string for embedding in JSON: NULs/control chars and
// quotes/backslashes would otherwise poison the whole FeatureCollection parse.
private fun String.jsonStr(): String {
    val sb = StringBuilder(length)
    for (c in this) {
        when {
            c < ' ' || c == '\u007f' -> {} // drop control chars incl. NUL
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// ─── Chapter 0x33: characteristic points (road stroke widths) ────────────────
// Format (verified bit-exact on 7 cities, v113): zlib body after uncompressed
// prefix. Header: varint strokeSpecCount(=65535 sentinel), pointCount, runCount.
// Then runCount runs of (characteristicId varint, runLength varint); per point:
//   styleIndex varint; f = rawFlags byte PEEKED at cursor, then TWO bytes consumed
//   (10-bit flag sync: 3 condition bits + 7 field bits span bytes P and P+1);
//   conditions read AFTER the 2-byte advance: bit7 inherit-varint, bit6 d0-varint,
//   bit5 float-varint; bit4 strokeWidth varint; bit3 colorData (nb,nh then
//   2*(nb+nh) varints). Runs align 1:1 with 0x1f roadFeatureCount.
private fun decodeRoadCharacteristics(body: ByteArray): IntArray {
    var pos = 0
    val (_, p1) = readVarUint32(body, pos); pos = p1          // strokeSpecCount (sentinel)
    val (pointCount, p2) = readVarUint32(body, pos); pos = p2
    val (runCount, p3) = readVarUint32(body, pos); pos = p3
    if (pointCount > 200_000L || runCount > 200_000L) return IntArray(0)
    val widths = IntArray(runCount.toInt())
    for (r in 0 until runCount.toInt()) {
        if (pos >= body.size) break
        val (_, pa) = readVarUint32(body, pos); pos = pa      // characteristicId
        val (runLen, pb) = readVarUint32(body, pos); pos = pb
        var maxW = 0
        repeat(runLen.toInt()) {
            if (pos + 2 >= body.size) return@repeat
            val (_, pc) = readVarUint32(body, pos); pos = pc  // styleIndex
            val f = body[pos].toInt() and 0xff
            pos += 2                                          // 10-bit flag sync
            if (f and 0x80 != 0) { val (_, pd) = readVarUint32(body, pos); pos = pd }
            if (f and 0x40 != 0) { val (_, pe) = readVarUint32(body, pos); pos = pe }
            if (f and 0x20 != 0) { val (_, pf) = readVarUint32(body, pos); pos = pf }
            if (f and 0x10 != 0) {
                val (w, pg) = readVarUint32(body, pos); pos = pg
                if (w > maxW) maxW = w.toInt()
            }
            if (f and 0x08 != 0) {
                val (nb, ph) = readVarUint32(body, pos); pos = ph
                val (nh, pi) = readVarUint32(body, pos); pos = pi
                if (nb > 64 || nh > 64) return@repeat
                repeat(nb.toInt() + nh.toInt()) {
                    val (_, pk) = readVarUint32(body, pos); pos = pk
                    val (_, pl) = readVarUint32(body, pos); pos = pl
                }
            }
        }
        widths[r] = maxW
    }
    return widths
}

fun decodeVmp4Tile(data: ByteArray, tileZ: Int, tileX: Int, tileY: Int): VectorTile {
    val sections = parseSections(data)

    // Collect by type
    val byType = mutableMapOf<Int, MutableList<ByteArray>>()
    for (s in sections) byType.getOrPut(s.type) { mutableListOf() }.add(s.data)

    val stringPool = byType[CH_STRING_POOL]?.firstOrNull() ?: ByteArray(0)

    // Map global section index → vertex chapter data (vtxIdx in feature chapters = global section index)
    val vtxByIndex = mutableMapOf<Int, ByteArray>()
    for ((i, s) in sections.withIndex()) {
        if (s.type == CH_VERTICES) vtxByIndex[i] = s.data
    }
    // Also expose as ordered list (for callers that use sequential indexing)
    val vtxChapters: List<ByteArray> = sections.filter { it.type == CH_VERTICES }.map { it.data }

    // Line features (roads)
    val lineFeaturesRaw = byType[CH_LINE_FEATURES]?.firstOrNull()?.let { ch ->
        try { decodeLineFeatures(ch, vtxByIndex, stringPool, tileX, tileY, tileZ) }
        catch (e: Exception) { Log.w(TAG, "Line decode error: $e"); emptyList() }
    } ?: emptyList()

    // Native road stroke widths (chapter 0x33) join 1:1 by run index with
    // roadFeatureCount; attach to features so class can use Apple's own data.
    val lineFeatures = byType[CH_POLY_CHAR_LINE]?.firstOrNull()?.let { ch ->
        try {
            val widths = decodeRoadCharacteristics(ch)
            if (widths.isNotEmpty() && widths.size == lineFeaturesRaw.size) {
                Log.d(TAG, "Road characteristics 0x33: ${widths.size} widths joined")
                lineFeaturesRaw.mapIndexed { i, f -> f.copy(roadWidth = widths[i]) }
            } else {
                if (widths.isNotEmpty()) Log.w(TAG, "0x33 runs=${widths.size} != lines=${lineFeaturesRaw.size}; widths dropped")
                lineFeaturesRaw
            }
        } catch (e: Exception) { Log.w(TAG, "0x33 decode error: $e"); lineFeaturesRaw }
    } ?: lineFeaturesRaw

    // Polygon features — mirroring geo_codec__readPolygons logic (confirmed from decompile):
    // 1. Always decode 0x20 (land + all area features)
    // 2. If 0x68 (modern buildings) chapter present → decode it as buildings, SKIP 0x21
    // 3. Else → decode 0x21 (legacy water/park) if present
    val polygonFeatures = mutableMapOf<Int, List<VectorTileFeature>>()

    byType[CH_POLY_LAND]?.firstOrNull()?.let { ch ->
        try {
            val feats = decodePolygonFeatures(ch, vtxByIndex, stringPool, FeatureType.LAND, tileX, tileY, tileZ)
            if (feats.isNotEmpty()) polygonFeatures[CH_POLY_LAND] = feats
        } catch (e: Exception) { Log.w(TAG, "Poly[0x20] decode error: $e") }
    }

    val hasModernBuildings = byType[CH_MODERN_BUILDINGS_VADDR] != null
    if (hasModernBuildings) {
        // Modern buildings chapter (0x68) present — skip legacy water/park path
        byType[CH_MODERN_BUILDINGS_VADDR]?.firstOrNull()?.let { ch ->
            try {
                val feats = decodeModernBuildings(ch, tileX, tileY, tileZ)
                if (feats.isNotEmpty()) polygonFeatures[CH_MODERN_BUILDINGS_VADDR] = feats
                Log.d(TAG, "Modern buildings 0x68: ${feats.size} features")
            } catch (e: Exception) { Log.w(TAG, "Poly[0x68] decode error: $e") }
        }
    } else {
        // Legacy path: 0x21 = water + park polygons
        byType[CH_POLY_LEGACY_WATER_PARK]?.firstOrNull()?.let { ch ->
            try {
                val feats = decodePolygonFeatures(ch, vtxByIndex, stringPool, FeatureType.WATER, tileX, tileY, tileZ)
                if (feats.isNotEmpty()) polygonFeatures[CH_POLY_LEGACY_WATER_PARK] = feats
                Log.d(TAG, "Legacy water/park 0x21: ${feats.size} features")
            } catch (e: Exception) { Log.w(TAG, "Poly[0x21] decode error: $e") }
        }
    }

    // POI point features (chapter 0x1e) — labels + positional anchors
    val pointFeatures = byType[CH_POINT_FEATURES]?.firstOrNull()?.let { ch ->
        try { decodePointFeatures(ch, sections, stringPool, tileX, tileY, tileZ) }
        catch (e: Exception) { Log.w(TAG, "POI decode error: $e"); emptyList() }
    } ?: emptyList()

    return VectorTile(
        tileZ = tileZ, tileX = tileX, tileY = tileY,
        lineFeatures = lineFeatures,
        polygonFeatures = polygonFeatures,
        pointFeatures = pointFeatures,
    )
}
