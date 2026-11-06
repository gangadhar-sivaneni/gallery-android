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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a")

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
            if (m.isVideo) VideoPage(vm, m, active = current && !slideshow, locked = videoLocked && current, aspect = aspect, speed = speed) { chrome = it }
            else ZoomableImage(m, current, vm.imageEpoch) {
                if (slideshow) slideshow = false
                chrome = !chrome
            }
        }

        if (videoLocked) LockOverlay { videoLocked = false; chrome = true }

        AnimatedVisibility(chrome && !videoLocked, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            TopAppBar(
                navigationIcon = { IconButton(leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(cur.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            TIME.format(Instant.ofEpochMilli(cur.date).atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(.75f), maxLines = 1,
                        )
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
                                    DropdownMenuItem({ Text(speedLabel(sp)) }, { speedMenu = false; speed = sp }, leadingIcon = { RadioButton(speed == sp, null) })
                                }
                            }
                        }
                        Box {
                            IconButton({ aspectMenu = true }) { Icon(Icons.Outlined.AspectRatio, "Aspect ratio") }
                            AppMenu(aspectMenu, { aspectMenu = false }) {
                                VideoAspect.entries.forEach { a ->
                                    DropdownMenuItem({ Text(a.label) }, { aspectMenu = false; aspect = a }, leadingIcon = { RadioButton(aspect == a, null) })
                                }
                            }
                        }
                        IconButton({ videoLocked = true; chrome = false }) { Icon(Icons.Outlined.Lock, "Lock controls") }
                    } else {
                        if (full && (cur.mime == "image/jpeg" || cur.mime == "image/png")) IconButton({ ask(vm.writeRequest(listOf(cur))) { vm.rotate(listOf(cur)) } }) {
                            Icon(Icons.Outlined.RotateRight, "Rotate")
                        }
                        if (!external) IconButton({ dialog = Dlg.Info(cur) }) { Icon(Icons.Outlined.Info, "Properties") }
                    }
                    if (external) IconButton({ openWith(ctx, cur) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open with") }
                    if (!external) Box {
                        IconButton({ more = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        AppMenu(more, { more = false; moreAll = false }) {
                            val close = { more = false; moreAll = false }
                            val photo = !cur.isVideo
                            // common actions first; the rest sit behind "View more"
                            if (cur.isVideo && full) MenuItem("Edit video", Icons.Outlined.ContentCut) { close(); vm.stack.add(Screen.VideoEditor(cur)) }
                            if (cur.isVideo) MenuItem(if (landscape) "Portrait" else "Landscape", Icons.Outlined.ScreenRotation) { close(); rotateScreen() }
                            if (photo && (full || inVault)) MenuItem("Slideshow", Icons.Outlined.Slideshow) { close(); slideshow = true; chrome = false }
                            if (full) {
                                MenuItem("Move to album", Icons.Outlined.DriveFileMove) { close(); dialog = Dlg.Move(listOf(cur), false) }
                                MenuItem("Copy to album", Icons.Outlined.ContentCopy) { close(); dialog = Dlg.Move(listOf(cur), true) }
                                MenuItem("Rename", Icons.Outlined.DriveFileRenameOutline) { close(); dialog = Dlg.Rename(cur) }
                                MenuItem("Move to Private", Icons.Outlined.Lock) { close(); vm.moveToVault(listOf(cur), ask) }
                            }
                            if (!moreAll) MenuItem("View more", Icons.Outlined.ExpandMore) { moreAll = true }
                            else {
                                HorizontalDivider()
                                if (cur.isVideo && full) {
                                    MenuItem("Share", Icons.Outlined.Share) { close(); share(ctx, listOf(cur)) }
                                    MenuItem(if (cur.favorite) "Remove favorite" else "Add to favorites", Icons.Outlined.FavoriteBorder) {
                                        close(); ask(vm.favoriteRequest(listOf(cur), !cur.favorite), null)
                                    }
                                    MenuItem("Move to trash", Icons.Outlined.Delete) { close(); ask(vm.trashRequest(listOf(cur), true), null) }
                                }
                                if (cur.isVideo) MenuItem("Properties", Icons.Outlined.Info) { close(); dialog = Dlg.Info(cur) }
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

        // videos use the player's own bottom controls; photos get our action bar
        AnimatedVisibility(chrome && !cur.isVideo, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.75f))))
                    .navigationBarsPadding().padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                when {
                    external -> {
                        BarAction(Icons.Outlined.Share, "Share", Color.White) { share(ctx, listOf(cur)) }
                        BarAction(Icons.Outlined.Edit, "Edit", Color.White) { edit(ctx, cur) }
                    }
                    inVault -> {
                        BarAction(Icons.Outlined.LockOpen, "Move out", Color.White) { vm.restoreFromVault(listOf(cur)) }
                        BarAction(Icons.Outlined.DeleteForever, "Delete", Color.White) { confirmVaultDelete = true }
                    }
                    inTrash -> {
                        BarAction(Icons.Outlined.RestoreFromTrash, "Restore", Color.White) { ask(vm.trashRequest(listOf(cur), false), null) }
                        BarAction(Icons.Outlined.DeleteForever, "Delete", Color.White) { ask(vm.deleteRequest(listOf(cur)), null) }
                    }
                    else -> {
                        BarAction(Icons.Outlined.Share, "Share", Color.White) { share(ctx, listOf(cur)) }
                        BarAction(
                            if (cur.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favorite",
                            if (cur.favorite) Color(0xFFFF6B81) else Color.White,
                        ) { ask(vm.favoriteRequest(listOf(cur), !cur.favorite), null) }
                        if (!cur.isGif) BarAction(Icons.Outlined.Edit, "Edit", Color.White) { vm.stack.add(Screen.Editor(cur)) }
                        if (!cur.isGif) BarAction(Icons.Outlined.Wallpaper, "Wallpaper", Color.White) { dialog = Dlg.Wallpaper(cur) }
                        BarAction(Icons.Outlined.Delete, "Delete", Color.White) { ask(vm.trashRequest(listOf(cur), true), null) }
                    }
                }
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
                    onDoubleTap = { p ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                        else {
                            scale = 2.5f
                            offset = clamp((Offset(size.width / 2f, size.height / 2f) - p) * 1.5f, scale)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val e = awaitPointerEvent()
                        if (e.changes.size > 1 || scale > 1f) {
                            scale = (scale * e.calculateZoom()).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else clamp(offset + e.calculatePan(), scale)
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
    onControls: (Boolean) -> Unit,
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
            setMediaItem(MediaItem.fromUri(m.uri))
            vm.videoPosition(m).takeIf { it > 0 }?.let { seekTo(it); vm.toast("Resumed at ${duration(it)}") }
            prepare()
            playWhenReady = vm.autoplay
            repeatMode = if (vm.loopVideos) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(player) {
        onDispose {
            vm.saveVideoPosition(m, player.currentPosition, player.duration)
            player.release()
        }
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        player.pause()
        vm.saveVideoPosition(m, player.currentPosition, player.duration)
    }
    // The video always fills the screen. Only the player's control strip is kept clear of the nav bar,
    // using the bar's size *ignoring visibility* so nothing moves when tapping shows/hides the bars.
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val bars = WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val padL = bars.getLeft(density, dir)
    val padR = bars.getRight(density, dir)
    val padB = bars.getBottom(density)
    // gestures: hold = 2x, swipe up/down on the left = brightness, on the right = volume.
    // Taps and sideways swipes are left alone so the controls and the pager still work.
    val audio = remember { ctx.getSystemService(android.media.AudioManager::class.java) }
    val window = (ctx as Activity).window
    val curSpeed by rememberUpdatedState(speed)
    var fast by remember { mutableStateOf(false) }
    var hudOn by remember { mutableStateOf(false) }
    var hudLeft by remember { mutableStateOf(true) }
    var hudLevel by remember { mutableFloatStateOf(0f) }
    val gestures = if (locked) Modifier else Modifier.pointerInput(Unit) {
        val edge = 56.dp.toPx() // keep clear of the status bar pull-down and the seek bar
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.y < edge || down.position.y > size.height - edge * 1.6f) return@awaitEachGesture
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
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                var level = if (left) vm.gestureBrightness ?: window.attributes.screenBrightness.takeIf { it >= 0f }
                    ?: (android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                else audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
                hudLeft = left; hudLevel = level; hudOn = true
                var lastY = down.position.y + drag.y
                try { do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    e.changes.forEach { it.consume() }
                    // a swipe over ~60% of the screen height goes from 0 to full
                    level = (level - (c.position.y - lastY) / (size.height * 0.6f)).coerceIn(0f, 1f)
                    lastY = c.position.y
                    hudLevel = level
                    if (left) vm.gestureBrightness = level.coerceAtLeast(0.01f)
                    else audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, kotlin.math.round(level * max).toInt(), 0)
                } while (c.pressed) } finally { hudOn = false } // never leave the indicator stuck on screen
            }
        }
    }
    Box(Modifier.fillMaxSize().then(gestures), Alignment.Center) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    setShowNextButton(false); setShowPreviousButton(false)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> onControls(v == View.VISIBLE) })
                }
            },
            update = { pv ->
                pv.resizeMode = aspect.mode
                pv.useController = !locked
                if (locked) pv.hideController()
                // pad the controls, but stretch the dark scrim back over the padding so it covers the whole screen
                (pv.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)?.parent as? android.view.ViewGroup)?.let { root ->
                    root.setPadding(padL, 0, padR, padB)
                    root.clipToPadding = false
                    root.findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)?.let { bg ->
                        val lp = bg.layoutParams as android.view.ViewGroup.MarginLayoutParams
                        if (lp.leftMargin != -padL || lp.rightMargin != -padR || lp.bottomMargin != -padB) {
                            lp.setMargins(-padL, 0, -padR, -padB); bg.layoutParams = lp
                        }
                    }
                }
            },
            modifier = aspect.ratio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
        )
        // only the control in use is shown, and only while it is being used
        AnimatedVisibility(fast, Modifier.align(Alignment.TopCenter).padding(top = 72.dp), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.background(Color.Black.copy(.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("2x speed", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
        AnimatedVisibility(
            hudOn, Modifier.align(if (hudLeft) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 40.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Column(
                Modifier.background(Color.Black.copy(.6f), RoundedCornerShape(24.dp)).padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    when {
                        hudLeft -> Icons.Outlined.BrightnessMedium
                        hudLevel == 0f -> Icons.AutoMirrored.Outlined.VolumeOff
                        else -> Icons.AutoMirrored.Outlined.VolumeUp
                    },
                    null, tint = Color.White,
                )
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(6.dp).height(120.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(.25f)), Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(hudLevel).background(Color.White))
                }
                Spacer(Modifier.height(8.dp))
                Text("${(hudLevel * 100).toInt()}", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
