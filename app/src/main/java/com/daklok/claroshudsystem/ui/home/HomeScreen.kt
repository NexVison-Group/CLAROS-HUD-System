package com.daklok.claroshudsystem.ui.home

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daklok.claroshudsystem.ble.BleDeviceItem
import com.daklok.claroshudsystem.ble.EspConnectionStatus
import com.daklok.claroshudsystem.ble.EspConnectionUiState
import com.daklok.claroshudsystem.ble.EspConnectionViewModel
import com.daklok.claroshudsystem.prefs.ThemeMode
import com.daklok.claroshudsystem.prefs.ThemePreferences
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.compass.CompassPlugin
import com.mapbox.maps.plugin.gestures.GesturesPlugin
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin
import com.mapbox.maps.plugin.scalebar.ScaleBarPlugin
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.search.ApiType
import com.mapbox.search.QueryType
import com.mapbox.search.ResponseInfo
import com.mapbox.search.ReverseGeoOptions
import com.mapbox.search.SearchCallback
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.SearchOptions
import com.mapbox.search.SearchSelectionCallback
import com.mapbox.search.SearchSuggestionsCallback
import com.mapbox.search.result.SearchResult
import com.mapbox.search.result.SearchSuggestion

// ──────────────────────────────────────────────────────────────────────────────

/**
 * QueryType values used for autocomplete suggestions. Including POI/CATEGORY
 * is what makes named venues like "Tipsport Arena Nitra" actually surface in
 * the result list — without them the search engine only returns addresses and
 * place-names, which is what the user experienced as "search doesn't work".
 */
private val FULL_QUERY_TYPES = listOf(
    QueryType.POI,
    QueryType.CATEGORY,
    QueryType.ADDRESS,
    QueryType.PLACE,
    QueryType.STREET,
    QueryType.NEIGHBORHOOD,
    QueryType.LOCALITY,
    QueryType.DISTRICT,
    QueryType.REGION
)

// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class, MapboxExperimental::class)
@Composable
fun HomeScreen(
    espViewModel: EspConnectionViewModel,
    onStartNavigation: (name: String, lat: Double, lng: Double, startLat: Double?, startLng: Double?) -> Unit,
    onOpenSettings: () -> Unit
) {
    // ── Permissions ───────────────────────────────────────────────────────────
    val locationPerms = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    val blePerms: MultiplePermissionsState = rememberMultiplePermissionsState(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else emptyList()
    )

    LaunchedEffect(Unit) {
        if (!locationPerms.allPermissionsGranted) locationPerms.launchMultiplePermissionRequest()
    }

    // ── Theme → map light preset ──────────────────────────────────────────────
    val themeMode by ThemePreferences.mode.collectAsState()
    val mapLightPreset = when (themeMode) {
        ThemeMode.DARK   -> LightPresetValue.NIGHT
        ThemeMode.LIGHT  -> LightPresetValue.DAWN
        ThemeMode.SYSTEM -> LightPresetValue.DUSK  // reasonable neutral fallback for system
    }

    // ── Map ───────────────────────────────────────────────────────────────────
    val mapViewportState = rememberMapViewportState {
        setCameraOptions {
            zoom(12.0)
            center(Point.fromLngLat(18.0870, 48.3069)) // Nitra default
        }
    }
    var deviceLocation by remember { mutableStateOf<Point?>(null) }

    // ── Search ────────────────────────────────────────────────────────────────
    var searchQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<SearchSuggestion>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }

    var customStartPoint by remember { mutableStateOf<Point?>(null) }
    var customStartName by remember { mutableStateOf<String?>(null) }
    var showStartEditor by remember { mutableStateOf(false) }
    var startQuery by remember { mutableStateOf("") }
    var startSuggestions by remember { mutableStateOf<List<SearchSuggestion>>(emptyList()) }
    var showStartSuggestions by remember { mutableStateOf(false) }

    var tappedDestination by remember { mutableStateOf<Pair<String, Point>?>(null) }

    // CRITICAL FIX: use the Search Box API explicitly with built-in data providers.
    // The default factory uses GEOCODING API which returns only addresses and
    // place names — which is why typing things like a venue name returned
    // nothing usable. Search Box API is the one designed for POI / venue /
    // address autocomplete.
    val searchEngine = remember {
        SearchEngine.createSearchEngineWithBuiltInDataProviders(
            ApiType.SEARCH_BOX,
            SearchEngineSettings()
        )
    }

    // ── ESP32 ─────────────────────────────────────────────────────────────────
    val espState by espViewModel.uiState.collectAsState()
    var showEspPanel by remember { mutableStateOf(false) }

    // ── Search helpers ────────────────────────────────────────────────────────

    fun buildSearchOptions(limit: Int) = SearchOptions.Builder()
        .limit(limit)
        .types(*FULL_QUERY_TYPES.toTypedArray())
        .apply { deviceLocation?.let { proximity(it) } }
        .build()

    fun searchDestination(q: String) {
        if (q.length < 2) { showSuggestions = false; return }
        searchEngine.search(q, buildSearchOptions(8), object : SearchSuggestionsCallback {
            override fun onSuggestions(s: List<SearchSuggestion>, r: ResponseInfo) {
                suggestions = s; showSuggestions = s.isNotEmpty()
            }
            override fun onError(e: Exception) { /* silent — UI shows no results */ }
        })
    }

    fun searchStart(q: String) {
        if (q.length < 2) { showStartSuggestions = false; return }
        searchEngine.search(q, buildSearchOptions(6), object : SearchSuggestionsCallback {
            override fun onSuggestions(s: List<SearchSuggestion>, r: ResponseInfo) {
                startSuggestions = s; showStartSuggestions = s.isNotEmpty()
            }
            override fun onError(e: Exception) {}
        })
    }

    fun resolveAndNavigate(sug: SearchSuggestion) {
        searchEngine.select(sug, object : SearchSelectionCallback {
            override fun onResult(s: SearchSuggestion, r: SearchResult, ri: ResponseInfo) {
                val c = r.coordinate
                onStartNavigation(r.name, c.latitude(), c.longitude(),
                    customStartPoint?.latitude(), customStartPoint?.longitude())
            }
            override fun onResults(s: SearchSuggestion, rs: List<SearchResult>, ri: ResponseInfo) {
                rs.firstOrNull()?.let { r ->
                    val c = r.coordinate
                    onStartNavigation(r.name, c.latitude(), c.longitude(),
                        customStartPoint?.latitude(), customStartPoint?.longitude())
                }
            }
            override fun onSuggestions(ss: List<SearchSuggestion>, ri: ResponseInfo) {
                ss.firstOrNull()?.let { resolveAndNavigate(it) }
            }
            override fun onError(e: Exception) {}
        })
    }

    fun resolveAndPreview(sug: SearchSuggestion) {
        searchEngine.select(sug, object : SearchSelectionCallback {
            override fun onResult(s: SearchSuggestion, r: SearchResult, ri: ResponseInfo) {
                searchQuery = r.name; showSuggestions = false
                mapViewportState.flyTo(CameraOptions.Builder().center(r.coordinate).zoom(15.5).build())
            }
            override fun onResults(s: SearchSuggestion, rs: List<SearchResult>, ri: ResponseInfo) {
                rs.firstOrNull()?.let { r ->
                    searchQuery = r.name; showSuggestions = false
                    mapViewportState.flyTo(CameraOptions.Builder().center(r.coordinate).zoom(15.5).build())
                }
            }
            override fun onSuggestions(ss: List<SearchSuggestion>, ri: ResponseInfo) {
                ss.firstOrNull()?.let { resolveAndPreview(it) }
            }
            override fun onError(e: Exception) {}
        })
    }

    fun resolveStart(sug: SearchSuggestion) {
        searchEngine.select(sug, object : SearchSelectionCallback {
            override fun onResult(s: SearchSuggestion, r: SearchResult, ri: ResponseInfo) {
                customStartPoint = r.coordinate; customStartName = r.name
                startQuery = r.name; showStartSuggestions = false; showStartEditor = false
            }
            override fun onResults(s: SearchSuggestion, rs: List<SearchResult>, ri: ResponseInfo) {
                rs.firstOrNull()?.let { r ->
                    customStartPoint = r.coordinate; customStartName = r.name
                    startQuery = r.name; showStartSuggestions = false; showStartEditor = false
                }
            }
            override fun onSuggestions(ss: List<SearchSuggestion>, ri: ResponseInfo) {
                ss.firstOrNull()?.let { resolveStart(it) }
            }
            override fun onError(e: Exception) {}
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {

        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = mapViewportState,
            style = {
                MapboxStandardStyle {
                    lightPreset = mapLightPreset
                }
            },
            compass = {},
            scaleBar = {},
            content = {
                MapEffect(Unit) { mapView ->
                    mapView.getPlugin<LocationComponentPlugin>(Plugin.MAPBOX_LOCATION_COMPONENT_PLUGIN_ID)?.let { location ->
                        location.updateSettings {
                            enabled = true
                            locationPuck = createDefault2DPuck(withBearing = true)
                            puckBearingEnabled = true
                            puckBearing = PuckBearing.HEADING
                        }
                        location.addOnIndicatorPositionChangedListener { pt ->
                            deviceLocation = pt
                        }
                    }

                    mapViewportState.transitionToFollowPuckState()
                    mapView.getPlugin<GesturesPlugin>(Plugin.MAPBOX_GESTURES_PLUGIN_ID)?.addOnMapClickListener { point ->
                        val opts = ReverseGeoOptions(
                            center = point, limit = 1,
                            types = listOf(
                                QueryType.POI, QueryType.ADDRESS,
                                QueryType.PLACE, QueryType.STREET
                            )
                        )
                        searchEngine.search(opts, object : SearchCallback {
                            override fun onResults(results: List<SearchResult>, ri: ResponseInfo) {
                                val top = results.firstOrNull()
                                val label = top?.let { formatTapLabel(it) }
                                    ?: "%.5f, %.5f".format(point.latitude(), point.longitude())
                                val target = top?.coordinate ?: point
                                tappedDestination = label to target
                            }
                            override fun onError(e: Exception) {
                                tappedDestination = "%.5f, %.5f".format(
                                    point.latitude(), point.longitude()
                                ) to point
                            }
                        })
                        true
                    }
                }
            }
        )

        // ── TOP: combined header + FROM + search + suggestions ──────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ── Search bar with integrated settings button ──────────────────
            SearchRowWithSettings(
                query = searchQuery,
                onQueryChange = { q -> searchQuery = q; searchDestination(q) },
                onClear = { searchQuery = ""; showSuggestions = false },
                onOpenSettings = onOpenSettings
            )

            // ── FROM row (custom start point) ───────────────────────────────
            FromRow(customStartName) { showStartEditor = !showStartEditor }

            AnimatedVisibility(
                visible = showStartEditor,
                enter = fadeIn() + expandVertically(),
                exit  = fadeOut() + shrinkVertically()
            ) {
                StartEditorPanel(
                    query = startQuery,
                    onQueryChange = { q -> startQuery = q; searchStart(q) },
                    suggestions = startSuggestions,
                    showSuggestions = showStartSuggestions,
                    onUseGps = {
                        customStartPoint = null; customStartName = null
                        startQuery = ""; showStartSuggestions = false; showStartEditor = false
                    },
                    onSuggestionSelect = { resolveStart(it) }
                )
            }

            AnimatedVisibility(
                visible = showSuggestions && suggestions.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit  = fadeOut() + shrinkVertically()
            ) {
                SuggestionsCard(suggestions, onPreview = ::resolveAndPreview, onNavigate = ::resolveAndNavigate)
            }
        }

        // ── BOTTOM: ESP panel + action row (just ESP pill + center button) ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(
                visible = showEspPanel,
                enter = fadeIn() + expandVertically(),
                exit  = fadeOut() + shrinkVertically()
            ) {
                EspPanel(espState = espState, espViewModel = espViewModel, blePerms = blePerms)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EspPill(status = espState.status, expanded = showEspPanel) {
                    showEspPanel = !showEspPanel
                }
                Spacer(Modifier.weight(1f))
                FloatingActionButton(
                    onClick = {
                        val t = deviceLocation ?: Point.fromLngLat(18.0870, 48.3069)
                        mapViewportState.flyTo(CameraOptions.Builder().center(t).zoom(16.0).build())
                    },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(4.dp)
                ) {
                    Icon(Icons.Rounded.MyLocation, "Center", modifier = Modifier.size(24.dp))
                }
            }
        }

        // ── Tap-destination bar ──────────────────────────────────────────────
        tappedDestination?.let { (name, point) ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 82.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NAVIGATE TO", fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        Text(name, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold, maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onStartNavigation(name, point.latitude(), point.longitude(),
                                customStartPoint?.latitude(), customStartPoint?.longitude())
                            tappedDestination = null
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Directions, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("GO", fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { tappedDestination = null }) {
                        Icon(Icons.Rounded.Close, "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

/**
 * Build a "Name — address" label from a SearchResult so the user can clearly
 * see what they tapped (a venue name plus its street address when both are
 * available). Falls back to whichever piece exists.
 */
private fun formatTapLabel(r: SearchResult): String {
    val name = r.name.takeIf { it.isNotBlank() }
    val addr = r.address?.formattedAddress()?.takeIf { it.isNotBlank() }
    return when {
        name != null && addr != null && !addr.contains(name, ignoreCase = true) ->
            "$name — $addr"
        name != null -> name
        addr != null -> addr
        else -> "Unknown location"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Search row with integrated settings button
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchRowWithSettings(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Main search field — takes all available width minus the settings button
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        ) {
            TextField(
                value = query, onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Street + number, venue, POI...",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = onClear) {
                            Icon(
                                Icons.Rounded.Close, "Clear",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                } else null,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                singleLine = true
            )
        }

        // Settings button — same height as the text field (56 dp)
        Surface(
            modifier = Modifier
                .size(56.dp)
                .clickable { onOpenSettings() },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FROM row
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun FromRow(customStartName: String?, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp)
                .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Text("FROM", fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.widthIn(min = 44.dp))
            Spacer(Modifier.width(8.dp))
            Text(customStartName ?: "My Location (GPS)",
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (customStartName != null) 1f else 0.7f),
                maxLines = 1)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Start point editor
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartEditorPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    suggestions: List<SearchSuggestion>,
    showSuggestions: Boolean,
    onUseGps: () -> Unit,
    onSuggestionSelect: (SearchSuggestion) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f))
    ) {
        Column {
            TextField(
                value = query, onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Street, number, venue...",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null,
                    tint = MaterialTheme.colorScheme.tertiary) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.tertiary
                ),
                singleLine = true
            )
            ListItem(
                headlineContent = { Text("Use My GPS Location") },
                leadingContent = { Icon(Icons.Rounded.MyLocation, null,
                    tint = MaterialTheme.colorScheme.tertiary) },
                modifier = Modifier.clickable { onUseGps() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            if (showSuggestions && suggestions.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                suggestions.take(6).forEach { sug ->
                    SugRow(sug,
                        onPreview = { onSuggestionSelect(sug) },
                        onNavigate = { onSuggestionSelect(sug) },
                        navigateLabel = "SET")
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Suggestions card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SuggestionsCard(
    suggestions: List<SearchSuggestion>,
    onPreview: (SearchSuggestion) -> Unit,
    onNavigate: (SearchSuggestion) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
            items(suggestions) { sug ->
                SugRow(sug, onPreview = { onPreview(sug) }, onNavigate = { onNavigate(sug) })
                HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
        }
    }
}

@Composable
private fun SugRow(
    suggestion: SearchSuggestion,
    onPreview: () -> Unit,
    onNavigate: () -> Unit,
    navigateLabel: String = "GO"
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onPreview() }
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(suggestion.name, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            val addr = suggestion.address?.formattedAddress()
            if (!addr.isNullOrBlank()) {
                Text(addr, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onNavigate, shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
            Icon(Icons.Rounded.Directions, null, Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(navigateLabel, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ESP32 status pill
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun EspPill(status: EspConnectionStatus, expanded: Boolean, onClick: () -> Unit) {
    val (label, dotColor) = when (status) {
        EspConnectionStatus.CONNECTED    -> "ESP32-HUD" to MaterialTheme.colorScheme.tertiary
        EspConnectionStatus.CONNECTING   -> "CONNECTING" to MaterialTheme.colorScheme.primary
        EspConnectionStatus.SCANNING     -> "SCANNING"   to MaterialTheme.colorScheme.primary
        EspConnectionStatus.ERROR        -> "ERROR"      to MaterialTheme.colorScheme.error
        EspConnectionStatus.DISCONNECTED -> "ESP32-HUD"  to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }

    val pulsing = status == EspConnectionStatus.SCANNING || status == EspConnectionStatus.CONNECTING
    val inf = rememberInfiniteTransition(label = "pill")
    val a by inf.animateFloat(0.3f, 1f,
        infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), "a")

    Surface(
        modifier = Modifier.height(56.dp).clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp,
            if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp)
                .background(dotColor.copy(alpha = if (pulsing) a else 1f), RoundedCornerShape(50)))
            val btIcon = when (status) {
                EspConnectionStatus.CONNECTED    -> Icons.Rounded.BluetoothConnected
                EspConnectionStatus.CONNECTING,
                EspConnectionStatus.SCANNING     -> Icons.Rounded.BluetoothSearching
                EspConnectionStatus.ERROR        -> Icons.Rounded.BluetoothDisabled
                EspConnectionStatus.DISCONNECTED -> Icons.Rounded.Bluetooth
            }
            Icon(btIcon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp))
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ESP32 expanded panel
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BleDevicePicker(
    devices: List<BleDeviceItem>,
    isScanning: Boolean,
    onSelect: (address: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SELECT BLE DEVICE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            if (devices.isEmpty()) {
                Text(
                    if (isScanning) "Scanning for nearby BLE devices…"
                    else "No devices found. Make sure your ESP32-HUD is powered on and advertising.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(devices) { device ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(device.address) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            border = BorderStroke(
                                1.dp,
                                if (device.name == "ESP32-HUD")
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Bluetooth,
                                    contentDescription = null,
                                    tint = if (device.name == "ESP32-HUD")
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        device.name,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        device.address,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun EspPanel(
    espState: EspConnectionUiState,
    espViewModel: EspConnectionViewModel,
    blePerms: MultiplePermissionsState
) {
    // Show BLE device picker sheet when scanning
    if (espState.showDevicePicker) {
        BleDevicePicker(
            devices    = espState.scannedDevices,
            isScanning = espState.status == EspConnectionStatus.SCANNING,
            onSelect   = { address -> espViewModel.connectToDevice(address) },
            onDismiss  = { espViewModel.cancelScan() }
        )
    }

    val isLinked = espState.status == EspConnectionStatus.CONNECTED ||
            espState.status == EspConnectionStatus.CONNECTING ||
            espState.status == EspConnectionStatus.SCANNING

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = when (espState.status) {
                    EspConnectionStatus.CONNECTED    -> MaterialTheme.colorScheme.tertiary
                    EspConnectionStatus.SCANNING,
                    EspConnectionStatus.CONNECTING   -> MaterialTheme.colorScheme.primary
                    EspConnectionStatus.ERROR        -> MaterialTheme.colorScheme.error
                    EspConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                }
                Box(Modifier.size(8.dp).background(dotColor, RoundedCornerShape(50)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(espState.deviceName.uppercase(),
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        fontSize = 12.sp, letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(espState.status.name,
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp,
                        color = dotColor)
                }
                Button(
                    onClick = {
                        if (isLinked) {
                            espViewModel.disconnect()
                        } else {
                            if (!blePerms.allPermissionsGranted) {
                                blePerms.launchMultiplePermissionRequest()
                            } else {
                                espViewModel.connect()
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLinked)
                            MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isLinked) "DISCONNECT" else "CONNECT",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
                }
            }

            espState.errorMessage?.let { msg ->
                Surface(shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)) {
                    Text(msg, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error)
                }
            }

            if (espState.lastPayloadSent.isNotBlank()) {
                Surface(shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                    Text("▸ ${espState.lastPayloadSent}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            if (espState.status == EspConnectionStatus.DISCONNECTED && espState.lastPayloadSent.isBlank()) {
                Text(
                    "Power on your ESP32-HUD, then tap CONNECT. " +
                            "The app will scan for nearby BLE devices and let you pick one.",
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}