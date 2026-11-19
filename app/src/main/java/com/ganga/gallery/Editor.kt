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
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.automirrored.outlined.Undo
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction

/** Everything the editor changes. Crop is stored as fractions of the rotated/flipped image, so it applies at any resolution. */
data class EditOps(
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val crop: android.graphics.RectF = android.graphics.RectF(0f, 0f, 1f, 1f),
    val color: FloatArray = android.graphics.ColorMatrix().array,
    val angle: Float = 0f,
    val marks: List<Mark> = emptyList(),
    val vignette: Float = 0f,
)

enum class Filter(val label: String) {
    NONE("Original"), FRESH("Fresh"), CLEAR("Clear"), VIVID("Vivid"), WARM("Warm"), COOL("Cool"), VINTAGE("Vintage"),
    FILM("Film"), FADE("Fade"), MONO("Mono"), NOIR("Noir"), SEPIA("Sepia"),
}

/** Builds one 4x5 colour matrix from a filter plus the four adjustment sliders (each -1..1). */
fun colorMatrix(filter: Filter, brightness: Float, contrast: Float, saturation: Float, warmth: Float, tint: Float = 0f): FloatArray {
    fun scale(c: Float, offset: Float) = android.graphics.ColorMatrix(
        floatArrayOf(c, 0f, 0f, 0f, offset, 0f, c, 0f, 0f, offset, 0f, 0f, c, 0f, offset, 0f, 0f, 0f, 1f, 0f),
    )
    fun tint(r: Float, g: Float, b: Float) = android.graphics.ColorMatrix().apply { setScale(r, g, b, 1f) }
    fun sat(s: Float) = android.graphics.ColorMatrix().apply { setSaturation(s) }
    val m = android.graphics.ColorMatrix()
    when (filter) {
        Filter.NONE -> {}
        Filter.FRESH -> { m.postConcat(sat(1.15f)); m.postConcat(tint(0.97f, 1.02f, 1.06f)); m.postConcat(scale(1.02f, 8f)) }
        Filter.CLEAR -> { m.postConcat(scale(1.18f, -18f)); m.postConcat(sat(1.08f)) }
        Filter.VINTAGE -> { m.postConcat(sat(0.7f)); m.postConcat(tint(1.1f, 1.0f, 0.82f)); m.postConcat(scale(0.88f, 22f)) }
        Filter.FILM -> { m.postConcat(sat(0.85f)); m.postConcat(tint(0.98f, 1.04f, 0.96f)); m.postConcat(scale(1.12f, -6f)) }
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
    m.postConcat(tint(1f + tint * 0.08f, 1f - tint * 0.12f, 1f + tint * 0.08f)) // + = magenta, - = green
    return m.array
}

private fun transform(src: Bitmap, turns: Int, flipH: Boolean, flipV: Boolean, angle: Float = 0f): Bitmap {
    val turned = if (turns % 4 == 0 && !flipH && !flipV) src else Bitmap.createBitmap(
        src, 0, 0, src.width, src.height,
        Matrix().apply { postRotate(90f * turns); postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f) }, true,
    )
    if (angle == 0f) return turned
    // straighten: turn by a few degrees and zoom just enough to hide the empty corners
    val w = turned.width.toFloat(); val h = turned.height.toFloat()
    val out = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
    val k = coverScale(w, h, angle)
    Canvas(out).apply { translate(w / 2, h / 2); rotate(angle); scale(k, k); drawBitmap(turned, -w / 2, -h / 2, Paint(Paint.FILTER_BITMAP_FLAG)) }
    if (turned !== src) turned.recycle()
    return out
}

/** Applies [ops] to a full bitmap: rotate/flip, crop, then colour. */
fun renderEdit(src: Bitmap, ops: EditOps): Bitmap {
    var t = transform(src, ops.quarterTurns, ops.flipH, ops.flipV, ops.angle)
    if (ops.marks.any { it.isEffect }) {
        val fx = effectSources(t)
        if (t === src || !t.isMutable) { val c = t.copy(Bitmap.Config.ARGB_8888, true); if (t !== src) t.recycle(); t = c }
        drawMarks(Canvas(t), t.width, t.height, ops.marks, effects = true, fx = fx)
    }
    val x = (ops.crop.left * t.width).toInt().coerceIn(0, t.width - 1)
    val y = (ops.crop.top * t.height).toInt().coerceIn(0, t.height - 1)
    val w = (ops.crop.width() * t.width).toInt().coerceIn(1, t.width - x)
    val h = (ops.crop.height() * t.height).toInt().coerceIn(1, t.height - y)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(
        t, android.graphics.Rect(x, y, x + w, y + h), android.graphics.Rect(0, 0, w, h),
        Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ops.color) },
    )
    val oc = Canvas(out)
    if (ops.marks.any { !it.isEffect }) {
        oc.save(); oc.translate(-x.toFloat(), -y.toFloat())
        drawMarks(oc, t.width, t.height, ops.marks, effects = false, fx = null)
        oc.restore()
    }
    drawVignette(oc, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), ops.vignette)
    if (t !== src) t.recycle()
    return out
}

/** What a drawing tool left on the photo. */
enum class MarkKind { PEN, PIXELATE, BLUR, TEXT }

/**
 * One stroke or text label. [points] are fractions of the turned/straightened photo (x of width, y of height),
 * [width] a fraction of its width (stroke width, or text size), so the same marks fit the preview and the full-size save.
 */
data class Mark(val kind: MarkKind, val points: List<Offset>, val color: Int, val width: Float, val text: String = "", val erase: Boolean = false) {
    val isEffect get() = kind == MarkKind.PIXELATE || kind == MarkKind.BLUR
}

/** Small copies of the photo that, stretched back up, paint as pixel blocks / blur. */
class EffectSources(val w: Int, val h: Int, val pixel: Bitmap, val blur: Bitmap)

fun effectSources(src: Bitmap): EffectSources {
    val blocks = 24 // mosaic squares across the photo's width: big enough to really hide things
    val ph = (blocks * src.height / src.width).coerceAtLeast(1)
    // blur: shrink in steps (each one averages neighbours), then the brush stretches it back smoothly
    val bw = 36; val bh = (bw * src.height / src.width).coerceAtLeast(1)
    val half = Bitmap.createScaledBitmap(src, bw * 4, (bh * 4).coerceAtLeast(1), true)
    val quarter = Bitmap.createScaledBitmap(half, bw * 2, (bh * 2).coerceAtLeast(1), true)
    return EffectSources(
        src.width, src.height,
        Bitmap.createScaledBitmap(src, blocks, ph, true),
        Bitmap.createScaledBitmap(quarter, bw, bh, true),
    )
}

/**
 * Draws [marks] onto a canvas whose units are pixels of a [w]x[h] photo.
 * [effects] picks the pixelate/blur strokes (drawn before colour) or the pen/text ones (drawn on top).
 * [color], when given, tints the effect strokes the same way the photo under them is tinted.
 */
fun drawMarks(c: android.graphics.Canvas, w: Int, h: Int, marks: List<Mark>, effects: Boolean, fx: EffectSources?, color: FloatArray? = null) {
    // eraser strokes punch holes in their own layer (pen layer or effect layer), so the photo underneath shows again
    val layered = marks.any { it.erase && it.isEffect == effects }
    if (layered) c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
    drawMarkLayer(c, w, h, marks, effects, fx, color)
    if (layered) c.restore()
}

private fun drawMarkLayer(c: android.graphics.Canvas, w: Int, h: Int, marks: List<Mark>, effects: Boolean, fx: EffectSources?, color: FloatArray?) {
    for (mk in marks) {
        if (mk.isEffect != effects || mk.points.isEmpty()) continue
        if (mk.kind == MarkKind.TEXT) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = mk.color; textSize = mk.width * w; typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER; setShadowLayer(textSize * .08f, 0f, textSize * .04f, 0x99000000.toInt())
            }
            c.drawText(mk.text, mk.points[0].x * w, mk.points[0].y * h, p)
            continue
        }
        val path = android.graphics.Path()
        mk.points.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x * w, pt.y * h) else path.lineTo(pt.x * w, pt.y * h) }
        if (mk.points.size == 1) path.lineTo(mk.points[0].x * w + 0.1f, mk.points[0].y * h) // a dot
        // the mosaic gets hard edges so its squares stay crisp; pen and blur stay smooth
        val p = Paint(if (mk.kind == MarkKind.PIXELATE) 0 else Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = mk.width * w
            this.color = android.graphics.Color.WHITE // opaque, so the brush shows the effect at full strength
        }
        if (mk.erase) p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        else if (mk.kind == MarkKind.PEN) p.color = mk.color
        else {
            val small = (if (mk.kind == MarkKind.PIXELATE) fx?.pixel else fx?.blur) ?: continue
            p.shader = android.graphics.BitmapShader(small, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(w.toFloat() / small.width, h.toFloat() / small.height) })
            }
            p.isFilterBitmap = mk.kind == MarkKind.BLUR // nearest-neighbour stretch = pixel blocks
            if (color != null) p.colorFilter = ColorMatrixColorFilter(color)
        }
        c.drawPath(path, p)
    }
}

/** Darkens the edges of [r]; [amount] 0..1. */
fun drawVignette(c: android.graphics.Canvas, r: android.graphics.RectF, amount: Float) {
    if (amount <= 0f) return
    val radius = kotlin.math.hypot(r.width(), r.height()) / 2f
    c.drawRect(r, Paint().apply {
        shader = android.graphics.RadialGradient(
            r.centerX(), r.centerY(), radius,
            intArrayOf(0, 0, android.graphics.Color.argb((amount * 220).toInt(), 0, 0, 0)), floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    })
}

/** Zoom needed so a [w]x[h] photo turned by [degrees] still covers its own frame (no empty corners). */
fun coverScale(w: Float, h: Float, degrees: Float): Float {
    val a = Math.toRadians(abs(degrees).toDouble())
    val cos = kotlin.math.cos(a).toFloat(); val sin = kotlin.math.sin(a).toFloat()
    return maxOf((w * cos + h * sin) / w, (w * sin + h * cos) / h)
}

private enum class Tool(val label: String, val icon: ImageVector) {
    CROP("Crop", Icons.Outlined.Crop), ADJUST("Adjust", Icons.Outlined.Tune), FILTERS("Filter", Icons.Outlined.FilterVintage),
    MARKUP("Markup", Icons.Outlined.Draw), TEXT("Text", Icons.Outlined.TextFields),
    PIXELATE("Pixelate", Icons.Outlined.GridOn), BLUR("Blur", Icons.Outlined.BlurOn),
}
private enum class Adj(val label: String, val icon: ImageVector) {
    BRIGHTNESS("Exposure", Icons.Outlined.Exposure), CONTRAST("Contrast", Icons.Outlined.Contrast),
    SATURATION("Saturation", Icons.Outlined.Palette), WARMTH("Warmth", Icons.Outlined.Thermostat),
    TINT("Tint", Icons.Outlined.Colorize), VIGNETTE("Vignette", Icons.Outlined.Vignette),
}
private val RATIOS = listOf<Pair<String, Float?>>("Free" to null, "Original" to -1f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
private val PEN_COLORS = listOf(0xFFFFFFFF, 0xFF111111, 0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF0A84FF, 0xFFFF2D92).map { it.toInt() }
/** Brush sizes as a fraction of the image width. */
/** Brush / text sizes, as fractions of the photo's width, picked freely on a slider. */
private val PEN_RANGE = 0.004f..0.08f
private val EFFECT_RANGE = 0.02f..0.25f
private val TEXT_RANGE = 0.03f..0.2f
/** Editor accent: the colour picked in Settings → Theme. */
private val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary

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
    var angle by remember { mutableFloatStateOf(0f) }
    // straightening is only drawn (rotated canvas) while editing, so the ruler follows the finger with no lag;
    // the pixels are turned for real when saving
    val shown = remember(base, turns, flipH, flipV) { base?.let { transform(it, turns, flipH, flipV) } }
    // crop rect in pixels of [shown]; kept while straightening (same size), reset when the shape changes
    var crop by remember(shown?.width, shown?.height) { mutableStateOf(shown?.let { Rect(0f, 0f, it.width.toFloat(), it.height.toFloat()) } ?: Rect.Zero) }
    var ratio by remember { mutableStateOf<Float?>(null) }
    var tool by remember { mutableStateOf<Tool?>(null) }
    var adj by remember { mutableStateOf(Adj.BRIGHTNESS) }
    val values = remember { mutableStateMapOf<Adj, Float>().apply { Adj.entries.forEach { put(it, 0f) } } }
    var filter by remember { mutableStateOf(Filter.NONE) }
    val marks = remember { mutableStateListOf<Mark>() }
    var penColor by remember { mutableIntStateOf(PEN_COLORS[2]) }
    var penSize by remember { mutableFloatStateOf(0.02f) }
    var effectSize by remember { mutableFloatStateOf(0.08f) }
    var textSize by remember { mutableFloatStateOf(0.08f) }
    var erasing by remember { mutableStateOf(false) } // brush or eraser, for markup / pixelate / blur
    var textAt by remember { mutableStateOf<Offset?>(null) } // where a tap asked for new text
    var draft by remember { mutableStateOf("") } // text being typed, shown live on the photo
    fun commitText() {
        val at = textAt
        if (at != null && draft.isNotBlank()) marks.add(Mark(MarkKind.TEXT, listOf(at), penColor, textSize, draft.trim()))
        textAt = null; draft = ""
    }
    var saving by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    val matrix = colorMatrix(filter, values[Adj.BRIGHTNESS]!!, values[Adj.CONTRAST]!!, values[Adj.SATURATION]!!, values[Adj.WARMTH]!!, values[Adj.TINT]!!)
    val composeFilter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(matrix))
    val full = shown?.let { Rect(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
    val changed = turns % 4 != 0 || flipH || flipV || angle != 0f || filter != Filter.NONE || values.values.any { it != 0f } ||
        marks.isNotEmpty() || (full != null && crop != full)

    // Cancel inside a tool puts everything back the way it was when the tool opened
    var snapshot by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun open(t: Tool) {
        val s = listOf(turns, flipH, flipV, angle, crop, ratio, filter, values.toMap(), marks.toList())
        snapshot = {
            turns = s[0] as Int; flipH = s[1] as Boolean; flipV = s[2] as Boolean; angle = s[3] as Float
            ratio = s[5] as Float?; filter = s[6] as Filter
            @Suppress("UNCHECKED_CAST") values.putAll(s[7] as Map<Adj, Float>)
            marks.clear(); @Suppress("UNCHECKED_CAST") marks.addAll(s[8] as List<Mark>)
            crop = s[4] as Rect
        }
        tool = t; erasing = false
    }
    fun close(keep: Boolean) {
        if (keep) commitText() else { textAt = null; draft = "" }
        if (!keep) snapshot?.invoke(); snapshot = null; tool = null
    }
    // drawings sit on the rotated photo, so turning/flipping it again would misplace them
    fun reshape(change: () -> Unit) {
        if (marks.isNotEmpty()) { marks.clear(); vm.toast("Drawings removed: the photo was turned") }
        change()
    }

    fun ops(): EditOps {
        val s = shown!!
        return EditOps(
            turns, flipH, flipV,
            android.graphics.RectF(crop.left / s.width, crop.top / s.height, crop.right / s.width, crop.bottom / s.height),
            matrix, angle, marks.toList(), values[Adj.VIGNETTE]!!,
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
    fun auto() {
        // brighten/darken towards a mid-grey average, with a little extra punch
        val t = thumb?.asAndroidBitmap() ?: return
        var sum = 0L; val px = IntArray(t.width * t.height).also { t.getPixels(it, 0, t.width, 0, 0, t.width, t.height) }
        px.forEach { sum += (android.graphics.Color.red(it) * 3 + android.graphics.Color.green(it) * 6 + android.graphics.Color.blue(it)) / 10 }
        val mean = sum.toFloat() / px.size / 255f
        values[Adj.BRIGHTNESS] = ((0.5f - mean) * 1.2f).coerceIn(-0.5f, 0.5f)
        values[Adj.CONTRAST] = 0.12f; values[Adj.SATURATION] = 0.15f
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

    BackHandler { when { tool != null -> close(keep = false); changed -> confirmExit = true; else -> onBack() } }

    // live brush for the drawing tools
    val brush = when (tool) {
        Tool.MARKUP -> Mark(MarkKind.PEN, emptyList(), penColor, penSize, erase = erasing)
        Tool.PIXELATE -> Mark(MarkKind.PIXELATE, emptyList(), 0, effectSize, erase = erasing)
        Tool.BLUR -> Mark(MarkKind.BLUR, emptyList(), 0, effectSize, erase = erasing)
        Tool.TEXT -> Mark(MarkKind.TEXT, emptyList(), penColor, textSize)
        else -> null
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()) {
        // top: Cancel / Save pills on the main screen, nothing while a tool is open
        if (tool == null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Cancel", Color(0xFF2C2C2E), Color.White) { if (changed) confirmExit = true else onBack() }
            Spacer(Modifier.weight(1f))
            Box {
                Pill(if (saving) "Saving…" else "Save", if (changed) Accent else Accent.copy(alpha = .35f), MaterialTheme.colorScheme.onPrimary, enabled = changed && !saving && shown != null) {
                    if (vm.canReplace(m)) saveMenu = true else save(replace = false)
                }
                AppMenu(saveMenu, { saveMenu = false }) {
                    MenuItem("Save as copy", Icons.Outlined.FileCopy) { saveMenu = false; save(replace = false) }
                    MenuItem("Replace original", Icons.Outlined.SaveAs) { saveMenu = false; save(replace = true) }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { // CropView keeps its own margin
            val s = shown
            if (s == null) CircularProgressIndicator(color = Accent)
            else {
                val img = remember(s) { s.asImageBitmap() }
                // tiny copies that become the pixelate / blur brushes
                val fx = remember(s) { effectSources(s) }
                val pending = textAt?.takeIf { draft.isNotBlank() }?.let { Mark(MarkKind.TEXT, listOf(it), penColor, textSize, draft) }
                CropView(
                    img, crop, ratio, interactive = tool == Tool.CROP, filter = composeFilter,
                    marks = if (pending != null) marks + pending else marks, fx = fx, color = matrix,
                    vignette = values[Adj.VIGNETTE]!!, brush = brush, angle = angle,
                    // a new tap while typing places the text being typed there
                    onMark = { mk -> if (mk.kind == MarkKind.TEXT) { if (draft.isNotBlank()) commitText(); textAt = mk.points.first() } else marks.add(mk) },
                ) { crop = it }
            }
        }
        val t = tool
        if (t == null) {
            // main screen: round tool buttons, like the phone's own editor
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(Tool.entries) { tl -> RoundTool(tl.icon, tl.label, false) { open(tl) } }
            }
        } else {
            Column(Modifier.fillMaxWidth().background(Color.Black).padding(top = 8.dp)) {
                when (t) {
                    Tool.CROP -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BarAction(Icons.AutoMirrored.Outlined.RotateLeft, "Rotate", Color.White) { reshape { turns = (turns + 3) % 4 } }
                            BarAction(Icons.Outlined.Flip, "Mirror", Color.White) { reshape { flipH = !flipH } }
                            BarAction(Icons.Outlined.SwapVert, "Flip", Color.White) { reshape { flipV = !flipV } }
                        }
                        LazyRow(Modifier.padding(top = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(RATIOS) { (label, r) -> EditorChip(label, ratio == r) { applyRatio(r) } }
                        }
                        AngleDial(angle) { a -> reshape { angle = a } }
                    }
                    Tool.ADJUST -> {
                        val v = values[adj]!!
                        SliderRow(adj.label, "${(v * 100).roundToInt()}", v, if (adj == Adj.VIGNETTE) 0f..1f else -1f..1f) { values[adj] = it }
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            item { RoundTool(Icons.Outlined.AutoFixHigh, "Auto", false) { auto() } }
                            items(Adj.entries) { a -> RoundTool(a.icon, a.label, adj == a, dot = values[a] != 0f) { adj = a } }
                        }
                    }
                    Tool.FILTERS -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(Filter.entries) { f ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { filter = f }) {
                                val sel = f == filter
                                thumb?.let {
                                    Image(
                                        it, f.label,
                                        Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                                            .then(if (sel) Modifier.border(2.5.dp, Accent, RoundedCornerShape(12.dp)) else Modifier),
                                        contentScale = ContentScale.Crop,
                                        colorFilter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(colorMatrix(f, 0f, 0f, 0f, 0f))),
                                    )
                                }
                                Text(f.label, color = if (sel) Accent else Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    Tool.MARKUP, Tool.TEXT -> {
                        if (!erasing) Swatches(penColor) { penColor = it }
                        if (t == Tool.TEXT) BrushRow(textSize, TEXT_RANGE, { textSize = it }, marks, eraser = null)
                        else BrushRow(penSize, PEN_RANGE, { penSize = it }, marks, eraser = erasing) { erasing = it }
                        if (t == Tool.TEXT && textAt != null) TextEntry(draft, { draft = it }, onAdd = { commitText() }, onDiscard = { textAt = null; draft = "" })
                        else Hint(
                            when { t == Tool.TEXT -> "Tap the photo where the text should go"; erasing -> "Rub over a drawing to erase it"; else -> "Draw on the photo with your finger" },
                        )
                    }
                    Tool.PIXELATE, Tool.BLUR -> {
                        BrushRow(effectSize, EFFECT_RANGE, { effectSize = it }, marks, eraser = erasing) { erasing = it }
                        Hint(if (erasing) "Rub over a painted part to bring the photo back" else "Paint over the parts to ${if (t == Tool.PIXELATE) "pixelate" else "blur"}")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ close(keep = false) }) { Text("Cancel", color = Accent, style = MaterialTheme.typography.titleMedium) }
                    Text(t.label, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    TextButton({ close(keep = true) }) { Text("Done", color = Accent, style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }

    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text("Discard changes?") },
        confirmButton = { TextButton({ confirmExit = false; onBack() }) { Text("Discard") } },
        dismissButton = { TextButton({ confirmExit = false }) { Text("Keep editing") } },
    )
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, enabled: Boolean = true, onClick: () -> Unit) = Box(
    Modifier.clip(CircleShape).background(if (enabled) bg else Color(0xFF2C2C2E)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 22.dp, vertical = 10.dp),
) { Text(label, color = if (enabled) fg else Color.White.copy(alpha = .35f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }

/** Round dark button with its name under it (tools, adjustments). */
@Composable
private fun RoundTool(icon: ImageVector, label: String, selected: Boolean, dot: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(Color(0xFF2C2C2E))
                .then(if (selected) Modifier.border(2.dp, Accent, CircleShape) else Modifier),
            Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(26.dp), tint = if (selected) Accent else Color.White)
            if (dot) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(6.dp).background(Accent, CircleShape))
        }
        Text(label, color = if (selected) Accent else Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun EditorChip(label: String, selected: Boolean, onClick: () -> Unit) = Box(
    Modifier.clip(CircleShape).background(if (selected) Accent else Color(0xFF2C2C2E)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 7.dp),
) { Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White, fontSize = 13.sp) }

@Composable
private fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row {
            Text(label, color = Color.White, modifier = Modifier.weight(1f))
            Text(value, color = Accent, fontWeight = FontWeight.SemiBold)
        }
        AppSlider(v, onChange, range = range, label = label, color = Accent, track = Color.White.copy(.25f))
    }
}

@Composable
private fun Swatches(selected: Int, onPick: (Int) -> Unit) = LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    items(PEN_COLORS) { c ->
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                .border(if (c == selected) 3.dp else 1.dp, if (c == selected) Accent else Color.White.copy(.4f), CircleShape)
                .clickable { onPick(c) },
        )
    }
}

@Composable
private fun UndoButton(marks: MutableList<Mark>) = IconButton({ if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) }, enabled = marks.isNotEmpty()) {
    Icon(Icons.AutoMirrored.Outlined.Undo, "Undo", tint = if (marks.isNotEmpty()) Color.White else Color.White.copy(.3f))
}

@Composable
private fun Hint(text: String) = Text(
    text, Modifier.fillMaxWidth().padding(top = 2.dp), color = Color.White.copy(.6f), fontSize = 12.sp, textAlign = TextAlign.Center,
)

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
        Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { // CropView keeps its own margin
            val b = img
            if (b == null) CircularProgressIndicator()
            else CropView(remember(b) { b.asImageBitmap() }, crop, ratio, interactive = true, filter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix())) { crop = it }
        }
    }
}

/**
 * Shows the image, dims outside [crop], and lets you drag corners or move the box. While cropping, letting go
 * zooms and pans so the crop fills the view (crop stays in image pixels; only the display transform changes).
 */
@Composable
internal fun CropView(
    img: ImageBitmap, crop: Rect, ratio: Float?, interactive: Boolean, filter: ColorFilter,
    marks: List<Mark> = emptyList(), fx: EffectSources? = null, color: FloatArray? = null, vignette: Float = 0f,
    brush: Mark? = null, angle: Float = 0f, onMark: (Mark) -> Unit = {}, onCrop: (Rect) -> Unit,
) {
    val live = remember { mutableStateListOf<Offset>() } // stroke being drawn right now
    val curBrush by rememberUpdatedState(brush)
    var box by remember { mutableStateOf(IntSize.Zero) }
    val touch = with(LocalDensity.current) { 36.dp.toPx() }
    val margin = with(LocalDensity.current) { 20.dp.toPx() } // room round the zoomed box to grab corners / drag outwards
    val full = Rect(0f, 0f, img.width.toFloat(), img.height.toFloat())
    // part of the image (in image pixels) fitted inside the margins; the crop when cropping, else the whole image
    val view = remember(img) { Animatable(full, Rect.VectorConverter) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(img, crop, interactive, dragging) {
        if (!dragging) view.animateTo(if (interactive && crop.width >= 1f && crop.height >= 1f) crop else full)
    }
    val v = view.value
    val availW = (box.width - 2 * margin).coerceAtLeast(1f); val availH = (box.height - 2 * margin).coerceAtLeast(1f)
    val scale = if (box == IntSize.Zero) 1f else min(availW / v.width, availH / v.height)
    val ox = box.width / 2f - v.center.x * scale
    val oy = box.height / 2f - v.center.y * scale
    // min crop is 48px at the un-zoomed fit, so repeated zooming can't shrink it to nothing
    val minSize = 48f / min(availW / img.width, availH / img.height)
    val xf by rememberUpdatedState(floatArrayOf(scale, ox, oy, minSize))
    val currentCrop by rememberUpdatedState(crop)
    val fixed = when (ratio) { null -> null; -1f -> img.width.toFloat() / img.height; else -> ratio }
    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { box = it }
            .then(
                if (!interactive) Modifier else Modifier.pointerInput(img, fixed) {
                    var mode = -1 // 0..3 corners (tl, tr, br, bl), 4 = move
                    // running rect for this drag: several move events arrive between frames, so each must
                    // build on the previous one, not on the last recomposed value (which made drags lag/stick)
                    var work = currentCrop
                    // view transform frozen for the whole drag (the zoom only animates after release)
                    var scale = 1f; var ox = 0f; var oy = 0f; var minSize = 1f
                    detectDragGestures(
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onDragStart = { p ->
                            dragging = true
                            xf.let { scale = it[0]; ox = it[1]; oy = it[2]; minSize = it[3] }
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
            )
            .then(
                if (brush == null) Modifier else Modifier.pointerInput(img, brush.kind) {
                    // screen point -> fraction of the photo
                    fun toImg(p: Offset): Offset {
                        val (sc, x0, y0) = xf.let { Triple(it[0], it[1], it[2]) }
                        return Offset((((p.x - x0) / sc) / img.width).coerceIn(0f, 1f), (((p.y - y0) / sc) / img.height).coerceIn(0f, 1f))
                    }
                    if (brush.kind == MarkKind.TEXT) detectTapGestures { p -> curBrush?.let { onMark(it.copy(points = listOf(toImg(p)))) } }
                    else detectDragGestures(
                        onDragStart = { p -> live.clear(); live.add(toImg(p)) },
                        onDrag = { ch, _ -> ch.consume(); live.add(toImg(ch.position)) },
                        onDragEnd = { curBrush?.let { onMark(it.copy(points = live.toList())) }; live.clear() },
                        onDragCancel = { live.clear() },
                    )
                },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val iw = img.width * scale; val ih = img.height * scale
            val paint = {
                drawImage(
                    img, dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                    dstSize = IntSize(iw.roundToInt(), ih.roundToInt()), colorFilter = filter,
                )
            }
            if (angle == 0f) paint()
            else clipRect(ox, oy, ox + iw, oy + ih) {
                // turn round the middle and zoom just enough to hide the corners, like the saved result
                val c = Offset(ox + iw / 2, oy + ih / 2)
                val k = coverScale(img.width.toFloat(), img.height.toFloat(), angle)
                withTransform({ rotate(angle, c); scale(k, k, c) }) { paint() }
            }
            val r = Rect(ox + crop.left * scale, oy + crop.top * scale, ox + crop.right * scale, oy + crop.bottom * scale)
            if (marks.isNotEmpty() || live.isNotEmpty() || vignette > 0f) drawIntoCanvas { cv ->
                val n = cv.nativeCanvas
                val b = curBrush
                val all = if (live.isEmpty() || b == null) marks else marks + b.copy(points = live.toList())
                n.save(); n.translate(ox, oy); n.scale(scale, scale)
                drawMarks(n, img.width, img.height, all, effects = true, fx = fx, color = color)
                drawMarks(n, img.width, img.height, all, effects = false, fx = null)
                n.restore()
                drawVignette(n, android.graphics.RectF(r.left, r.top, r.right, r.bottom), vignette)
            }
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

/** Ruler dragged sideways to straighten, like the phone's own editor; snaps to 0° and double-tap resets. */
@Composable
private fun AngleDial(angle: Float, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val cur by rememberUpdatedState(angle)
    val accent = Accent
    fun set(v: Float) = change(v.coerceIn(-45f, 45f).let { if (abs(it) < 0.4f) 0f else it })
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (angle == 0f) "0°" else "%+.1f°".format(angle),
            color = if (angle == 0f) Color.White else accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        )
        Canvas(
            Modifier.fillMaxWidth().height(48.dp)
                .semantics {
                    contentDescription = "Straighten"
                    progressBarRangeInfo = ProgressBarRangeInfo(angle, -45f..45f)
                    setProgress { v -> set(v); true }
                }
                .pointerInput(Unit) {
                    val perDeg = 9.dp.toPx()
                    detectHorizontalDragGestures { ch, dx -> ch.consume(); set(cur - dx / perDeg) }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { set(0f) }) },
        ) {
            val perDeg = 9.dp.toPx(); val cx = size.width / 2; val cy = size.height / 2
            for (d in -45..45) {
                val x = cx + (d - angle) * perDeg
                if (x < 0f || x > size.width) continue
                val major = d % 5 == 0
                val h = size.height * (if (major) .55f else .3f)
                // ticks fade towards the edges
                val edge = 1f - abs(x - cx) / cx
                drawLine(
                    Color.White.copy(alpha = (if (major) .8f else .45f) * edge.coerceIn(.15f, 1f)),
                    Offset(x, cy - h / 2), Offset(x, cy + h / 2), (if (major) 2.dp else 1.dp).toPx(),
                )
            }
            drawLine(accent, Offset(cx, 2.dp.toPx()), Offset(cx, size.height - 2.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Inline text box shown while adding text: what's typed appears on the photo straight away. */
@Composable
private fun TextEntry(text: String, onText: (String) -> Unit, onAdd: () -> Unit, onDiscard: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onDiscard) { Icon(Icons.Default.Close, "Discard text", tint = Color.White) }
        BasicTextField(
            text, onText,
            Modifier.weight(1f).focusRequester(focus).clip(CircleShape).background(Color(0xFF2C2C2E)).padding(horizontal = 18.dp, vertical = 12.dp),
            singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onAdd() }),
            decorationBox = { inner -> Box { if (text.isEmpty()) Text("Type your text", color = Color.White.copy(.45f), fontSize = 16.sp); inner() } },
        )
        Spacer(Modifier.width(8.dp))
        Pill("Add", Accent, MaterialTheme.colorScheme.onPrimary, enabled = text.isNotBlank(), onClick = onAdd)
    }
}

/**
 * Brush / Eraser switch, a free size slider with a dot showing the size, and Undo.
 * [eraser] null hides the switch (text has no eraser).
 */
@Composable
private fun BrushRow(
    size: Float, range: ClosedFloatingPointRange<Float>, onSize: (Float) -> Unit, marks: MutableList<Mark>,
    eraser: Boolean?, onEraser: (Boolean) -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (eraser != null) Row(Modifier.clip(CircleShape).background(Color(0xFF2C2C2E)).padding(3.dp)) {
            listOf(false to Icons.Outlined.Brush, true to Icons.Outlined.AutoFixOff).forEach { (e, icon) ->
                val sel = eraser == e
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (sel) Accent else Color.Transparent).clickable { onEraser(e) },
                    Alignment.Center,
                ) { Icon(icon, if (e) "Eraser" else "Brush", Modifier.size(20.dp), tint = if (sel) MaterialTheme.colorScheme.onPrimary else Color.White) }
            }
        }
        // dot grows with the brush, so the size is visible before drawing
        Box(Modifier.padding(start = 10.dp).size(28.dp), Alignment.Center) {
            val d = 6.dp + 22.dp * ((size - range.start) / (range.endInclusive - range.start))
            Box(Modifier.size(d).clip(CircleShape).background(Color.White))
        }
        AppSlider(size, onSize, Modifier.weight(1f), range = range, label = "Size", color = Accent, track = Color.White.copy(.25f))
        UndoButton(marks)
    }
}
