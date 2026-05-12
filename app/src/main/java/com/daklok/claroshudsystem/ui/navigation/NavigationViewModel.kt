package com.daklok.claroshudsystem.ui.navigation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.turf.TurfMeasurement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Maneuver type for icon selection
enum class ManeuverType {
    STRAIGHT, TURN_LEFT, TURN_RIGHT, TURN_SLIGHT_LEFT, TURN_SLIGHT_RIGHT,
    TURN_SHARP_LEFT, TURN_SHARP_RIGHT, U_TURN, ROUNDABOUT, ARRIVE, DEPART, UNKNOWN
}

data class NavigationUiState(
    val destinationName: String = "",
    val distanceRemaining: String = "--",
    val durationRemaining: String = "--",
    val eta: String = "--:--",
    val nextManeuver: String = "Acquiring GPS...",
    val nextManeuverDistance: String = "",
    // Raw meters for the BT payload — survives unit formatting changes.
    val nextManeuverDistanceMeters: Float = 0f,
    val maneuverType: ManeuverType = ManeuverType.STRAIGHT,
    // For roundabouts: which exit to take (1-based). null when not a roundabout.
    val roundaboutExit: Int? = null,
    // Live vehicle / device speed in km/h, rounded to nearest int.
    val speedKmh: Int = 0,
    val isNavigating: Boolean = false,
    val routeReady: Boolean = false,
    val routes: List<NavigationRoute> = emptyList(),
    // The points of the route that are still ahead of the user
    val remainingRoutePoints: List<Point> = emptyList(),
    val error: String? = null
)

class NavigationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    private val _matchedLocation = MutableStateFlow<LocationMatcherResult?>(null)
    val matchedLocation: StateFlow<LocationMatcherResult?> = _matchedLocation.asStateFlow()

    private val mapboxNavigation: MapboxNavigation?
        get() = MapboxNavigationApp.current()

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) {}
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            _matchedLocation.value = locationMatcherResult
            val mps: Double = locationMatcherResult.enhancedLocation.speed ?: 0.0
            val kmh = (mps * 3.6).toInt().coerceAtLeast(0)
            _uiState.value = _uiState.value.copy(speedKmh = kmh)
        }
    }

    private val routeProgressObserver = object : RouteProgressObserver {
        override fun onRouteProgressChanged(routeProgress: RouteProgress) {
            val etaMs = System.currentTimeMillis() + (routeProgress.durationRemaining * 1000).toLong()
            val etaFormatted = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(etaMs))

            val stepDistanceM = routeProgress.currentLegProgress?.currentStepProgress?.distanceRemaining ?: 0f
            val stepDistStr = when {
                stepDistanceM >= 1000 -> "%.1f km".format(stepDistanceM / 1000f)
                else -> "${stepDistanceM.toInt()} m"
            }

            // ── Parse banner instruction for text + maneuver type ─────────
            val banner = routeProgress.bannerInstructions
            val (maneuverText, maneuverType) = parseBannerInstruction(banner)

            // ── Roundabout exit number, if the current step is a roundabout
            val maneuver: StepManeuver? = routeProgress
                .currentLegProgress
                ?.currentStepProgress
                ?.step
                ?.maneuver()
            val exit: Int? = if (maneuverType == ManeuverType.ROUNDABOUT) {
                maneuver?.exit()?.takeIf { it > 0 }
            } else null

            // ── Compute remaining route points for the UI ──────────────
            val distRemaining = routeProgress.distanceRemaining.toDouble()
            val remainingPoints = getRemainingPoints(routeProgress)

            _uiState.value = _uiState.value.copy(
                distanceRemaining = "%.1f km".format(distRemaining / 1000.0),
                durationRemaining = "${(routeProgress.durationRemaining / 60.0).toInt()} min",
                eta = etaFormatted,
                nextManeuver = maneuverText,
                nextManeuverDistance = stepDistStr,
                nextManeuverDistanceMeters = stepDistanceM,
                maneuverType = maneuverType,
                roundaboutExit = exit,
                remainingRoutePoints = remainingPoints,
                routes = listOf(routeProgress.navigationRoute)
            )
        }
    }

    private fun getRemainingPoints(routeProgress: RouteProgress): List<Point> {
        val legProgress = routeProgress.currentLegProgress ?: return emptyList()
        val allSteps = legProgress.routeLeg?.steps() ?: return emptyList()
        val currentStepProgress = legProgress.currentStepProgress ?: return emptyList()
        val currentStep = currentStepProgress.step ?: return emptyList()
        
        val currentStepIdx = allSteps.indexOf(currentStep).coerceAtLeast(0)
        
        val points = mutableListOf<Point>()
        
        for (i in currentStepIdx until allSteps.size) {
            val step = allSteps[i]
            val geometry = step.geometry() ?: continue
            val stepPoints = com.mapbox.geojson.utils.PolylineUtils.decode(geometry, 6)
            
            if (i == currentStepIdx) {
                // Drop points from the current step based on distanceTraveled in METERS
                val traveled = currentStepProgress.distanceTraveled.toDouble()
                var accumulated = 0.0
                var dropIndex = 0
                for (j in 0 until stepPoints.size - 1) {
                    val d = TurfMeasurement.distance(stepPoints[j], stepPoints[j+1], "meters")
                    if (accumulated + d > traveled) break
                    accumulated += d
                    dropIndex = j + 1
                }
                points.addAll(stepPoints.drop(dropIndex))
            } else {
                points.addAll(stepPoints)
            }
        }
        return points
    }

    init {
        startSessionIfPermitted()
    }

    fun startSessionIfPermitted() {
        if (!hasLocationPermissions()) {
            _uiState.value = _uiState.value.copy(nextManeuver = "Location permission needed")
            return
        }
        try {
            mapboxNavigation?.registerLocationObserver(locationObserver)
            mapboxNavigation?.registerRouteProgressObserver(routeProgressObserver)
            mapboxNavigation?.startTripSession()
        } catch (e: SecurityException) {
            _uiState.value = _uiState.value.copy(error = "Location permission denied.")
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val ctx = getApplication<Application>().applicationContext
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun startNavigation(
        name: String,
        destLat: Double,
        destLng: Double,
        startLat: Double? = null,
        startLng: Double? = null
    ) {
        _uiState.value = _uiState.value.copy(
            destinationName = name,
            nextManeuver = "Calculating Route..."
        )

        val nav = mapboxNavigation ?: run {
            _uiState.value = _uiState.value.copy(nextManeuver = "Navigation not ready")
            return
        }

        val origin: Point? = if (startLat != null && startLng != null) {
            Point.fromLngLat(startLng, startLat)
        } else {
            _matchedLocation.value?.enhancedLocation?.let {
                Point.fromLngLat(it.longitude, it.latitude)
            }
        }

        if (origin == null) {
            _uiState.value = _uiState.value.copy(nextManeuver = "Waiting for GPS fix...")
            val retryObserver = object : LocationObserver {
                override fun onNewRawLocation(rawLocation: Location) {}
                override fun onNewLocationMatcherResult(result: LocationMatcherResult) {
                    nav.unregisterLocationObserver(this)
                    val pt = Point.fromLngLat(
                        result.enhancedLocation.longitude,
                        result.enhancedLocation.latitude
                    )
                    requestRoute(nav, name, pt, Point.fromLngLat(destLng, destLat))
                }
            }
            nav.registerLocationObserver(retryObserver)
            return
        }

        requestRoute(nav, name, origin, Point.fromLngLat(destLng, destLat))
    }

    private fun requestRoute(nav: MapboxNavigation, name: String, origin: Point, destination: Point) {
        val routeOptions = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .coordinatesList(listOf(origin, destination))
            .bannerInstructions(true)
            .voiceInstructions(true)
            .build()

        nav.requestRoutes(
            routeOptions,
            object : NavigationRouterCallback {
                override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) {
                    if (routes.isEmpty()) return
                    nav.setNavigationRoutes(routes)
                    val leg = routes.first().directionsRoute.legs()?.firstOrNull()
                    val totalSeconds = leg?.duration() ?: 0.0
                    val totalMeters = leg?.distance() ?: 0.0
                    val etaMs = System.currentTimeMillis() + (totalSeconds * 1000).toLong()
                    val etaFormatted = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(etaMs))

                    val firstStep = leg?.steps()?.firstOrNull()
                    val firstBanner = firstStep?.bannerInstructions()?.firstOrNull()
                    val (firstText, firstType) = parseBannerInstructionModel(firstBanner)
                    val firstStepDist = firstStep?.distance()?.toFloat() ?: 0f
                    val firstStepDistStr = when {
                        firstStepDist >= 1000 -> "%.1f km".format(firstStepDist / 1000f)
                        firstStepDist > 0     -> "${firstStepDist.toInt()} m"
                        else                  -> ""
                    }
                    val firstExit = if (firstType == ManeuverType.ROUNDABOUT)
                        firstStep?.maneuver()?.exit()?.takeIf { it > 0 }
                    else null

                    val initialPoints = leg?.steps()?.flatMap { step ->
                        step.geometry()?.let { com.mapbox.geojson.utils.PolylineUtils.decode(it, 6) } ?: emptyList()
                    } ?: emptyList()

                    _uiState.value = _uiState.value.copy(
                        routeReady = true,
                        isNavigating = true,
                        routes = routes,
                        remainingRoutePoints = initialPoints,
                        distanceRemaining = "%.1f km".format(totalMeters / 1000.0),
                        durationRemaining = "${(totalSeconds / 60.0).toInt()} min",
                        eta = etaFormatted,
                        nextManeuver = firstText.ifEmpty { "Head toward $name" },
                        nextManeuverDistance = firstStepDistStr,
                        nextManeuverDistanceMeters = firstStepDist,
                        maneuverType = firstType,
                        roundaboutExit = firstExit
                    )
                }

                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                    val msg = reasons.firstOrNull()?.message ?: "Unknown"
                    _uiState.value = _uiState.value.copy(nextManeuver = "Route failed: $msg")
                }

                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {}
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        mapboxNavigation?.unregisterLocationObserver(locationObserver)
        mapboxNavigation?.unregisterRouteProgressObserver(routeProgressObserver)
        mapboxNavigation?.stopTripSession()
    }

    // ── Banner instruction parsing helpers ────────────────────────────────────

    private fun parseBannerInstruction(banner: BannerInstructions?): Pair<String, ManeuverType> {
        if (banner == null) return Pair(_uiState.value.nextManeuver, _uiState.value.maneuverType)
        val primary = banner.primary() ?: return Pair("Continue", ManeuverType.STRAIGHT)
        val type = primary.type() ?: ""
        val modifier = primary.modifier() ?: ""
        val text = primary.text() ?: "Continue"
        return Pair(text, resolveManeuverType(type, modifier))
    }

    private fun parseBannerInstructionModel(
        banner: com.mapbox.api.directions.v5.models.BannerInstructions?
    ): Pair<String, ManeuverType> {
        if (banner == null) return Pair("", ManeuverType.STRAIGHT)
        val primary = banner.primary() ?: return Pair("", ManeuverType.STRAIGHT)
        val type = primary.type() ?: ""
        val modifier = primary.modifier() ?: ""
        val text = primary.text() ?: ""
        return Pair(text, resolveManeuverType(type, modifier))
    }

    private fun resolveManeuverType(type: String, modifier: String): ManeuverType {
        val t = type.lowercase().trim()
        val m = modifier.lowercase().trim()

        return when {
            t == "arrive" -> ManeuverType.ARRIVE
            t == "depart" -> ManeuverType.DEPART
            t.contains("roundabout") || t.contains("rotary") -> ManeuverType.ROUNDABOUT
            m == "uturn" -> ManeuverType.U_TURN
            m == "sharp right" -> ManeuverType.TURN_SHARP_RIGHT
            m == "right" -> ManeuverType.TURN_RIGHT
            m == "slight right" -> ManeuverType.TURN_SLIGHT_RIGHT
            m == "sharp left" -> ManeuverType.TURN_SHARP_LEFT
            m == "left" -> ManeuverType.TURN_LEFT
            m == "slight left" -> ManeuverType.TURN_SLIGHT_LEFT
            m == "straight" || t == "continue" -> ManeuverType.STRAIGHT
            t == "turn" || t == "end of road" || t == "fork" -> when {
                m.contains("right") -> ManeuverType.TURN_RIGHT
                m.contains("left") -> ManeuverType.TURN_LEFT
                else -> ManeuverType.STRAIGHT
            }
            else -> ManeuverType.STRAIGHT
        }
    }
}
