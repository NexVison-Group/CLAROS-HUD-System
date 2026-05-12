package com.daklok.claroshudsystem.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daklok.claroshudsystem.prefs.ThemeMode
import com.daklok.claroshudsystem.prefs.ThemePreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val currentMode by ThemePreferences.mode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "SETTINGS",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontSize = 14.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionHeader("APPEARANCE", icon = Icons.Rounded.Brightness6)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ThemeOptionRow(
                        label = "Follow system",
                        sub = "Match the device's dark/light setting",
                        icon = Icons.Rounded.PhoneAndroid,
                        selected = currentMode == ThemeMode.SYSTEM,
                        onClick = { ThemePreferences.setMode(context, ThemeMode.SYSTEM) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    )
                    ThemeOptionRow(
                        label = "Light",
                        sub = "Always use the light theme",
                        icon = Icons.Rounded.LightMode,
                        selected = currentMode == ThemeMode.LIGHT,
                        onClick = { ThemePreferences.setMode(context, ThemeMode.LIGHT) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    )
                    ThemeOptionRow(
                        label = "Dark",
                        sub = "Always use the dark theme",
                        icon = Icons.Rounded.DarkMode,
                        selected = currentMode == ThemeMode.DARK,
                        onClick = { ThemePreferences.setMode(context, ThemeMode.DARK) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Theme changes apply immediately across the app.",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ThemeOptionRow(
    label: String,
    sub: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = if (selected)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                sub,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
        )
    }
}
