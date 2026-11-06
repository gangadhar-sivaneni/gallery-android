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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val bioAvailable = remember { canUseBiometric(ctx as FragmentActivity) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).consumeWindowInsets(pad).verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Group("Theme") { ThemeSection(vm) }

            Group("Display") {
                ChoiceRow(Icons.Outlined.Home, "Start on", StartTab.entries, vm.startTab, { it.label }, vm::changeStartTab)
                ChoiceRow(
                    Icons.Outlined.GridView, "Grid style", listOf(true, false), vm.roundedThumbs,
                    { if (it) "Soft corners" else "Square" }, vm::changeRounded,
                )
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

            Group("Timeline") {
                ChoiceRow(Icons.Outlined.Event, "Arrange by", DateMode.entries, vm.dateMode, { it.label }, vm::changeDateMode)
                Text(
                    vm.dateMode.hint, Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Group("Viewing") {
                ToggleRow(Icons.Outlined.LightMode, "Full brightness", "Brighter screen while viewing photos", vm.maxBrightness, vm::changeBrightness)
                ToggleRow(Icons.Outlined.StayCurrentPortrait, "Keep screen on", "Screen won't sleep while viewing", vm.keepScreenOn, vm::changeScreenOn)
                SliderRow(Icons.Outlined.Slideshow, "Slideshow speed", "${vm.slideSeconds} seconds per photo", vm.slideSeconds, 1..15, vm::setSlide)
            }

            Group("Videos") {
                ToggleRow(Icons.Outlined.PlayCircle, "Autoplay", "Start playing as soon as a video opens", vm.autoplay, vm::changeAutoplay)
                ToggleRow(Icons.Outlined.Repeat, "Loop", "Play videos again when they end", vm.loopVideos, vm::setLoop)
                ToggleRow(Icons.Outlined.History, "Resume playback", "Continue from where you stopped", vm.resumeVideos, vm::changeResume)
            }

            Group("Editing & deleting") {
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

            Group("Security") {
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

            Group("Storage") {
                val total = remember(vm.all) { vm.all.sumOf { it.size } }
                InfoRow(
                    Icons.Outlined.Storage, "${vm.all.size} items · ${Formatter.formatFileSize(ctx, total)}",
                    "${vm.all.count { it.isVideo }} videos · ${vm.trash.size} in trash (kept 30 days)",
                )
                LinkRow(Icons.Outlined.CleaningServices, "Clear thumbnail cache", "Thumbnails reload as you scroll") {
                    vm.clearImageCache(); vm.toast("Cache cleared")
                }
            }

            Group("About") {
                InfoRow(
                    Icons.Outlined.Info, "Gallery ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}",
                    "Works fully offline. Nothing leaves this phone.",
                )
                InfoRow(Icons.Outlined.Person, "Developed by Gangadhar", "Designed and built for personal use")
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title, Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
    )
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
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
        Slider(
            value.toFloat(), { onChange(it.toInt()) }, Modifier.padding(start = 48.dp),
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
        )
    }
}
