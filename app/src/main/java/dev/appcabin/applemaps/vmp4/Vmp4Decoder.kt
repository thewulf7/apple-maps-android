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
)

data class VectorTile(
    val tileZ: Int,
    val tileX: Int,
    val tileY: Int,
    val lineFeatures: List<VectorTileFeature>,
    val polygonFeatures: Map<Int, List<VectorTileFeature>>, // chapterType → features
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
private const val CH_POLY_LAND        = 0x20
private const val CH_POLY_WATER       = 0x21
private const val CH_POLY_PARK        = 0x22
private const val CH_POLY_BUILDING    = 0x24
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
        // Per-feature: 1 byte = (hi nibble=labelCount | lo nibble=shieldCount)
        if (pos >= lineCh.size) break
        val byte0 = lineCh[pos++].toInt() and 0xff
        val featLabels  = (byte0 ushr 4) and 0xf
        val featShields = byte0 and 0xf

        val labels = mutableListOf<String>()
        val shields = mutableListOf<Pair<String, Long>>()

        for (j in 0 until featLabels) {
            val (strOff, p7) = readVarUint32(lineCh, pos); pos = p7
            val (strLen, p8) = readVarUint32(lineCh, pos); pos = p8
            val text = if (strOff < stringPool.size) {
                if (strLen == 0L) readNullTermStr(stringPool, strOff.toInt())
                else String(stringPool, strOff.toInt(), strLen.toInt().coerceAtMost(stringPool.size - strOff.toInt()), Charsets.UTF_8)
            } else ""
            if (text.isNotEmpty()) labels.add(text)
        }

        for (j in 0 until featShields) {
            val (strOff, p7) = readVarUint32(lineCh, pos); pos = p7
            val (shieldId, p8) = readVarUint64(lineCh, pos); pos = p8
            val text = if (strOff < stringPool.size) readNullTermStr(stringPool, strOff.toInt()) else ""
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
            repeat(addCnt.toInt()) { val (_, pa) = readVarUint32(polyCh, pos); pos = pa; val (_, pb) = readVarUint32(polyCh, pos); pos = pb }
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
            featureType = featureType,
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
    Log.d("Vmp4Decoder", "toGeoJson: polyChs=${polygonFeatures.size} lineFts=${lineFeatures.size} polyFtsCounts=${polygonFeatures.entries.map{it.value.size}}")

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
            polySb.append(""""properties":{"ft":$ftTag,"label":"${label.replace("\"", "\\\"")}","ch":$chType}}""")
        }
    }
    polySb.append("]}")

    // Lines — road network
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
            lineSb.append(""""properties":{"ft":$ftTag,"label":"${label.replace("\"", "\\\"")}","shield":"${shieldText.replace("\"", "\\\"")}","highway":"road"}}""")
        }
    }
    lineSb.append("]}")

    return Pair(polySb.toString(), lineSb.toString())
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
    val lineFeatures = byType[CH_LINE_FEATURES]?.firstOrNull()?.let { ch ->
        try { decodeLineFeatures(ch, vtxByIndex, stringPool, tileX, tileY, tileZ) }
        catch (e: Exception) { Log.w(TAG, "Line decode error: $e"); emptyList() }
    } ?: emptyList()

    // Polygon features by chapter type
    val polygonFeatures = mutableMapOf<Int, List<VectorTileFeature>>()
    val polyChapterTypes = listOf(
        CH_POLY_LAND     to FeatureType.LAND,
        CH_POLY_WATER    to FeatureType.WATER,
        CH_POLY_PARK     to FeatureType.PARK,
        CH_POLY_BUILDING to FeatureType.BUILDING,
    )
    for ((chType, fType) in polyChapterTypes) {
        byType[chType]?.firstOrNull()?.let { ch ->
            try {
                val feats = decodePolygonFeatures(ch, vtxByIndex, stringPool, fType, tileX, tileY, tileZ)
                if (feats.isNotEmpty()) polygonFeatures[chType] = feats
            } catch (e: Exception) { Log.w(TAG, "Poly[$chType] decode error: $e") }
        }
    }

    return VectorTile(
        tileZ = tileZ, tileX = tileX, tileY = tileY,
        lineFeatures = lineFeatures,
        polygonFeatures = polygonFeatures,
    )
}
