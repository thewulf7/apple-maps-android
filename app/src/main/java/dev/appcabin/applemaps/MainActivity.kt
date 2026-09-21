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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
                    primary = Color(0xFF007AFF),
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
    var showResults by remember { mutableStateOf(false) }
    var mapCenter by remember { mutableStateOf(LatLng(50.0755, 14.4378)) }
    var mapZoom by remember { mutableStateOf(14f) }
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var searchPin by remember { mutableStateOf<LatLng?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Try to get location on first launch
    LaunchedEffect(Unit) {
        if (hasLocationPermission()) {
            getLastLocation { loc ->
                userLocation = loc
                mapCenter = loc
            }
        }
    }

    // Debounced autocomplete (as user types)
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) {
            searchResults = emptyList()
            showResults = false
            return@LaunchedEffect
        }
        delay(350)
        try {
            val results = search.autocomplete(searchQuery, mapCenter.lat, mapCenter.lng)
            searchResults = results
            showResults = results.isNotEmpty()
        } catch (e: Exception) {
            Log.e("AppleMapsScreen", "Autocomplete error: ${e.message}")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Map
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

        // Search bar + results
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.97f),
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search Maps", color = Color.Gray) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (showResults) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .shadow(8.dp, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.97f),
                ) {
                    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                        items(searchResults) { result ->
                            SearchResultRow(result) {
                                if (result.lat != 0.0 && result.lng != 0.0) {
                                    // Has coords — navigate directly
                                    mapCenter = LatLng(result.lat, result.lng)
                                    mapZoom = 16f
                                    searchPin = LatLng(result.lat, result.lng)
                                    searchQuery = result.name
                                    showResults = false
                                    focusManager.clearFocus()
                                } else {
                                    // Completion only — do a full search
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
                                                showResults = true
                                            }
                                        } catch (e: Exception) {
                                            Log.e("AppleMapsScreen", "Search error: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Left side: Location + Zoom
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Location button
            Surface(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(4.dp, CircleShape)
                    .clickable {
                        if (hasLocationPermission()) {
                            getLastLocation { loc ->
                                userLocation = loc
                                mapCenter = loc
                                mapZoom = 16f
                                searchPin = null
                            }
                        } else {
                            requestLocationPermission {
                                getLastLocation { loc ->
                                    userLocation = loc
                                    mapCenter = loc
                                    mapZoom = 16f
                                    searchPin = null
                                }
                            }
                        }
                    },
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.95f),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("◎", fontSize = 22.sp, color = Color(0xFF007AFF))
                }
            }

            // Zoom controls
            Column(
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.95f)),
            ) {
                IconButton(onClick = { mapZoom = (mapZoom + 1f).coerceAtMost(19f) }) {
                    Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = { mapZoom = (mapZoom - 1f).coerceAtLeast(2f) }) {
                    Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF007AFF))
                }
            }
        }

        // Style toggle — bottom right
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp)
                .shadow(4.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.95f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            StyleButton("Map", mapStyle == "standard") { mapStyle = "standard" }
            StyleButton("Sat", mapStyle == "satellite") { mapStyle = "satellite" }
            StyleButton("Hyb", mapStyle == "hybrid-overlay") { mapStyle = "hybrid-overlay" }
        }
    }
}

@Composable
fun SearchResultRow(result: AppleMapSearch.SearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = Color(0xFFE8F0FE),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (result.isCompletion) "🔍" else "📍",
                    fontSize = 16.sp,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.name,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.subtitle.isNotBlank()) {
                Text(
                    text = result.subtitle,
                    fontSize = 13.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun StyleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = if (selected) Color(0xFF007AFF) else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color.DarkGray,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}