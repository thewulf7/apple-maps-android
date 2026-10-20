package dev.appcabin.applemaps

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.request.forms.*
import kotlinx.coroutines.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.*
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import dev.appcabin.applemaps.vmp4.*
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Native Apple Maps renderer: MapLibre + VMP4 vector tiles.
 * ponytail: fetches style 1 (buildings/polygons) + style 20 (road network)
 * and merges them into a single render.
 */
class NativeMapView(private val context: Context,
                    private val initLat: Double = 50.0868,
                    private val initLon: Double = 14.4115,
                    private val initZoom: Double = 15.0) {

    companion object {
        private const val TAG = "NativeMapView"
        private const val SRC_POLY = "apple-poly"
        private const val SRC_LINE = "apple-line"
        private const val SRC_POINT = "apple-point"
    }

    private val client = HttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var map: MapLibreMap? = null
    private val tileCache = object : LinkedHashMap<String, VectorTile>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VectorTile>?) = size > 256
    }
    /** Per-tile style 99 ft maps: key="z/x/y", value=IntArray[512*512] of ft values */
    private val style99Cache = object : LinkedHashMap<String, IntArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 256
    }
    /** Track which style99 image sources have been added to the MapLibre style (avoid duplicates). */
    private val style99Added = mutableSetOf<String>()
    val mapView: MapView

    init {
        MapLibre.getInstance(context)
        val opts = MapLibreMapOptions.createFromAttributes(context)
        mapView = MapView(context, opts)
    }

    fun onCreate() {
        mapView.getMapAsync { mlMap ->
            map = mlMap
            mlMap.setStyle(Style.Builder().fromJson(BLANK_STYLE)) { style ->
                style.addSource(GeoJsonSource(SRC_POLY))
                style.addSource(GeoJsonSource(SRC_LINE))
                style.addSource(GeoJsonSource(SRC_POINT))
                // Load bundled place/neighborhood names
                val placesGeoJson = try {
                    context.assets.open("prague_places.geojson").bufferedReader().use { it.readText() }
                } catch (_: Exception) { null }
                val SRC_PLACES = "places"
                if (placesGeoJson != null) style.addSource(GeoJsonSource(SRC_PLACES, placesGeoJson))
                // OSM vector water polygons (Vltava + ponds/canals)
                val waterGeoJson = try {
                    val wgj = context.assets.open("prague_water.geojson").bufferedReader().use { it.readText() }
                    Log.d(TAG, "Water GeoJSON loaded: ${wgj.length} chars")
                    wgj
                } catch (e: Exception) { Log.w(TAG, "Water load failed: ${e.message}"); null }
                val SRC_WATER = "osm-water"
                if (waterGeoJson != null) {
                    style.addSource(GeoJsonSource(SRC_WATER, waterGeoJson))
                    Log.d(TAG, "Water source added with ${waterGeoJson.length} chars")
                }
                // OSM park polygons (parks, gardens, grass areas)
                val parkGeoJson = try {
                    context.assets.open("prague_parks.geojson").bufferedReader().use { it.readText() }
                } catch (e: Exception) { Log.w(TAG, "Parks load failed: ${e.message}"); null }
                val SRC_PARKS = "osm-parks"
                if (parkGeoJson != null) {
                    style.addSource(GeoJsonSource(SRC_PARKS, parkGeoJson))
                    Log.d(TAG, "Parks source added: ${parkGeoJson.length} chars")
                }
                // OSM building footprints (Staré Město + Malá Strana + Josefov center)
                val buildingGeoJson = try {
                    context.assets.open("prague_buildings.geojson").bufferedReader().use { it.readText() }
                } catch (e: Exception) { Log.w(TAG, "Buildings load failed: ${e.message}"); null }
                val SRC_BLDG = "osm-buildings"
                if (buildingGeoJson != null) {
                    style.addSource(GeoJsonSource(SRC_BLDG, buildingGeoJson))
                    Log.d(TAG, "Buildings source added: ${buildingGeoJson.length} chars")
                }
                // OSM POIs (restaurants, museums, attractions, hotels)
                val poisGeoJson = try {
                    context.assets.open("prague_pois.geojson").bufferedReader().use { it.readText() }
                } catch (e: Exception) { Log.w(TAG, "POIs load failed: ${e.message}"); null }
                val SRC_POI = "osm-pois"
                if (poisGeoJson != null) {
                    style.addSource(GeoJsonSource(SRC_POI, poisGeoJson))
                    Log.d(TAG, "POIs source added: ${poisGeoJson.length} chars")
                }

                val pedGeoJson = try {
                    context.assets.open("prague_pedestrian.geojson").bufferedReader().use { it.readText() }
                } catch (e: Exception) { Log.w(TAG, "Pedestrian load failed: ${e.message}"); null }
                val SRC_PED = "osm-pedestrian"
                if (pedGeoJson != null) {
                    style.addSource(GeoJsonSource(SRC_PED, pedGeoJson))
                    Log.d(TAG, "Pedestrian source added: ${pedGeoJson.length} chars")
                }
                // Area labels: park names + water names (Vltava etc.)
                val areaLabelsGeoJson = try {
                    context.assets.open("prague_area_labels.geojson").bufferedReader().use { it.readText() }
                } catch (e: Exception) { Log.w(TAG, "Area labels load failed: ${e.message}"); null }
                val SRC_AREA_LABELS = "osm-area-labels"
                if (areaLabelsGeoJson != null) {
                    style.addSource(GeoJsonSource(SRC_AREA_LABELS, areaLabelsGeoJson))
                    Log.d(TAG, "Area labels source added: ${areaLabelsGeoJson.length} chars")
                }

                // Polygon fills — Apple Maps color palette by feature type
                // ponytail: separate sources per feature class beats data-driven for clarity
                // ft=0: land/ground, ft=64-69: parks/recreation, ft=90-99: water
                // Base land fill (covers everything)
                style.addLayer(FillLayer("l-land", SRC_POLY).withProperties(
                    PropertyFactory.fillColor("#F9F5ED"),  // VectorKit land-fill extracted from binary
                    PropertyFactory.fillOpacity(1.0f),
                ))
                // OSM vector water — cleaned (fountains removed), Vltava 1175pts + 32 islands
                if (waterGeoJson != null) {
                    style.addLayer(FillLayer("l-water-osm", SRC_WATER).withProperties(
                        PropertyFactory.fillColor("#B5D8EA"),   // Water — soft steel blue (Apple Maps calibrated)
                        PropertyFactory.fillOpacity(1.0f),
                        PropertyFactory.fillAntialias(true),
                    ))
                    // Water edge stroke — thin darker blue outline (Apple Maps riverbank style)
                    style.addLayer(LineLayer("l-water-edge", SRC_WATER).withProperties(
                        PropertyFactory.lineColor("#78B8D2"),   // slightly darker than fill
                        PropertyFactory.lineWidth(0.9f),
                        PropertyFactory.lineOpacity(0.6f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ))
                }

                // Green space: parks, gardens, grass (lighter green)
                if (parkGeoJson != null) {
                    style.addLayer(FillLayer("l-park-osm", SRC_PARKS).withProperties(
                        PropertyFactory.fillColor("#C4D9A8"),   // Park — display-calibrated sage (VK #B4D48D too vivid on screen)
                        PropertyFactory.fillOpacity(1.0f),
                        PropertyFactory.fillAntialias(true),
                    ))
                    // Forests/woods on top of parks — slightly darker green
                    style.addLayer(FillLayer("l-forest-osm", SRC_PARKS).withProperties(
                        PropertyFactory.fillColor("#9DC8A8"),   // Forest — muted teal-green (was #8DD4B4, too bright)
                        PropertyFactory.fillOpacity(1.0f),
                        PropertyFactory.fillAntialias(true),
                    ).withFilter(
                        Expression.raw("""["in",["get","ftype"],["literal",["forest","wood","scrub"]]]""")
                    ))
                    // Park outline — thin green border (Apple Maps style)
                    style.addLayer(LineLayer("l-park-outline", SRC_PARKS).withProperties(
                        PropertyFactory.lineColor("#8DB87A"),   // medium green — subtle park edge
                        PropertyFactory.lineWidth(0.6f),
                        PropertyFactory.lineOpacity(0.5f),
                    ))
                }
                // VMP4-based water layer — ENABLED (native style attribute key=71 classification)
                // key71=2 → ft=WATER(0x02), key71=1,5 → ft=PARK(0x03), else → ft=LAND(0x01)
                // Validated Oct 20 2026 against 27 real Prague z14 tiles
                style.addLayer(FillLayer("l-water-vmp4", SRC_POLY).withProperties(
                    PropertyFactory.fillColor("#B5D8EA"),   // Water — Apple Maps steel blue
                    PropertyFactory.fillOpacity(1.0f),
                    PropertyFactory.fillAntialias(true),
                ).withFilter(
                    Expression.raw("""["==",["get","ft"],${FeatureType.WATER.toInt() and 0xff}]""")
                ))
                // VMP4-based park layer — ENABLED (native style attribute key=71 classification)
                style.addLayer(FillLayer("l-park-vmp4", SRC_POLY).withProperties(
                    PropertyFactory.fillColor("#C4D9A8"),   // Park — Apple Maps sage green
                    PropertyFactory.fillOpacity(1.0f),
                    PropertyFactory.fillAntialias(true),
                ).withFilter(
                    Expression.raw("""["==",["get","ft"],${FeatureType.PARK.toInt() and 0xff}]""")
                ))
                // Buildings require OSM data or z16 VMP4 tiles — deferred.
                style.addLayer(FillLayer("l-building", SRC_POLY).withProperties(
                    PropertyFactory.fillColor("#EDE7DC"),       // very slightly darker than land
                    PropertyFactory.fillOpacity(1.0f),
                    PropertyFactory.fillOutlineColor("#C8C0B4"), // subtle gray outline
                    PropertyFactory.fillAntialias(true),
                ).withFilter(
                    Expression.raw("""["<",["get","am"],20000]""")
                ))
                // Known detected VMP4 types (when ft is correctly decoded) get more contrast
                style.addLayer(FillLayer("l-building-detected", SRC_POLY).withProperties(
                    PropertyFactory.fillColor("#E2D9CE"),
                    PropertyFactory.fillOpacity(1.0f),
                    PropertyFactory.fillOutlineColor("#B8B0A4"),
                    PropertyFactory.fillAntialias(true),
                ).withFilter(
                    Expression.raw("""[">=",["get","ft"],100]""")
                ))
                // OSM building footprints — strong contrast vs land (#F0EBE3) for urban texture
                if (buildingGeoJson != null) {
                    style.addLayer(FillLayer("l-osm-buildings", SRC_BLDG).withProperties(
                        PropertyFactory.fillColor("#E2D8CE"),   // Apple Maps: warm light gray-beige buildings
                        PropertyFactory.fillOpacity(0.65f),
                        PropertyFactory.fillOutlineColor("#C8BEB4"), // subtle darker outline
                        PropertyFactory.fillAntialias(true),
                    ))
                }
                // Bridge + pedestrian layer
                // Apple Maps: Charles Bridge = thin white line across water, same style as pedestrian streets on land
                if (pedGeoJson != null) {
                    // Casing (slightly wider, darker edge)
                    style.addLayer(LineLayer("l-bridge-case", SRC_PED).withProperties(
                        PropertyFactory.lineColor("#D0C8BC"),
                        PropertyFactory.lineWidth(
                            Expression.interpolate(Expression.linear(), Expression.zoom(),
                                Expression.stop(13, 3.5f), Expression.stop(15, 6f), Expression.stop(17, 10f))),
                        PropertyFactory.lineCap("round"),
                        PropertyFactory.lineJoin("round"),
                    ).withFilter(Expression.raw("""["in",["get","name"],["literal",["Karlův most","most Legií","Čechův most","Štefánikův most","Mánesův most","Palacký most"]]]""")))

                    // Fill (white/near-white, same as pedestrian streets on land)
                    style.addLayer(LineLayer("l-bridge-fill", SRC_PED).withProperties(
                        PropertyFactory.lineColor("#F7F4EF"),
                        PropertyFactory.lineWidth(
                            Expression.interpolate(Expression.linear(), Expression.zoom(),
                                Expression.stop(13, 2f), Expression.stop(15, 4f), Expression.stop(17, 7f))),
                        PropertyFactory.lineCap("round"),
                        PropertyFactory.lineJoin("round"),
                    ).withFilter(Expression.raw("""["in",["get","name"],["literal",["Karlův most","most Legií","Čechův most","Štefánikův most","Mánesův most","Palacký most"]]]""")))

                    // Pedestrian streets (non-bridge) — warm white thin lines
                    style.addLayer(LineLayer("l-ped-case", SRC_PED).withProperties(
                        PropertyFactory.lineColor("#C8C0B4"),   // warm gray casing
                        PropertyFactory.lineWidth(
                            Expression.interpolate(Expression.linear(), Expression.zoom(),
                                Expression.stop(13, 1.5f), Expression.stop(15, 3.5f), Expression.stop(17, 6f))),
                        PropertyFactory.lineCap("round"),
                        PropertyFactory.lineJoin("round"),
                    ).withFilter(Expression.raw("""["!",["in",["get","name"],["literal",["Karlův most","most Legií","Čechův most","Štefánikův most","Mánesův most","Palacký most"]]]]""")))
                    style.addLayer(LineLayer("l-ped-fill", SRC_PED).withProperties(
                        PropertyFactory.lineColor("#F4EFE9"),   // very warm near-white — lighter than land, same as Apple pedestrian
                        PropertyFactory.lineWidth(
                            Expression.interpolate(Expression.linear(), Expression.zoom(),
                                Expression.stop(13, 1f), Expression.stop(15, 2.5f), Expression.stop(17, 4.5f))),
                        PropertyFactory.lineCap("round"),
                        PropertyFactory.lineJoin("round"),
                    ).withFilter(Expression.raw("""["!",["in",["get","name"],["literal",["Karlův most","most Legií","Čechův most","Štefánikův most","Mánesův most","Palacký most"]]]]""")))
                }

                // Road network — aggressive Apple Maps-style hierarchy
                // KEY: primary roads are CREAM/WARM WHITE, residential are COOL GRAY
                // This color distinction is as important as width for hierarchy

                // Layer 1: Motorways/trunk (yellow, thick)
                style.addLayer(LineLayer("l-road-hw-case", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#C8940A"),
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(10, 6f), Expression.stop(14, 14f), Expression.stop(16, 22f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["in",["get","highway"],["literal",["motorway","trunk"]]]""")))
                style.addLayer(LineLayer("l-road-hw", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#F0BB28"),
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(10, 4f), Expression.stop(14, 10f), Expression.stop(16, 17f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["in",["get","highway"],["literal",["motorway","trunk"]]]""")))

                // Layer 2: Primary — warm cream/white with thick casing (clearly wider than residential)
                style.addLayer(LineLayer("l-road-primary-case", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#94907F"),  // VectorKit road-casing
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(10, 4f), Expression.stop(14, 14f), Expression.stop(16, 20f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["==",["get","highway"],"primary"]""")))
                style.addLayer(LineLayer("l-road-primary", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#FFFEF5"),  // warm white
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(10, 2f), Expression.stop(14, 10f), Expression.stop(16, 16f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["==",["get","highway"],"primary"]""")))

                // Layer 2b: Secondary — slightly thinner than primary
                style.addLayer(LineLayer("l-road-secondary-case", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#B0A898"),  // VectorKit road-casing secondary
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(10, 2.5f), Expression.stop(14, 10f), Expression.stop(16, 15f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["==",["get","highway"],"secondary"]""")))
                style.addLayer(LineLayer("l-road-secondary", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#FFFEF5"),  // same warm white
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(10, 1.5f), Expression.stop(14, 7f), Expression.stop(16, 12f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["==",["get","highway"],"secondary"]""")))

                // Layer 3: Tertiary
                style.addLayer(LineLayer("l-road-tertiary-case", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#C0BAB0"),  // VectorKit road-casing tertiary
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(12, 2f), Expression.stop(14, 7f), Expression.stop(16, 10f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["==",["get","highway"],"tertiary"]""")))
                style.addLayer(LineLayer("l-road-tertiary", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#FEFEF8"),
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(12, 1f), Expression.stop(14, 5f), Expression.stop(16, 7.5f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["==",["get","highway"],"tertiary"]""")))

                // Layer 4: Residential — COOL GRAY fill (distinguishable from warm white primaries)
                style.addLayer(LineLayer("l-road-case", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#D0CBC4"),  // VectorKit road-casing residential (lightest)
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(13, 1.5f), Expression.stop(14, 4f), Expression.stop(16, 6f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["in",["get","highway"],["literal",["residential","unclassified","service"]]]""")))
                style.addLayer(LineLayer("l-road-fill", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#F4F2F0"),  // slightly gray-white (vs warm #FFFEF5)
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(13, 0.5f), Expression.stop(14, 2.5f), Expression.stop(16, 4f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["in",["get","highway"],["literal",["residential","unclassified","service"]]]""")))
                // Service / living_street — even narrower, same fill colour, on top
                style.addLayer(LineLayer("l-road-service-case", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#D8D4CF"),  // slightly lighter casing for service lanes
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(13, 0.8f), Expression.stop(14, 2.5f), Expression.stop(16, 4.5f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["in",["get","highway"],["literal",["service","living_street"]]]""")))
                style.addLayer(LineLayer("l-road-service-fill", SRC_LINE).withProperties(
                    PropertyFactory.lineColor("#F8F7F5"),  // slightly warmer white for service roads
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(13, 0.3f), Expression.stop(14, 1.2f), Expression.stop(16, 2.5f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.raw("""["in",["get","highway"],["literal",["service","living_street"]]]""")))
                // Road name labels — only on features that have a name property
                // Road name labels — crisp, Apple Maps style with strong halos
                style.addLayer(SymbolLayer("l-label-road-major", SRC_LINE).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textTransform(Property.TEXT_TRANSFORM_UPPERCASE),
                    PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(12, 9f), Expression.stop(14, 11f), Expression.stop(16, 13f))),
                    PropertyFactory.textColor("#3A3830"),
                    PropertyFactory.textHaloColor("#FFFEF9"),
                    PropertyFactory.textHaloWidth(3.0f),
                    PropertyFactory.textHaloBlur(0.5f),
                    PropertyFactory.symbolPlacement("line"),
                    PropertyFactory.symbolSpacing(280.0f),
                    PropertyFactory.textMaxAngle(25.0f),
                    PropertyFactory.textOptional(true),
                    PropertyFactory.textAllowOverlap(false),
                    PropertyFactory.textLetterSpacing(0.08f),
                    PropertyFactory.textFont(arrayOf("Open Sans SemiBold", "Open Sans Bold", "Arial Unicode MS Regular")),
                ).withFilter(Expression.raw("""["all",["has","name"],["in",["get","highway"],["literal",["primary","secondary","trunk","motorway"]]]]""")))
                style.addLayer(SymbolLayer("l-label-road-minor", SRC_LINE).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textTransform(Property.TEXT_TRANSFORM_UPPERCASE),
                    PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(13, 8f), Expression.stop(14, 9.5f), Expression.stop(16, 11f))),
                    PropertyFactory.textColor("#555048"),
                    PropertyFactory.textHaloColor("#FFFEF9"),
                    PropertyFactory.textHaloWidth(2.5f),
                    PropertyFactory.textHaloBlur(0.5f),
                    PropertyFactory.symbolPlacement("line"),
                    PropertyFactory.symbolSpacing(200.0f),
                    PropertyFactory.textMaxAngle(25.0f),
                    PropertyFactory.textOptional(true),
                    PropertyFactory.textAllowOverlap(false),
                    PropertyFactory.textFont(arrayOf("Open Sans Regular", "Arial Unicode MS Regular")),
                ).withFilter(Expression.raw("""["all",["has","name"],["in",["get","highway"],["literal",["tertiary","residential","unclassified","living_street"]]]]""")))
                // Neighborhood / district labels
                style.addLayer(SymbolLayer("l-label-places", SRC_PLACES).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textSize(Expression.match(
                        Expression.get("type"),
                        Expression.literal(11.5f),
                        Expression.stop("suburb", 14.0f),
                        Expression.stop("neighbourhood", 11.5f),
                        Expression.stop("water", 12.0f)
                    )),
                    PropertyFactory.textColor(Expression.match(
                        Expression.get("type"),
                        Expression.literal("#555050"),
                        Expression.stop("water", "#2E6E96"),
                        Expression.stop("suburb", "#2C2C28")
                    )),
                    PropertyFactory.textHaloColor("#FFFEFB"),
                    PropertyFactory.textHaloWidth(2.5f),
                    PropertyFactory.textHaloBlur(0.5f),
                    PropertyFactory.textLetterSpacing(0.10f),
                    PropertyFactory.textTransform(Property.TEXT_TRANSFORM_UPPERCASE),
                    PropertyFactory.textFont(arrayOf("Open Sans Regular", "Arial Unicode MS Regular")),
                    PropertyFactory.textAllowOverlap(false),
                    PropertyFactory.textOptional(true),
                    PropertyFactory.iconAllowOverlap(false),
                ))

                // POI — Apple Maps teardrop pins (circle + downward point) with white unicode glyphs
                if (areaLabelsGeoJson != null) {
                    // Water labels — italic blue (Apple Maps shows river names in light blue-gray)
                    style.addLayer(SymbolLayer("l-label-water", SRC_AREA_LABELS).withProperties(
                        PropertyFactory.textField(Expression.get("name")),
                        PropertyFactory.textFont(arrayOf("Open Sans Regular,Arial Unicode MS Regular")),
                        PropertyFactory.textSize(11f),
                        PropertyFactory.textColor("#5591B8"),         // Apple Maps water label — muted steel blue
                        PropertyFactory.textHaloColor("#B5D8EA"),      // Match water fill for tight halo
                        PropertyFactory.textHaloWidth(1.2f),
                        PropertyFactory.textLetterSpacing(0.08f),
                        PropertyFactory.textAllowOverlap(false),
                        PropertyFactory.textOptional(true),
                        PropertyFactory.symbolSpacing(300f),
                        PropertyFactory.textMaxWidth(8f),
                    ).withFilter(
                        Expression.raw("""["==",["get","label_type"],"water"]""")
                    ))
                    // Park labels — italic dark green (Apple Maps style)
                    style.addLayer(SymbolLayer("l-label-park", SRC_AREA_LABELS).withProperties(
                        PropertyFactory.textField(Expression.get("name")),
                        PropertyFactory.textFont(arrayOf("Open Sans Regular,Arial Unicode MS Regular")),
                        PropertyFactory.textSize(10f),
                        PropertyFactory.textColor("#3D7A44"),          // Apple Maps park label — medium dark green
                        PropertyFactory.textHaloColor("#C4D9A8"),      // Match park fill
                        PropertyFactory.textHaloWidth(1.5f),
                        PropertyFactory.textAllowOverlap(false),
                        PropertyFactory.textOptional(true),
                        PropertyFactory.symbolSpacing(300f),
                        PropertyFactory.textMaxWidth(8f),
                    ).withFilter(
                        Expression.raw("""["==",["get","label_type"],"park"]""")
                    ))
                }

                // POI — Apple Maps teardrop pins (circle + downward point) with white unicode glyphs
                if (poisGeoJson != null) {
                    val landmarkFilter = Expression.raw("""["==",["get","rank"],1000]""")
                    val foodFilter = Expression.raw("""["all",["!=",["get","rank"],1000],["in",["get","cat"],["literal",["restaurant","cafe","bar"]]]]""")

                    data class IconDef(
                        val imgName: String,
                        val bgColor: Int,
                        val glyph: String,
                        val labelColor: String,
                    )

                    // Draw Apple Maps-style circular pin with vector-drawn icon (no unicode glyph)
                    // icon = "museum"|"food"|"hotel"|"viewpoint"|"attraction"|"theatre"
                    fun makePin(colorArgb: Int, icon: String): android.graphics.Bitmap {
                        val SZ = 128  // 128px = icons stay crisp at 0.28× MapLibre scale
                        val bmp = android.graphics.Bitmap.createBitmap(SZ, SZ, android.graphics.Bitmap.Config.ARGB_8888)
                        val cv = android.graphics.Canvas(bmp)
                        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        val cx = SZ / 2f; val cy = SZ / 2f; val r = SZ / 2f - 3f

                        // Drop shadow
                        p.color = 0x33000000.toInt(); p.style = android.graphics.Paint.Style.FILL
                        cv.drawCircle(cx + 3f, cy + 4f, r, p)

                        // Circle body
                        p.color = colorArgb
                        cv.drawCircle(cx, cy, r, p)

                        // White ring
                        p.color = 0xFFFFFFFF.toInt(); p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 4.5f
                        cv.drawCircle(cx, cy, r, p)

                        // White icon drawn as vector paths — scaled for 128px bitmap (cx=64, cy=64, icon ±25px)
                        // These are direct Canvas draw calls on the 128px bitmap
                        p.color = 0xFFFFFFFF.toInt(); p.style = android.graphics.Paint.Style.FILL
                        p.strokeJoin = android.graphics.Paint.Join.ROUND; p.strokeCap = android.graphics.Paint.Cap.ROUND

                        when (icon) {
                            "food" -> {
                                // Fork (left) + knife (right) — Apple Maps restaurant icon
                                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 6f
                                // Fork: vertical handle left
                                cv.drawLine(cx - 14f, cy - 25f, cx - 14f, cy + 25f, p)
                                // Tines
                                cv.drawLine(cx - 21f, cy - 25f, cx - 21f, cy - 7f, p)
                                cv.drawLine(cx - 7f,  cy - 25f, cx - 7f,  cy - 7f, p)
                                // Cross-bar
                                cv.drawLine(cx - 21f, cy - 7f, cx - 7f, cy - 7f, p)
                                // Knife: blade + handle right
                                cv.drawLine(cx + 14f, cy - 25f, cx + 14f, cy + 25f, p)
                                val kPath = android.graphics.Path()
                                kPath.moveTo(cx + 9f, cy - 25f); kPath.lineTo(cx + 14f, cy - 25f)
                                kPath.lineTo(cx + 14f, cy - 4f); kPath.lineTo(cx + 9f,  cy - 4f); kPath.close()
                                p.style = android.graphics.Paint.Style.FILL; cv.drawPath(kPath, p)
                            }
                            "hotel" -> {
                                p.style = android.graphics.Paint.Style.FILL
                                // Mattress base
                                cv.drawRoundRect(android.graphics.RectF(cx-27f, cy-4f, cx+27f, cy+23f), 7f, 7f, p)
                                // Headboard
                                cv.drawRoundRect(android.graphics.RectF(cx-27f, cy-23f, cx-14f, cy+4f), 5f, 5f, p)
                                // Pillow
                                cv.drawRoundRect(android.graphics.RectF(cx-11f, cy-16f, cx+25f, cy-2f), 9f, 9f, p)
                            }
                            "museum" -> {
                                p.style = android.graphics.Paint.Style.FILL
                                // Base
                                cv.drawRect(cx-25f, cy+14f, cx+25f, cy+25f, p)
                                // Step
                                cv.drawRect(cx-21f, cy+7f, cx+21f, cy+16f, p)
                                // 3 columns
                                for (col in -1..1) {
                                    cv.drawRect(cx+col*14f-3.5f, cy-14f, cx+col*14f+3.5f, cy+9f, p)
                                }
                                // Pediment
                                val tri = android.graphics.Path()
                                tri.moveTo(cx-25f, cy-14f); tri.lineTo(cx, cy-32f); tri.lineTo(cx+25f, cy-14f); tri.close()
                                cv.drawPath(tri, p)
                            }
                            "viewpoint" -> {
                                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 7f
                                cv.drawCircle(cx-12f, cy+4f, 14f, p)
                                cv.drawCircle(cx+12f, cy+4f, 14f, p)
                                p.style = android.graphics.Paint.Style.FILL
                                cv.drawRect(cx-5f, cy-7f, cx+5f, cy+2f, p)
                                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 7f
                                cv.drawLine(cx-12f, cy-10f, cx-16f, cy-23f, p)
                                cv.drawLine(cx+12f, cy-10f, cx+16f, cy-23f, p)
                            }
                            "attraction" -> {
                                p.style = android.graphics.Paint.Style.FILL
                                val starPath = android.graphics.Path()
                                val outerR = 25f; val innerR = 11f
                                for (i in 0 until 10) {
                                    val angle = (Math.PI * i / 5 - Math.PI / 2).toFloat()
                                    val rr2 = if (i % 2 == 0) outerR else innerR
                                    val x = cx + rr2 * Math.cos(angle.toDouble()).toFloat()
                                    val y = cy + rr2 * Math.sin(angle.toDouble()).toFloat()
                                    if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
                                }
                                starPath.close(); cv.drawPath(starPath, p)
                            }
                            "theatre" -> {
                                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 5f
                                cv.drawCircle(cx+9f, cy-5f, 18f, p)
                                val smilePath = android.graphics.Path(); smilePath.addArc(
                                    android.graphics.RectF(cx, cy-14f, cx+18f, cy+4f), 0f, 180f)
                                cv.drawPath(smilePath, p)
                                cv.drawCircle(cx-11f, cy+5f, 14f, p)
                                val frown = android.graphics.Path(); frown.addArc(
                                    android.graphics.RectF(cx-20f, cy+4f, cx-2f, cy+22f), 0f, -180f)
                                cv.drawPath(frown, p)
                            }
                            else -> {
                                val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                                tp.color = 0xFFFFFFFF.toInt(); tp.textSize = r * 0.92f
                                tp.isFakeBoldText = true; tp.textAlign = android.graphics.Paint.Align.CENTER
                                tp.typeface = android.graphics.Typeface.DEFAULT_BOLD
                                cv.drawText(icon.first().uppercaseChar().toString(), cx, cy + tp.textSize * 0.37f, tp)
                            }
                        }
                        return bmp
                    }
                    // Alias for compatibility with call site
                    fun makeTeardropPin(colorArgb: Int, glyph: String) = makePin(colorArgb, glyph)

                    val iconDefs = listOf(
                        // label colors — Apple Maps uses dark labels for all categories
                        IconDef("poi-museum",     0xFF9166DB.toInt(), "museum",     "#1C1C1E"),  // dark (Apple SF-style)
                        IconDef("poi-attraction", 0xFFDC3048.toInt(), "attraction", "#1C1C1E"),
                        IconDef("poi-theatre",    0xFF7C3AED.toInt(), "theatre",    "#1C1C1E"),
                        IconDef("poi-viewpoint",  0xFF16A34A.toInt(), "viewpoint",  "#1C1C1E"),
                        IconDef("poi-hotel",      0xFF3B6FD4.toInt(), "hotel",      "#1C1C1E"),
                        IconDef("poi-food",       0xFFEA580C.toInt(), "food",       "#1C1C1E"),
                    )

                    for (def in iconDefs) {
                        // Load pre-rendered pin PNG from assets (guaranteed pixel-perfect)
                        val bmp = try {
                            val stream = context.assets.open("${def.imgName}.png")
                            android.graphics.BitmapFactory.decodeStream(stream).also { stream.close() }
                        } catch (e: Exception) {
                            makeTeardropPin(def.bgColor, def.glyph)  // fallback to canvas
                        }
                        style.addImage(def.imgName, bmp)
                    }

                    val catFilterStr = mapOf(
                        "poi-museum"     to """["in",["get","cat"],["literal",["museum","gallery"]]]""",
                        "poi-attraction" to """["in",["get","cat"],["literal",["attraction","castle"]]]""",
                        "poi-theatre"    to """["in",["get","cat"],["literal",["theatre","cinema"]]]""",
                        "poi-viewpoint"  to """["==",["get","cat"],"viewpoint"]""",
                        "poi-hotel"      to """["==",["get","cat"],"hotel"]""",
                        "poi-food"       to """["in",["get","cat"],["literal",["restaurant","cafe","bar","food","pub","fast_food","bistro"]]]""",
                    )

                    for (def in iconDefs) {
                        val isFood = def.imgName == "poi-food"
                        val catStr = catFilterStr[def.imgName] ?: """["==","cat","${def.imgName}"]"""
                        // Simple static rank filter — no zoom expressions in filter (MapLibre Android doesn't support them)
                        // rank=1000 landmarks handled by the landmark layer above; regular layer shows rank 600-999
                        // Food uses its own minZoom gate (15.0) with no rank filter
                        val regularFilter = if (isFood)
                            Expression.raw("""["all",["!=",["get","rank"],1000],$catStr]""")
                        else
                            Expression.raw("""["all",["!=",["get","rank"],1000],[">=",["get","rank"],600],$catStr]""")
                        style.addLayer(SymbolLayer("l-${def.imgName}", SRC_POI).withProperties(
                            PropertyFactory.iconImage(def.imgName),
                            PropertyFactory.iconAnchor("center"),   // circle pins: center-anchored
                            PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                                Expression.stop(12, if (isFood) 0.28f else 0.32f),
                                Expression.stop(14, if (isFood) 0.38f else 0.44f),
                                Expression.stop(16, if (isFood) 0.48f else 0.56f))),
                            PropertyFactory.iconAllowOverlap(false),
                            PropertyFactory.iconIgnorePlacement(false),
                            PropertyFactory.textField(if (isFood) Expression.literal("") else Expression.get("name")),
                            PropertyFactory.textSize(9.5f),
                            PropertyFactory.textColor(def.labelColor),
                            PropertyFactory.textHaloColor("#FFFEF5"),
                            PropertyFactory.textHaloWidth(1.8f),
                            PropertyFactory.textHaloBlur(0.2f),
                            PropertyFactory.textAnchor("top"),
                            PropertyFactory.textOffset(arrayOf(0f, 0.6f)),  // offset from center, not bottom
                            PropertyFactory.textOptional(true),
                            PropertyFactory.textAllowOverlap(false),
                            PropertyFactory.textMaxWidth(8.0f),
                            PropertyFactory.symbolSpacing(if (isFood) 400f else 80f),
                            PropertyFactory.textFont(arrayOf("Open Sans Bold", "Arial Unicode MS Regular")),
                        ).withFilter(regularFilter))
                        if (isFood) style.getLayer("l-${def.imgName}")?.minZoom = 16f
                    }

                    // Bridge name labels — text only, no pin (Apple Maps style: small-caps gray label)
                    style.addLayer(SymbolLayer("l-poi-bridges", SRC_POI).withProperties(
                        PropertyFactory.textField(Expression.get("name")),
                        PropertyFactory.textSize(10.0f),
                        PropertyFactory.textColor("#555555"),
                        PropertyFactory.textHaloColor("#FFFFF8"),
                        PropertyFactory.textHaloWidth(1.5f),
                        PropertyFactory.textHaloBlur(0.3f),
                        PropertyFactory.textFont(arrayOf("Open Sans Bold", "Arial Unicode MS Regular")),
                        PropertyFactory.textAnchor("center"),
                        PropertyFactory.textAllowOverlap(false),
                        PropertyFactory.textIgnorePlacement(false),
                        PropertyFactory.textMaxWidth(8.0f),
                        PropertyFactory.textLetterSpacing(0.06f),
                        PropertyFactory.textTransform(Property.TEXT_TRANSFORM_UPPERCASE),
                    ).withFilter(Expression.raw("""["==",["get","cat"],"bridge"]""")))
                    // Landmarks — always visible
                    style.addLayer(SymbolLayer("l-poi-landmarks", SRC_POI).withProperties(
                        PropertyFactory.iconImage(
                            Expression.match(Expression.get("cat"), Expression.literal("poi-attraction"),
                                Expression.stop("museum",     "poi-museum"),
                                Expression.stop("gallery",    "poi-museum"),
                                Expression.stop("attraction", "poi-attraction"),
                                Expression.stop("castle",     "poi-attraction"),
                                Expression.stop("viewpoint",  "poi-viewpoint"),
                                Expression.stop("theatre",    "poi-theatre"),
                                Expression.stop("cinema",     "poi-theatre"),
                                Expression.stop("hotel",      "poi-hotel"),
                            )
                        ),
                        PropertyFactory.iconAnchor("center"),   // circle pins: center-anchored
                        PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(12, 0.36f), Expression.stop(14, 0.50f), Expression.stop(16, 0.64f))),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.textField(Expression.get("name")),
                        PropertyFactory.textSize(10.5f),
                        PropertyFactory.textColor("#1C1C1E"),   // Apple Maps: landmark labels — near-black (SF style)
                        PropertyFactory.textHaloColor("#FFFFF8"),
                        PropertyFactory.textHaloWidth(2.0f),
                        PropertyFactory.textHaloBlur(0.2f),
                        PropertyFactory.textAnchor("top"),
                        PropertyFactory.textOffset(arrayOf(0f, 0.6f)),
                        PropertyFactory.textAllowOverlap(false),
                        PropertyFactory.textIgnorePlacement(false),
                        PropertyFactory.textOptional(false),
                        PropertyFactory.textMaxWidth(9.0f),
                        PropertyFactory.textFont(arrayOf("Open Sans Bold", "Arial Unicode MS Regular")),
                    ).withFilter(landmarkFilter))
                }

                mlMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(initLat, initLon)).zoom(initZoom).build()

                mlMap.addOnCameraIdleListener { loadVisibleTiles() }
                loadVisibleTiles()
            }
        }
    }

    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() { scope.cancel(); mapView.onDestroy() }

    /** Take a screenshot via MapLibre's snapshot API (captures GL surface) */
    fun takeSnapshot(callback: (android.graphics.Bitmap) -> Unit) {
        map?.snapshot(callback)
    }

    fun moveTo(lat: Double, lon: Double, zoom: Double = 14.0) {
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build()
        ), 500)
    }

    // ── Tile loading ───────────────────────────────────────────────

    private fun Double.round(decimals: Int) = String.format("%.${decimals}f", this)

    private fun loadVisibleTiles() {
        val m = map ?: return
        val bounds = m.projection.visibleRegion.latLngBounds
        val zoom = m.cameraPosition.zoom.toInt().coerceIn(1, 17)
        val n = 2.0.pow(zoom)
        // Add ±1 tile padding for denser road coverage and seamless edges
        val xMin = (((bounds.longitudeWest + 180) / 360 * n).toInt() - 1).coerceAtLeast(0)
        val xMax = (((bounds.longitudeEast + 180) / 360 * n).toInt() + 1).coerceAtMost(n.toInt() - 1)
        val yMin = (latToTileY(bounds.latitudeNorth, zoom) - 1).coerceAtLeast(0)
        val yMax = (latToTileY(bounds.latitudeSouth, zoom) + 1).coerceAtMost(n.toInt() - 1)
        val tileCount = (xMax - xMin + 1) * (yMax - yMin + 1)
        Log.d(TAG, "Loading $tileCount tiles z=$zoom x=$xMin..$xMax y=$yMin..$yMax | bounds lon=[${bounds.longitudeWest.round(4)},${bounds.longitudeEast.round(4)}] lat=[${bounds.latitudeSouth.round(4)},${bounds.latitudeNorth.round(4)}]")

        scope.launch {
            // Fetch style 1 (polygons), style 20 (roads)
            data class TileResult(val x: Int, val y: Int, val tile: VectorTile)
            val results = mutableListOf<TileResult>()
            val jobs = mutableListOf<Deferred<TileResult?>>()
            val style99Jobs = mutableListOf<Deferred<Pair<String, IntArray?>>>()

            // Style 1 = base polygons/roads. Apple VMP4 polygon/road data is at z=14.
            // At z≥14: request z=14 parent tiles for VMP4 decode; OSM roads layer handles z=15 detail.
            val styles = listOf(1)
            val vmpZ = 14  // VMP4 polygon data is published at z=14

            // Compute z=14 tile range from visible bounds
            val n14 = (1 shl vmpZ).toDouble()
            val vmpXMin = (((bounds.longitudeWest + 180) / 360 * n14).toInt() - 1).coerceAtLeast(0)
            val vmpXMax = (((bounds.longitudeEast + 180) / 360 * n14).toInt() + 1).coerceAtMost(n14.toInt() - 1)
            val vmpYMin = (latToTileY(bounds.latitudeNorth, vmpZ) - 1).coerceAtLeast(0)
            val vmpYMax = (latToTileY(bounds.latitudeSouth, vmpZ) + 1).coerceAtMost(n14.toInt() - 1)

            // Kick off OSM road fetch for the entire visible area (one request covers all tiles)
            val osmJob: Deferred<String?> = async { fetchOsmRoads(xMin, xMax, yMin, yMax, zoom) }

            for (x in vmpXMin..vmpXMax) for (y in vmpYMin..vmpYMax) {
                val tx = x; val ty = y
                for (style in styles) {
                    jobs.add(async { fetchAndParseVmp4(vmpZ, tx, ty, style = style)?.let { TileResult(tx, ty, it) } })
                }
                // Style 99 = per-pixel style class raster (water=1, park=2, land=0)
                // Style 99 is ONLY available at z=14 (confirmed from ActiveTileGroup.pbd).
                // For z>14: clamp to z=14 parent tile.
                // For z<14: not available (no polygon data either at z<14).
                val s99z = 14
                val s99x = if (zoom <= 14) tx shr (14 - zoom) else tx shl (zoom - 14)
                val s99y = if (zoom <= 14) ty shr (14 - zoom) else ty shl (zoom - 14)
                val key99 = "$s99z/$s99x/$s99y"
                if (zoom >= 13 && !synchronized(style99Cache) { style99Cache.containsKey(key99) }) {
                    style99Jobs.add(async {
                        key99 to fetchStyle99(s99z, s99x, s99y)
                    })
                }
            }
            jobs.mapNotNull { it.await() }.let { results.addAll(it) }
            // Store style 99 results
            style99Jobs.forEach { job ->
                val (k, ftMap) = job.await()
                if (ftMap != null) synchronized(style99Cache) { style99Cache[k] = ftMap }
            }

            val polySb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            val lineSb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            val pointSb = StringBuilder("""{"type":"FeatureCollection","features":[""")
            var pf = true; var lf = true; var ptf = true

            for (tile in results) {
                val (tx, ty, vtile) = tile
                // Use new Vmp4Decoder — real Apple format with validated vertex codec
                val (tilePolyJson, tileLineJson) = vtile.toGeoJson()
                Log.d(TAG, "Tile toGeoJson: poly=${tilePolyJson.length} line=${tileLineJson.length}")
                // Merge into overall FeatureCollections
                // Strip outer wrapper and extract features array content
                val polyFeats = tilePolyJson.removePrefix("""{"type":"FeatureCollection","features":[""").removeSuffix("]}")
                val lineFeats = tileLineJson.removePrefix("""{"type":"FeatureCollection","features":[""").removeSuffix("]}")
                if (polyFeats.isNotEmpty()) {
                    if (!pf) polySb.append(','); pf = false
                    polySb.append(polyFeats)
                }
                if (lineFeats.isNotEmpty()) {
                    if (!lf) lineSb.append(','); lf = false
                    lineSb.append(lineFeats)
                }
            }
            polySb.append("]}"); lineSb.append("]}"); pointSb.append("]}")
            // Merge VMP4 road lines with OSM roads (OSM takes priority for labeled roads)
            val osmRoadsJson = osmJob.await()
            val polyJson = polySb.toString()
            val emptyGeoJson = """{"type":"FeatureCollection","features":[]}"""
            // Prefer OSM roads (richer classification) but fall back to VMP4 road lines
            val lineJson = osmRoadsJson ?: if (lineSb.length > 50) lineSb.toString() else emptyGeoJson
            val pointJson = pointSb.toString()

            withContext(Dispatchers.Main) {
                m.style?.let { s ->
                    (s.getSource(SRC_POLY) as? GeoJsonSource)?.setGeoJson(polyJson)
                    (s.getSource(SRC_LINE) as? GeoJsonSource)?.setGeoJson(lineJson)
                    (s.getSource(SRC_POINT) as? GeoJsonSource)?.setGeoJson(pointJson)
                    Log.d(TAG, "Updated map: poly=${polySb.length}, line=${lineJson.length}, point=${pointSb.length} chars")

                    // Composite ALL loaded style99 tiles into ONE seamless bitmap
                    // This eliminates seam lines between individual tile images
                    val n14 = 1 shl 14
                    val tileSize = 512
                    val cols = xMax - xMin + 1
                    val rows = yMax - yMin + 1

                    // Collect tiles (map to z=14 coords)
                    val tileGrid = Array(rows) { row -> Array(cols) { col ->
                        val s99x = (xMin + col) shr (zoom - 14).coerceAtLeast(0)
                        val s99y = (yMin + row) shr (zoom - 14).coerceAtLeast(0)
                        synchronized(style99Cache) { style99Cache["14/$s99x/$s99y"] }
                    }}

                    // Check if all tiles loaded and composite changed
                    val allLoaded = tileGrid.all { row -> row.all { it != null } }
                    val compositeKey = "composite-$zoom-$xMin-$xMax-$yMin-$yMax"
                    if (allLoaded && compositeKey !in style99Added) {
                        // Build composite at 1× (512px tiles) with software Gaussian blur for edge AA.
                        // 2× supersampling OOMed on device (4×4 grid @ 2× = 67MB just for pixel arrays).
                        // 1× blur with radius=2 uses only ~6MB for a 5×5 grid — safe.
                        // MapLibre rasterResampling=LINEAR handles sub-pixel smoothness during map rendering.
                        val finalW = cols * tileSize; val finalH = rows * tileSize
                        val compositePixels = IntArray(finalW * finalH)

                        for (row in 0 until rows) for (col in 0 until cols) {
                            val ftMap = tileGrid[row][col] ?: continue
                            for (py in 0 until tileSize) for (px in 0 until tileSize) {
                                val ft = ftMap[py * tileSize + px]
                                val color = when (ft) {
                                    94 -> 0xFF8FD3E8.toInt()   // water  #8FD3E8
                                    64 -> 0xFFB9D89B.toInt()   // park   #B9D89B
                                    else -> 0x00000000          // transparent
                                }
                                compositePixels[(row * tileSize + py) * finalW + (col * tileSize + px)] = color
                            }
                        }

                        // No software blur — MapLibre LINEAR resampling handles edge smoothing.
                        // Software blur was causing color bleed at water/park boundaries.
                        // The style99 raster placed above l-land provides clean water/park coloring.
                        val composite = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
                        composite.setPixels(compositePixels, 0, finalW, 0, 0, finalW, finalH)

                        // Remove any old composite source/layer
                        style99Added.filter { it.startsWith("composite-") }.forEach { old ->
                            try { s.removeLayer("l-s99-$old"); s.removeSource("s99-$old") } catch (_: Exception) {}
                        }
                        style99Added.removeAll { it.startsWith("composite-") }

                        // Geographic bounds of the entire composite
                        val lonMin = xMin.toDouble() / n14 * 360.0 - 180.0
                        val lonMax = (xMax + 1).toDouble() / n14 * 360.0 - 180.0
                        val latMax = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * yMin / n14))))
                        val latMin = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (yMax + 1).toDouble() / n14))))

                        try {
                            s.addSource(ImageSource("s99-$compositeKey", LatLngQuad(
                                LatLng(latMax, lonMin), LatLng(latMax, lonMax),
                                LatLng(latMin, lonMax), LatLng(latMin, lonMin)
                            ), composite))
                            // DISABLED: OSM vector water+parks now cover all features.
                            // Style99 caused blue noise slivers (fountains/small water) in Josefov.
                            // Keep the bitmap built for potential future use, but don't add the layer.
                            // s.addLayerAbove(RasterLayer(...), "l-land")
                            composite.recycle()
                            Log.d(TAG, "Style99 composite built but NOT added (OSM vector takes over)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to add composite raster: ${e.message}")
                            composite.recycle()
                        }
                    }
                }
            }
        }
    }

    /** Fetch + decode a VMP4 tile using the validated real-format decoder */
    private suspend fun fetchAndParseVmp4(z: Int, x: Int, y: Int, style: Int = 1): VectorTile? {
        val key = "$z/$x/$y/s${style}_v5"
        synchronized(tileCache) { tileCache[key] }?.let { return it }
        return try {
            val assetName = "tile_${z}_${x}_${y}_s${style}.bin"
            val bytes: ByteArray = try {
                context.assets.open(assetName).readBytes().also {
                    Log.d(TAG, "Tile $key: loaded from asset $assetName (${it.size}b)")
                }
            } catch (_: Exception) {
                val url = NativeAuth.vectorTileUrl(z, x, y, style = style)
                client.get(url).bodyAsBytes()
            }
            if (bytes.size < 8 || bytes[0] != 'V'.code.toByte()) {
                Log.w(TAG, "Tile $key: not VMP4, ${bytes.size}b")
                null
            } else {
                val tile = decodeVmp4Tile(bytes, z, x, y)
                val polyCount = tile.polygonFeatures.values.sumOf { it.size }
                val lineCount = tile.lineFeatures.size
                Log.d(TAG, "Tile $key: polys=$polyCount lines=$lineCount")
                synchronized(tileCache) { tileCache[key] = tile }
                tile
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tile $key failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchAndParse(z: Int, x: Int, y: Int, style: Int = 1): ParsedTile? {
        val key = "$z/$x/$y/s$style/old"
        return try {
            // Try asset fallback first (for offline testing)
            val assetName = "tile_${z}_${x}_${y}_s${style}.bin"
            val bytes: ByteArray = try {
                context.assets.open(assetName).readBytes().also {
                    Log.d(TAG, "Tile $key: loaded from asset $assetName (${it.size}b)")
                }
            } catch (_: Exception) {
                val url = NativeAuth.vectorTileUrl(z, x, y, style = style)
                client.get(url).bodyAsBytes()
            }
            if (bytes.size < 8 || bytes[0] != 'V'.code.toByte()) {
                Log.w(TAG, "Tile $key: not VMP4, ${bytes.size}b")
                null
            } else {
                val parsed = parseTile(bytes)
                val polyTypes = parsed.polygons.take(20).map { it.featureType }
                val lineTypes = parsed.lines.take(10).map { it.featureType }
                Log.d(TAG, "Tile $key: polys=${parsed.polygons.size} polyTypes=$polyTypes lines=${parsed.lines.size} lineTypes=$lineTypes")
                parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tile $key failed: ${e.message}")
            null
        }
    }


    /**
     * Fetch road centerlines from OpenStreetMap Overpass API for the visible tile area.
     * Returns a GeoJSON FeatureCollection string with LineString features and highway properties.
     * 
     * Uses OpenStreetMap data © OpenStreetMap contributors, ODbL license.
     * Data fetched from https://overpass-api.de/api/interpreter
     */
    /** Cached OSM GeoJSON per tile bbox (keyed by "z/xmin/xmax/ymin/ymax") */
    private val cachedOsmByBbox = mutableMapOf<String, String>()
    
    private suspend fun fetchOsmRoads(xMin: Int, xMax: Int, yMin: Int, yMax: Int, zoom: Int): String? {
        val bboxKey = "$zoom/$xMin/$xMax/$yMin/$yMax"
        synchronized(cachedOsmByBbox) { cachedOsmByBbox[bboxKey] }?.let { return it }

        // Check if viewport is over Prague — use bundled asset for instant load
        val n = (1 shl zoom).toDouble()
        val lonCenter = ((xMin + xMax) / 2.0 + 0.5) / n * 360.0 - 180.0
        val latCenter = tileYToLat((yMin + yMax) / 2, zoom)
        val overPrague = latCenter in 50.02..50.13 && lonCenter in 14.30..14.52

        if (overPrague) {
            val asset = try {
                context.assets.open("prague_roads.geojson").bufferedReader().use { it.readText() }.also {
                    Log.d(TAG, "OSM roads from asset (Prague): ${it.length} chars")
                }
            } catch (e: Exception) { null }
            if (asset != null) {
                synchronized(cachedOsmByBbox) { cachedOsmByBbox[bboxKey] = asset }
                return asset
            }
        }
        
        // Fallback: fetch from Overpass API
        return try {
            val n = 2.0.pow(zoom)
            // Use tighter bbox (no padding) to reduce Overpass response size
            val baseXMin = (xMin + 1).coerceAtMost(xMax)
            val baseXMax = (xMax - 1).coerceAtLeast(xMin)
            val baseYMin = (yMin + 1).coerceAtMost(yMax)
            val baseYMax = (yMax - 1).coerceAtLeast(yMin)
            val lonWest = (baseXMin.toDouble() / n * 360.0 - 180.0).format(5)
            val lonEast = ((baseXMax + 1).toDouble() / n * 360.0 - 180.0).format(5)
            val latSouth = tileYToLat(baseYMax + 1, zoom).format(5)
            val latNorth = tileYToLat(baseYMin, zoom).format(5)
            
            val query = """[out:json][timeout:10];(way["highway"~"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified)$"]($latSouth,$lonWest,$latNorth,$lonEast););out geom;"""
            
            // Use java.net for simplicity (Ktor POST may require additional config)
            val conn = java.net.URL("https://overpass-api.de/api/interpreter")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "AppleMapsAndroid/1.0")
            Log.d(TAG, "OSM query for bbox: $latSouth,$lonWest,$latNorth,$lonEast")
            val queryBytes = query.toByteArray(Charsets.UTF_8)
            // Overpass API requires data=QUERY as URL-encoded form field
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val formData = "data=$encodedQuery".toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(formData) }
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errText = conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: "(no body)"
                Log.w(TAG, "OSM HTTP $responseCode: $errText")
                return null
            }
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            Log.d(TAG, "OSM response: ${responseText.length} chars")
            
            val result = osmToGeoJson(responseText)
            synchronized(cachedOsmByBbox) { cachedOsmByBbox[bboxKey] = result }
            result
        } catch (e: Exception) {
            Log.w(TAG, "OSM fetch failed: ${e.message}")
            null
        }
    }
    
    private fun tileYToLat(ty: Int, zoom: Int): Double {
        val n = 2.0.pow(zoom)
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * ty / n))))
    }
    
    private fun Double.format(decimals: Int) = "%.${decimals}f".format(java.util.Locale.US, this)
    
    /** Convert Overpass API JSON response to GeoJSON FeatureCollection */
    private fun osmToGeoJson(overpassJson: String): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        
        try {
            // Simple JSON parsing without full library — extract ways
            val elementsStart = overpassJson.indexOf("\"elements\":")
            if (elementsStart < 0) return """{"type":"FeatureCollection","features":[]}"""
            
            var pos = overpassJson.indexOf('[', elementsStart)
            var depth = 0
            val elements = StringBuilder()
            while (pos < overpassJson.length) {
                val ch = overpassJson[pos]
                if (ch == '[' || ch == '{') depth++
                if (ch == ']' || ch == '}') depth--
                elements.append(ch)
                pos++
                if (depth == 0) break
            }
            
            // Extract each way object
            val wayRegex = Regex("""\{"type":"way".*?"geometry":\[.*?\]\}""", RegexOption.DOT_MATCHES_ALL)
            for (match in wayRegex.findAll(overpassJson)) {
                val wayStr = match.value
                
                // Extract highway tag
                val hwMatch = Regex(""""highway":"([^"]+)"""").find(wayStr) ?: continue
                val hw = hwMatch.groupValues[1]
                
                // Extract geometry array
                val geomStart = wayStr.indexOf("\"geometry\":[")
                if (geomStart < 0) continue
                val geomEnd = wayStr.indexOf("]", geomStart + 12)
                if (geomEnd < 0) continue
                val geomStr = wayStr.substring(geomStart + 12, geomEnd)
                
                // Parse lat/lon pairs
                val coords = StringBuilder()
                var firstCoord = true
                val nodeRegex = Regex(""""lat":(-?[0-9.]+),"lon":(-?[0-9.]+)""")
                for (node in nodeRegex.findAll(geomStr)) {
                    val lat = node.groupValues[1]; val lon = node.groupValues[2]
                    if (!firstCoord) coords.append(',')
                    coords.append("[$lon,$lat]")
                    firstCoord = false
                }
                
                if (firstCoord) continue  // no coords found
                
                if (!first) sb.append(',')
                first = false
                sb.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{"highway":"$hw"}}""")
            }
        } catch (e: Exception) {
            Log.w(TAG, "OSM JSON parse error: ${e.message}")
        }
        
        sb.append("]}")
        return sb.toString()
    }

    // ── GeoJSON helpers ────────────────────────────────────────────

    /**
     * Compute convex hull of a vertex list (Andrew's monotone chain).
     * Used to eliminate self-intersections in road polygon meshes.
     */
    private fun convexHull(points: List<Vertex>): List<Vertex> {
        if (points.size < 3) return points
        // Remove duplicates first
        val unique = points.distinctBy { "${it.x.toBits()},${it.y.toBits()}" }
        if (unique.size < 3) return unique
        val sorted = unique.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = mutableListOf<Vertex>()
        for (p in sorted) {
            while (lower.size >= 2) {
                val a = lower[lower.size - 2]; val b = lower[lower.size - 1]
                val cross = (b.x - a.x).toDouble() * (p.y - a.y) - (b.y - a.y).toDouble() * (p.x - a.x)
                if (cross <= 0) lower.removeAt(lower.size - 1) else break
            }
            lower.add(p)
        }
        val upper = mutableListOf<Vertex>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2) {
                val a = upper[upper.size - 2]; val b = upper[upper.size - 1]
                val cross = (b.x - a.x).toDouble() * (p.y - a.y) - (b.y - a.y).toDouble() * (p.x - a.x)
                if (cross <= 0) upper.removeAt(upper.size - 1) else break
            }
            upper.add(p)
        }
        lower.addAll(upper.drop(1).dropLast(1))
        return lower
    }

    /**
     * Extract road centerline spine from a road area polygon using PCA.
     * Returns a LineString path through the center of the road polygon.
     * Works for simple road segments and complex junction areas.
     */
    private fun roadSpine(verts: List<Vertex>): List<Vertex> {
        if (verts.size < 2) return verts
        // Compute centroid
        val cx = verts.sumOf { it.x.toDouble() } / verts.size
        val cy = verts.sumOf { it.y.toDouble() } / verts.size
        // PCA: covariance matrix
        var covXX = 0.0; var covXY = 0.0; var covYY = 0.0
        for (v in verts) {
            val dx = v.x - cx; val dy = v.y - cy
            covXX += dx * dx; covXY += dx * dy; covYY += dy * dy
        }
        val n = verts.size.toDouble()
        covXX /= n; covXY /= n; covYY /= n
        // Principal direction (eigenvector of largest eigenvalue)
        val trace = covXX + covYY
        val det = covXX * covYY - covXY * covXY
        val lambda = trace / 2 + Math.sqrt(trace * trace / 4 - det)
        // Eigenvector direction
        val ex = if (Math.abs(covXY) > 1e-10) covXY else (lambda - covYY)
        val ey = if (Math.abs(covXY) > 1e-10) lambda - covXX else covXY
        val len = Math.sqrt(ex * ex + ey * ey)
        if (len < 1e-10) return listOf(Vertex(cx.toFloat(), cy.toFloat()))
        val nx = (ex / len).toFloat(); val ny = (ey / len).toFloat()
        // Project all vertices onto principal axis
        val projections = verts.map { v -> (v.x - cx) * nx + (v.y - cy) * ny }
        val minP = projections.min()
        val maxP = projections.max()
        // Generate spine points along the principal axis
        val spineCount = minOf(8, maxOf(2, (verts.size / 8)))
        return (0 until spineCount).map { k ->
            val t = minP + (maxP - minP) * k / (spineCount - 1)
            Vertex((cx + t * nx).toFloat(), (cy + t * ny).toFloat())
        }
    }

    /** Fetch and decode style 99 tile (per-pixel style class raster). */
    private suspend fun fetchStyle99(z: Int, x: Int, y: Int): IntArray? {
        return try {
            val url = NativeAuth.style99TileUrl(z, x, y)
            val bytes = client.get(url).bodyAsBytes()
            if (bytes.size < 8) {
                Log.w(TAG, "Style99 $z/$x/$y: too small ${bytes.size}b")
                null
            } else {
                Style99Decoder.decode(bytes).also { result ->
                    if (result != null)
                        Log.d(TAG, "Style99 $z/$x/$y: decoded ${result.size} pixels")
                    else
                        Log.w(TAG, "Style99 $z/$x/$y: decode failed")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Style99 $z/$x/$y fetch failed: ${e.message}")
            null
        }
    }

    private fun appendCoord(sb: StringBuilder, v: Vertex, tx: Int, ty: Int, z: Int) {
        val (lon, lat) = tileToWgs84(tx, ty, z, v.x, v.y)
        sb.append("[%.6f,%.6f]".format(java.util.Locale.US, lon, lat))
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val n = 2.0.pow(zoom)
        val r = Math.toRadians(lat)
        return floor((1.0 - ln(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * n).toInt()
    }

    /**
     * Software separable box blur on a raw ARGB IntArray (2 passes: horizontal + vertical).
     * Uses sliding-window sums: O(W×H) — not O(W×H×kernel) — regardless of radius.
     * Used for anti-aliasing water/park edges in the style99 composite.
     * radius=2 gives a ~2px soft edge — enough to remove the hard pixel-grid appearance.
     * Memory: 3 IntArrays of finalW × finalH × 4 bytes (~6MB for a 5×5 tile grid).
     */
    private fun boxBlur2D(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val r = radius.coerceAtLeast(1)
        val tmp = IntArray(w * h)

        // Horizontal pass using sliding window: O(W×H) total
        for (y in 0 until h) {
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            // Seed window [-r, 0)
            for (dx in -r until 0) {
                val p = src[y * w + 0]  // clamp left
                aSum += (p ushr 24) and 0xFF; rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF; bSum += p and 0xFF
            }
            // Seed window [0, r]
            for (dx in 0..r) {
                val p = src[y * w + dx.coerceAtMost(w - 1)]
                aSum += (p ushr 24) and 0xFF; rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF; bSum += p and 0xFF
            }
            val cnt = r * 2 + 1
            for (x in 0 until w) {
                tmp[y * w + x] = ((aSum / cnt) shl 24) or ((rSum / cnt) shl 16) or
                                  ((gSum / cnt) shl 8) or (bSum / cnt)
                // Slide: remove leftmost, add next right
                val remove = src[y * w + (x - r).coerceAtLeast(0)]
                val add    = src[y * w + (x + r + 1).coerceAtMost(w - 1)]
                aSum += ((add ushr 24) and 0xFF) - ((remove ushr 24) and 0xFF)
                rSum += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
                gSum += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
                bSum += (add and 0xFF) - (remove and 0xFF)
            }
        }
        // Vertical pass using sliding window
        val out = IntArray(w * h)
        for (x in 0 until w) {
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (dy in -r until 0) {
                val p = tmp[0 * w + x]  // clamp top
                aSum += (p ushr 24) and 0xFF; rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF; bSum += p and 0xFF
            }
            for (dy in 0..r) {
                val p = tmp[dy.coerceAtMost(h - 1) * w + x]
                aSum += (p ushr 24) and 0xFF; rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF; bSum += p and 0xFF
            }
            val cnt = r * 2 + 1
            for (y in 0 until h) {
                out[y * w + x] = ((aSum / cnt) shl 24) or ((rSum / cnt) shl 16) or
                                  ((gSum / cnt) shl 8) or (bSum / cnt)
                val remove = tmp[(y - r).coerceAtLeast(0) * w + x]
                val add    = tmp[(y + r + 1).coerceAtMost(h - 1) * w + x]
                aSum += ((add ushr 24) and 0xFF) - ((remove ushr 24) and 0xFF)
                rSum += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
                gSum += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
                bSum += (add and 0xFF) - (remove and 0xFF)
            }
        }
        return out
    }

    /**
     * Alpha-edge blur: blurs ONLY the alpha channel using a sliding-window box blur.
     * RGB values are taken from the nearest fully-opaque pixel to avoid color bleed into land.
     * This gives clean, soft edges without desaturating or bleeding water/park colors into land.
     */
    private fun alphaEdgeBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val r = radius.coerceAtLeast(1)
        val alphaOnly = IntArray(w * h) { i -> (src[i] ushr 24) and 0xFF }

        // Horizontal blur on alpha
        val tmpAlpha = IntArray(w * h)
        for (y in 0 until h) {
            var sum = 0
            for (dx in -r until 0) sum += alphaOnly[y * w + 0]
            for (dx in 0..r) sum += alphaOnly[y * w + dx.coerceAtMost(w - 1)]
            val cnt = r * 2 + 1
            for (x in 0 until w) {
                tmpAlpha[y * w + x] = sum / cnt
                sum -= alphaOnly[y * w + (x - r).coerceAtLeast(0)]
                sum += alphaOnly[y * w + (x + r + 1).coerceAtMost(w - 1)]
            }
        }
        // Vertical blur on alpha
        val outAlpha = IntArray(w * h)
        for (x in 0 until w) {
            var sum = 0
            for (dy in -r until 0) sum += tmpAlpha[0 * w + x]
            for (dy in 0..r) sum += tmpAlpha[dy.coerceAtMost(h - 1) * w + x]
            val cnt = r * 2 + 1
            for (y in 0 until h) {
                outAlpha[y * w + x] = sum / cnt
                sum -= tmpAlpha[(y - r).coerceAtLeast(0) * w + x]
                sum += tmpAlpha[(y + r + 1).coerceAtMost(h - 1) * w + x]
            }
        }

        // Rebuild: keep RGB from original (or nearest opaque if transparent), blend alpha only
        return IntArray(w * h) { i ->
            val origAlpha = (src[i] ushr 24) and 0xFF
            val blurAlpha = outAlpha[i]
            if (origAlpha == 255 || blurAlpha == 0) {
                // Fully opaque or invisible — just use blurred alpha with original RGB
                (blurAlpha shl 24) or (src[i] and 0x00FFFFFF)
            } else {
                // Edge pixel: use original RGB (from the feature color) with blurred alpha
                (blurAlpha shl 24) or (src[i] and 0x00FFFFFF)
            }
        }
    }
}

private val BLANK_STYLE = """
{"version":8,"name":"Apple Maps","glyphs":"asset://fonts/{fontstack}/{range}.pbf","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#F2EFE9"}}]}
""".trim()
