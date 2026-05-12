package com.daklok.claroshudsystem.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand accents (kept compatible with the previous palette names) ──────────
val NeonCyan      = Color(0xFF00E5FF)
val DeepNeonCyan  = Color(0xFF00B8D4)
val ElectricBlue  = Color(0xFF2979FF)
val VibrantOrange = Color(0xFFFF3D00)
val LimeVibrant   = Color(0xFF76FF03)

// ── Dark palette (matches the dark UI on the NexVision CLAROS site) ─────────
// Background is a deep near-black; surface is a slightly elevated black-grey.
// Primary is cool cyan (the brand accent). Tertiary is a soft mint for "online"
// / positive states. Secondary stays vibrant orange for highlights and warnings.
val PrimaryDark               = Color(0xFF00E5FF)
val OnPrimaryDark             = Color(0xFF00131A)
val PrimaryContainerDark      = Color(0xFF003A47)
val OnPrimaryContainerDark    = Color(0xFFA7EDFF)

val SecondaryDark             = Color(0xFFFF6A3D)
val OnSecondaryDark           = Color(0xFF2A0500)
val SecondaryContainerDark    = Color(0xFF5A1200)
val OnSecondaryContainerDark  = Color(0xFFFFDBD0)

val TertiaryDark              = Color(0xFF7CE08C)
val OnTertiaryDark            = Color(0xFF003912)
val TertiaryContainerDark     = Color(0xFF005321)
val OnTertiaryContainerDark   = Color(0xFFA8F4B5)

// Deep near-black background like the site
val BackgroundDark            = Color(0xFF07090C)
val OnBackgroundDark          = Color(0xFFE9ECEF)
// Surface (cards / panels) — a touch lighter than the background for layering
val SurfaceDark               = Color(0xFF101418)
val OnSurfaceDark             = Color(0xFFE9ECEF)

// ── Light palette (kept readable, used when device is in light mode) ────────
val PrimaryLight              = Color(0xFF006875)
val OnPrimaryLight            = Color(0xFFFFFFFF)
val PrimaryContainerLight     = Color(0xFFA7EDFF)
val OnPrimaryContainerLight   = Color(0xFF001F24)

val SecondaryLight            = Color(0xFFA93600)
val OnSecondaryLight          = Color(0xFFFFFFFF)
val SecondaryContainerLight   = Color(0xFFFFDBD2)
val OnSecondaryContainerLight = Color(0xFF380C00)

val TertiaryLight             = Color(0xFF2C6B00)
val OnTertiaryLight           = Color(0xFFFFFFFF)
val TertiaryContainerLight    = Color(0xFF91FF4D)
val OnTertiaryContainerLight  = Color(0xFF082100)

val BackgroundLight           = Color(0xFFF8FDFF)
val OnBackgroundLight         = Color(0xFF191C1D)
val SurfaceLight              = Color(0xFFF8FDFF)
val OnSurfaceLight            = Color(0xFF191C1D)