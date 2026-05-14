package com.daklok.claroshudsystem.ui.navigation

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daklok.claroshudsystem.R
import com.daklok.claroshudsystem.ble.BluetoothPayload
import com.daklok.claroshudsystem.ble.BleDeviceItem
import com.daklok.claroshudsystem.ble.EspConnectionStatus
import com.daklok.claroshudsystem.ble.EspConnectionViewModel
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotationGroup
import com.mapbox.maps.plugin.annotation.AnnotationConfig
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.locationcomponent.LocationComponentConstants
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.LocationPuck3D
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.Plugin
import com.mapbox.maps.plugin.compass.CompassPlugin
import com.mapbox.maps.plugin.gestures.GesturesPlugin
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin
import com.mapbox.maps.plugin.scalebar.ScaleBarPlugin
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import com.mapbox.maps.extension.compose.style.BooleanValue
import com.mapbox.maps.extension.compose.style.standard.rememberStandardStyleState
import com.mapbox.maps.MapboxExperimental
import com.mapbox.search.QueryType
import com.mapbox.search.ResponseInfo
import com.mapbox.search.ReverseGeoOptions
import com.mapbox.search.SearchCallback
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.ApiType
import com.mapbox.search.result.SearchResult
import com.daklok.claroshudsystem.prefs.PuckModel
import com.daklok.claroshudsystem.prefs.ThemeMode
import com.daklok.claroshudsystem.prefs.ThemePreferences

// ── Orientation mode ──────────────────────────────────────────────────────────
enum class MapOrientation { NORTH_UP, HEADING_UP }

@OptIn(MapboxExperimental::class, ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    destinationName: String,
    destLat: Double,
    destLng: Double,
    startLat: Double? = null,
    startLng: Double? = null,
    onBack: () -> Unit,
    viewModel: NavigationViewModel = viewModel(),
    espViewModel: EspConnectionViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val routePoints by viewModel.remainingRoutePoints.collectAsState()
    val espState by espViewModel.uiState.collectAsState()

    var orientation by remember { mutableStateOf(MapOrientation.HEADING_UP) }
    var showDeveloperPanel by remember { mutableStateOf(false) }
    var tappedDestination by remember { mutableStateOf<Pair<String, Point>?>(null) }

    // ── Theme → map light preset ──────────────────────────────────────────────
    val themeMode by ThemePreferences.mode.collectAsState()
    val mapLightPreset = when (themeMode) {
        ThemeMode.DARK   -> LightPresetValue.NIGHT
        ThemeMode.LIGHT  -> LightPresetValue.DAWN
        ThemeMode.SYSTEM -> LightPresetValue.NIGHT  // navigation defaults to dark
    }

    // Search engine for reverse-geocoding building/POI taps on the map.
    val searchEngine = remember {
        SearchEngine.createSearchEngineWithBuiltInDataProviders(
            ApiType.SEARCH_BOX,
            SearchEngineSettings()
        )
    }

    val mapViewportState = rememberMapViewportState {
        setCameraOptions {
            zoom(17.0)
            pitch(55.0)
            center(Point.fromLngLat(destLng, destLat))
        }
    }

    LaunchedEffect(orientation, uiState.routeReady) {
        val bearingOption: FollowPuckViewportStateBearing = when (orientation) {
            MapOrientation.NORTH_UP   -> FollowPuckViewportStateBearing.Constant(0.0)
            MapOrientation.HEADING_UP -> FollowPuckViewportStateBearing.SyncWithLocationPuck
        }
        mapViewportState.transitionToFollowPuckState(
            followPuckViewportStateOptions = FollowPuckViewportStateOptions.Builder()
                .zoom(17.2)
                .pitch(55.0)
                .bearing(bearingOption)
                .padding(EdgeInsets(120.0, 0.0, 100.0, 0.0))
                .build()
        )
    }

    LaunchedEffect(destinationName, destLat, destLng) {
        viewModel.startNavigation(destinationName, destLat, destLng, startLat, startLng)
    }

    // ── Build the BT payload string + push it whenever inputs change ────────
    val btPayload = remember(
        uiState.maneuverType,
        uiState.nextManeuverDistanceMeters,
        uiState.eta,
        uiState.speedKmh,
        uiState.roundaboutExit
    ) {
        BluetoothPayload.build(
            maneuver = uiState.maneuverType,
            distanceToManeuverMeters = uiState.nextManeuverDistanceMeters,
            etaHHmm = uiState.eta,
            speedKmh = uiState.speedKmh,
            roundaboutExit = uiState.roundaboutExit
        )
    }
    LaunchedEffect(btPayload) {
        espViewModel.sendPayload(btPayload)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // BLE device picker sheet — shown when scanning
        if (espState.showDevicePicker) {
            BleDevicePicker(
                devices    = espState.scannedDevices,
                isScanning = espState.status == EspConnectionStatus.SCANNING,
                onSelect   = { address -> espViewModel.connectToDevice(address) },
                onDismiss  = { espViewModel.cancelScan() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EspConnectionBar(
                status = espState.status,
                deviceName = espState.deviceName,
                onConnect = { espViewModel.connect() },
                onDisconnect = { espViewModel.disconnect() },
                onToggleDev = { showDeveloperPanel = !showDeveloperPanel },
                devActive = showDeveloperPanel
            )

            RouteEndpointsCard(
                origin = if (startLat != null && startLng != null)
                    "%.5f, %.5f".format(startLat, startLng)
                else "GPS · My Location",
                destination = uiState.destinationName.ifBlank { destinationName }
            )

            NavigationStatPanel(
                maneuverType = uiState.maneuverType,
                maneuverText = uiState.nextManeuver,
                nextDistance = uiState.nextManeuverDistance,
                eta = uiState.eta,
                speedKmh = uiState.speedKmh,
                durationRemaining = uiState.durationRemaining,
                distanceRemaining = uiState.distanceRemaining,
                roundaboutExit = uiState.roundaboutExit
            )

            AnimatedVisibility(
                visible = showDeveloperPanel,
                enter = fadeIn() + expandVertically(),
                exit  = fadeOut() + shrinkVertically()
            ) {
                DeveloperPanel(
                    currentPayload = btPayload,
                    log = espState.payloadLog,
                    onClear = { espViewModel.clearLog() }
                )
            }

            MapPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                mapViewportState = mapViewportState,
                routePoints = routePoints,
                orientation = orientation,
                lightPreset = mapLightPreset,
                onToggleOrientation = {
                    orientation = if (orientation == MapOrientation.NORTH_UP)
                        MapOrientation.HEADING_UP else MapOrientation.NORTH_UP
                    mapViewportState.transitionToFollowPuckState()
                },
                onAbort = onBack,
                onMapTap = { point ->
                    // Reverse geocode the tapped point for a friendly name/address.
                    val opts = ReverseGeoOptions(
                        center = point, limit = 1,
                        types = listOf(
                            QueryType.POI, QueryType.ADDRESS, QueryType.PLACE, QueryType.STREET
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
                }
            )
        }

        // ── Tap-info bar (shown when the user taps a building/POI on the map)
        tappedDestination?.let { (name, _) ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "LOCATION",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { tappedDestination = null }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

/** Build a "Name — address" display string from a Mapbox SearchResult. */
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
// BLE device picker bottom sheet
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

// ─────────────────────────────────────────────────────────────────────────────
// Top bar: ESP32-HUD connection status + developer toggle
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun EspConnectionBar(
    status: EspConnectionStatus,
    deviceName: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleDev: () -> Unit,
    devActive: Boolean
) {
    val (label, dotColor, icon) = when (status) {
        EspConnectionStatus.CONNECTED    -> Triple("CONNECTED",  MaterialTheme.colorScheme.tertiary, Icons.Rounded.BluetoothConnected)
        EspConnectionStatus.CONNECTING   -> Triple("CONNECTING", MaterialTheme.colorScheme.primary,  Icons.Rounded.BluetoothSearching)
        EspConnectionStatus.SCANNING     -> Triple("SCANNING",   MaterialTheme.colorScheme.primary,  Icons.Rounded.BluetoothSearching)
        EspConnectionStatus.ERROR        -> Triple("ERROR",      MaterialTheme.colorScheme.error,    Icons.Rounded.BluetoothDisabled)
        EspConnectionStatus.DISCONNECTED -> Triple("OFFLINE",    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), Icons.Rounded.Bluetooth)
    }

    val pulsing = status == EspConnectionStatus.SCANNING || status == EspConnectionStatus.CONNECTING
    val infinite = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )
    val effectiveDotAlpha = if (pulsing) dotAlpha else 1f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = dotColor.copy(alpha = effectiveDotAlpha),
                        shape = RoundedCornerShape(50)
                    )
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    color = dotColor
                )
            }

            val isLinkedOrBusy = status == EspConnectionStatus.CONNECTED ||
                    status == EspConnectionStatus.CONNECTING ||
                    status == EspConnectionStatus.SCANNING
            TextButton(
                onClick = { if (isLinkedOrBusy) onDisconnect() else onConnect() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isLinkedOrBusy) "DISCONNECT" else "CONNECT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.width(2.dp))
            FilledIconToggleButton(
                checked = devActive,
                onCheckedChange = { onToggleDev() },
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(10.dp),
                colors = IconButtonDefaults.filledIconToggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    checkedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Code,
                    contentDescription = "Developer",
                    tint = if (devActive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FROM / TO summary card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RouteEndpointsCard(origin: String, destination: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            EndpointRow(label = "FROM", value = origin, dotColor = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(6.dp))
            EndpointRow(label = "TO",   value = destination, dotColor = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EndpointRow(label: String, value: String, dotColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, RoundedCornerShape(50))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.widthIn(min = 44.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Big navigation stat panel — Mapbox-style arrow + DIST/ETA/SPD numbers
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun NavigationStatPanel(
    maneuverType: ManeuverType,
    maneuverText: String,
    nextDistance: String,
    eta: String,
    speedKmh: Int,
    durationRemaining: String,
    distanceRemaining: String,
    roundaboutExit: Int?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManeuverIcon(
                    maneuverType = maneuverType,
                    roundaboutExit = roundaboutExit,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (nextDistance.isNotBlank()) {
                        Text(
                            text = "IN $nextDistance",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = maneuverText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatCell(label = "ETA",   value = eta)
                StatCell(label = "TIME",  value = durationRemaining)
                StatCell(label = "DIST",  value = distanceRemaining)
                StatCell(
                    label = "KM/H",
                    value = speedKmh.toString(),
                    valueColor = if (speedKmh > 0)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, valueColor: Color? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Maneuver icon — looks up the appropriate drawable for the current maneuver.
// Replaces the old hand-drawn Canvas arrows. For roundabouts we additionally
// overlay the numeric exit ("EXIT 2") for clearer guidance on screen.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ManeuverIcon(
    maneuverType: ManeuverType,
    roundaboutExit: Int? = null,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    val resId = when (maneuverType) {
        ManeuverType.TURN_LEFT          -> R.drawable.ic_maneuver_turn_left
        ManeuverType.TURN_RIGHT         -> R.drawable.ic_maneuver_turn_right
        ManeuverType.TURN_SLIGHT_LEFT   -> R.drawable.ic_maneuver_slight_left
        ManeuverType.TURN_SLIGHT_RIGHT  -> R.drawable.ic_maneuver_slight_right
        ManeuverType.TURN_SHARP_LEFT    -> R.drawable.ic_maneuver_sharp_left
        ManeuverType.TURN_SHARP_RIGHT   -> R.drawable.ic_maneuver_sharp_right
        ManeuverType.U_TURN             -> R.drawable.ic_maneuver_uturn
        ManeuverType.ROUNDABOUT         -> R.drawable.ic_maneuver_roundabout
        ManeuverType.ARRIVE             -> R.drawable.ic_maneuver_arrive
        ManeuverType.STRAIGHT,
        ManeuverType.DEPART,
        ManeuverType.UNKNOWN            -> R.drawable.ic_maneuver_straight
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(id = resId),
            contentDescription = maneuverType.name,
            tint = tint,
            modifier = Modifier.fillMaxSize()
        )
        if (maneuverType == ManeuverType.ROUNDABOUT && roundaboutExit != null && roundaboutExit > 0) {
            // Small exit-number badge in the lower-left corner.
            Surface(
                modifier = Modifier.align(Alignment.BottomStart),
                shape = RoundedCornerShape(6.dp),
                color = tint
            ) {
                Text(
                    text = roundaboutExit.toString(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Developer panel — live BT payload + scrolling log
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DeveloperPanel(
    currentPayload: String,
    log: List<String>,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "BLUETOOTH PAYLOAD · LIVE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        "CLEAR",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            ) {
                Text(
                    text = "▸ $currentPayload",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            val listState = rememberLazyListState()
            LaunchedEffect(log.size) {
                if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
            }

            if (log.isEmpty()) {
                // Show the actual payload schema we now emit.
                Text(
                    text = "<DIR>, <DIST>, <ETA>, <SPD>   e.g.  RIGHT, 11m, 20:00, 15",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                ) {
                    items(log) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Map panel — Mapbox map with route polyline. The native scale-bar and
// compass plugins are explicitly disabled here so they don't clutter the
// view (we have our own orientation toggle and the user doesn't want the
// stock scale indicator).
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun MapPanel(
    modifier: Modifier = Modifier,
    mapViewportState: com.mapbox.maps.extension.compose.animation.viewport.MapViewportState,
    routePoints: List<Point>,
    orientation: MapOrientation,
    lightPreset: LightPresetValue,
    onToggleOrientation: () -> Unit,
    onAbort: () -> Unit,
    onMapTap: (Point) -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            MapboxMap(
                modifier = Modifier.fillMaxSize(),
                mapViewportState = mapViewportState,
                style = {
                    MapboxStandardStyle(
                        standardStyleState = rememberStandardStyleState {
                            configurationsState.lightPreset = lightPreset
                            configurationsState.show3dObjects = BooleanValue(true)
                        }
                    )
                },
                compass = {},
                scaleBar = {},
                onMapLongClickListener = { false }
            ) {
                val currentPuckModel by ThemePreferences.puckModel.collectAsState()



                if (routePoints.size >= 2) {
                    // Apple Maps style route: vivid blue inner line with contrasting border.
                    // Use lightPreset (already passed as parameter) to decide colours.
                    // Dark/Night map → bright sky-blue inner + white border to pop off dark tiles.
                    // Light/Dawn map → strong royal-blue inner + dark-blue border to stay visible.
                    val isDark = lightPreset == LightPresetValue.NIGHT
                    val innerColor = if (isDark) "#4FC3F7" else "#1A8FFF"
                    val outerColor = if (isDark) "#FFFFFF" else "#0057CC"

                    PolylineAnnotationGroup(
                        annotations = listOf(
                            PolylineAnnotationOptions()
                                .withPoints(routePoints)
                                .withLineColor(outerColor)
                                .withLineWidth(13.0)
                                .withLineOpacity(1.0),
                            PolylineAnnotationOptions()
                                .withPoints(routePoints)
                                .withLineColor(innerColor)
                                .withLineWidth(8.5)
                                .withLineOpacity(1.0)
                        ),
                        annotationConfig = AnnotationConfig(
                            slotName = "middle"
                        )
                    ) {
                        lineEmissiveStrength = 1.0
                    }
                }

                MapEffect(currentPuckModel) { mapView ->
                    val navigationLocationProvider =
                        com.mapbox.navigation.ui.maps.location.NavigationLocationProvider()

                    mapView.getPlugin<LocationComponentPlugin>(Plugin.MAPBOX_LOCATION_COMPONENT_PLUGIN_ID)?.let { location ->
                        location.setLocationProvider(navigationLocationProvider)

                        val navigation = com.mapbox.navigation.core.lifecycle.MapboxNavigationApp.current()
                        navigation?.registerLocationObserver(object :
                            com.mapbox.navigation.core.trip.session.LocationObserver {
                            override fun onNewRawLocation(rawLocation: com.mapbox.common.location.Location) {}
                            override fun onNewLocationMatcherResult(locationMatcherResult: com.mapbox.navigation.core.trip.session.LocationMatcherResult) {
                                navigationLocationProvider.changePosition(locationMatcherResult.enhancedLocation)
                            }
                        })

                        location.updateSettings {
                            puckBearing = PuckBearing.COURSE
                            puckBearingEnabled = true
                            locationPuck = when (currentPuckModel) {
                                PuckModel.SPORTS_CAR -> LocationPuck3D(
                                    modelUri = "asset://car_sports.glb",
                                    modelScale = listOf(15.0f, 15.0f, 15.0f),
                                    modelRotation = listOf(0.0f, 0.0f, 180.0f)
                                )
                                PuckModel.REGULAR_CAR -> LocationPuck3D(
                                    modelUri = "asset://car_regular.glb",
                                    modelScale = listOf(15.0f, 15.0f, 15.0f),
                                    modelRotation = listOf(0.0f, 0.0f, 0.0f)
                                )
                                PuckModel.ARROW -> LocationPuck2D(
                                    topImage = ImageHolder.from(com.daklok.claroshudsystem.R.drawable.ic_nav_cursor),
                                    scaleExpression = "[\"literal\", 2.0]"
                                )
                            }
                            slot = "top"
                            enabled = true
                        }
                    }

                    // Tap-to-info on buildings / POIs.
                    mapView.getPlugin<GesturesPlugin>(Plugin.MAPBOX_GESTURES_PLUGIN_ID)?.addOnMapClickListener { pt ->
                        onMapTap(pt)
                        true
                    }

                    // Move Logo and Attribution to top area to avoid clutter
                    mapView.getPlugin<com.mapbox.maps.plugin.logo.LogoPlugin>(Plugin.MAPBOX_LOGO_PLUGIN_ID)?.let { logo ->
                        logo.updateSettings {
                            position = android.view.Gravity.TOP or android.view.Gravity.START
                            marginTop = 10f
                            marginLeft = 10f
                        }
                    }
                    mapView.getPlugin<com.mapbox.maps.plugin.attribution.AttributionPlugin>(Plugin.MAPBOX_ATTRIBUTION_PLUGIN_ID)?.let { attr ->
                        attr.updateSettings {
                            position = android.view.Gravity.TOP or android.view.Gravity.START
                            marginTop = 10f
                            marginLeft = 85f
                        }
                    }

                    // The route line is placed in the "middle" slot via AnnotationConfig,
                    // and the puck is in the "top" slot, so the line stays below the puck.

                }
            }

            OrientationToggle(
                orientation = orientation,
                onToggle = onToggleOrientation,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            )

            FilledTonalButton(
                onClick = onAbort,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "EXIT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Orientation toggle button (compact, sits in the corner of the map)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun OrientationToggle(
    orientation: MapOrientation,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isNorth = orientation == MapOrientation.NORTH_UP
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier
                .clickable { onToggle() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isNorth) Icons.Rounded.Explore else Icons.Rounded.Navigation,
                contentDescription = if (isNorth) "North up" else "Heading up",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isNorth) "N" else "↑",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
