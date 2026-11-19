package com.ganga.gallery

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import android.app.WallpaperManager
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Dialogs shared by the grid and the viewer. */
sealed interface Dlg {
    data class Move(val items: List<Media>, val copy: Boolean) : Dlg
    data class Rename(val m: Media) : Dlg
    data class Info(val m: Media) : Dlg
    data class Resize(val m: Media) : Dlg
    data class Wallpaper(val m: Media) : Dlg
}

@Composable
fun MediaDialogs(vm: GalleryViewModel, dlg: Dlg?, ask: Ask, onDone: () -> Unit, onDismiss: () -> Unit) {
    when (dlg) {
        null -> {}
        is Dlg.Move -> AlbumPicker(vm, dlg.items, if (dlg.copy) "Copy to album" else "Move to album", onDismiss) { path ->
            onDone()
            if (dlg.copy) vm.copy(dlg.items, path)
            else ask(vm.writeRequest(dlg.items)) { vm.move(dlg.items, path) }
        }
        is Dlg.Rename -> RenameDialog(dlg.m, onDismiss) { name ->
            onDone()
            ask(vm.writeRequest(listOf(dlg.m))) { vm.rename(dlg.m, name) }
        }
        is Dlg.Info -> InfoSheet(vm, dlg.m, onDismiss)
        is Dlg.Resize -> ResizeDialog(dlg.m, onDismiss) { w, h -> onDone(); vm.resize(dlg.m, w, h) }
        is Dlg.Wallpaper -> WallpaperDialog(vm, dlg.m, onDismiss)
    }
}

/** Pick an existing album folder or type a new one. Returns a MediaStore RELATIVE_PATH. */
@Composable
private fun AlbumPicker(vm: GalleryViewModel, items: List<Media>, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    // albums you moved/copied to recently come first, then A-Z
    val albums = remember(vm.all, vm.recentTargets) {
        vm.albums().distinctBy { it.path }.filter { a -> items.all { canLiveIn(it, a.path) } }.sortedWith(
            compareBy<Album> { vm.recentTargets.indexOf(it.path).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.name.lowercase() },
        )
    }
    var newName by remember { mutableStateOf("") }
    val clean = newName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    newName, { newName = it }, label = { Text("New album") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                    supportingText = { Text("Created in Pictures/") },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(albums, key = { it.path }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(a.path) }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(a.cover.uri, null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    a.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onPick("Pictures/$clean/") }, enabled = clean.isNotEmpty()) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(m: Media, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val ext = m.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var base by remember { mutableStateOf(m.name.removeSuffix(ext)) }
    val valid = base.isNotBlank() && base.none { it in "/\\:*?\"<>|" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(base, { base = it }, suffix = { Text(ext) }, singleLine = true, isError = !valid) },
        confirmButton = { TextButton({ onRename(base.trim() + ext) }, enabled = valid) { Text("Rename") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private val FULL = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a")


/** Details from the grid's Properties: the same panel as in the viewer, in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(vm: GalleryViewModel, m: Media, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        InfoContent(vm, m) { onDismiss(); vm.stack.add(Screen.Grid(Source.Bucket(m.bucketId, m.bucket))) }
    }
}

/** Date and time, then what the file is, then where it lives: rows on one solid surface, divided by thin lines. */
@Composable
fun InfoContent(vm: GalleryViewModel, m: Media, onOpenAlbum: () -> Unit) {
    val ctx = LocalContext.current
    var exif by remember(m.uri) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(m.uri) { exif = withContext(Dispatchers.IO) { exifRows(ctx, m).toMap() } }
    val at = Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault())
    val kind = when {
        m.isVideo -> "Video"
        "screenshot" in m.path.lowercase() -> "Screenshot"
        else -> exif["Camera"] ?: "Photo"
    }
    val specs = listOfNotNull(
        if (m.width > 0 && !m.isVideo) "%d MP".format(((m.width.toLong() * m.height + 500_000) / 1_000_000).coerceAtLeast(1)) else null,
        if (m.width > 0) "${m.width} × ${m.height}" else null,
        if (m.isVideo) clock(m.duration) else null,
        m.mime.substringAfter('/').uppercase().takeIf { it.isNotBlank() && it != "*" },
    ).joinToString("  ·  ")
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(DAY_LONG.format(at), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(TIME_ONLY.format(at), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = line)
        InfoRow(
            when { m.isVideo -> Icons.Outlined.Videocam; kind == "Screenshot" -> Icons.Outlined.Screenshot; else -> Icons.Outlined.PhotoCamera },
            kind, specs,
        )
        InfoRow(Icons.Outlined.InsertDriveFile, m.name, Formatter.formatShortFileSize(ctx, m.size).takeIf { m.size > 0 })
        exif["Exposure"]?.let { InfoRow(Icons.Outlined.Camera, it, null) }
        InfoRow(Icons.Outlined.LocationOn, exif["Location"] ?: "No location information", null, last = m.bucketId == -1L)
        if (m.bucketId != -1L) InfoRow(Icons.Outlined.PhotoAlbum, vm.albumName(m.bucketId, m.bucket), "Phone/${m.path}", last = true, onClick = onOpenAlbum)
    }
}

private val DAY_LONG = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
private val TIME_ONLY = DateTimeFormatter.ofPattern("h:mm a")

/** One compact details row: icon, value, small grey line under it; a chevron when it opens something. */
@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?,
    last: Boolean = false, onClick: (() -> Unit)? = null,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
    }
}

@Composable
private fun WallpaperDialog(vm: GalleryViewModel, m: Media, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // choose where, then pick the exact area on a screen-shaped crop before anything is applied
    fun apply(which: Int) {
        onDismiss()
        vm.stack.add(Screen.WallpaperCrop(m, which))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Wallpaper, null) },
        title = { Text("Set as wallpaper") },
        text = {
            Column {
                listOf(
                    Triple(Icons.Outlined.Home, "Home screen", WallpaperManager.FLAG_SYSTEM),
                    Triple(Icons.Outlined.Lock, "Lock screen", WallpaperManager.FLAG_LOCK),
                    Triple(Icons.Outlined.Smartphone, "Home and lock screens", WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
                ).forEach { (icon, label, which) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { apply(which) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                        Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onDismiss(); setAs(ctx, m) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.MoreHoriz, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Other app (contact photo, crop…)", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ResizeDialog(m: Media, onDismiss: () -> Unit, onResize: (Int, Int) -> Unit) {
    var pct by remember { mutableFloatStateOf(50f) }
    val w = (m.width * pct / 100).toInt().coerceAtLeast(1)
    val h = (m.height * pct / 100).toInt().coerceAtLeast(1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resize") },
        text = {
            Column {
                Text("${pct.toInt()}%  ·  $w × $h", style = MaterialTheme.typography.titleMedium)
                AppSlider(pct, { pct = it }, range = 10f..90f, steps = 15, label = "Size")
                Text(
                    "Saves a new copy next to the original.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton({ onResize(w, h) }, enabled = m.width > 0) { Text("Save copy") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
