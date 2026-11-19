package com.ganga.gallery

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class VTool(val label: String, val icon: ImageVector) {
    TRIM("Trim", Icons.Outlined.ContentCut), CUT("Split", Icons.Outlined.CallSplit), MUSIC("Music", Icons.Outlined.MusicNote),
    TEXT("Text", Icons.Outlined.TextFields), ADJUST("Adjust", Icons.Outlined.Tune), FILTERS("Filter", Icons.Outlined.FilterVintage),
    SPEED("Speed", Icons.Outlined.Speed), CROP("Crop", Icons.Outlined.Crop), CANVAS("Canvas", Icons.Outlined.AspectRatio),
    VOLUME("Volume", Icons.Outlined.VolumeUp), ROTATE("Rotate", Icons.AutoMirrored.Outlined.RotateRight),
    FRAME("Frame", Icons.Outlined.PhotoCamera),
}

/** Editor accent: the colour picked in Settings → Theme. */
private val VAccent: Color @Composable get() = MaterialTheme.colorScheme.primary
private val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFF111111, 0xFFFFCC00, 0xFFFF3B30, 0xFF34C759, 0xFF0A84FF).map { it.toInt() }

/** Output shapes. null ratio = keep the video's own shape. */
internal enum class VCanvas(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null), R9_16("9:16", 9f / 16f), R1_1("1:1", 1f), R4_5("4:5", 4f / 5f), R16_9("16:9", 16f / 9f), R4_3("4:3", 4f / 3f),
}

private val V_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/** Everything the video editor changes; one place both the live preview and the export read from. */
internal data class VEdit(
    val start: Float = 0f, val end: Float = 1f, // trim, fractions of duration
    val splits: List<Float> = emptyList(), // split points on the timeline (fractions), sorted
    val removed: Set<Float> = emptySet(), // start of every deleted part
    val canvas: VCanvas = VCanvas.ORIGINAL, val blurBg: Boolean = true,
    val crop: android.graphics.RectF = android.graphics.RectF(0f, 0f, 1f, 1f),
    val speed: Float = 1f, val filter: Filter = Filter.NONE,
    val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f,
    val volume: Float = 1f, val mute: Boolean = false,
    val turns: Int = 0, val flip: Boolean = false,
    // text laid over the whole video; [textPos] 0 = top .. 1 = bottom, [textSize] a fraction of the frame height
    val text: String = "", val textColor: Int = 0xFFFFFFFF.toInt(), val textPos: Float = 0.85f, val textSize: Float = 0.06f,
    // background music from the phone, looped under the video
    val music: android.net.Uri? = null, val musicName: String = "", val musicVolume: Float = 1f, val originalSound: Boolean = true,
)

/** Parts between split points, as (start, end) fractions. */
internal fun VEdit.parts() = (listOf(0f) + splits + 1f).zipWithNext()
internal fun VEdit.partAt(f: Float) = parts().firstOrNull { f < it.second } ?: parts().last()
/** Kept parts clipped to the trim: what the export contains. */
internal fun VEdit.kept() = parts().filter { it.first !in removed }
    .mapNotNull { (a, b) -> val s = maxOf(a, start); val en = minOf(b, end); if (en > s) s to en else null }
/** Splits at [f]; both halves of a deleted part stay deleted. */
internal fun VEdit.split(f: Float): VEdit {
    if (f < 0.005f || f > 0.995f || splits.any { kotlin.math.abs(it - f) < 0.005f }) return this
    val inRemoved = partAt(f).first in removed
    return copy(splits = (splits + f).sorted(), removed = if (inRemoved) removed + f else removed)
}
internal fun VEdit.togglePart(f: Float) = partAt(f).first.let { s -> copy(removed = if (s in removed) removed - s else removed + s) }

private val VEdit.hasColor get() = filter != Filter.NONE || brightness != 0f || contrast != 0f || saturation != 0f

/** Colour matrix read by the GPU every frame, so changing [set] updates the picture without rebuilding the player. */
@UnstableApi
private class LiveColor : RgbMatrix {
    @Volatile private var gl = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    fun set(e: VEdit) {
        // Android's 4x5 row-major matrix (offsets in 0..255) → GL 4x4 column-major; offsets ride the 4th column since alpha = 1
        val m = colorMatrix(e.filter, e.brightness, e.contrast, e.saturation, 0f)
        gl = FloatArray(16) { i ->
            val r = i % 4; val col = i / 4
            when {
                r == 3 -> if (col == 3) 1f else 0f
                col == 3 -> m[r * 5 + 3] + m[r * 5 + 4] / 255f
                else -> m[r * 5 + col]
            }
        }
    }
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean) = gl
}

/** Video-frame effects in the order they apply: crop → rotate/flip → colour → canvas. [aspect] = video's own w/h. */
@UnstableApi
private fun videoEffects(e: VEdit, aspect: Float, color: LiveColor? = null, previewH: Int = 0, sigma: Float = 12f, frameH: Int = 0): List<Effect> = buildList {
    // preview only: shrink frames first so every later effect works on far fewer pixels
    if (previewH > 0) add(Presentation.createForHeight(previewH))
    val c = e.crop
    if (c.left > 0f || c.top > 0f || c.right < 1f || c.bottom < 1f) {
        // Crop takes normalised device coords: -1..1, y up
        add(Crop(c.left * 2 - 1, c.right * 2 - 1, 1 - c.bottom * 2, 1 - c.top * 2))
    }
    if (e.turns % 4 != 0 || e.flip) add(
        ScaleAndRotateTransformation.Builder().setRotationDegrees(360f - 90f * (e.turns % 4)).setScale(if (e.flip) -1f else 1f, 1f).build(),
    )
    // filter + brightness/contrast/saturation are one matrix pass; the preview's matrix changes live without a rebuild
    when {
        color != null -> add(color)
        e.hasColor -> add(LiveColor().apply { set(e) })
    }
    val target = e.canvas.ratio
    if (target != null) {
        var a0 = aspect * (c.width() / c.height().coerceAtLeast(0.01f))
        if (e.turns % 2 == 1) a0 = 1f / a0
        if (e.blurBg) {
            // CapCut-style: stretch to the new shape and blur it as the background, then lay the sharp video on top at its true shape
            add(Presentation.createForAspectRatio(target, Presentation.LAYOUT_STRETCH_TO_FIT))
            if (target < a0) add(GaussianBlurWithFrameOverlaid(sigma, 1f, target / a0))
            else add(GaussianBlurWithFrameOverlaid(sigma, a0 / target, 1f))
        } else add(Presentation.createForAspectRatio(target, Presentation.LAYOUT_SCALE_TO_FIT))
    }
    if (e.text.isNotBlank() && frameH > 0) add(textOverlay(e, frameH))
}

@UnstableApi
private fun textOverlay(e: VEdit, frameH: Int): Effect {
    val span = android.text.SpannableString(e.text)
    val all = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    span.setSpan(android.text.style.ForegroundColorSpan(e.textColor), 0, e.text.length, all)
    span.setSpan(android.text.style.AbsoluteSizeSpan((e.textSize * frameH).toInt().coerceAtLeast(12)), 0, e.text.length, all)
    span.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, e.text.length, all)
    // anchor: -1 bottom .. 1 top of the frame
    val y = (1f - e.textPos * 2f).coerceIn(-0.85f, 0.85f)
    return androidx.media3.effect.OverlayEffect(
        listOf(androidx.media3.effect.TextOverlay.createStaticTextOverlay(span, androidx.media3.effect.StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).build())),
    )
}

/** Volume for the music track. */
@UnstableApi
private fun musicFx(volume: Float): List<AudioProcessor> = if (volume == 1f) emptyList() else listOf(ChannelMixingAudioProcessor().apply {
    putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1).scaleBy(volume))
    putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(volume))
})

@UnstableApi
private fun speedFx(e: VEdit): List<Effect> = if (e.speed != 1f) listOf(SpeedChangeEffect(e.speed)) else emptyList()

/** Audio for speed (pitch kept) and volume. Processors hold state, so every clip/preview needs a fresh list. */
@UnstableApi
private fun audioFx(e: VEdit): List<AudioProcessor> = buildList {
    if (e.speed != 1f) add(SonicAudioProcessor().apply { setSpeed(e.speed) })
    if (e.volume != 1f && !e.mute) add(ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1).scaleBy(e.volume))
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2).scaleBy(e.volume))
    })
}

/** CapCut-style editor: preview, frame timeline with trim/cut handles, and a scrolling tool bar. Always saves a copy. */
@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun VideoEditor(vm: GalleryViewModel, m: Media, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Media3's editing player: plays the same clips + effects the export renders, so the preview matches the result
    val player = remember(m.uri) { CompositionPlayer.Builder(ctx).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }

    var e by remember { mutableStateOf(VEdit()) }
    var tool by remember { mutableStateOf(VTool.TRIM) }
    var duration by remember { mutableLongStateOf(m.duration.coerceAtLeast(1)) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var previewScale by remember { mutableFloatStateOf(1f) }
    var previewH by remember { mutableIntStateOf(0) }
    val liveColor = remember { LiveColor() }
    var thumbs by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var adj by remember { mutableIntStateOf(0) } // 0 brightness, 1 contrast, 2 saturation
    var cropFrame by remember { mutableStateOf<Bitmap?>(null) }
    var cropRect by remember { mutableStateOf(Rect.Zero) }
    var cropRatio by remember { mutableStateOf<Float?>(null) }
    val changed = e != VEdit()
    var res by remember { mutableIntStateOf(0) } // 0 = original quality, else the short side in pixels
    var resMenu by remember { mutableStateOf(false) }
    var vidShort by remember { mutableIntStateOf(0) }
    var vidH by remember { mutableIntStateOf(0) } // shown height, after the video's own rotation
    // undo / redo: quick runs of changes (a slider drag) count as one step
    val undo = remember { mutableStateListOf<VEdit>() }
    val redo = remember { mutableStateListOf<VEdit>() }
    val lastPush = remember { longArrayOf(0L) }
    fun set(n: VEdit) {
        if (n == e) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPush[0] > 600) { undo.add(e); redo.clear() }
        lastPush[0] = now
        e = n
    }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            val name = runCatching {
                ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "Music"
            set(e.copy(music = u, musicName = name.substringBeforeLast('.')))
        }
    }

    // video shape + timeline thumbnails, once
    LaunchedEffect(m.uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(ctx, m.uri)
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 16f
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 9f
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: duration
                    withContext(Dispatchers.Main) {
                        aspect = if (rot % 180 == 0) w / h else h / w; duration = d.coerceAtLeast(1)
                        vidShort = minOf(w, h).toInt(); vidH = (if (rot % 180 == 0) h else w).toInt()
                        // preview renders at most 720p (short side); blur shrinks with it so it looks like the export
                        val short = minOf(w, h)
                        previewScale = if (short > 720f) 720f / short else 1f
                        previewH = if (previewScale < 1f) (((if (rot % 180 == 0) h else w) * previewScale).toInt() / 2 * 2) else 0
                    }
                    val n = 10
                    // thumbnails appear one by one as they decode
                    for (i in 0 until n) {
                        val f = r.getScaledFrameAtTime(d * 1000L * i / n, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160) ?: continue
                        val img = f.asImageBitmap()
                        withContext(Dispatchers.Main) { thumbs = thumbs + img }
                    }
                }
            }
        }
    }

    // preview plays the whole video (so the timeline maps 1:1) with every effect, speed and volume applied;
    // trim and cut are enforced by seeking. Positions below are in the ORIGINAL video's time.
    var speedNow by remember { mutableFloatStateOf(1f) }
    fun posMs() = (player.currentPosition * speedNow).toLong()
    fun seekMs(ms: Long) = player.seekTo((ms / speedNow).toLong())
    // only shape/speed changes rebuild the player; colour and volume are applied live below (rebuilding is what lagged)
    val previewKey = VEdit(
        crop = e.crop, canvas = e.canvas, blurBg = e.blurBg, speed = e.speed, turns = e.turns, flip = e.flip,
        text = e.text, textColor = e.textColor, textPos = e.textPos, textSize = e.textSize,
    )
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(previewKey, aspect, duration, previewH, vidH) {
        if (!first) delay(250) // let a tap settle before rebuilding
        val wasPlaying = player.isPlaying
        val pos = if (first) 0L else posMs()
        runCatching {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(m.uri)).setDurationUs(duration * 1000)
                .setEffects(Effects(audioFx(previewKey), videoEffects(previewKey, aspect, liveColor, previewH, 12f * previewScale, if (previewH > 0) previewH else vidH) + speedFx(previewKey))).build()
            player.setComposition(Composition.Builder(EditedMediaItemSequence.Builder(listOf(item)).build()).build(), (pos / previewKey.speed).toLong())
            speedNow = previewKey.speed
            player.prepare()
            if (wasPlaying) player.play()
            first = false
        }.onFailure { android.util.Log.w("Gallery", "preview failed", it) }
    }
    LaunchedEffect(e.mute, e.volume, e.originalSound) { player.volume = if (e.mute || !e.originalSound) 0f else e.volume.coerceAtMost(1f) }
    LaunchedEffect(e.filter, e.brightness, e.contrast, e.saturation) {
        liveColor.set(e)
        // paused: redraw the current frame so the change shows; playing frames pick it up by themselves
        if (!player.isPlaying) { delay(40); player.seekTo(player.currentPosition) }
    }

    val partRemoved by remember { derivedStateOf { e.partAt(position.toFloat() / duration).first in e.removed } }
    val startMs = (e.start * duration).toLong()
    val endMs = (e.end * duration).toLong()
    // music preview: a second player that follows play/pause
    val music = remember { ExoPlayer.Builder(ctx).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
    DisposableEffect(music) { onDispose { music.release() } }
    LaunchedEffect(e.music) {
        val u = e.music
        if (u == null) music.clearMediaItems() else { music.setMediaItem(MediaItem.fromUri(u)); music.prepare() }
    }
    LaunchedEffect(e.musicVolume) { music.volume = e.musicVolume.coerceIn(0f, 1f) }
    LaunchedEffect(playing, e.music) {
        if (e.music == null) return@LaunchedEffect
        if (playing) { music.seekTo((posMs() - startMs).coerceAtLeast(0L)); music.play() } else music.pause()
    }
    // keep playback inside the trim and skip the cut part; polls only while this screen is open
    LaunchedEffect(player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        })
        while (true) {
            position = posMs()
            val ed = e
            // jump over deleted parts while playing (paused, you can scrub into one to restore it)
            val part = ed.partAt(position.toFloat() / duration)
            if (playing && part.first in ed.removed) seekMs((part.second * duration).toLong())
            if (playing && position >= (ed.end * duration).toLong()) { player.pause(); seekMs((ed.start * duration).toLong()) }
            delay(80)
        }
    }

    fun export() {
        player.pause()
        progress = 0
        val out = File(ctx.cacheDir, "edit_${System.currentTimeMillis()}.mp4")
        val fx = videoEffects(e, aspect, frameH = vidH) + speedFx(e) + (if (res > 0) listOf(Presentation.createForShortSide(res)) else emptyList())
        fun clip(fromMs: Long, toMs: Long) = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(m.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(fromMs).setEndPositionMs(toMs).build(),
            ).build(),
        ).setRemoveAudio(e.mute || !e.originalSound).setEffects(Effects(audioFx(e), fx)).build()
        val clips = e.kept().map { (a, b) -> (a * duration).toLong() to (b * duration).toLong() }
            .filter { (a, b) -> b - a > 200 }.map { (a, b) -> clip(a, b) }
        if (clips.isEmpty()) { progress = null; vm.toast("Nothing left after the cut"); return }
        val sequences = mutableListOf(EditedMediaItemSequence.Builder(clips).build())
        e.music?.let { u ->
            sequences += EditedMediaItemSequence.Builder(
                listOf(EditedMediaItem.Builder(MediaItem.fromUri(u)).setRemoveVideo(true).setEffects(Effects(musicFx(e.musicVolume), emptyList())).build()),
            ).setIsLooping(true).build()
        }
        val composition = Composition.Builder(sequences).build()
        val transformer = Transformer.Builder(ctx).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                scope.launch {
                    val ok = vm.saveVideoCopy(m, out)
                    out.delete(); progress = null
                    vm.toast(if (ok) "Saved as a copy" else "Couldn't save the video")
                    if (ok) onBack()
                }
            }
            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                android.util.Log.w("Gallery", "video export failed", exception)
                out.delete(); progress = null
                vm.toast("Couldn't export this video")
            }
        }).build()
        transformer.start(composition, out.absolutePath)
        scope.launch {
            val holder = ProgressHolder()
            while (progress != null) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = holder.progress
                delay(250)
            }
        }
    }

    fun grabFrame(full: Boolean, then: (Bitmap?) -> Unit) = scope.launch {
        val at = posMs()
        then(withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(ctx, m.uri)
                    if (full) r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    else r.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 1280, 1280)
                }
            }.getOrNull()
        })
    }

    // crop works on a still of the current frame
    LaunchedEffect(tool) {
        if (tool == VTool.CROP) {
            player.pause()
            grabFrame(false) { f ->
                cropFrame = f
                f?.let { b ->
                    cropRect = Rect(e.crop.left * b.width, e.crop.top * b.height, e.crop.right * b.width, e.crop.bottom * b.height)
                }
            }
        }
    }
    fun commitCrop() {
        val b = cropFrame ?: return
        if (cropRect.width <= 0f) return
        set(e.copy(crop = android.graphics.RectF(cropRect.left / b.width, cropRect.top / b.height, cropRect.right / b.width, cropRect.bottom / b.height)))
    }

    BackHandler(progress == null) { if (changed) confirmExit = true else onBack() }
    val resOptions = remember(vidShort) { listOf(0) + listOf(1080, 720, 480).filter { vidShort == 0 || it < vidShort } }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        // top: Cancel · quality · Save, like the phone's own video editor
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            VPill("Cancel", Color(0xFF2C2C2E), Color.White, enabled = progress == null) { if (changed) confirmExit = true else onBack() }
            Box(Modifier.weight(1f), Alignment.Center) {
                TextButton({ resMenu = true }) {
                    Text(if (res == 0) "Original" else "${res}p", color = Color.White.copy(.8f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Outlined.ArrowDropDown, "Choose quality", tint = Color.White.copy(.8f))
                }
                AppMenu(resMenu, { resMenu = false }) {
                    resOptions.forEach { r ->
                        ChoiceItem(if (r == 0) "Original quality" else "${r}p", res == r) { res = r; resMenu = false }
                    }
                }
            }
            VPill("Save", if (changed) VAccent else VAccent.copy(alpha = .35f), MaterialTheme.colorScheme.onPrimary, enabled = changed && progress == null) {
                if (tool == VTool.CROP) commitCrop(); export()
            }
        }
        // preview
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), Alignment.Center) {
            val frame = cropFrame
            if (tool == VTool.CROP && frame != null) {
                CropView(remember(frame) { frame.asImageBitmap() }, cropRect, cropRatio, interactive = true, filter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix())) {
                    cropRect = it
                }
            } else {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player; useController = false } },
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { if (player.isPlaying) player.pause() else { if (posMs() >= endMs) seekMs(startMs); player.play() } }
                    },
                )
                if (!playing) Icon(
                    Icons.Default.PlayArrow, "Play",
                    Modifier.size(64.dp).background(Color.Black.copy(.45f), RoundedCornerShape(50)).padding(12.dp), tint = Color.White,
                )
            }
                }
        // time · play · undo / redo
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${clock(position)} / ${clock(duration)}", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            IconButton({ if (player.isPlaying) player.pause() else { if (posMs() >= endMs) seekMs(startMs); player.play() } }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(34.dp), tint = Color.White)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                IconButton({ redo.add(e); e = undo.removeAt(undo.lastIndex) }, enabled = undo.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, "Undo", tint = if (undo.isNotEmpty()) Color.White else Color.White.copy(.3f))
                }
                IconButton({ undo.add(e); e = redo.removeAt(redo.lastIndex) }, enabled = redo.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, "Redo", tint = if (redo.isNotEmpty()) Color.White else Color.White.copy(.3f))
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF1C1C1E))) {
            // panel for the selected tool
            Box(Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 16.dp, vertical = 8.dp), Alignment.Center) {
                ToolPanel(
                    tool, e, onChange = { set(it) }, adj = adj, onAdj = { adj = it },
                    at = { position.toFloat() / duration }, partRemoved = partRemoved,
                    thumb = thumbs.firstOrNull(), cropRatio = cropRatio,
                    onCropRatio = { r ->
                        cropRatio = r
                        val b = cropFrame ?: return@ToolPanel
                        val want = r ?: return@ToolPanel
                        var w = b.width.toFloat(); var h = w / want
                        if (h > b.height) { h = b.height.toFloat(); w = h * want }
                        cropRect = Rect(Offset((b.width - w) / 2, (b.height - h) / 2), Size(w, h))
                        commitCrop()
                    },
                    onSnapshot = {
                        grabFrame(true) { f ->
                            if (f == null) vm.toast("Couldn't grab this frame")
                            else scope.launch { vm.toast(if (vm.saveFrame(m, f, posMs())) "Frame saved as a photo" else "Couldn't save this frame") }
                        }
                    },
                    onPickMusic = { pickMusic.launch("audio/*") },
                )
            }
            Timeline(
                thumbs, e, { position.toFloat() / duration },
                onTrim = { s, en -> set(e.copy(start = s, end = en)); seekMs(((if (s != e.start) s else en) * duration).toLong()) },
                onSeek = { f -> seekMs((f * duration).toLong()) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            // tool tabs, the chosen one in yellow
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                items(VTool.entries) { t ->
                    val sel = t == tool
                    Column(
                        Modifier.width(68.dp).clip(RoundedCornerShape(12.dp))
                            .clickable { if (tool == VTool.CROP) commitCrop(); tool = t }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(t.icon, null, Modifier.size(26.dp), tint = if (sel) VAccent else Color.White.copy(.85f))
                        Spacer(Modifier.height(4.dp))
                        Text(t.label, color = if (sel) VAccent else Color.White.copy(.85f), fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    progress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting video…") },
            text = {
                Column {
                    LinearProgressIndicator({ p / 100f }, Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(8.dp))
                    Text("$p%  ·  keep Gallery open")
                }
            },
            confirmButton = {},
        )
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text("Discard changes?") },
        confirmButton = { TextButton({ confirmExit = false; onBack() }) { Text("Discard") } },
        dismissButton = { TextButton({ confirmExit = false }) { Text("Keep editing") } },
    )
}

/** Frame strip with white trim handles, split lines, red deleted parts, and a playhead. Drag anywhere else to scrub. */
@Composable
private fun Timeline(
    thumbs: List<ImageBitmap>, e: VEdit, playhead: () -> Float, // lambda: playhead ticks redraw only the strip
    onTrim: (Float, Float) -> Unit, onSeek: (Float) -> Unit, modifier: Modifier,
) {
    val grab = with(LocalDensity.current) { 24.dp.toPx() }
    val primary = VAccent // playhead in the accent colour
    val cur by rememberUpdatedState(e)
    Box(modifier.fillMaxWidth().height(64.dp)) {
        Row(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(0xFF23252A))) {
            thumbs.forEach { Image(it, null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop) }
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var mode = 0 // 1 start, 2 end, 5 scrub
                detectDragGestures(
                    onDragStart = { p ->
                        val w = size.width.toFloat()
                        val ed = cur
                        mode = when {
                            kotlin.math.abs(p.x - ed.start * w) < grab -> 1
                            kotlin.math.abs(p.x - ed.end * w) < grab -> 2
                            else -> 5
                        }
                        if (mode == 5) onSeek((p.x / w).coerceIn(0f, 1f))
                    },
                    onDrag = { ch, _ ->
                        ch.consume()
                        val f = (ch.position.x / size.width).coerceIn(0f, 1f)
                        val ed = cur
                        val gap = 0.02f
                        when (mode) {
                            1 -> onTrim(f.coerceAtMost(ed.end - gap), ed.end)
                            2 -> onTrim(ed.start, f.coerceAtLeast(ed.start + gap))
                            5 -> onSeek(f)
                        }
                    },
                )
            },
        ) {
            val w = size.width; val h = size.height
            val dim = Color.Black.copy(alpha = .6f)
            drawRect(dim, Offset.Zero, Size(e.start * w, h))
            drawRect(dim, Offset(e.end * w, 0f), Size(w - e.end * w, h))
            e.parts().forEach { (a, b) ->
                if (a in e.removed) drawRect(Color(0xFFE53935).copy(alpha = .55f), Offset(a * w, 0f), Size((b - a) * w, h))
            }
            e.splits.forEach { x -> drawLine(Color.White, Offset(x * w, 0f), Offset(x * w, h), 2.dp.toPx()) }
            // trim frame + handles
            drawRoundRect(Color.White, Offset(e.start * w, 0f), Size((e.end - e.start) * w, h), CornerRadius(10.dp.toPx()), style = Stroke(3.dp.toPx()))
            listOf(e.start * w, e.end * w).forEach { x ->
                drawRoundRect(Color.White, Offset(x - 6.dp.toPx(), 0f), Size(12.dp.toPx(), h), CornerRadius(6.dp.toPx()))
            }
            // playhead
            val x = playhead().coerceIn(0f, 1f) * w
            drawLine(primary, Offset(x, -4.dp.toPx()), Offset(x, h + 4.dp.toPx()), 3.dp.toPx())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolPanel(
    tool: VTool, e: VEdit, onChange: (VEdit) -> Unit, adj: Int, onAdj: (Int) -> Unit, thumb: ImageBitmap?,
    cropRatio: Float?, onCropRatio: (Float?) -> Unit, onSnapshot: () -> Unit, onPickMusic: () -> Unit,
    at: () -> Float, partRemoved: Boolean,
) {
    val white = Color.White
    @Composable fun hint(t: String) = Text(t, color = white.copy(.75f), style = MaterialTheme.typography.bodySmall)
    @Composable fun chip(label: String, sel: Boolean, onClick: () -> Unit) = Box(
        Modifier.clip(CircleShape).background(if (sel) VAccent else Color(0xFF2C2C2E)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, color = if (sel) MaterialTheme.colorScheme.onPrimary else white, fontSize = 13.sp) }
    when (tool) {
        VTool.TRIM -> hint("Drag the white handles on the timeline to choose the part to keep")
        VTool.MUSIC -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = if (e.music == null) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            if (e.music == null) {
                DarkAction(Icons.Outlined.LibraryMusic, "Add music from phone", onClick = onPickMusic)
                hint("The song plays under the video and repeats if it's shorter")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.MusicNote, null, tint = VAccent)
                    Text(e.musicName, color = white, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onPickMusic) { Text("Change", color = VAccent) }
                    TextButton({ onChange(e.copy(music = null, musicName = "")) }) { Text("Remove", color = white) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Music", color = white, modifier = Modifier.width(56.dp))
                    AppSlider(e.musicVolume, { onChange(e.copy(musicVolume = it)) }, Modifier.weight(1f), label = "Music volume", color = VAccent, track = white.copy(.25f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Original sound", color = white, modifier = Modifier.weight(1f))
                    Switch(e.originalSound, { onChange(e.copy(originalSound = it)) })
                }
            }
        }
        VTool.TEXT -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                e.text, { onChange(e.copy(text = it)) },
                Modifier.fillMaxWidth().clip(CircleShape).background(Color(0xFF2C2C2E)).padding(horizontal = 18.dp, vertical = 12.dp),
                singleLine = true, textStyle = TextStyle(color = white, fontSize = 16.sp), cursorBrush = SolidColor(VAccent),
                decorationBox = { inner -> Box { if (e.text.isEmpty()) Text("Type text for the video", color = white.copy(.45f), fontSize = 16.sp); inner() } },
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(TEXT_COLORS) { c ->
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == e.textColor) 3.dp else 1.dp, if (c == e.textColor) VAccent else white.copy(.4f), CircleShape)
                            .clickable { onChange(e.copy(textColor = c)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Top" to 0.12f, "Middle" to 0.5f, "Bottom" to 0.85f).forEach { (l, p) -> chip(l, e.textPos == p) { onChange(e.copy(textPos = p)) } }
                Spacer(Modifier.width(8.dp))
                listOf("S" to 0.04f, "M" to 0.06f, "L" to 0.09f).forEach { (l, z) -> chip(l, e.textSize == z) { onChange(e.copy(textSize = z)) } }
            }
        }
        VTool.CUT -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DarkAction(Icons.Outlined.ContentCut, "Split here") { onChange(e.split(at())) }
                DarkAction(
                    if (partRemoved) Icons.Outlined.RestoreFromTrash else Icons.Outlined.Delete,
                    if (partRemoved) "Restore part" else "Delete part", enabled = e.splits.isNotEmpty(),
                ) { onChange(e.togglePart(at())) }
            }
            hint(if (e.splits.isEmpty()) "Move the timeline to where you want to cut, then tap Split here" else "Move into a part and delete it; red parts are left out")
        }
        VTool.CANVAS -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(VCanvas.entries) { c -> chip(c.label, e.canvas == c) { onChange(e.copy(canvas = c)) } }
            }
            if (e.canvas != VCanvas.ORIGINAL) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Background", color = white.copy(.8f), style = MaterialTheme.typography.bodySmall)
                chip("Blur", e.blurBg) { onChange(e.copy(blurBg = true)) }
                chip("Black", !e.blurBg) { onChange(e.copy(blurBg = false)) }
            }
        }
        VTool.CROP -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf<Pair<String, Float?>>("Free" to null, "1:1" to 1f, "4:5" to 0.8f, "9:16" to 9f / 16f, "16:9" to 16f / 9f, "4:3" to 4f / 3f)) { (l, r) ->
                    chip(l, cropRatio == r) { onCropRatio(r) }
                }
            }
            hint("Drag the box or its corners on the picture above")
        }
        VTool.SPEED -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(V_SPEEDS) { s -> chip(speedText(s), e.speed == s) { onChange(e.copy(speed = s)) } }
            }
            hint("Slow motion or fast forward; sound keeps its pitch")
        }
        VTool.FILTERS -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Filter.entries) { f ->
                val sel = f == e.filter
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onChange(e.copy(filter = f)) }) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF23252A))
                            .then(if (sel) Modifier.border(2.dp, VAccent, RoundedCornerShape(10.dp)) else Modifier),
                    ) {
                        thumb?.let {
                            Image(it, f.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                colorFilter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(colorMatrix(f, 0f, 0f, 0f, 0f))))
                        }
                    }
                    Text(f.label, color = if (sel) VAccent else white, fontSize = 11.sp)
                }
            }
        }
        VTool.ADJUST -> Column {
            val names = listOf("Brightness", "Contrast", "Saturation")
            val v = when (adj) { 0 -> e.brightness; 1 -> e.contrast; else -> e.saturation }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { names.forEachIndexed { i, n -> chip(n, adj == i) { onAdj(i) } } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSlider(v, { x ->
                    onChange(when (adj) { 0 -> e.copy(brightness = x); 1 -> e.copy(contrast = x); else -> e.copy(saturation = x) })
                }, Modifier.weight(1f), range = -1f..1f, label = names[adj], color = VAccent, track = white.copy(.25f))
                Text("${(v * 100).toInt()}", color = white, modifier = Modifier.width(40.dp), fontWeight = FontWeight.SemiBold)
            }
        }
        VTool.VOLUME -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (e.mute) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, null, tint = white)
                AppSlider(e.volume, { onChange(e.copy(volume = it, mute = false)) }, Modifier.weight(1f).padding(horizontal = 8.dp), range = 0f..2f, enabled = !e.mute, label = "Volume", color = VAccent, track = white.copy(.25f))
                Text("${(e.volume * 100).toInt()}%", color = white, modifier = Modifier.width(48.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mute (remove sound)", color = white, modifier = Modifier.weight(1f))
                Switch(e.mute, { onChange(e.copy(mute = it)) })
            }
        }
        VTool.ROTATE -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarAction(Icons.AutoMirrored.Outlined.RotateLeft, "Left", white) { onChange(e.copy(turns = (e.turns + 3) % 4)) }
            BarAction(Icons.AutoMirrored.Outlined.RotateRight, "Right", white) { onChange(e.copy(turns = (e.turns + 1) % 4)) }
            BarAction(Icons.Outlined.Flip, if (e.flip) "Flipped" else "Mirror", white) { onChange(e.copy(flip = !e.flip)) }
        }
        VTool.FRAME -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DarkAction(Icons.Outlined.PhotoCamera, "Save this frame as a photo", onClick = onSnapshot)
            hint("Pause on the moment you want, then tap")
        }
    }
}

private fun speedText(s: Float) = (if (s % 1f == 0f) "${s.toInt()}" else "$s".trimEnd('0')) + "x"

@Composable
private fun VPill(label: String, bg: Color, fg: Color, enabled: Boolean = true, onClick: () -> Unit) = Box(
    Modifier.clip(CircleShape).background(if (enabled) bg else Color(0xFF2C2C2E)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 22.dp, vertical = 10.dp),
) { Text(label, color = if (enabled) fg else Color.White.copy(alpha = .35f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
