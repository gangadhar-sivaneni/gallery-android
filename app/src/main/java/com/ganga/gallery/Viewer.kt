package com.ganga.gallery

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.runtime.withFrameMillis
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMMM")
private val DATE_SHORT = DateTimeFormatter.ofPattern("d MMM")
private val DATE_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy")
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a")

/** Video picture shapes. Fixed ratios letterbox the player into that shape and stretch to fill it. */
enum class VideoAspect(val label: String, val ratio: Float?, val mode: Int) {
    FIT("Fit", null, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    CROP("Crop to fill", null, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", null, AspectRatioFrameLayout.RESIZE_MODE_FILL),
    R16_9("16:9", 16f / 9f, AspectRatioFrameLayout.RESIZE_MODE_FILL),
    R4_3("4:3", 4f / 3f, AspectRatioFrameLayout.RESIZE_MODE_FILL),
    R1_1("1:1", 1f, AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private fun speedLabel(s: Float) = (if (s % 1f == 0f) "${s.toInt()}" else "$s".trimEnd('0')) + "x"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Viewer(vm: GalleryViewModel, s: Screen.Viewer, ask: Ask, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = ctx as Activity
    val scope = rememberCoroutineScope()
    val landscape = isLandscape()
    val items = remember(vm.all, vm.trash, vm.vault, vm.sortBy, vm.ascending, vm.kinds, vm.hiddenItems, vm.hiddenAlbums, vm.showHidden) {
        vm.items(s.source, s.query)
    }
    if (items.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val start = remember { items.indexOfFirst { it.id == s.startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { items.size }
    val cur = items[pager.currentPage.coerceAtMost(items.lastIndex)]
    val inTrash = s.source == Source.Trash
    val inVault = s.source == Source.Vault
    // a file from another app may not be in MediaStore: only offer actions that work on any file
    val external = s.source is Source.Single && vm.all.none { it.uri == cur.uri }
    val full = !inTrash && !inVault && !external // every action available
    val leave = { if (s.exitOnBack) activity.finish() else onBack() }
    var chrome by remember { mutableStateOf(!s.slideshow) }
    var more by remember { mutableStateOf(false) }
    var moreAll by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var slideshow by remember { mutableStateOf(s.slideshow) }
    var rotationLocked by remember { mutableStateOf(false) }
    var confirmVaultDelete by remember { mutableStateOf(false) }
    // video controls
    var videoLocked by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(VideoAspect.FIT) }
    var speed by remember { mutableFloatStateOf(1f) }
    var aspectMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    val pipSupported = remember { ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    BackHandler(!slideshow && !videoLocked) { leave() }
    BackHandler(slideshow) { slideshow = false; chrome = true }
    BackHandler(videoLocked) { vm.toast("Screen is locked. Tap the lock to unlock.") }
    ImmersiveMode(!chrome || videoLocked)

    // slideshow: advance every N seconds, wrap to the start
    LaunchedEffect(slideshow, pager.currentPage) {
        if (!slideshow) return@LaunchedEffect
        delay(vm.slideSeconds * 1000L)
        pager.animateScrollToPage(if (pager.currentPage >= items.lastIndex) 0 else pager.currentPage + 1)
    }
    // leaving a video resets its controls
    LaunchedEffect(pager.currentPage) { videoLocked = false; aspect = VideoAspect.FIT; speed = 1f }
    DisposableEffect(slideshow, cur.isVideo, vm.keepScreenOn) {
        view.keepScreenOn = slideshow || cur.isVideo || vm.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    fun rotateScreen() {
        activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        rotationLocked = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            pager, Modifier.fillMaxSize(), key = { items[it].id }, beyondViewportPageCount = 1,
            userScrollEnabled = !videoLocked,
        ) { page ->
            val m = items[page]
            val current = page == pager.currentPage
            if (m.isVideo) VideoPage(vm, m, active = current && !slideshow, locked = videoLocked && current, aspect = aspect, speed = speed, controls = chrome) { chrome = it }
            else ZoomableImage(m, current, vm.imageEpoch) {
                if (slideshow) slideshow = false
                chrome = !chrome
            }
        }

        if (videoLocked && !vm.inPip) LockOverlay { videoLocked = false; chrome = true }

        AnimatedVisibility(chrome && !videoLocked && !vm.inPip, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            TopAppBar(
                navigationIcon = { IconButton(leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    val at = Instant.ofEpochMilli(cur.date).atZone(ZoneId.systemDefault())
                    Column {
                        Text(
                            if (external) cur.name else (if (cur.isVideo) DATE_SHORT else if (at.year == java.time.Year.now().value) DATE else DATE_YEAR).format(at),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (!external) Text(CLOCK.format(at), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(.75f), maxLines = 1)
                    }
                },
                actions = {
                    if (cur.isVideo) {
                        Box {
                            TextButton({ speedMenu = true }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                                Text(speedLabel(speed), fontWeight = FontWeight.SemiBold)
                            }
                            AppMenu(speedMenu, { speedMenu = false }) {
                                SPEEDS.forEach { sp ->
                                    ChoiceItem(speedLabel(sp), speed == sp) { speedMenu = false; speed = sp }
                                }
                            }
                        }
                        if (pipSupported) IconButton({ vm.pipParams?.let { runCatching { activity.enterPictureInPictureMode(it) } } }) {
                            Icon(Icons.Outlined.PictureInPictureAlt, "Picture in picture")
                        }
                    }
                    // files from other apps have Save / Open with in the bottom bar; videos still get a menu for their player options
                    if (!external || cur.isVideo) Box {
                        IconButton({ more = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        AppMenu(aspectMenu, { aspectMenu = false }) {
                            VideoAspect.entries.forEach { a -> ChoiceItem(a.label, aspect == a) { aspectMenu = false; aspect = a } }
                        }
                        AppMenu(more, { more = false; moreAll = false }) {
                            val close = { more = false; moreAll = false }
                            val photo = !cur.isVideo
                            if (cur.isVideo) {
                                MenuItem("Aspect ratio: ${aspect.label}", Icons.Outlined.AspectRatio) { close(); aspectMenu = true }
                                MenuItem("Lock controls", Icons.Outlined.Lock) { close(); videoLocked = true; chrome = false }
                            }
                            // common actions first; the rest sit behind "View more"
                            if (cur.isVideo) MenuItem(if (landscape) "Portrait" else "Landscape", Icons.Outlined.ScreenRotation) { close(); rotateScreen() }
                            if (photo && (full || inVault)) MenuItem("Slideshow", Icons.Outlined.Slideshow) { close(); slideshow = true; chrome = false }
                            if (photo && full && (cur.mime == "image/jpeg" || cur.mime == "image/png")) MenuItem("Rotate", Icons.Outlined.RotateRight) {
                                close(); ask(vm.writeRequest(listOf(cur))) { vm.rotate(listOf(cur)) }
                            }
                            if (photo && full && !cur.isGif) MenuItem("Set as wallpaper", Icons.Outlined.Wallpaper) { close(); dialog = Dlg.Wallpaper(cur) }
                            if (full) {
                                MenuItem("Move to album", Icons.Outlined.DriveFileMove) { close(); dialog = Dlg.Move(listOf(cur), false) }
                                MenuItem("Copy to album", Icons.Outlined.ContentCopy) { close(); dialog = Dlg.Move(listOf(cur), true) }
                                MenuItem("Rename", Icons.Outlined.DriveFileRenameOutline) { close(); dialog = Dlg.Rename(cur) }
                                MenuItem("Move to Private", Icons.Outlined.Shield) { close(); vm.moveToVault(listOf(cur), ask) }
                            }
                            if (!moreAll) MenuItem("View more", Icons.Outlined.ExpandMore) { moreAll = true }
                            else {
                                HorizontalDivider()
                                if (full) {
                                    val hidden = vm.isHidden(cur)
                                    MenuItem(if (hidden) "Unhide" else "Hide", if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff) {
                                        close(); vm.setHidden(listOf(cur), !hidden)
                                    }
                                    if (photo) MenuItem("Set as album cover", Icons.Outlined.Image) { close(); vm.setAlbumCover(cur.bucketId, cur.id) }
                                    if (photo) MenuItem("Set as…", Icons.Outlined.AccountBox) { close(); setAs(ctx, cur) }
                                    if (photo && !cur.isGif) MenuItem("Resize", Icons.Outlined.PhotoSizeSelectLarge) { close(); dialog = Dlg.Resize(cur) }
                                    if (photo) MenuItem("Print", Icons.Outlined.Print) { close(); print(ctx, cur) }
                                    MenuItem("Copy to clipboard", Icons.Outlined.ContentPaste) { close(); copyToClipboard(ctx, cur) }
                                    MenuItem("Open with…", Icons.AutoMirrored.Outlined.OpenInNew) { close(); openWith(ctx, cur) }
                                    MenuItem("Create shortcut", Icons.Outlined.AddToHomeScreen) {
                                        close(); scope.launch(Dispatchers.IO) { createShortcut(ctx, cur) }
                                    }
                                }
                                MenuItem(if (rotationLocked) "Unlock rotation" else "Lock rotation", Icons.Outlined.ScreenLockRotation) {
                                    close()
                                    rotationLocked = !rotationLocked
                                    activity.requestedOrientation =
                                        if (rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                                if (full) MenuItem("Settings", Icons.Outlined.Settings) { close(); vm.stack.add(Screen.Settings) }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                ),
                modifier = Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(.7f), Color.Transparent))),
            )
        }

        // like the phone's Photos app: round Share | Favourite · Edit · Delete pill | round Details
        AnimatedVisibility(chrome && !videoLocked && !vm.inPip, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            val info = Act(Icons.Outlined.Info, "Details") { dialog = Dlg.Info(cur) }
            when {
                external -> ViewerBar(
                    Act(Icons.Outlined.Share, "Share") { share(ctx, listOf(cur)) },
                    listOf(
                        Act(Icons.Outlined.SaveAlt, "Save to gallery") { vm.saveToGallery(cur) },
                        Act(Icons.Outlined.Edit, "Edit with") { edit(ctx, cur) },
                    ),
                    Act(Icons.AutoMirrored.Outlined.OpenInNew, "Open with") { openWith(ctx, cur) },
                )
                inVault -> ViewerBar(
                    null,
                    listOf(
                        Act(Icons.Outlined.LockOpen, "Move out of Private") { vm.restoreFromVault(listOf(cur)) },
                        Act(Icons.Outlined.DeleteForever, "Delete forever") { confirmVaultDelete = true },
                    ),
                    info,
                )
                inTrash -> ViewerBar(
                    null,
                    listOf(
                        Act(Icons.Outlined.RestoreFromTrash, "Restore") { ask(vm.trashRequest(listOf(cur), false), null) },
                        Act(Icons.Outlined.DeleteForever, "Delete forever") { ask(vm.deleteRequest(listOf(cur)), null) },
                    ),
                    info,
                )
                else -> ViewerBar(
                    Act(Icons.Outlined.Share, "Share") { share(ctx, listOf(cur)) },
                    listOfNotNull(
                        Act(
                            if (cur.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            if (cur.favorite) "Remove from favourites" else "Favourite",
                            if (cur.favorite) Color(0xFFFF5470) else Color.White,
                        ) { ask(vm.favoriteRequest(listOf(cur), !cur.favorite), null) },
                        when {
                            cur.isVideo -> Act(Icons.Outlined.Edit, "Edit video") { vm.stack.add(Screen.VideoEditor(cur)) }
                            !cur.isGif -> Act(Icons.Outlined.Edit, "Edit") { vm.stack.add(Screen.Editor(cur)) }
                            else -> null
                        },
                        Act(Icons.Outlined.Delete, "Delete") { ask(vm.trashRequest(listOf(cur), true), null) },
                    ),
                    info,
                )
            }
        }
    }

    if (confirmVaultDelete) AlertDialog(
        onDismissRequest = { confirmVaultDelete = false },
        title = { Text("Delete forever?") },
        text = { Text("Private items don't go to trash. This can't be undone.") },
        confirmButton = { TextButton({ confirmVaultDelete = false; vm.deleteFromVault(listOf(cur)) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmVaultDelete = false }) { Text("Cancel") } },
    )
    MediaDialogs(vm, dialog, ask, onDone = { dialog = null }, onDismiss = { dialog = null })
}

/** Swallows every touch while a video is locked; a tap reveals the unlock button for a few seconds. */
@Composable
private fun LockOverlay(onUnlock: () -> Unit) {
    var showButton by remember { mutableStateOf(true) }
    LaunchedEffect(showButton) { if (showButton) { delay(2500); showButton = false } }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                showButton = true
                do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
            }
        },
    ) {
        AnimatedVisibility(showButton, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            FilledTonalButton(onUnlock) {
                Icon(Icons.Outlined.LockOpen, null); Spacer(Modifier.width(8.dp)); Text("Tap to unlock")
            }
        }
    }
}

@Composable
private fun ImmersiveMode(hide: Boolean) {
    val view = LocalView.current
    DisposableEffect(hide) {
        val c = WindowCompat.getInsetsController((view.context as Activity).window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hide) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        onDispose { c.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Pinch / double-tap zoom and pan. Single-finger swipes pass through to the pager when not zoomed. */
@Composable
private fun ZoomableImage(m: Media, current: Boolean, epoch: Int, onTap: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(current) { if (!current) { scale = 1f; offset = Offset.Zero } }
    var failed by remember(m.uri) { mutableStateOf(false) }
    if (failed) {
        // e.g. TIFF/PSD or a damaged file: say so and offer another app instead of a blank screen
        Column(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.BrokenImage, null, Modifier.size(64.dp), tint = Color.White.copy(.7f))
            Spacer(Modifier.height(12.dp))
            Text("Can't preview this ${m.name.substringAfterLast('.', "file").uppercase()} file", color = Color.White)
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ openWith(ctx, m) }) { Text("Open with another app", color = Color.White) }
        }
        return
    }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1) / 2
        val my = size.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    // the layer scales round its centre, so to keep point [c] still while zooming by [k]: o' = (c - centre)(1 - k) + o·k
    fun zoomAt(c: Offset, k: Float, o: Offset) = zoomOffset(c, Offset(size.width / 2f, size.height / 2f), k, o)
    val scope = rememberCoroutineScope()
    fun animateTo(s1: Float, o1: Offset) {
        val s0 = scale; val o0 = offset
        scope.launch {
            androidx.compose.animation.core.animate(0f, 1f, animationSpec = androidx.compose.animation.core.tween(220)) { t, _ ->
                scale = s0 + (s1 - s0) * t
                offset = o0 + (o1 - o0) * t
            }
        }
    }
    AsyncImage(
        // ponytail: capped at 3072px to stay clear of OOM; tile-based decoding if 50MP+ zoom detail matters
        model = ImageRequest.Builder(ctx).data(m.uri).size(3072).memoryCacheKey("f${m.id}:${m.size}:${m.modified}:$epoch").build(),
        contentDescription = m.name,
        contentScale = ContentScale.Fit,
        onError = { failed = true },
        modifier = Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    // double-tap zooms smoothly into the tapped spot, and back out
                    onDoubleTap = { p ->
                        if (scale > 1f) animateTo(1f, Offset.Zero)
                        else animateTo(2.5f, clamp(zoomAt(p, 2.5f, Offset.Zero), 2.5f))
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val e = awaitPointerEvent()
                        if (e.changes.size > 1 || scale > 1f) {
                            // zoom round the point between the fingers, then follow the fingers' movement
                            val next = (scale * e.calculateZoom()).coerceIn(1f, 8f)
                            val c = e.calculateCentroid(useCurrent = true)
                            val k = next / scale
                            val zoomed = if (c.isSpecified) zoomAt(c, k, offset) else offset * k
                            offset = if (next == 1f) Offset.Zero else clamp(zoomed + e.calculatePan(), next)
                            scale = next
                            e.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (e.changes.any { it.pressed })
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoPage(
    vm: GalleryViewModel, m: Media, active: Boolean, locked: Boolean, aspect: VideoAspect, speed: Float,
    controls: Boolean, onControls: (Boolean) -> Unit,
) {
    if (!active) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            AsyncImage(m.uri, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Icon(Icons.Default.PlayCircle, null, Modifier.size(72.dp), tint = Color.White)
        }
        return
    }
    val ctx = LocalContext.current
    val player = remember(m.uri) {
        ExoPlayer.Builder(ctx).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().apply {
            // a same-named .srt/.vtt/.ass next to the video is offered as subtitles (needs All files access to read)
            setMediaItem(MediaItem.Builder().setUri(m.uri).setSubtitleConfigurations(sidecarSubtitles(m)).build())
            vm.videoPosition(m).takeIf { it > 0 }?.let { seekTo(it); vm.toast("Resumed at ${duration(it)}") }
            prepare()
            playWhenReady = vm.autoplay
            repeatMode = if (vm.loopVideos) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }
    val activity = ctx as Activity
    val pipSupported = remember { ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    var isPlaying by remember { mutableStateOf(player.playWhenReady) }
    var videoSize by remember { mutableStateOf(player.videoSize) }
    var hasSubs by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        // media session: gives the PiP window its play/pause buttons
        val session = runCatching { androidx.media3.session.MediaSession.Builder(ctx, player).setId("video-${m.id}").build() }.getOrNull()
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) { videoSize = v }
            override fun onTracksChanged(t: androidx.media3.common.Tracks) { hasSubs = t.containsType(androidx.media3.common.C.TRACK_TYPE_TEXT) }
        }
        player.addListener(l)
        onDispose {
            vm.saveVideoPosition(m, player.currentPosition, player.duration)
            vm.pipParams = null; vm.pipAuto = false
            if (pipSupported && android.os.Build.VERSION.SDK_INT >= 31) runCatching {
                activity.setPictureInPictureParams(android.app.PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
            player.removeListener(l)
            session?.release()
            player.release()
        }
    }
    // PiP shape follows the video; going Home while it plays drops into PiP
    LaunchedEffect(isPlaying, videoSize) {
        if (!pipSupported) return@LaunchedEffect
        val r = if (videoSize.width > 0 && videoSize.height > 0) videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height else 16f / 9f
        val b = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational((r.coerceIn(0.42f, 2.38f) * 1000).toInt(), 1000))
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(isPlaying).setSeamlessResizeEnabled(true)
        val p = b.build()
        vm.pipParams = p; vm.pipAuto = isPlaying
        runCatching { activity.setPictureInPictureParams(p) }
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (!activity.isInPictureInPictureMode) player.pause() // keep playing in the PiP window
        vm.saveVideoPosition(m, player.currentPosition, player.duration)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() } // PiP window closed
    // The video always fills the screen. Only the player's control strip is kept clear of the nav bar,
    // using the bar's size *ignoring visibility* so nothing moves when tapping shows/hides the bars.
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val bars = WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val padL = bars.getLeft(density, dir)
    val padR = bars.getRight(density, dir)
    val padB = bars.getBottom(density)
    val barBottom = with(density) { padB.toDp() } + VIEWER_BAR_SPACE
    // gestures: hold = 2x, swipe up/down on the left = brightness, on the right = volume.
    // Taps and sideways swipes are left alone so the controls and the pager still work.
    val audio = remember { ctx.getSystemService(android.media.AudioManager::class.java) }
    val window = activity.window
    val curSpeed by rememberUpdatedState(speed)
    var fast by remember { mutableStateOf(false) }
    var hudOn by remember { mutableStateOf(false) }
    var hudLeft by remember { mutableStateOf(true) }
    var hudLevel by remember { mutableFloatStateOf(0f) }
    var askWrite by remember { mutableStateOf(false) }
    val gestures = if (locked || vm.inPip) Modifier else Modifier.pointerInput(Unit) {
        val edge = 56.dp.toPx() // keep clear of the status bar pull-down and the seek bar
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.y < edge || down.position.y > size.height - edge * 4f) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            var drag = Offset.Zero
            var moved = false
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id }
                    if (e.changes.size > 1 || c == null || !c.pressed) return@withTimeoutOrNull false
                    drag = c.position - down.position
                    if (drag.getDistance() > slop) { moved = true; return@withTimeoutOrNull false }
                }
                @Suppress("UNREACHABLE_CODE") false
            } == null
            if (held) {
                fast = true; player.setPlaybackSpeed(2f)
                try {
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                } finally { fast = false; player.setPlaybackSpeed(curSpeed) } // also if the gesture is cancelled
            } else if (moved && kotlin.math.abs(drag.y) > kotlin.math.abs(drag.x)) {
                val left = down.position.x < size.width / 2f
                if (left && !android.provider.Settings.System.canWrite(ctx)) { askWrite = true; return@awaitEachGesture }
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val cr = ctx.contentResolver
                var level = if (left) android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                else audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
                hudLeft = left; hudLevel = level; hudOn = true
                if (left) {
                    // phone brightness, like the quick-settings slider: auto-brightness off, any "max brightness" override dropped
                    android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    vm.phoneBrightnessUsed = true
                    window.attributes = window.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                }
                var lastY = down.position.y + drag.y
                try { do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    e.changes.forEach { it.consume() }
                    // a swipe over ~60% of the screen height goes from 0 to full
                    level = (level - (c.position.y - lastY) / (size.height * 0.6f)).coerceIn(0f, 1f)
                    lastY = c.position.y
                    hudLevel = level
                    if (left) android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, (level * 255).toInt().coerceIn(1, 255))
                    else audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, kotlin.math.round(level * max).toInt(), 0)
                } while (c.pressed) } finally { hudOn = false } // never leave the indicator stuck on screen
            }
        }
    }
    // controls hide by themselves a moment after playback starts
    LaunchedEffect(controls, isPlaying) { if (controls && isPlaying && !locked) { delay(3500); onControls(false) } }
    val taps = if (locked || vm.inPip) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = { onControls(!controls) },
            // double-tap the left / right half to jump 10 s back / forward
            onDoubleTap = { p -> if (p.x < size.width / 2f) player.seekBack() else player.seekForward() },
        )
    }
    Box(Modifier.fillMaxSize().then(gestures).then(taps), Alignment.Center) {
        AndroidView(
            factory = {
                PlayerView(it).apply { this.player = player; useController = false }
            },
            update = { pv -> pv.resizeMode = aspect.mode },
            modifier = aspect.ratio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
        )
        AnimatedVisibility(controls && !locked && !vm.inPip, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            VideoControls(player, isPlaying, hasSubs, Modifier.padding(bottom = barBottom))
        }
        if (askWrite) AlertDialog(
            onDismissRequest = { askWrite = false },
            title = { Text("Control phone brightness") },
            text = { Text("To change the phone brightness by swiping, allow Gallery to \"Modify system settings\" on the next screen.") },
            confirmButton = {
                TextButton({
                    askWrite = false
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
                    }
                }) { Text("Allow") }
            },
            dismissButton = { TextButton({ askWrite = false }) { Text("Not now") } },
        )
        // only the control in use is shown, and only while it is being used: icon, thin bar, number, no box
        val shade = androidx.compose.ui.graphics.Shadow(Color.Black.copy(.8f), Offset(0f, 1f), 6f)
        AnimatedVisibility(fast, Modifier.align(Alignment.TopCenter).padding(top = 72.dp), enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text("2x", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(shadow = shade))
            }
        }
        AnimatedVisibility(
            hudOn && !vm.inPip, Modifier.align(if (hudLeft) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 36.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    when {
                        hudLeft -> Icons.Outlined.BrightnessMedium
                        hudLevel == 0f -> Icons.AutoMirrored.Outlined.VolumeOff
                        else -> Icons.AutoMirrored.Outlined.VolumeUp
                    },
                    null, tint = Color.White, modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(3.dp).height(110.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(.3f)), Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(hudLevel).background(Color.White))
                }
                Spacer(Modifier.height(6.dp))
                Text("${(hudLevel * 100).toInt()}", color = Color.White, style = MaterialTheme.typography.labelMedium.copy(shadow = shade))
            }
        }
    }
}

/** Subtitle files with the video's name in the same folder (movie.mp4 + movie.srt). Empty when none or unreadable. */
private fun sidecarSubtitles(m: Media): List<MediaItem.SubtitleConfiguration> = runCatching {
    val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), m.path)
    val base = m.name.substringBeforeLast('.')
    listOf(
        "srt" to androidx.media3.common.MimeTypes.APPLICATION_SUBRIP, "vtt" to androidx.media3.common.MimeTypes.TEXT_VTT,
        "ass" to androidx.media3.common.MimeTypes.TEXT_SSA, "ssa" to androidx.media3.common.MimeTypes.TEXT_SSA,
    ).mapNotNull { (ext, mime) ->
        java.io.File(dir, "$base.$ext").takeIf { it.canRead() }?.let {
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(it)).setMimeType(mime)
                .setLabel(ext.uppercase()).setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT).build()
        }
    }
}.getOrDefault(emptyList())

/** One button of the viewer's bottom bar. [label] is read by TalkBack. */
private class Act(val icon: ImageVector, val label: String, val tint: Color = Color.White, val onClick: () -> Unit)

/** Height the viewer bottom bar takes above the navigation bar; the video controls sit just above it. */
val VIEWER_BAR_SPACE = 84.dp

/** Dark floating bar: a round button left and right, the main actions grouped in a pill in the middle. */
@Composable
private fun ViewerBar(left: Act?, center: List<Act>, right: Act?) {
    val fill = Color(0xFF2A2A2C).copy(alpha = .92f)
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        if (left != null) RoundAct(left, fill, Modifier.align(Alignment.CenterStart))
        Row(
            Modifier.align(Alignment.Center).clip(CircleShape).background(fill).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            center.forEach { a ->
                IconButton(a.onClick, Modifier.size(48.dp)) {
                    // the heart pops when it fills in
                    AnimatedContent(
                        a.icon to a.tint, label = "pop",
                        transitionSpec = {
                            (scaleIn(spring(dampingRatio = .4f, stiffness = 500f), initialScale = .3f) + fadeIn()) togetherWith
                                (scaleOut(targetScale = 1.3f) + fadeOut())
                        },
                    ) { (icon, tint) -> Icon(icon, a.label, Modifier.size(26.dp), tint = tint) }
                }
            }
        }
        if (right != null) RoundAct(right, fill, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun RoundAct(a: Act, fill: Color, modifier: Modifier) = IconButton(
    a.onClick, modifier.size(56.dp), colors = IconButtonDefaults.iconButtonColors(containerColor = fill),
) { Icon(a.icon, a.label, Modifier.size(26.dp), tint = a.tint) }

/** Play/pause · time · seek bar · subtitles · mute, in one row above the bottom bar (like the phone's own player). */
@Composable
private fun VideoControls(player: Player, playing: Boolean, hasSubs: Boolean, modifier: Modifier) {
    var pos by remember { mutableLongStateOf(player.currentPosition) }
    var dur by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var muted by remember { mutableStateOf(player.volume == 0f) }
    var subsOn by remember { mutableStateOf(true) }
    // every frame while playing (smooth line), slower when paused
    LaunchedEffect(player, playing) {
        while (true) {
            pos = player.currentPosition; dur = player.duration.coerceAtLeast(0L)
            if (playing) withFrameMillis { } else delay(250)
        }
    }
    val fraction = { drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f }
    // the clock text only changes once a second, so it only recomposes once a second
    val secs by remember { derivedStateOf { ((drag?.let { it * dur } ?: pos.toFloat()) / 1000).toLong() } }
    val shade = androidx.compose.ui.graphics.Shadow(Color.Black.copy(.8f), Offset(0f, 1f), 6f)
    val timeStyle = MaterialTheme.typography.labelLarge.copy(shadow = shade, fontFeatureSettings = "tnum")
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(28.dp), tint = Color.White)
        }
        Text(clock(secs * 1000), color = Color.White, style = timeStyle)
        SeekLine(
            fraction,
            onSeek = { drag = it }, onDone = { drag?.let { player.seekTo((it * dur).toLong()) }; drag = null },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Text(clock(dur), color = Color.White.copy(.8f), style = timeStyle)
        if (hasSubs) IconButton({
            subsOn = !subsOn
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !subsOn).build()
        }) { Icon(if (subsOn) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled, if (subsOn) "Hide subtitles" else "Show subtitles", tint = Color.White) }
        IconButton({ muted = !muted; player.volume = if (muted) 0f else 1f }) {
            Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (muted) "Unmute" else "Mute", tint = Color.White)
        }
    }
}

/** Thin white seek line with a small round knob; drag or tap to jump. */
@Composable
private fun SeekLine(fraction: () -> Float, onSeek: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val seek by rememberUpdatedState(onSeek)
    val done by rememberUpdatedState(onDone)
    var active by remember { mutableStateOf(false) }
    val knob by androidx.compose.animation.core.animateDpAsState(if (active) 8.dp else 6.dp, label = "knob")
    Canvas(
        modifier.height(32.dp)
            .semantics { contentDescription = "Seek"; progressBarRangeInfo = ProgressBarRangeInfo(fraction(), 0f..1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { active = true; seek((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { active = false; done() }, onDragCancel = { active = false; done() },
                ) { ch, _ -> ch.consume(); seek((ch.position.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)); done() } },
    ) {
        // read here, in drawing: the moving line only repaints, nothing re-measures
        val y = size.height / 2; val x = size.width * fraction()
        val stroke = (if (active) 5.dp else 3.dp).toPx()
        drawLine(Color.White.copy(.3f), Offset(0f, y), Offset(size.width, y), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color.White, Offset(0f, y), Offset(x, y), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color.White, knob.toPx(), Offset(x, y))
    }
}

/**
 * New pan offset after zooming by [k] round screen point [c], for a layer that scales round [centre]:
 * keeps whatever is under [c] exactly under [c] (so pinching zooms where the fingers are).
 */
fun zoomOffset(c: Offset, centre: Offset, k: Float, o: Offset) = (c - centre) * (1 - k) + o * k
