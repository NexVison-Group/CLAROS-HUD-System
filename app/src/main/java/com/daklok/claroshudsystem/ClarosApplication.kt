package com.daklok.claroshudsystem

import android.app.Application
import com.mapbox.common.MapboxOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp

class ClarosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val token = getString(R.string.mapbox_access_token)
        if (token.isNotEmpty()) {
            MapboxOptions.accessToken = token
        }
        
        // Initialize Navigation SDK
        if (!MapboxNavigationApp.isSetup()) {
            MapboxNavigationApp.setup(
                NavigationOptions.Builder(this).build()
            )
        }
    }
}
