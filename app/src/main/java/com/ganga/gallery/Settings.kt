package com.ganga.gallery

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.saveable.rememberSaveable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val bioAvailable = remember { canUseBiometric(ctx as FragmentActivity) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(page != null) { page = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page ?: "Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton({ if (page != null) page = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        // pages slide in from the right and back out, like the phone's own settings
        AnimatedContent(
            page, label = "settings",
            transitionSpec = {
                val forward = targetState != null
                (slideInHorizontally(tween(260)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(220)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(150)))
            },
        ) { p ->
        CompositionLocalProvider(LocalPage provides Nav(p) { page = it }) {
        Column(
            Modifier.padding(pad).consumeWindowInsets(pad).verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        // the main page is one white card listing the categories
        Column(
            if (p == null) Modifier.padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 4.dp)
            else Modifier,
        ) {
            Group("Appearance", "Theme, accent colour, background", Icons.Outlined.Palette) { ThemeSection(vm) }

            Group("Home screen", "Start tab, photos per row, corners", Icons.Outlined.Home) {
                ChoiceRow(Icons.Outlined.Home, "Start on", StartTab.entries, vm.startTab, { it.label }, vm::changeStartTab)
                SliderRow(Icons.Outlined.GridView, "Photos per row", "${vm.columns} per row · pinch the grid to change it too", vm.columns, 2..6, vm::setCols)
                SliderRow(
                    Icons.Outlined.RoundedCorner, "Corner roundness",
                    if (vm.thumbCorner == 0) "Square tiles" else "${vm.thumbCorner} dp rounded corners", vm.thumbCorner, 0..16, vm::changeCorner,
                )
            }

            Group("Albums", "Albums per row, collections, hidden media", Icons.Outlined.PhotoAlbum) {
                SliderRow(Icons.Outlined.PhotoAlbum, "Albums per row", "${vm.albumColumns} per row", vm.albumColumns, 2..4, vm::setAlbumCols)
                ToggleRow(Icons.Outlined.AutoAwesomeMosaic, "Collections", "Camera, Screenshots, WhatsApp and more in Albums", vm.showCollections, vm::changeCollections)
                // needs Android's "All files access"; re-check when coming back from that screen
                var allFiles by remember { mutableStateOf(hasAllFilesAccess()) }
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                    val now = hasAllFilesAccess()
                    if (now != allFiles) { allFiles = now; if (vm.includeAppHidden) vm.reload() }
                    onPauseOrDispose {}
                }
                ToggleRow(
                    Icons.Outlined.FolderSpecial, "Media hidden by other apps",
                    when {
                        vm.includeAppHidden && !allFiles -> "Needs All files access. Tap to allow it in Android settings."
                        else -> "Include photos apps keep out of galleries, e.g. WhatsApp Sent, Private and Stickers"
                    },
                    vm.includeAppHidden,
                ) { on ->
                    vm.changeIncludeAppHidden(on)
                    if (on && !allFiles) openAllFilesAccess(ctx)
                }
            }

            Group("Timeline", "Which date each photo is sorted under", Icons.Outlined.Event) {
                ChoiceRow(Icons.Outlined.Event, "Arrange by", DateMode.entries, vm.dateMode, { it.label }, vm::changeDateMode)
                Text(
                    vm.dateMode.hint, Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Group("Photo viewer", "Brightness, screen on, slideshow", Icons.Outlined.Image) {
                ToggleRow(Icons.Outlined.LightMode, "Full brightness", "Brighter screen while viewing photos", vm.maxBrightness, vm::changeBrightness)
                ToggleRow(Icons.Outlined.StayCurrentPortrait, "Keep screen on", "Screen won't sleep while viewing", vm.keepScreenOn, vm::changeScreenOn)
                SliderRow(Icons.Outlined.Slideshow, "Slideshow speed", "${vm.slideSeconds} seconds per photo", vm.slideSeconds, 1..15, vm::setSlide)
            }

            Group("Video player", "Autoplay, loop, resume", Icons.Outlined.PlayCircle) {
                ToggleRow(Icons.Outlined.PlayCircle, "Autoplay", "Start playing as soon as a video opens", vm.autoplay, vm::changeAutoplay)
                ToggleRow(Icons.Outlined.Repeat, "Loop", "Play videos again when they end", vm.loopVideos, vm::setLoop)
                ToggleRow(Icons.Outlined.History, "Resume playback", "Continue from where you stopped", vm.resumeVideos, vm::changeResume)
            }

            Group("Editing", "Confirmations for edits and moves", Icons.Outlined.Edit) {
                // re-read after returning from Android's settings screen
                var skip by remember { mutableStateOf(canSkipConfirm(ctx)) }
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { skip = canSkipConfirm(ctx); onPauseOrDispose {} }
                if (android.os.Build.VERSION.SDK_INT >= 31) LinkRow(
                    Icons.Outlined.DoneAll,
                    "Skip confirmations",
                    if (skip) "On: rotate, edit, move and delete happen instantly. Deleted items still go to Trash."
                    else "Off: Android asks before each change. Tap to allow Gallery to manage media.",
                ) { openMediaManagement(ctx) }
            }

            Group("Recently deleted", "How long deleted items are kept", Icons.Outlined.Delete) {
                ChoiceRow(Icons.Outlined.AutoDelete, "Empty trash after", listOf(30, 14, 7, 1), vm.trashDays, { if (it == 1) "1 day" else "$it days" }, vm::changeTrashDays)
                Text(
                    "Trashed photos and videos are deleted for good after this. Android never keeps them longer than 30 days.",
                    Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Group("Privacy & security", "App lock, private folder, hidden items", Icons.Outlined.Security) {
                // switching app lock off needs its PIN; switching it on sets one up if missing
                ToggleRow(Icons.Outlined.Lock, "App lock", if (vm.appLock) "PIN needed to open Gallery" else "Off", vm.appLock) { on ->
                    vm.stack.add(Screen.Pin(PinKind.APP, if (on) PinAction.ENABLE_APP_LOCK else PinAction.DISABLE_APP_LOCK))
                }
                if (vm.appLock) LinkRow(Icons.Outlined.Password, "Change app lock PIN", "Current PIN required") {
                    vm.stack.add(Screen.Pin(PinKind.APP, PinAction.CHANGE))
                }
                LinkRow(
                    Icons.Outlined.EnhancedEncryption,
                    if (vm.hasPin(PinKind.VAULT)) "Change private folder PIN" else "Create private folder PIN",
                    "Separate from the app lock PIN",
                ) { vm.stack.add(Screen.Pin(PinKind.VAULT, PinAction.CHANGE)) }
                if (bioAvailable) ToggleRow(Icons.Outlined.Fingerprint, "Fingerprint unlock", "Unlock with fingerprint instead of typing", vm.useBiometric, vm::changeBiometric)
                ToggleRow(Icons.Outlined.VisibilityOff, "Hide in recent apps", "Blank preview in recents, no screenshots", vm.secureScreen, vm::changeSecure)
                ToggleRow(Icons.Outlined.Visibility, "Show hidden items", "Until Gallery is closed", vm.showHidden) { vm.showHidden = it }
            }

            Group("Storage", "Space used, thumbnail cache", Icons.Outlined.Storage) {
                val total = remember(vm.all) { vm.all.sumOf { it.size } }
                InfoRow(
                    Icons.Outlined.Storage, "${vm.all.size} items · ${Formatter.formatFileSize(ctx, total)}",
                    "${vm.all.count { it.isVideo }} videos · ${vm.trash.size} in trash (kept ${vm.trashDays} days)",
                )
                LinkRow(Icons.Outlined.CleaningServices, "Clear thumbnail cache", "Thumbnails reload as you scroll") {
                    vm.clearImageCache(); vm.toast("Cache cleared")
                }
            }

            Group("About", "Version and developer", Icons.Outlined.Info) {
                InfoRow(
                    Icons.Outlined.Info, "Gallery ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}",
                    "Works fully offline. Nothing leaves this phone.",
                )
                InfoRow(Icons.Outlined.Person, "Developed by Gangadhar", "Designed and built for personal use")
            }
        }
        }
        }
        }
    }
}

private class Nav(val page: String?, val open: (String) -> Unit)
private val LocalPage = staticCompositionLocalOf { Nav(null) {} }

@Composable
private fun Group(title: String, subtitle: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val nav = LocalPage.current
    when (nav.page) {
        // main page: one row per category; its settings are only built when opened
        null -> LinkRow(icon, title, subtitle) { nav.open(title) }
        title -> Surface(Modifier.padding(top = 8.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun Lead(icon: ImageVector) = Box(
    Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), CircleShape),
    Alignment.Center,
) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }

@Composable
private fun RowBase(icon: ImageVector, title: String, subtitle: String?, onClick: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lead(icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    RowBase(icon, title, subtitle, { onChange(!checked) }) { Switch(checked, onChange) }

@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) =
    RowBase(icon, title, subtitle, onClick) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }

@Composable
private fun InfoRow(icon: ImageVector, title: String, subtitle: String) = RowBase(icon, title, subtitle, null)

/** Title on the left, a segmented choice underneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(icon: ImageVector, title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lead(icon)
            Text(title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 48.dp, top = 8.dp)) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = o == selected, onClick = { onPick(o) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Text(label(o), maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun SliderRow(icon: ImageVector, title: String, subtitle: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lead(icon)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AppSlider(
            value.toFloat(), { onChange(kotlin.math.round(it).toInt()) }, Modifier.padding(start = 40.dp),
            range = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1, label = title,
        )
    }
}
