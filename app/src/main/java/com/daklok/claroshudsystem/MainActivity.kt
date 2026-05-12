package com.daklok.claroshudsystem

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.daklok.claroshudsystem.navigation.ClarosNavGraph
import com.daklok.claroshudsystem.ui.theme.CLAROSHUDSystemTheme
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp  // FIX: added import

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // FIX: attach the lifecycle owner so MapboxNavigationApp.current() returns
        // a non-null instance. Without this, requestRoutes() is silently skipped
        // and the UI stays stuck on "Calculating Route..." forever.
        MapboxNavigationApp.attach(this)

        setContent {
            CLAROSHUDSystemTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ClarosNavGraph()
                }
            }
        }
    }
}