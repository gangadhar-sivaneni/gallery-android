package com.ganga.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/** Everything the editor changes. Crop is stored as fractions of the rotated/flipped image, so it applies at any resolution. */
data class EditOps(
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val crop: android.graphics.RectF = android.graphics.RectF(0f, 0f, 1f, 1f),
    val color: FloatArray = android.graphics.ColorMatrix().array,
)

enum class Filter(val label: String) { NONE("Original"), VIVID("Vivid"), WARM("Warm"), COOL("Cool"), FADE("Fade"), MONO("Mono"), NOIR("Noir"), SEPIA("Sepia") }

/** Builds one 4x5 colour matrix from a filter plus the four adjustment sliders (each -1..1). */
fun colorMatrix(filter: Filter, brightness: Float, contrast: Float, saturation: Float, warmth: Float): FloatArray {
    fun scale(c: Float, offset: Float) = android.graphics.ColorMatrix(
        floatArrayOf(c, 0f, 0f, 0f, offset, 0f, c, 0f, 0f, offset, 0f, 0f, c, 0f, offset, 0f, 0f, 0f, 1f, 0f),
    )
    fun tint(r: Float, g: Float, b: Float) = android.graphics.ColorMatrix().apply { setScale(r, g, b, 1f) }
    fun sat(s: Float) = android.graphics.ColorMatrix().apply { setSaturation(s) }
    val m = android.graphics.ColorMatrix()
    when (filter) {
        Filter.NONE -> {}
        Filter.VIVID -> { m.postConcat(sat(1.45f)); m.postConcat(scale(1.08f, -10f)) }
        Filter.WARM -> m.postConcat(tint(1.12f, 1.02f, 0.85f))
        Filter.COOL -> m.postConcat(tint(0.88f, 1.0f, 1.15f))
        Filter.FADE -> { m.postConcat(sat(0.75f)); m.postConcat(scale(0.82f, 30f)) }
        Filter.MONO -> m.postConcat(sat(0f))
        Filter.NOIR -> { m.postConcat(sat(0f)); m.postConcat(scale(1.35f, -45f)) }
        Filter.SEPIA -> { m.postConcat(sat(0f)); m.postConcat(tint(1.07f, 0.74f, 0.43f)) }
    }
    m.postConcat(sat(1f + saturation))
    val c = 1f + contrast * 0.6f
    m.postConcat(scale(c, (1f - c) * 128f + brightness * 80f))
    m.postConcat(tint(1f + warmth * 0.15f, 1f, 1f - warmth * 0.15f))
    return m.array
}

private fun transform(src: Bitmap, turns: Int, flipH: Boolean, flipV: Boolean): Bitmap {
    if (turns % 4 == 0 && !flipH && !flipV) return src
    val mx = Matrix().apply {
        postRotate(90f * turns)
        postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
    }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, mx, true)
}

/** Applies [ops] to a full bitmap: rotate/flip, crop, then colour. */
fun renderEdit(src: Bitmap, ops: EditOps): Bitmap {
    val t = transform(src, ops.quarterTurns, ops.flipH, ops.flipV)
    val x = (ops.crop.left * t.width).toInt().coerceIn(0, t.width - 1)
    val y = (ops.crop.top * t.height).toInt().coerceIn(0, t.height - 1)
    val w = (ops.crop.width() * t.width).toInt().coerceIn(1, t.width - x)
    val h = (ops.crop.height() * t.height).toInt().coerceIn(1, t.height - y)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(
        t, android.graphics.Rect(x, y, x + w, y + h), android.graphics.Rect(0, 0, w, h),
        Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ops.color) },
    )
    if (t !== src) t.recycle()
    return out
}

private enum class Tool(val label: String) { CROP("Crop & rotate"), ADJUST("Adjust"), FILTERS("Filters") }
private enum class Adj(val label: String) { BRIGHTNESS("Brightness"), CONTRAST("Contrast"), SATURATION("Saturation"), WARMTH("Warmth") }
private val RATIOS = listOf<Pair<String, Float?>>("Free" to null, "Original" to -1f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(vm: GalleryViewModel, m: Media, ask: Ask, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<Bitmap?>(null) } // decoded once, preview size
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) } // tiny copy for filter previews
    LaunchedEffect(m) {
        base = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, m.uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 2048) { val s = 2048f / longest; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
                }
            }.getOrNull()
        }
        if (base == null) { vm.toast("Can't edit this file"); onBack() }
        base?.let { b -> thumb = Bitmap.createScaledBitmap(b, 160, (160f * b.height / b.width).toInt().coerceAtLeast(1), true).asImageBitmap() }
    }

    var turns by remember { mutableIntStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    val shown = remember(base, turns, flipH, flipV) { base?.let { transform(it, turns, flipH, flipV) } }
    // crop rect in pixels of [shown]
    var crop by remember(shown) { mutableStateOf(shown?.let { Rect(0f, 0f, it.width.toFloat(), it.height.toFloat()) } ?: Rect.Zero) }
    var ratio by remember { mutableStateOf<Float?>(null) }
    var tool by remember { mutableStateOf(Tool.CROP) }
    var adj by remember { mutableStateOf(Adj.BRIGHTNESS) }
    val values = remember { mutableStateMapOf<Adj, Float>().apply { Adj.entries.forEach { put(it, 0f) } } }
    var filter by remember { mutableStateOf(Filter.NONE) }
    var saving by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    val matrix = colorMatrix(filter, values[Adj.BRIGHTNESS]!!, values[Adj.CONTRAST]!!, values[Adj.SATURATION]!!, values[Adj.WARMTH]!!)
    val composeFilter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(matrix))
    val changed = turns % 4 != 0 || flipH || flipV || filter != Filter.NONE || values.values.any { it != 0f } ||
        (shown != null && crop != Rect(0f, 0f, shown.width.toFloat(), shown.height.toFloat()))

    fun ops(): EditOps {
        val s = shown!!
        return EditOps(
            turns, flipH, flipV,
            android.graphics.RectF(crop.left / s.width, crop.top / s.height, crop.right / s.width, crop.bottom / s.height),
            matrix,
        )
    }
    fun applyRatio(r: Float?) {
        ratio = r
        val s = shown ?: return
        val want = when (r) { null -> return; -1f -> s.width.toFloat() / s.height; else -> r }
        // largest centred rect with that ratio
        var w = s.width.toFloat(); var h = w / want
        if (h > s.height) { h = s.height.toFloat(); w = h * want }
        crop = Rect(Offset((s.width - w) / 2, (s.height - h) / 2), Size(w, h))
    }
    fun save(replace: Boolean) {
        val o = ops()
        val run = {
            saving = true
            scope.launch {
                val ok = vm.saveEdit(m, o, replace)
                saving = false
                if (ok) onBack()
            }
            Unit
        }
        if (replace) ask(vm.writeRequest(listOf(m))) { run() } else run()
    }

    BackHandler { if (changed) confirmExit = true else onBack() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton({ if (changed) confirmExit = true else onBack() }) { Icon(Icons.Default.Close, "Close editor") } },
                title = { Text("Edit") },
                actions = {
                    TextButton({ values.keys.forEach { values[it] = 0f }; filter = Filter.NONE; turns = 0; flipH = false; flipV = false; ratio = null
                        base?.let { crop = Rect(0f, 0f, it.width.toFloat(), it.height.toFloat()) } }, enabled = changed) {
                        Text("Reset")
                    }
                    Box {
                        Button({ saveMenu = true }, enabled = changed && !saving && shown != null, modifier = Modifier.padding(end = 8.dp)) {
                            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
                        }
                        AppMenu(saveMenu, { saveMenu = false }) {
                            MenuItem("Save as copy", Icons.Outlined.FileCopy) { saveMenu = false; save(replace = false) }
                            // only for JPEG/PNG within full-quality size; everything else saves a copy
                            if (vm.canReplace(m)) MenuItem("Replace original", Icons.Outlined.SaveAs) { saveMenu = false; save(replace = true) }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            Column(Modifier.background(Color(0xFF111214)).navigationBarsPadding().padding(bottom = 4.dp)) {
                when (tool) {
                    Tool.CROP -> {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(RATIOS) { (label, r) ->
                                FilterChip(ratio == r, { applyRatio(r) }, { Text(label) })
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BarAction(Icons.AutoMirrored.Outlined.RotateLeft, "Left", Color.White) { turns = (turns + 3) % 4 }
                            BarAction(Icons.AutoMirrored.Outlined.RotateRight, "Right", Color.White) { turns = (turns + 1) % 4 }
                            BarAction(Icons.Outlined.Flip, "Flip", Color.White) { flipH = !flipH }
                            BarAction(Icons.Outlined.SwapVert, "Flip vertical", Color.White) { flipV = !flipV }
                        }
                    }
                    Tool.ADJUST -> {
                        val v = values[adj]!!
                        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(adj.label, color = Color.White, modifier = Modifier.weight(1f))
                            Text("${(v * 100).toInt()}", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(v, { values[adj] = it }, Modifier.padding(horizontal = 16.dp), valueRange = -1f..1f)
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(Adj.entries) { a ->
                                FilterChip(adj == a, { adj = a }, { Text(a.label) }, leadingIcon = if (values[a] != 0f) ({ Text("•") }) else null)
                            }
                        }
                    }
                    Tool.FILTERS -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(Filter.entries) { f ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { filter = f }) {
                                val sel = f == filter
                                thumb?.let {
                                    Image(
                                        it, f.label,
                                        Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))
                                            .then(if (sel) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier),
                                        contentScale = ContentScale.Crop,
                                        colorFilter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(colorMatrix(f, 0f, 0f, 0f, 0f))),
                                    )
                                }
                                Text(f.label, color = if (sel) MaterialTheme.colorScheme.primary else Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(.1f), modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Tool.entries.forEach { t ->
                        TextButton({ tool = t }) {
                            Text(t.label, color = if (tool == t) MaterialTheme.colorScheme.primary else Color.White.copy(.7f), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().padding(20.dp), Alignment.Center) {
            val s = shown
            if (s == null) CircularProgressIndicator()
            else CropView(remember(s) { s.asImageBitmap() }, crop, ratio, interactive = tool == Tool.CROP, filter = composeFilter) { crop = it }
        }
    }

    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text("Discard changes?") },
        confirmButton = { TextButton({ confirmExit = false; onBack() }) { Text("Discard") } },
        dismissButton = { TextButton({ confirmExit = false }) { Text("Keep editing") } },
    )
}

/** Pick the exact area for the wallpaper; the crop box is locked to the phone screen's shape. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperCrop(vm: GalleryViewModel, m: Media, which: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // real screen size in portrait, so the wallpaper fits pixel for pixel
    val (screenW, screenH) = remember {
        val b = ctx.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        minOf(b.width(), b.height()) to maxOf(b.width(), b.height())
    }
    val ratio = screenW.toFloat() / screenH
    var img by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableStateOf(Rect.Zero) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(m) {
        img = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, m.uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 2048) { val s = 2048f / longest; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
                }
            }.getOrNull()
        }
        val b = img
        if (b == null) { vm.toast("Can't use this file as wallpaper"); onBack(); return@LaunchedEffect }
        // start with the largest centred screen-shaped area
        var w = b.width.toFloat(); var h = w / ratio
        if (h > b.height) { h = b.height.toFloat(); w = h * ratio }
        crop = Rect(Offset((b.width - w) / 2, (b.height - h) / 2), Size(w, h))
    }
    val target = when (which) {
        android.app.WallpaperManager.FLAG_SYSTEM -> "home screen"
        android.app.WallpaperManager.FLAG_LOCK -> "lock screen"
        else -> "home and lock screens"
    }
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.Close, "Cancel") } },
                title = {
                    Column {
                        Text("Choose area")
                        Text("Wallpaper for $target", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(.7f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black, titleContentColor = Color.White, navigationIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            Column(Modifier.background(Color(0xFF111214)).navigationBarsPadding().padding(16.dp)) {
                Text(
                    "Drag the box or its corners to pick what shows on your screen.",
                    color = Color.White.copy(.75f), style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    {
                        val b = img ?: return@Button
                        busy = true
                        val frac = android.graphics.RectF(crop.left / b.width, crop.top / b.height, crop.right / b.width, crop.bottom / b.height)
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { setWallpaper(ctx, m, frac, screenW, screenH, which) }
                            busy = false
                            vm.toast(if (ok) "Wallpaper set" else "Couldn't set wallpaper")
                            if (ok) onBack()
                        }
                    },
                    Modifier.fillMaxWidth(), enabled = img != null && !busy,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Set wallpaper")
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().padding(20.dp), Alignment.Center) {
            val b = img
            if (b == null) CircularProgressIndicator()
            else CropView(remember(b) { b.asImageBitmap() }, crop, ratio, interactive = true, filter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix())) { crop = it }
        }
    }
}

/** Shows the image fitted to the box, dims outside [crop], and lets you drag corners or move the box. */
@Composable
internal fun CropView(img: ImageBitmap, crop: Rect, ratio: Float?, interactive: Boolean, filter: ColorFilter, onCrop: (Rect) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val touch = with(LocalDensity.current) { 36.dp.toPx() }
    val scale = if (box == IntSize.Zero) 1f else min(box.width.toFloat() / img.width, box.height.toFloat() / img.height)
    val ox = (box.width - img.width * scale) / 2
    val oy = (box.height - img.height * scale) / 2
    val minSize = 48f / scale
    val currentCrop by rememberUpdatedState(crop)
    val fixed = when (ratio) { null -> null; -1f -> img.width.toFloat() / img.height; else -> ratio }
    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }
            .then(
                if (!interactive) Modifier else Modifier.pointerInput(img.width, img.height, fixed, scale) {
                    var mode = -1 // 0..3 corners (tl, tr, br, bl), 4 = move
                    // running rect for this drag: several move events arrive between frames, so each must
                    // build on the previous one, not on the last recomposed value (which made drags lag/stick)
                    var work = currentCrop
                    detectDragGestures(
                        onDragStart = { p ->
                            work = currentCrop
                            val c = work
                            val pts = listOf(c.topLeft, c.topRight, c.bottomRight, c.bottomLeft).map { Offset(ox + it.x * scale, oy + it.y * scale) }
                            val i = pts.indexOfFirst { (it - p).getDistance() < touch }
                            mode = when {
                                i >= 0 -> i
                                Rect(pts[0], pts[2]).contains(p) -> 4
                                else -> -1
                            }
                        },
                        onDrag = { change, d ->
                            change.consume()
                            val c = work
                            val dx = d.x / scale; val dy = d.y / scale
                            val w = img.width.toFloat(); val h = img.height.toFloat()
                            val r = when (mode) {
                                4 -> {
                                    val nx = (c.left + dx).coerceIn(0f, w - c.width); val ny = (c.top + dy).coerceIn(0f, h - c.height)
                                    Rect(Offset(nx, ny), c.size)
                                }
                                in 0..3 -> {
                                    // move the grabbed corner; the opposite corner stays put
                                    var l = c.left; var t = c.top; var rr = c.right; var b = c.bottom
                                    if (mode == 0 || mode == 3) l = (l + dx).coerceIn(0f, rr - minSize) else rr = (rr + dx).coerceIn(l + minSize, w)
                                    if (mode == 0 || mode == 1) t = (t + dy).coerceIn(0f, b - minSize) else b = (b + dy).coerceIn(t + minSize, h)
                                    if (fixed != null) {
                                        // keep the ratio: height follows width, clamped to the image
                                        var nw = rr - l; var nh = nw / fixed
                                        val maxH = if (mode == 0 || mode == 1) c.bottom else h - c.top
                                        if (nh > maxH) { nh = maxH; nw = nh * fixed }
                                        if (mode == 0 || mode == 3) l = rr - nw else rr = l + nw
                                        if (mode == 0 || mode == 1) t = b - nh else b = t + nh
                                    }
                                    Rect(l, t, rr, b)
                                }
                                else -> c
                            }
                            if (abs(r.width) >= 1f) { work = r; onCrop(r) }
                        },
                    )
                },
            ),
    ) {
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = filter)
        Canvas(Modifier.fillMaxSize()) {
            val r = Rect(ox + crop.left * scale, oy + crop.top * scale, ox + crop.right * scale, oy + crop.bottom * scale)
            val dim = Color.Black.copy(alpha = .6f)
            drawRect(dim, Offset(ox, oy), Size(img.width * scale, r.top - oy))
            drawRect(dim, Offset(ox, r.bottom), Size(img.width * scale, oy + img.height * scale - r.bottom))
            drawRect(dim, Offset(ox, r.top), Size(r.left - ox, r.height))
            drawRect(dim, Offset(r.right, r.top), Size(ox + img.width * scale - r.right, r.height))
            if (interactive) {
                drawRect(Color.White, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
                // rule-of-thirds guides
                for (i in 1..2) {
                    drawLine(Color.White.copy(.4f), Offset(r.left + r.width * i / 3, r.top), Offset(r.left + r.width * i / 3, r.bottom))
                    drawLine(Color.White.copy(.4f), Offset(r.left, r.top + r.height * i / 3), Offset(r.right, r.top + r.height * i / 3))
                }
                val k = 18.dp.toPx(); val sw = 4.dp.toPx()
                listOf(r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f), r.bottomRight to Offset(-1f, -1f), r.bottomLeft to Offset(1f, -1f))
                    .forEach { (p, dir) ->
                        drawLine(Color.White, p, p + Offset(k * dir.x, 0f), sw)
                        drawLine(Color.White, p, p + Offset(0f, k * dir.y), sw)
                    }
            }
        }
    }
}
