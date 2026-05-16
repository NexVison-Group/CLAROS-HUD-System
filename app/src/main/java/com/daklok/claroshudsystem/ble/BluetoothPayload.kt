package com.daklok.claroshudsystem.ble

import com.daklok.claroshudsystem.ui.navigation.ManeuverType

/**
 * Format the HUD payload that gets sent to the ESP32 over Bluetooth.
 *
 * Format (single line, comma-separated values — NO keys):
 *     <DIR>, <DIST>, <ETA>, <SPD>
 *
 * Examples:
 *     RIGHT, 11m, 20:00, 15
 *     FORWARD, 320m, 14:32, 47
 *     ROUNDABOUT_2, 80m, 09:15, 35  (roundabout — DIR carries the exit number)
 *
 * The ESP32 firmware can split on ',' and trim whitespace to obtain the four
 * positional fields in order: direction, distance, ETA, speed.
 *
 * Direction values:
 *     FORWARD            — drive straight / continue / depart
 *     LEFT / RIGHT       — standard 90-degree turns
 *     SHARP_LEFT / SHARP_RIGHT
 *     SLIGHT_LEFT / SLIGHT_RIGHT
 *     UTURN              — make a U-turn
 *     ARRIVE             — arrival at destination
 *     ROUNDABOUT_<int>   — roundabout: the exit number (e.g. "ROUNDABOUT_2")
 */
object BluetoothPayload {

    /**
     * Build the payload string from the live nav state.
     *
     * @param maneuver                  next maneuver enum
     * @param distanceToManeuverMeters  remaining distance for the maneuver, in meters
     * @param etaHHmm                   arrival time, HH:mm; "--:--" while unknown
     * @param speedKmh                  current speed in km/h
     * @param roundaboutExit            exit number when the maneuver is a roundabout;
     *                                  ignored otherwise. null means "unknown".
     */
    fun build(
        maneuver: ManeuverType,
        distanceToManeuverMeters: Float,
        etaHHmm: String,
        speedKmh: Int,
        roundaboutExit: Int? = null
    ): String {
        val dir = directionCode(maneuver, roundaboutExit)
        val dist = formatDistance(distanceToManeuverMeters)
        val eta = etaHHmm.ifBlank { "--:--" }
        return "$dir, $dist, $eta, $speedKmh"
    }

    /**
     * Returns the DIR field for the given maneuver. For roundabouts the field
     * carries the numeric exit. If the exit is unknown we fall back to "1" —
     * Mapbox always reports at least one exit when a roundabout step is active.
     */
    private fun directionCode(m: ManeuverType, roundaboutExit: Int?): String = when (m) {
        ManeuverType.STRAIGHT,
        ManeuverType.DEPART          -> "FORWARD"
        ManeuverType.TURN_LEFT       -> "LEFT"
        ManeuverType.TURN_RIGHT      -> "RIGHT"
        ManeuverType.TURN_SHARP_LEFT -> "SHARP_LEFT"
        ManeuverType.TURN_SHARP_RIGHT-> "SHARP_RIGHT"
        ManeuverType.TURN_SLIGHT_LEFT-> "SLIGHT_LEFT"
        ManeuverType.TURN_SLIGHT_RIGHT->"SLIGHT_RIGHT"
        ManeuverType.U_TURN          -> "UTURN"
        ManeuverType.ARRIVE          -> "ARRIVE"
        ManeuverType.ROUNDABOUT      -> "ROUNDABOUT_${roundaboutExit?.takeIf { it > 0 } ?: 1}"
        ManeuverType.UNKNOWN         -> "FORWARD"
    }

    private fun formatDistance(meters: Float): String {
        if (meters.isNaN() || meters < 0f) return "0m"
        return if (meters >= 1000f) {
            "%.1fkm".format(meters / 1000f)
        } else {
            "${meters.toInt()}m"
        }
    }
}
