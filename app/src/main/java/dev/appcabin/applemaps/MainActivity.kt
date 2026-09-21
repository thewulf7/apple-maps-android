package dev.appcabin.applemaps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import io.ktor.client.*
import io.ktor.client.engine.android.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Apple Maps iOS colour tokens
object AppleColors {
    val Blue = Color(0xFF007AFF)
    val Red = Color(0xFFFF3B30)
    val SearchFieldBg = Color(0xFFE9E9EB)
    val TextPrimary = Color(0xFF000000)
    val TextSecondary = Color(0xFF6E6E73)
    val TextTertiary = Color(0xFF8E8E93)
    val Separator = Color(0xFFE5E5EA)
    val ControlBg = Color(0xFAFFFFFF)
}

class MainActivity : ComponentActivity() {

    private var onLocationPermissionGranted: (() -> Unit)? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            onLocationPermissionGranted?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val client = HttpClient(Android) {
            engine {
                connectTimeout = 10_000
                socketTimeout = 15_000
            }
        }
        val auth = AppleMapAuth(client)
        val search = AppleMapSearch(client, auth)
        val tileCache = TileCache(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = AppleColors.Blue,
                    surface = Color.White,
                    background = Color(0xFFF2F2F7),
                )
            ) {
                AppleMapsScreen(
                    auth = auth,
                    client = client,
                    search = search,
                    tileCache = tileCache,
                    requestLocationPermission = { callback ->
                        onLocationPermissionGranted = callback
                        locationPermissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ))
                    },
                    hasLocationPermission = {
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                    },
                    getLastLocation = { callback ->
                        try {
                            val fusedClient = LocationServices.getFusedLocationProviderClient(this)
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                                PackageManager.PERMISSION_GRANTED) {
                                fusedClient.lastLocation.addOnSuccessListener { loc ->
                                    if (loc != null) {
                                        callback(LatLng(loc.latitude, loc.longitude))
                                    } else {
                                        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                                            .setMaxUpdates(1)
                                            .build()
                                        fusedClient.requestLocationUpdates(req, object : LocationCallback() {
                                            override fun onLocationResult(result: LocationResult) {
                                                val l = result.lastLocation
                                                if (l != null) callback(LatLng(l.latitude, l.longitude))
                                                fusedClient.removeLocationUpdates(this)
                                            }
                                        }, Looper.getMainLooper())
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Location error: ${e.message}")
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleMapsScreen(
    auth: AppleMapAuth,
    client: HttpClient,
    search: AppleMapSearch,
    tileCache: TileCache,
    requestLocationPermission: (() -> Unit) -> Unit,
    hasLocationPermission: () -> Boolean,
    getLastLocation: ((LatLng) -> Unit) -> Unit,
) {
    var mapStyle by remember { mutableStateOf("standard") }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<AppleMapSearch.SearchResult>>(emptyList()) }
    var mapCenter by remember { mutableStateOf(LatLng(50.0755, 14.4378)) }
    var mapZoom by remember { mutableStateOf(14f) }
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var searchPin by remember { mutableStateOf<LatLng?>(null) }
    var selectedPlace by remember { mutableStateOf<AppleMapSearch.SearchResult?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
        )
    )

    // GPS on launch
    LaunchedEffect(Unit) {
        if (hasLocationPermission()) {
            getLastLocation { loc ->
                userLocation = loc
                mapCenter = loc
            }
        }
    }

    // Debounced autocomplete
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        try {
            val results = search.autocomplete(searchQuery, mapCenter.lat, mapCenter.lng)
            searchResults = results
        } catch (e: Exception) {
            Log.e("AppleMapsScreen", "Autocomplete error: ${e.message}")
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 100.dp,
        sheetShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        sheetContainerColor = Color.White.copy(alpha = 0.97f),
        sheetShadowElevation = 12.dp,
        sheetTonalElevation = 0.dp,
        sheetDragHandle = {
            // iOS-style drag handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .width(36.dp)
                        .height(5.dp),
                    shape = RoundedCornerShape(2.5.dp),
                    color = Color(0xFFD1D1D6),
                ) {}
            }
        },
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                // --- Search bar row ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // iOS-style search field
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = AppleColors.SearchFieldBg,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                tint = AppleColors.TextTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 16.sp,
                                    color = AppleColors.TextPrimary,
                                ),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search Maps",
                                            color = AppleColors.TextTertiary,
                                            fontSize = 16.sp,
                                        )
                                    }
                                    innerTextField()
                                },
                            )
                            if (searchQuery.isNotEmpty()) {
                                Icon(
                                    Icons.Default.Cancel,
                                    contentDescription = "Clear",
                                    tint = AppleColors.TextTertiary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable {
                                            searchQuery = ""
                                            searchResults = emptyList()
                                            searchPin = null
                                            selectedPlace = null
                                            focusManager.clearFocus()
                                        },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // User avatar circle
                    Surface(
                        modifier = Modifier.size(36.dp),
                        shape = CircleShape,
                        color = AppleColors.SearchFieldBg,
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Account",
                                tint = AppleColors.TextTertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                // --- Dynamic content ---
                if (selectedPlace != null) {
                    PlaceDetailContent(
                        place = selectedPlace!!,
                        onClose = {
                            selectedPlace = null
                            searchPin = null
                        },
                    )
                } else if (searchResults.isNotEmpty()) {
                    SearchResultsList(
                        results = searchResults,
                        onResultClick = { result ->
                            if (result.lat != 0.0 && result.lng != 0.0) {
                                mapCenter = LatLng(result.lat, result.lng)
                                mapZoom = 16f
                                searchPin = LatLng(result.lat, result.lng)
                                selectedPlace = result
                                searchQuery = result.name
                                focusManager.clearFocus()
                            } else {
                                searchQuery = result.name
                                scope.launch {
                                    try {
                                        val results = search.search(result.name, mapCenter.lat, mapCenter.lng)
                                        if (results.isNotEmpty()) {
                                            val first = results.first()
                                            mapCenter = LatLng(first.lat, first.lng)
                                            mapZoom = 14f
                                            searchPin = LatLng(first.lat, first.lng)
                                            searchResults = results
                                        }
                                    } catch (e: Exception) {
                                        Log.e("AppleMapsScreen", "Search: ${e.message}")
                                    }
                                }
                            }
                        },
                    )
                } else {
                    FavoritesSection()
                }

                // Spacer so half-expanded sheet has content to fill
                Spacer(modifier = Modifier.height(200.dp))
            }
        },
    ) { innerPadding ->
        // ===== MAP fills behind the sheet =====
        Box(modifier = Modifier.fillMaxSize()) {
            AppleMapView(
                modifier = Modifier.fillMaxSize(),
                auth = auth,
                client = client,
                tileCache = tileCache,
                mapStyle = mapStyle,
                center = mapCenter,
                zoom = mapZoom,
                userLocation = userLocation,
                searchPin = searchPin,
                onMapMoved = { center, zoom ->
                    mapCenter = center
                    mapZoom = zoom
                },
            )

            // ===== Top-right map controls (iOS capsule) =====
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 12.dp),
            ) {
                MapControlCapsule {
                    MapControlButton(Icons.Outlined.Layers) {
                        mapStyle = when (mapStyle) {
                            "standard" -> "satellite"
                            "satellite" -> "hybrid-overlay"
                            else -> "standard"
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        thickness = 0.5.dp,
                        color = AppleColors.Separator,
                    )
                    MapControlButton(Icons.Outlined.NearMe) {
                        if (hasLocationPermission()) {
                            getLastLocation { loc ->
                                userLocation = loc
                                mapCenter = loc
                                mapZoom = 16f
                                searchPin = null
                                selectedPlace = null
                            }
                        } else {
                            requestLocationPermission {
                                getLastLocation { loc ->
                                    userLocation = loc
                                    mapCenter = loc
                                    mapZoom = 16f
                                    searchPin = null
                                    selectedPlace = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===== Map control capsule =====

@Composable
fun MapControlCapsule(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .width(40.dp)
            .shadow(4.dp, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = AppleColors.ControlBg,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
fun MapControlButton(icon: ImageVector, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color(0xFF3C3C43),
            modifier = Modifier.size(20.dp),
        )
    }
}

// ===== Search results list =====

@Composable
fun SearchResultsList(
    results: List<AppleMapSearch.SearchResult>,
    onResultClick: (AppleMapSearch.SearchResult) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        items(results) { result ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onResultClick(result) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    color = AppleColors.SearchFieldBg,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (result.isCompletion) Icons.Default.Search else Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = if (result.isCompletion) AppleColors.TextTertiary else AppleColors.Blue,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        color = AppleColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (result.subtitle.isNotBlank()) {
                        Text(
                            text = result.subtitle,
                            fontSize = 13.sp,
                            color = AppleColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = AppleColors.Separator,
                modifier = Modifier.padding(start = 42.dp),
            )
        }
    }
}

// ===== Place detail =====

@Composable
fun PlaceDetailContent(
    place: AppleMapSearch.SearchResult,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = place.name,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppleColors.TextPrimary,
                )
                if (place.category != null) {
                    Text(
                        text = place.category,
                        fontSize = 13.sp,
                        color = AppleColors.TextSecondary,
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onClose),
                shape = CircleShape,
                color = AppleColors.SearchFieldBg,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = AppleColors.TextTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { /* directions placeholder */ },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppleColors.Blue,
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Directions", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Surface(
                modifier = Modifier
                    .size(44.dp)
                    .clickable { },
                shape = RoundedCornerShape(10.dp),
                color = AppleColors.SearchFieldBg,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.MoreHoriz,
                        contentDescription = "More",
                        tint = AppleColors.Blue,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Address
        if (place.subtitle.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = AppleColors.SearchFieldBg,
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = AppleColors.TextTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = place.subtitle,
                        fontSize = 14.sp,
                        color = AppleColors.TextPrimary,
                    )
                }
            }
        }
    }
}

// ===== Favorites =====

@Composable
fun FavoritesSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Favourites",
                fontSize = 13.sp,
                color = AppleColors.TextSecondary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "More",
                fontSize = 13.sp,
                color = AppleColors.Blue,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            FavoriteItem(Icons.Filled.Home, "Home", "Add")
            FavoriteItem(Icons.Filled.Work, "Work", "Add")
            FavoriteItem(Icons.Default.Add, "Add", null)
        }
    }
}

@Composable
fun FavoriteItem(icon: ImageVector, label: String, subtitle: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = AppleColors.SearchFieldBg,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = AppleColors.Blue,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AppleColors.TextPrimary)
        if (subtitle != null) {
            Text(subtitle, fontSize = 11.sp, color = AppleColors.TextTertiary)
        }
    }
}