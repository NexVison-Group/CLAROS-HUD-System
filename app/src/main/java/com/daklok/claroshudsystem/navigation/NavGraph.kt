package com.daklok.claroshudsystem.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daklok.claroshudsystem.ble.EspConnectionViewModel
import com.daklok.claroshudsystem.ui.home.HomeScreen
import com.daklok.claroshudsystem.ui.navigation.NavigationScreen
import com.daklok.claroshudsystem.ui.settings.SettingsScreen
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
sealed interface NavRoute : NavKey {
    @Serializable
    data object Home : NavRoute

    @Serializable
    data object Settings : NavRoute

    @Serializable
    data class Navigation(
        val destinationName: String,
        val lat: Double,
        val lng: Double,
        val startLat: Double? = null,
        val startLng: Double? = null
    ) : NavRoute
}

@Composable
fun ClarosNavGraph() {
    val backStack = rememberNavBackStack(NavRoute.Home)

    // Single EspConnectionViewModel instance scoped to the whole nav graph.
    // Both HomeScreen and NavigationScreen share the same BLE connection state.
    val espViewModel: EspConnectionViewModel = viewModel()

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.isNotEmpty()) backStack.removeAt(backStack.size - 1) },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = { key ->
            when (key) {
                is NavRoute.Home -> NavEntry(key) {
                    HomeScreen(
                        espViewModel = espViewModel,
                        onStartNavigation = { name, lat, lng, startLat, startLng ->
                            backStack.add(NavRoute.Navigation(name, lat, lng, startLat, startLng))
                        },
                        onOpenSettings = { backStack.add(NavRoute.Settings) }
                    )
                }
                is NavRoute.Settings -> NavEntry(key) {
                    SettingsScreen(
                        onBack = { backStack.removeAt(backStack.size - 1) }
                    )
                }
                is NavRoute.Navigation -> NavEntry(key) {
                    NavigationScreen(
                        destinationName = key.destinationName,
                        destLat = key.lat,
                        destLng = key.lng,
                        startLat = key.startLat,
                        startLng = key.startLng,
                        espViewModel = espViewModel,
                        onBack = { backStack.removeAt(backStack.size - 1) }
                    )
                }
                else -> NavEntry(key) { }
            }
        }
    )
}
