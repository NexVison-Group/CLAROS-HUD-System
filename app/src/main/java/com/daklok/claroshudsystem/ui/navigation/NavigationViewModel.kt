package com.daklok.claroshudsystem.ui.navigation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.RouteOptions
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
    val error: String? = null
)

class NavigationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    private val _remainingRoutePoints = MutableStateFlow<List<Point>>(emptyList())
    val remainingRoutePoints: StateFlow<List<Point>> = _remainingRoutePoints.asStateFlow()

    private val _matchedLocation = MutableStateFlow<LocationMatcherResult?>(null)
    val matchedLocation: StateFlow<LocationMatcherResult?> = _matchedLocation.asStateFlow()

    private var decodedSteps: List<List<Point>> = emptyList()
    private var lastRouteProgress: RouteProgress? = null
    private var lastRouteId: String? = null

    // For smooth interpolation
    private var lastUpdateTimestamp = 0L
    private var previousPuckLocation: Point? = null
    private var lastPuckLocation: Point? = null
    private var currentSpeedMps = 0.0
    private var currentBearing = 0.0
    private var interpolationJob: kotlinx.coroutines.Job? = null

    private fun cacheRouteGeometry(navRoute: NavigationRoute) {
        val leg = navRoute.directionsRoute.legs()?.firstOrNull()
        decodedSteps = leg?.steps()?.map { step ->
            step.geometry()?.let { com.mapbox.geojson.utils.PolylineUtils.decode(it, 6) } ?: emptyList()
        } ?: emptyList()
        lastRouteId = navRoute.id
    }

    val mapboxNavigation: MapboxNavigation?
        get() = MapboxNavigationApp.current()

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) {}
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            _matchedLocation.value = locationMatcherResult
            val mps: Double = locationMatcherResult.enhancedLocation.speed ?: 0.0
            val kmh = (mps * 3.6).toInt().coerceAtLeast(0)

            // Update interpolation variables
            currentSpeedMps = mps
            currentBearing = locationMatcherResult.enhancedLocation.bearing ?: currentBearing

            // Store previous for interpolation
            previousPuckLocation = lastPuckLocation
            lastPuckLocation = Point.fromLngLat(
                locationMatcherResult.enhancedLocation.longitude,
                locationMatcherResult.enhancedLocation.latitude
            )

            // If this is the first point, initialize previous to current
            if (previousPuckLocation == null) {
                previousPuckLocation = lastPuckLocation
            }

            lastUpdateTimestamp = System.currentTimeMillis()

            // Immediate update on new GPS result
            updateRoutePoints()

            _uiState.value = _uiState.value.copy(
                speedKmh = kmh
            )
        }
    }

    private fun updateRoutePoints() {
        val puck = lastPuckLocation ?: return
        val progress = lastRouteProgress ?: return
        _remainingRoutePoints.value = getRemainingPoints(puck, progress)
    }

    private fun startInterpolation() {
        interpolationJob?.cancel()
        interpolationJob = viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(16) // ~60fps

                if (currentSpeedMps > 0.5 && previousPuckLocation != null && lastPuckLocation != null) {
                    val now = System.currentTimeMillis()
                    // Mapbox LocationPuck usually animates over the interval between updates (1000ms for 1Hz)
                    // We interpolate from previous to current to match that visual smoothing.
                    val progress = (now - lastUpdateTimestamp).toDouble() / 1000.0

                    if (progress in 0.0..1.0) {
                        val totalDist = TurfMeasurement.distance(previousPuckLocation!!, lastPuckLocation!!, "meters")
                        val bearing = TurfMeasurement.bearing(previousPuckLocation!!, lastPuckLocation!!)
                        val progressDist = totalDist * progress

                        val interpolatedPoint = TurfMeasurement.destination(
                            previousPuckLocation!!,
                            progressDist,
                            bearing,
                            "meters"
                        )
                        _remainingRoutePoints.value = getRemainingPoints(interpolatedPoint, lastRouteProgress!!)
                    } else if (progress > 1.0 && progress <= 2.0) {
                        // If update is late, continue slightly in current direction
                        val overshotDist = currentSpeedMps * (progress - 1.0)
                        val interpolatedPoint = TurfMeasurement.destination(
                            lastPuckLocation!!,
                            overshotDist,
                            currentBearing,
                            "meters"
                        )
                        _remainingRoutePoints.value = getRemainingPoints(interpolatedPoint, lastRouteProgress!!)
                    }
                }
            }
        }
    }

    private val routeProgressObserver = object : RouteProgressObserver {
        override fun onRouteProgressChanged(routeProgress: RouteProgress) {
            // Detect reroute: if routeId changed, refresh our cached geometry
            if (routeProgress.navigationRoute.id != lastRouteId) {
                cacheRouteGeometry(routeProgress.navigationRoute)
            }

            lastRouteProgress = routeProgress
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

            // ── Roundabout exit number ────────────────────────────────────
            // The banner can already be announcing the roundabout while the
            // active step is still the approach step (whose maneuver carries
            // no exit), and conversely the banner can switch to the next
            // post-roundabout instruction while we're still inside the
            // circle. Reading exit() off currentStepProgress.step is therefore
            // unreliable and produces the wrong number on screen.
            //
            // Instead, when the banner-derived maneuver type is ROUNDABOUT,
            // locate the nearest upcoming step in the leg whose own maneuver
            // type is a roundabout/rotary and read exit() from THAT step.
            // That value is set at route-build time and reflects the actual
            // exit the driver should take.
            val legProgress = routeProgress.currentLegProgress
            val exit: Int? = if (maneuverType == ManeuverType.ROUNDABOUT) {
                val steps = legProgress?.routeLeg?.steps().orEmpty()
                val currentStepIdx = legProgress?.currentStepProgress?.stepIndex ?: 0
                steps.asSequence()
                    .drop(currentStepIdx)
                    .firstOrNull { step ->
                        val t = step.maneuver()?.type()?.lowercase().orEmpty()
                        t.contains("roundabout") || t.contains("rotary")
                    }
                    ?.maneuver()
                    ?.exit()
                    ?.takeIf { it > 0 }
            } else null

            // ── Compute remaining route points for the UI ──────────────
            val distRemaining = routeProgress.distanceRemaining.toDouble()
            updateRoutePoints()

            _uiState.value = _uiState.value.copy(
                distanceRemaining = "%.1f km".format(distRemaining / 1000.0),
                durationRemaining = "${(routeProgress.durationRemaining / 60.0).toInt()} min",
                eta = etaFormatted,
                nextManeuver = maneuverText,
                nextManeuverDistance = stepDistStr,
                nextManeuverDistanceMeters = stepDistanceM,
                maneuverType = maneuverType,
                roundaboutExit = exit,
                routes = listOf(routeProgress.navigationRoute)
            )
        }
    }

    private fun getRemainingPoints(currentPoint: Point, routeProgress: RouteProgress): List<Point> {
        val legProgress = routeProgress.currentLegProgress ?: return emptyList()
        val allSteps = legProgress.routeLeg?.steps() ?: return emptyList()
        val currentStepProgress = legProgress.currentStepProgress ?: return emptyList()
        val currentStep = currentStepProgress.step ?: return emptyList()

        val currentStepIdx = allSteps.indexOf(currentStep).coerceAtLeast(0)

        if (decodedSteps.isEmpty() || currentStepIdx >= decodedSteps.size) {
            return _remainingRoutePoints.value
        }

        val points = mutableListOf<Point>()

        // 1. Prepend current location (puck) for a gapless connection
        points.add(currentPoint)

        // 2. Add remaining part of current step
        val stepPoints = decodedSteps[currentStepIdx]

        // Find the segment (i, i+1) closest to current location
        var bestSegmentIndex = 0
        var minDistanceToSegment = Double.MAX_VALUE

        for (i in 0 until stepPoints.size - 1) {
            val p1 = stepPoints[i]
            val p2 = stepPoints[i + 1]

            // Calculate distance from point to segment using a simple distance-to-segment approximation
            // Since we're in a local area, we can use a simplified approach or just the average distance to endpoints
            // if Turf doesn't have a direct segment distance tool.
            // However, a better proxy is the distance to the midpoint or the minimum distance to either vertex.
            val d1 = TurfMeasurement.distance(currentPoint, p1, "meters")
            val d2 = TurfMeasurement.distance(currentPoint, p2, "meters")
            val d = (d1 + d2) / 2.0 // Simple heuristic: average distance to vertices

            if (d < minDistanceToSegment) {
                minDistanceToSegment = d
                bestSegmentIndex = i
            }
        }

        // We include all points from the NEXT vertex of the current segment onwards.
        // This ensures the line starts at the puck and continues forward along the route.
        if (bestSegmentIndex + 1 < stepPoints.size) {
            points.addAll(stepPoints.subList(bestSegmentIndex + 1, stepPoints.size))
        }

        // 3. Add all subsequent steps
        for (i in (currentStepIdx + 1) until decodedSteps.size) {
            points.addAll(decodedSteps[i])
        }

        return points
    }

    // The default Mapbox reroute controller is fast and reliable for
    // *detecting* off-route and firing a reroute, but its built-in
    // RouteOptions updater pins the request to the driver's current heading
    // with a narrow tolerance — which after a wrong turn corresponds to the
    // *wrong* direction. The router then dutifully produces "continuations"
    // of that wrong direction: U-turns, opposing-direction one-way streets,
    // etc.
    //
    // The fix is a RerouteOptionsAdapter that strips the bearingsList from
    // the route options on every reroute, so the router is free to find the
    // best legal path from the user's current GPS fix to the destination.
    private val rerouteOptionsAdapter =
        object : com.mapbox.navigation.core.reroute.RerouteOptionsAdapter {
            override fun onRouteOptions(routeOptions: RouteOptions): RouteOptions =
                routeOptions.toBuilder().bearingsList(null).build()
        }

    init {
        startSessionIfPermitted()
        startInterpolation()
    }

    fun startSessionIfPermitted() {
        if (!hasLocationPermissions()) {
            _uiState.value = _uiState.value.copy(nextManeuver = "Location permission needed")
            return
        }
        try {
            mapboxNavigation?.registerLocationObserver(locationObserver)
            mapboxNavigation?.registerRouteProgressObserver(routeProgressObserver)
            // Keep the default reroute controller — it's the only thing that
            // detects off-route quickly and triggers a fresh route. We just
            // intercept the RouteOptions it builds so bearings (i.e. the
            // wrong-direction constraint) get stripped before the request
            // goes out.
            mapboxNavigation?.setRerouteOptionsAdapter(rerouteOptionsAdapter)
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

        val dest = Point.fromLngLat(destLng, destLat)

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
                    requestRoute(nav, name, pt, dest)
                }
            }
            nav.registerLocationObserver(retryObserver)
            return
        }

        requestRoute(nav, name, origin, dest)
    }

    private fun requestRoute(
        nav: MapboxNavigation,
        name: String,
        origin: Point,
        destination: Point
    ) {
        // No bearingsList. Pinning the request to the vehicle's heading is
        // actively harmful on reroute: after a wrong turn the live heading
        // points the wrong way, and the router will dutifully produce routes
        // that "continue in that direction" — i.e. illegal U-turns or
        // driving the wrong way down one-way streets just to satisfy the
        // bearing constraint. Without bearings the router picks the best
        // legal route from the origin point. The RerouteOptionsAdapter
        // installed in startSessionIfPermitted enforces the same policy for
        // automatic reroutes triggered by the default reroute controller.
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
                    val primaryRoute = routes.first()
                    nav.setNavigationRoutes(routes)

                    // Cache decoded geometries for performance
                    cacheRouteGeometry(primaryRoute)

                    val leg = primaryRoute.directionsRoute.legs()?.firstOrNull()
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
                    val firstExit = if (firstType == ManeuverType.ROUNDABOUT) {
                        leg?.steps()?.firstOrNull { step ->
                            val t = step.maneuver()?.type()?.lowercase().orEmpty()
                            t.contains("roundabout") || t.contains("rotary")
                        }?.maneuver()?.exit()?.takeIf { it > 0 }
                    } else null

                    val initialPoints = leg?.steps()?.flatMap { step ->
                        step.geometry()?.let { com.mapbox.geojson.utils.PolylineUtils.decode(it, 6) } ?: emptyList()
                    } ?: emptyList()

                    _remainingRoutePoints.value = initialPoints

                    _uiState.value = _uiState.value.copy(
                        routeReady = true,
                        isNavigating = true,
                        routes = routes,
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