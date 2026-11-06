package com.ganga.gallery

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import coil3.gif.AnimatedImageDecoder
import coil3.video.VideoFrameDecoder

// FragmentActivity (not ComponentActivity) because the fingerprint prompt needs it
class MainActivity : FragmentActivity() {
    private val vm: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx).components {
                    add(VideoFrameDecoder.Factory())
                    add(AnimatedImageDecoder.Factory())
                    add(coil3.svg.SvgDecoder.Factory())
                }.crossfade(120).build() // thumbnails fade in instead of popping
            }
            val dark = isDarkTheme(vm)
            val screen = vm.stack.last()
            val inViewer = screen is Screen.Viewer
            LaunchedEffect(inViewer) { if (!inViewer) vm.phoneBrightnessUsed = false }
            val private = vm.secureScreen || (screen as? Screen.Grid)?.source == Source.Vault ||
                (screen as? Screen.Viewer)?.source == Source.Vault
            SideEffect {
                // dark status-bar icons on light screens; the viewer is always black
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark && !inViewer
                    isAppearanceLightNavigationBars = !dark && !inViewer
                }
                // hide private content from screenshots and the recent-apps preview
                if (private) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                window.attributes = window.attributes.apply {
                    screenBrightness = when {
                        inViewer && vm.maxBrightness && !vm.phoneBrightnessUsed -> 1f
                        else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
            MaterialTheme(appColorScheme(vm)) {
                Box(Modifier.fillMaxSize()) {
                    AppBackdrop(vm)
                    Surface(color = MaterialTheme.colorScheme.background) { App(vm) }
                }
            }
        }
    }

    // configChanges covers rotation, so onStop only happens when the app really leaves the screen
    override fun onStart() {
        super.onStart()
        vm.setForeground(true)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT < 31 && vm.pipAuto) vm.pipParams?.let { runCatching { enterPictureInPictureMode(it) } }
    }

    override fun onStop() {
        super.onStop()
        vm.setForeground(false)
        if (!isChangingConfigurations) vm.lockAll()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        i.getLongExtra(EXTRA_ID, -1).takeIf { it >= 0 }?.let { vm.pendingOpen = it; return }
        val uri = i.data ?: return
        if (i.action != Intent.ACTION_VIEW && i.action != MediaStore.ACTION_REVIEW) return
        vm.openedFromOtherApp = true
        // a MediaStore item: open it inside its album so you can swipe through
        if (uri.authority == MediaStore.AUTHORITY) {
            runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it >= 0 }?.let { vm.pendingOpen = it; return }
        }
        // anything else (WhatsApp, Files…): view just that file
        val mime = i.type ?: contentResolver.getType(uri) ?: "image/*"
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "File"
        vm.pendingExternal = Media(
            id = -(uri.hashCode().toLong() and 0x7fffffff) - 1, uri = uri, name = name,
            date = System.currentTimeMillis(), modified = System.currentTimeMillis(), size = 0, width = 0, height = 0,
            mime = mime, bucketId = -1, bucket = "", path = "", duration = 0, favorite = false,
        )
    }
}


/** Launch a MediaStore confirmation dialog; [after] runs if the user approves. */
typealias Ask = (PendingIntent, (() -> Unit)?) -> Unit

private val PERMS = if (Build.VERSION.SDK_INT >= 33)
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

@Composable
fun isLandscape() = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

@Composable
fun App(vm: GalleryViewModel) {
    val ctx = LocalContext.current
    fun granted() = PERMS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var ok by remember { mutableStateOf(granted()) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = granted() }
    LifecycleResumeEffect(Unit) { ok = granted(); onPauseOrDispose {} }
    LaunchedEffect(ok) {
        if (!ok) return@LaunchedEffect
        // ACCESS_MEDIA_LOCATION has no dialog of its own; needed for GPS in Properties
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            req.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
        }
        vm.reload()
    }

    if (!ok) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Allow access to your photos and videos", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Choose \"Allow all\". Everything stays on this phone.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { req.launch(PERMS) }) { Text("Allow access") }
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }) { Text("Open app settings") }
        }
        return
    }

    // registered before any lock-screen early return, so an Allow/Deny result is never dropped
    val approve = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.afterApproval?.invoke() else vm.afterDenial?.invoke()
        vm.afterApproval = null
        vm.afterDenial = null
        vm.reload()
    }
    val ask: Ask = { pi, after ->
        vm.afterApproval = after
        vm.afterDenial = vm.nextDenial
        vm.nextDenial = null
        // one-time tip: the popups can be switched off in Settings
        if (!canSkipConfirm(ctx)) vm.hintSkipConfirmOnce()
        runCatching { approve.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
            .onFailure { android.util.Log.e("Gallery", "system dialog failed to launch", it); vm.toast("Couldn't open Android's permission dialog") }
    }

    if (vm.appLock && !vm.appUnlocked) {
        LockGate(vm, PinKind.APP, "Gallery is locked") { vm.appUnlocked = true }
        return
    }

    // open an item from a home-screen shortcut or another app once media is loaded
    LaunchedEffect(vm.pendingOpen, vm.loading) {
        val id = vm.pendingOpen ?: return@LaunchedEffect
        if (vm.loading) return@LaunchedEffect
        vm.pendingOpen = null
        val m = vm.all.firstOrNull { it.id == id }
        if (m == null) vm.toast("That item no longer exists")
        else {
            val album = Source.Bucket(m.bucketId, m.bucket)
            // swipe through its album when it's visible there; otherwise (hidden/filtered) show just this item
            val source = if (vm.items(album).any { it.id == id }) album else Source.Single(m)
            vm.stack.add(Screen.Viewer(source, "", id, exitOnBack = vm.openedFromOtherApp))
        }
    }
    LaunchedEffect(vm.pendingExternal) {
        val m = vm.pendingExternal ?: return@LaunchedEffect
        vm.pendingExternal = null
        vm.stack.add(Screen.Viewer(Source.Single(m), "", m.id, exitOnBack = true))
    }

    val pop = {
        if (vm.stack.size > 1) vm.stack.removeAt(vm.stack.lastIndex)
        val stillInVault = vm.stack.any { (it as? Screen.Grid)?.source == Source.Vault || (it as? Screen.Viewer)?.source == Source.Vault }
        if (!stillInVault) vm.vaultUnlocked = false
    }
    BackHandler(vm.stack.size > 1) { pop() }
    val holder = rememberSaveableStateHolder()
    val screen = vm.stack.last()
    val needsVault = (screen as? Screen.Grid)?.source == Source.Vault || (screen as? Screen.Viewer)?.source == Source.Vault
    if (needsVault && !vm.vaultUnlocked) {
        LockGate(vm, PinKind.VAULT, "Private folder") { vm.vaultUnlocked = true }
        return
    }
    AnimatedContent(
        targetState = vm.stack.lastIndex to screen,
        transitionSpec = {
            // forward: new screen grows in slightly; back: old one shrinks away
            val forward = targetState.first >= initialState.first
            (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = if (forward) 0.96f else 1.03f)) togetherWith fadeOut(tween(150))
        },
        contentKey = { "${it.first}:${it.second}" },
        label = "screens",
    ) { (idx, scr) ->
    holder.SaveableStateProvider("$idx:$scr") {
        when (val screen = scr) {
            Screen.Home -> Home(vm, ask)
            Screen.Settings -> SettingsScreen(vm, onBack = { pop() })
            is Screen.Pin -> PinFlow(vm, screen) { pop() }
            is Screen.Grid -> MediaGrid(vm, screen.source, ask, onBack = { pop() })
            is Screen.Pick -> MediaGrid(vm, Source.All, ask, onBack = { pop() }, pick = screen)
            is Screen.Viewer -> Viewer(vm, screen, ask, onBack = { pop() })
            is Screen.Editor -> Editor(vm, screen.m, ask, onBack = { pop() })
            is Screen.VideoEditor -> VideoEditor(vm, screen.m, onBack = { pop() })
            is Screen.WallpaperCrop -> WallpaperCrop(vm, screen.m, screen.which, onBack = { pop() })
        }
    }
    }
}

@Composable
fun Home(vm: GalleryViewModel, ask: Ask) {
    var tab by rememberSaveable { mutableIntStateOf(vm.startTab.ordinal) }
    // order matches StartTab
    val tabs = listOf(
        Triple("All", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
        Triple("Photos", Icons.Filled.Image, Icons.Outlined.Image),
        Triple("Videos", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
        Triple("Albums", Icons.Filled.PhotoAlbum, Icons.Outlined.PhotoAlbum),
    )
    val content: @Composable () -> Unit = {
        Crossfade(tab, animationSpec = tween(180), label = "tab") { t -> when (t) {
            0 -> MediaGrid(vm, Source.All, ask, onBack = null)
            1 -> MediaGrid(vm, Source.Images, ask, onBack = null)
            2 -> MediaGrid(vm, Source.Videos, ask, onBack = null)
            else -> Albums(vm, ask)
        } }
    }
    // bottom tab bar in both orientations
    Scaffold(
        bottomBar = {
            if (!vm.selectionActive) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, (label, on, off) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(if (tab == i) on else off, null) }, label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding()).consumeWindowInsets(PaddingValues(bottom = pad.calculateBottomPadding()))) {
            content()
        }
    }
}

val CardShape = RoundedCornerShape(16.dp)
