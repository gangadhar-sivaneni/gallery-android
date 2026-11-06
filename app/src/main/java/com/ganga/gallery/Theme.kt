package com.ganga.gallery

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** Preset accent colours shown as named swatches in Settings. */
val ACCENTS: List<Pair<String, Int>> = listOf(
    "Blue" to 0xFF1A73E8, "Indigo" to 0xFF3F51B5, "Violet" to 0xFF7B61FF, "Purple" to 0xFF8E24AA,
    "Rose" to 0xFFE8506A, "Red" to 0xFFD93025, "Coral" to 0xFFFF7043, "Amber" to 0xFFF29900,
    "Green" to 0xFF43A047, "Teal" to 0xFF00A884, "Cyan" to 0xFF0097A7, "Slate" to 0xFF546E7A,
).map { (n, c) -> n to c.toInt() }

private val LightBase = lightColorScheme(
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF0F2F5), onSurfaceVariant = Color(0xFF5F6368),
    surfaceContainer = Color(0xFFF3F5F8), surfaceContainerHigh = Color(0xFFEBEEF2),
    outlineVariant = Color(0xFFDADCE0),
)

private val DarkBase = darkColorScheme(
    background = Color(0xFF111214), onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF111214), onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF26282C), onSurfaceVariant = Color(0xFFB4B8BF),
    surfaceContainer = Color(0xFF1B1C1F), surfaceContainerHigh = Color(0xFF232529),
    outlineVariant = Color(0xFF3A3D42),
)

/** Is the current theme dark? Photo backgrounds use light text on a dimmed image, so they count as dark. */
@Composable
fun isDarkTheme(vm: GalleryViewModel): Boolean = when (vm.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.BLACK, ThemeMode.PHOTO -> true
}

/** Builds the app colour scheme from the chosen background + accent. */
@Composable
fun appColorScheme(vm: GalleryViewModel): ColorScheme {
    val ctx = LocalContext.current
    val dark = isDarkTheme(vm)
    val accent = if (vm.wallpaperColors && Build.VERSION.SDK_INT >= 31) {
        (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)).primary
    } else Color(vm.accent)
    return remember(dark, accent, vm.theme) { buildScheme(dark, accent, vm.theme) }
}

private fun buildScheme(dark: Boolean, accent: Color, mode: ThemeMode): ColorScheme {
    val base = if (dark) DarkBase else LightBase
    val black = mode == ThemeMode.BLACK
    val photo = mode == ThemeMode.PHOTO
    // keep the accent readable on its background
    val primary = if (dark) lerp(accent, Color.White, 0.22f) else accent
    val onPrimary = if (primary.luminance() > 0.5f) Color(0xFF111111) else Color.White
    val canvas = if (dark) Color(0xFF111214) else Color.White
    val container = lerp(accent, canvas, if (dark) 0.62f else 0.82f)
    val onContainer = if (dark) lerp(accent, Color.White, 0.78f) else lerp(accent, Color.Black, 0.55f)
    return base.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = container, onPrimaryContainer = onContainer,
        secondaryContainer = container, onSecondaryContainer = onContainer,
        background = when { photo -> Color.Transparent; black -> Color.Black; else -> base.background },
        surface = if (black) Color.Black else base.surface,
        // photo mode: bars and cards are slightly see-through so the background shows
        // solid even over a photo: see-through menus and bars were hard to read
        surfaceContainer = if (black) Color(0xFF0B0B0C) else base.surfaceContainer,
        surfaceContainerHigh = if (black) Color(0xFF17181A) else base.surfaceContainerHigh,
        surfaceVariant = if (black) Color(0xFF1C1D20) else base.surfaceVariant,
    )
}

/** Full-screen photo behind the app when the "Photo" background is chosen. Drawn once; no per-frame cost while scrolling. */
@Composable
fun AppBackdrop(vm: GalleryViewModel) {
    if (vm.theme != ThemeMode.PHOTO) return
    val ctx = LocalContext.current
    val file = vm.backgroundFile
    if (!file.exists()) return
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            ImageRequest.Builder(ctx).data(file).memoryCacheKey("bg:${vm.backgroundVersion}").build(),
            null,
            Modifier.fillMaxSize().then(if (vm.backgroundBlur) Modifier.blur(28.dp) else Modifier),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = vm.backgroundDim)))
    }
}
