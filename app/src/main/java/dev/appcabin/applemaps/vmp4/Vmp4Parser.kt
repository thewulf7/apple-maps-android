package dev.appcabin.applemaps.vmp4

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * VMP4 tile parser — Apple Maps proprietary vector tile format.
 * Ported from vmp4-dump (Rust, MIT) by Kenan Sulayman.
 *
 * Decodes: container → sections → vertices + features + labels → GeoJSON.
 * Coordinate system: tile-local normalized [0,1] → WGS84 via slippy-map math.
 */

// ── Container ──────────────────────────────────────────────────────────

data class Vmp4Tile(
    val sections: List<Vmp4Section>,
) {
    companion object {
        fun parse(data: ByteArray): Vmp4Tile {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(magic.decodeToString() == "VMP4") { "Not a VMP4 tile" }
            buf.short // skip 2 unknown bytes
            val numSections = buf.short.toInt() and 0xFFFF

            data class Header(val typeId: Int, val offset: Int, val size: Int)
            val headers = (0 until numSections).map {
                Header(
                    buf.short.toInt() and 0xFFFF,
                    buf.int,
                    buf.int,
                )
            }

            return Vmp4Tile(headers.map { h ->
                val raw = data.copyOfRange(h.offset, h.offset + h.size)
                Vmp4Section(h.typeId, decompressSection(raw))
            })
        }

        private fun decompressSection(raw: ByteArray): ByteArray {
            if (raw.isEmpty()) return raw
            val compressed = raw[0] != 0.toByte()
            if (!compressed) return raw.copyOfRange(1, raw.size)
            val decompSize = ByteBuffer.wrap(raw, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val payload = raw.copyOfRange(5, raw.size)
            val inflater = Inflater()
            inflater.setInput(payload)
            val out = ByteArrayOutputStream(decompSize)
            val tmp = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(tmp)
                if (n == 0) break
                out.write(tmp, 0, n)
            }
            inflater.end()
            return out.toByteArray()
        }
    }
}

data class Vmp4Section(val typeId: Int, val data: ByteArray)

// Section type IDs (from GeoServices decompilation)
object SectionTypes {
    const val GLOBAL = 0x01
    const val LABELS = 0x0A
    const val LABEL_LANGUAGES = 0x0B
    const val LABEL_LOCALIZATIONS = 0x0D
    const val VERTICES = 0x14       // 20
    const val POINT_FEATURES = 0x1E // 30
    const val LINE_FEATURES = 0x1F  // 31
    const val POLYGON_FEATURES = 0x20 // 32
    const val COORDINATE_BOUNDS = 0x1F // TODO: verify
    const val COASTLINE = 0x26
}

// ── Varint reader ──────────────────────────────────────────────────────

class ChapterReader(private val data: ByteArray) {
    var pos = 0

    fun remaining() = data.size - pos
    fun hasMore() = pos < data.size

    fun readU8(): Int {
        require(pos < data.size)
        return data[pos++].toInt() and 0xFF
    }

    fun readU16LE(): Int {
        require(pos + 2 <= data.size)
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun readU32LE(): Int {
        require(pos + 4 <= data.size)
        val v = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
        pos += 4
        return v
    }

    fun readF32LE(): Float {
        require(pos + 4 <= data.size)
        val v = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float
        pos += 4
        return v
    }

    fun readVarUint32(): Int {
        var result = 0
        var shift = 0
        while (true) {
            if (pos >= data.size) throw IllegalStateException("EOF in varint")
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 35) throw IllegalStateException("Varint overflow")
        }
    }

    fun toBitStream() = BitStream(data, pos * 8)
}

// ── Bitstream reader ───────────────────────────────────────────────────

class BitStream(private val data: ByteArray, private var bitPos: Int = 0) {

    fun readBits(n: Int): Int {
        if (n == 0) return 0
        var result = 0
        var remaining = n
        while (remaining > 0) {
            val byteOff = bitPos ushr 3
            val bitOff = bitPos and 7
            val avail = 8 - bitOff
            val take = minOf(remaining, avail)
            val shift = 8 - bitOff - take
            val mask = (1 shl take) - 1
            val bits = (data[byteOff].toInt() ushr shift) and mask
            result = (result shl take) or bits
            bitPos += take
            remaining -= take
        }
        return result
    }

    fun readBitsSigned(n: Int): Int {
        if (n == 0) return 0
        val v = readBits(n)
        val signBit = 1 shl (n - 1)
        return if (v and signBit != 0) v or ((-1) shl n) else v
    }

    fun readBit(): Boolean = readBits(1) != 0
}

// ── Vertex decoding ────────────────────────────────────────────────────

data class Vertex(val x: Float, val y: Float)

data class VertexPool(
    val precisionBits: Int,
    val vertices: List<Vertex>,
    /** Start index in `vertices` for each shape */
    val shapeStarts: List<Int>,
    /** Vertex count per shape */
    val shapeLengths: List<Int>,
)

fun decodeVertices(section: Vmp4Section): VertexPool? {
    val r = ChapterReader(section.data)
    val shapeCount = try { r.readVarUint32() } catch (_: Exception) { return null }
    val vertexCount = try { r.readVarUint32() } catch (_: Exception) { return null }
    if (shapeCount == 0 || vertexCount == 0) return null

    val bs = r.toBitStream()
    val coordBits = bs.readBits(6)
    val deltaBits = bs.readBits(6)
    val runBits = bs.readBits(4)
    val hasCurves = bs.readBit()

    val scale = 1.0f / ((1 shl coordBits) - 1).toFloat()
    val verts = mutableListOf<Vertex>()
    val starts = mutableListOf<Int>()
    val lengths = mutableListOf<Int>()
    var remaining = vertexCount
    var cx = 0; var cy = 0

    for (s in 0 until shapeCount) {
        val runLen = if (remaining > 0) {
            if (runBits > 0) bs.readBits(runBits) else remaining
        } else 0
        starts.add(verts.size)
        lengths.add(runLen)

        for (v in 0 until runLen) {
            if (v == 0) {
                cx = bs.readBits(coordBits)
                cy = bs.readBits(coordBits)
            } else {
                cx += bs.readBitsSigned(deltaBits)
                cy += bs.readBitsSigned(deltaBits)
            }
            if (hasCurves) bs.readBit() // skip curve flag
            verts.add(Vertex(cx * scale, cy * scale))
        }
        remaining -= runLen
    }

    return VertexPool(coordBits, verts, starts, lengths)
}

// ── Feature decoding ───────────────────────────────────────────────────

data class PointFeature(val vertexIndex: Int, val featureType: Int, val styleIndex: Int)
data class LineFeature(val vertexStart: Int, val vertexCount: Int, val featureType: Int, val styleIndex: Int)
data class PolygonFeature(val vertexStart: Int, val outerCount: Int, val holeCount: Int, val featureType: Int, val styleIndex: Int)

fun decodePointFeatures(section: Vmp4Section): List<PointFeature> {
    val r = ChapterReader(section.data)
    val count = try { r.readVarUint32() } catch (_: Exception) { return emptyList() }
    return (0 until count).mapNotNull {
        try {
            PointFeature(r.readVarUint32(), r.readVarUint32(), r.readVarUint32())
        } catch (_: Exception) { null }
    }
}

fun decodeLineFeatures(section: Vmp4Section): List<LineFeature> {
    val r = ChapterReader(section.data)
    val count = try { r.readVarUint32() } catch (_: Exception) { return emptyList() }
    return (0 until count).mapNotNull {
        try {
            LineFeature(r.readVarUint32(), r.readVarUint32(), r.readVarUint32(), r.readVarUint32())
        } catch (_: Exception) { null }
    }
}

fun decodePolygonFeatures(section: Vmp4Section): List<PolygonFeature> {
    val r = ChapterReader(section.data)
    val count = try { r.readVarUint32() } catch (_: Exception) { return emptyList() }
    return (0 until count).mapNotNull {
        try {
            PolygonFeature(r.readVarUint32(), r.readVarUint32(), r.readVarUint32(), r.readVarUint32(), r.readVarUint32())
        } catch (_: Exception) { null }
    }
}

// ── Label decoding ─────────────────────────────────────────────────────

fun decodeLabels(section: Vmp4Section): List<String> {
    // Labels are null-terminated UTF-8 strings
    val result = mutableListOf<String>()
    var start = 0
    for (i in section.data.indices) {
        if (section.data[i] == 0.toByte()) {
            if (i > start) {
                result.add(section.data.copyOfRange(start, i).decodeToString())
            }
            start = i + 1
        }
    }
    if (start < section.data.size) {
        result.add(section.data.copyOfRange(start, section.data.size).decodeToString())
    }
    return result
}

// ── Tile → WGS84 conversion ───────────────────────────────────────────

/** Convert tile-local normalized (0-1) coords to WGS84 [lon, lat]. */
fun tileToWgs84(tileX: Int, tileY: Int, zoom: Int, normX: Float, normY: Float): DoubleArray {
    val n = (1 shl zoom).toDouble()
    val lon = (tileX + normX) / n * 360.0 - 180.0
    val latRad = atan(sinh(PI * (1.0 - 2.0 * (tileY + normY) / n)))
    val lat = latRad * 180.0 / PI
    return doubleArrayOf(lon, lat)
}

// ── Full tile parse → GeoJSON ──────────────────────────────────────────

data class ParsedTile(
    /** Per-feature-type vertex pools, paired with their features */
    val pointVertices: VertexPool?,
    val lineVertices: VertexPool?,
    val polygonVertices: VertexPool?,
    val points: List<PointFeature>,
    val lines: List<LineFeature>,
    val polygons: List<PolygonFeature>,
    val labels: List<String>,
) {
    /** Legacy: first available vertex pool (for backward compat) */
    val vertices: VertexPool? get() = pointVertices ?: lineVertices ?: polygonVertices
}

fun parseTile(data: ByteArray): ParsedTile {
    val tile = Vmp4Tile.parse(data)

    var pointVerts: VertexPool? = null
    var lineVerts: VertexPool? = null
    var polyVerts: VertexPool? = null
    var points = listOf<PointFeature>()
    var lines = listOf<LineFeature>()
    var polygons = listOf<PolygonFeature>()
    var labels = listOf<String>()

    // The section order is: Vertices → Features, Vertices → Features, ...
    // Each feature section uses the immediately preceding Vertices section.
    var lastVertexPool: VertexPool? = null

    for (section in tile.sections) {
        when (section.typeId) {
            SectionTypes.VERTICES -> {
                lastVertexPool = decodeVertices(section)
            }
            SectionTypes.POINT_FEATURES -> {
                points = decodePointFeatures(section)
                pointVerts = lastVertexPool
            }
            SectionTypes.LINE_FEATURES -> {
                lines = decodeLineFeatures(section)
                lineVerts = lastVertexPool
            }
            SectionTypes.POLYGON_FEATURES -> {
                polygons = decodePolygonFeatures(section)
                polyVerts = lastVertexPool
            }
            SectionTypes.LABELS -> labels = decodeLabels(section)
        }
    }

    return ParsedTile(pointVerts, lineVerts, polyVerts, points, lines, polygons, labels)
}

/**
 * Convert a parsed VMP4 tile to GeoJSON string.
 * ponytail: skip broken feature metadata, use vertex shapes directly.
 * Each vertex pool's shapes are the geometry — pool paired with its feature section type
 * tells us point/line/polygon.
 */
fun toGeoJson(parsed: ParsedTile, tileX: Int, tileY: Int, zoom: Int): String {
    val sb = StringBuilder()
    sb.append("""{"type":"FeatureCollection","features":[""")
    var first = true

    fun comma() { if (!first) sb.append(','); first = false }
    fun coord(v: Vertex): String {
        val (lon, lat) = tileToWgs84(tileX, tileY, zoom, v.x, v.y)
        return "[%.6f,%.6f]".format(java.util.Locale.US, lon, lat)
    }

    // Polygons — each shape in polygonVertices is a ring
    parsed.polygonVertices?.let { pool ->
        for (i in pool.shapeStarts.indices) {
            val start = pool.shapeStarts[i]
            val len = pool.shapeLengths[i]
            if (len < 3) continue // need at least 3 verts for polygon
            if (start + len > pool.vertices.size) continue
            comma()
            sb.append("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[""")
            for (j in 0 until len) {
                if (j > 0) sb.append(',')
                sb.append(coord(pool.vertices[start + j]))
            }
            // Close ring
            sb.append(',').append(coord(pool.vertices[start]))
            sb.append("""]]},""")
            sb.append(""""properties":{"kind":"polygon"}}""")
        }
    }

    // Lines — each shape is a polyline
    parsed.lineVertices?.let { pool ->
        for (i in pool.shapeStarts.indices) {
            val start = pool.shapeStarts[i]
            val len = pool.shapeLengths[i]
            if (len < 2) continue // need at least 2 verts for line
            if (start + len > pool.vertices.size) continue
            comma()
            sb.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
            for (j in 0 until len) {
                if (j > 0) sb.append(',')
                sb.append(coord(pool.vertices[start + j]))
            }
            sb.append("""]},"properties":{"kind":"line"}}""")
        }
    }

    // Points — each vertex in pointVertices pool
    parsed.pointVertices?.let { pool ->
        for (v in pool.vertices) {
            comma()
            sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":${coord(v)}}""")
            sb.append(""","properties":{"kind":"point"}}""")
        }
    }

    sb.append("]}")
    return sb.toString()
}
