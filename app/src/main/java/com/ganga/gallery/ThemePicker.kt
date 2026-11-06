package com.ganga.gallery

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** Settings → Theme: live preview, background cards, named accent swatches and a custom colour picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSection(vm: GalleryViewModel) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::setBackgroundPhoto) }
    fun pickPhoto() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    var customOpen by remember { mutableStateOf(false) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ThemePreview(vm)

        // --- background ---
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Background")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ThemeMode.entries.forEach { t ->
                    BackgroundCard(vm, t, vm.theme == t) {
                        if (t == ThemeMode.PHOTO && !vm.backgroundFile.exists()) pickPhoto() else vm.changeTheme(t)
                    }
                }
            }
            if (vm.theme == ThemeMode.PHOTO) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ pickPhoto() }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Change photo")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dim", Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
                        Slider(vm.backgroundDim, vm::changeBackgroundDim, Modifier.weight(1f), valueRange = 0.1f..0.85f)
                        Text("${(vm.backgroundDim * 100).toInt()}%", Modifier.width(44.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Blur", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(vm.backgroundBlur, vm::changeBackgroundBlur)
                    }
                }
            }
        }

        // --- accent colour ---
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Accent colour")
            // one row; swipe sideways for more colours
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (Build.VERSION.SDK_INT >= 31) NamedSwatch(
                    "Wallpaper",
                    Brush.sweepGradient(listOf(Color(0xFFE8506A), Color(0xFFF29900), Color(0xFF43A047), Color(0xFF1A73E8), Color(0xFFE8506A))),
                    vm.wallpaperColors, Icons.Outlined.Wallpaper,
                ) { vm.changeWallpaperColors(true) }
                ACCENTS.forEach { (name, c) ->
                    NamedSwatch(name, Brush.linearGradient(listOf(Color(c), Color(c))), !vm.wallpaperColors && vm.accent == c) { vm.changeAccent(c) }
                }
                val custom = !vm.wallpaperColors && ACCENTS.none { it.second == vm.accent }
                NamedSwatch(
                    "Custom",
                    if (custom) Brush.linearGradient(listOf(Color(vm.accent), Color(vm.accent)))
                    else Brush.sweepGradient(HUES),
                    custom, Icons.Outlined.Tune,
                ) { customOpen = true }
            }
        }
    }

    if (customOpen) CustomColorDialog(Color(vm.accent), onDismiss = { customOpen = false }) { vm.changeAccent(it.toArgb()); customOpen = false }
}

private val HUES = (0..6).map { Color.hsv(it * 60f % 360f, 0.8f, 0.95f) }

@Composable
private fun Label(text: String) = Text(
    text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
)

/** A tiny mock of the app in the current theme, so changes are visible before leaving Settings. */
@Composable
private fun ThemePreview(vm: GalleryViewModel) {
    val ctx = LocalContext.current
    val sample = remember(vm.all) { vm.all.filter { !it.isVideo }.sortedByDescending { it.date }.take(6) } // your newest photos
    val scheme = MaterialTheme.colorScheme
    val dark = isDarkTheme(vm)
    val canvas = when (vm.theme) {
        ThemeMode.BLACK -> Color.Black
        ThemeMode.LIGHT -> Color.White
        ThemeMode.SYSTEM -> if (dark) Color(0xFF111214) else Color.White
        else -> Color(0xFF111214)
    }
    Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp)).background(canvas)) {
        if (vm.theme == ThemeMode.PHOTO && vm.backgroundFile.exists()) {
            AsyncImage(
                ImageRequest.Builder(ctx).data(vm.backgroundFile).memoryCacheKey("bgp:${vm.backgroundVersion}").build(), null,
                Modifier.fillMaxSize().then(if (vm.backgroundBlur) Modifier.blur(16.dp) else Modifier), contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = vm.backgroundDim)))
        }
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text("Gallery", color = scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(6) { i ->
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(scheme.surfaceVariant)) {
                        sample.getOrNull(i)?.let { AsyncImage(it.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(scheme.primary).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("Button", color = scheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.clip(RoundedCornerShape(50)).background(scheme.primaryContainer).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("Selected", color = scheme.onPrimaryContainer, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.FavoriteBorder, null, tint = scheme.primary)
            }
            Spacer(Modifier.weight(1f))
            // mini tab bar
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainer).padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                listOf(Icons.Outlined.PhotoLibrary, Icons.Outlined.Image, Icons.Outlined.VideoLibrary, Icons.Outlined.PhotoAlbum).forEachIndexed { i, ic ->
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(if (i == 0) scheme.secondaryContainer else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                    ) { Icon(ic, null, Modifier.size(18.dp), tint = if (i == 0) scheme.onSecondaryContainer else scheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun BackgroundCard(vm: GalleryViewModel, mode: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val ring by animateDpAsState(if (selected) 3.dp else 1.dp, tween(150), label = "ring")
    val ringColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "ringColor")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(2.dp)) {
        Box(Modifier.size(width = 54.dp, height = 78.dp).clip(RoundedCornerShape(12.dp)).border(ring, ringColor, RoundedCornerShape(12.dp))) {
            when (mode) {
                ThemeMode.SYSTEM -> Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.White)
                    val p = androidx.compose.ui.graphics.Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                    drawPath(p, Color(0xFF111214))
                }
                ThemeMode.LIGHT -> Box(Modifier.fillMaxSize().background(Color.White))
                ThemeMode.DARK -> Box(Modifier.fillMaxSize().background(Color(0xFF111214)))
                ThemeMode.BLACK -> Box(Modifier.fillMaxSize().background(Color.Black))
                ThemeMode.PHOTO -> if (vm.backgroundFile.exists()) AsyncImage(
                    ImageRequest.Builder(ctx).data(vm.backgroundFile).memoryCacheKey("bgc:${vm.backgroundVersion}").build(), null,
                    Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                ) else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected) Icon(
                Icons.Outlined.CheckCircle, null,
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            mode.label, style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun NamedSwatch(name: String, fill: Brush, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, tween(150), label = "ring")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(56.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = name, onClick = onClick).padding(vertical = 4.dp),
    ) {
        Box(
            Modifier.size(44.dp).border(ring, MaterialTheme.colorScheme.onSurface, CircleShape).padding(if (selected) 5.dp else 0.dp)
                .clip(CircleShape).background(fill),
            Alignment.Center,
        ) {
            when {
                selected -> Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = Color.White)
                icon != null -> Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Hue strip + tone strip + hex field, with a live swatch. */
@Composable
private fun CustomColorDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    // one "tone" control: left = soft/dark, right = vivid/bright, mapped onto saturation+value
    var tone by remember { mutableFloatStateOf(((hsv[1] + hsv[2]) / 2f).coerceIn(0f, 1f)) }
    val color = Color.hsv(hue, (0.35f + tone * 0.6f).coerceAtMost(1f), (0.55f + tone * 0.42f).coerceAtMost(1f))
    var hex by remember(color) { mutableStateOf("%06X".format(color.toArgb() and 0xFFFFFF)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        hex, { v ->
                            hex = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                            if (hex.length == 6) {
                                val c = android.graphics.Color.parseColor("#$hex")
                                val h = FloatArray(3); android.graphics.Color.colorToHSV(c, h)
                                hue = h[0]; tone = ((h[1] + h[2]) / 2f).coerceIn(0f, 1f)
                            }
                        },
                        prefix = { Text("#") }, singleLine = true, label = { Text("Hex") }, modifier = Modifier.weight(1f),
                    )
                }
                Text("Hue", style = MaterialTheme.typography.labelLarge)
                GradientStrip(Brush.horizontalGradient((0..12).map { Color.hsv(it * 30f % 360f, 0.85f, 0.95f) }), hue / 360f) { hue = it * 359.9f }
                Text("Tone", style = MaterialTheme.typography.labelLarge)
                GradientStrip(
                    Brush.horizontalGradient(listOf(Color.hsv(hue, 0.35f, 0.55f), Color.hsv(hue, 0.95f, 0.97f))), tone,
                ) { tone = it }
            }
        },
        confirmButton = { Button({ onPick(color) }) { Text("Use colour") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** A rounded gradient bar with a draggable thumb; reports 0..1. */
@Composable
private fun GradientStrip(brush: Brush, value: Float, onChange: (Float) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    val thumbColor = MaterialTheme.colorScheme.onSurface
    Canvas(
        Modifier.fillMaxWidth().height(32.dp)
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume(); onChange((change.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        width = size.width
        val r = size.height / 2
        drawRoundRect(brush, cornerRadius = CornerRadius(r, r))
        val x = (value.coerceIn(0f, 1f) * size.width).coerceIn(r, size.width - r)
        drawCircle(Color.White, r - 3.dp.toPx(), Offset(x, r))
        drawCircle(thumbColor.copy(alpha = .4f), r - 3.dp.toPx(), Offset(x, r), style = Stroke(2.dp.toPx()))
    }
}
