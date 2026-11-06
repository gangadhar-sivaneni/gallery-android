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
        is Dlg.Info -> InfoDialog(dlg.m, onDismiss)
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

@Composable
private fun InfoDialog(m: Media, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var exif by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(m) { exif = withContext(Dispatchers.IO) { exifRows(ctx, m) } }
    val rows = buildList {
        add("Name" to m.name)
        add("Date taken" to FULL.format(Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault())))
        add("Modified" to FULL.format(Instant.ofEpochMilli(m.modified).atZone(ZoneId.systemDefault())))
        add("Size" to Formatter.formatFileSize(ctx, m.size))
        if (m.width > 0) add("Resolution" to "${m.width} × ${m.height}  ·  %.1f MP".format(m.width * m.height / 1e6))
        if (m.isVideo) add("Duration" to duration(m.duration))
        add("Type" to m.mime)
        addAll(exif)
        add("Path" to "/storage/emulated/0/${m.path}${m.name}")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (k, v) ->
                    Column {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
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
                Slider(pct, { pct = it }, valueRange = 10f..90f, steps = 15)
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
