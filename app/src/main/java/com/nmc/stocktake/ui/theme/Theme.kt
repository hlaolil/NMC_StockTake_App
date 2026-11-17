package com.nmc.stocktake.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The web app's blue, so the two feel like one system.
private val NmcBlue = Color(0xFF0056B3)

private val Colors = lightColorScheme(
    primary = NmcBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF15803D),      // green: "matches the system"
    onSecondary = Color.White,
    tertiary = Color(0xFFB45309),       // amber: needs a look
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    error = Color(0xFFB91C1C),
)

@Composable
fun StockTakeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
