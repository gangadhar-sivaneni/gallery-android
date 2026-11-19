package com.ganga.gallery

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Albums(vm: GalleryViewModel, ask: Ask) {
    val ctx = LocalContext.current
    val albums = remember(vm.all, vm.hiddenAlbums, vm.hiddenItems, vm.pinned, vm.showHidden, vm.albumNames, vm.albumCovers, vm.albumSort) { vm.albums() }
    val favCount = remember(vm.all) { vm.all.count { it.favorite } }
    val vidCount = remember(vm.all) { vm.all.count { it.isVideo } }
    val collections = remember(vm.all, vm.hiddenItems, vm.hiddenAlbums, vm.kinds) {
        Smart.entries.map { it to vm.collectionCover(it) }.filter { it.second.second > 0 }
    }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<AlbumDlg?>(null) }
    val landscape = isLandscape()
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    Scaffold(
        topBar = {
            AppBar(
                large = true,
                title = { Text("Albums", maxLines = 1) },
                actions = {
                    HeaderButton(if (vm.albumList) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList, "Change view type") {
                        vm.changeAlbumList(!vm.albumList)
                    }
                    Box {
                        HeaderButton(Icons.Outlined.MoreVert, "More options") { menu = true }
                        AppMenu(menu, { menu = false }) {
                            MenuItem("Open camera", Icons.Outlined.PhotoCamera) { menu = false; openCamera(ctx) }
                            if (!vm.albumList) {
                                MenuItem("Fewer columns", Icons.Outlined.ZoomIn, enabled = vm.albumColumns > 2) { vm.setAlbumCols(vm.albumColumns - 1) }
                                MenuItem("More columns", Icons.Outlined.ZoomOut, enabled = vm.albumColumns < 4) { vm.setAlbumCols(vm.albumColumns + 1) }
                            }
                            MenuItem(if (vm.showHidden) "Stop showing hidden" else "Temporarily show hidden", Icons.Outlined.VisibilityOff) {
                                menu = false; vm.showHidden = !vm.showHidden
                            }
                            MenuItem("Settings", Icons.Outlined.Settings) { menu = false; vm.stack.add(Screen.Settings) }
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (vm.loading) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val cols = when {
            vm.albumList -> if (landscape) 2 else 1
            landscape -> vm.albumColumns * 2
            else -> vm.albumColumns
        }
        LazyVerticalGrid(
            GridCells.Fixed(cols),
            state = grid,
            contentPadding = PaddingValues(16.dp + pad.calculateStartPadding(LocalLayoutDirection.current), pad.calculateTopPadding() + 4.dp, 16.dp + pad.calculateEndPadding(LocalLayoutDirection.current), pad.calculateBottomPadding() + LocalBottomBarSpace.current + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (vm.albumList) 4.dp else 16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Header("My albums") }
            items(albums, key = { it.id }) { a -> AlbumItem(vm, a, ask) { dialog = it } }
            if (vm.showCollections && collections.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header("Collections") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(collections, key = { it.first.name }) { (kind, info) ->
                            CollectionCard(kind.label, info.first, info.second) { vm.stack.add(Screen.Grid(Source.Collection(kind))) }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Header("More") }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        MoreRow(Icons.Outlined.FavoriteBorder, "Favourites", "$favCount") { vm.stack.add(Screen.Grid(Source.Favorites)) }
                        MoreRow(Icons.Outlined.Videocam, "Videos", "$vidCount") { vm.stack.add(Screen.Grid(Source.Videos)) }
                        MoreRow(Icons.Outlined.Shield, "Private", null) { vm.stack.add(Screen.Grid(Source.Vault)) }
                        MoreRow(Icons.Outlined.Delete, "Recently deleted", "${vm.trash.size}", last = true) { vm.stack.add(Screen.Grid(Source.Trash)) }
                    }
                }
            }
        }
    }
    when (val d = dialog) {
        null -> {}
        is AlbumDlg.Rename -> RenameAlbumDialog(vm, d.a) { dialog = null }
        is AlbumDlg.Cover -> CoverPicker(vm, d.a) { dialog = null }
    }
}

private sealed interface AlbumDlg {
    data class Rename(val a: Album) : AlbumDlg
    data class Cover(val a: Album) : AlbumDlg
}


@Composable
private fun Header(text: String) = Text(
    text, Modifier.padding(top = 18.dp, start = 2.dp), style = MaterialTheme.typography.titleLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)),
    fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface,
)


@Composable
private fun CollectionCard(label: String, cover: Media?, count: Int, onClick: () -> Unit) {
    Box(Modifier.size(width = 132.dp, height = 96.dp).clip(CardShape).clickable(onClick = onClick)) {
        AsyncImage(cover?.let { ThumbReq(it.uri, 320, it.isVideo) }, label, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.7f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text("$count", color = Color.White.copy(.8f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumItem(vm: GalleryViewModel, a: Album, ask: Ask, open: (AlbumDlg) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val hidden = a.id in vm.hiddenAlbums
    val pinned = a.id in vm.pinned
    val go = { vm.stack.add(Screen.Grid(Source.Bucket(a.id, a.folder))); Unit }
    Box {
        val click = Modifier.clip(CardShape).combinedClickable(onClick = go, onLongClick = { menu = true })
        if (vm.albumList) Row(click.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                ThumbReq(a.cover.uri, 160, a.cover.isVideo), a.name, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop, alpha = if (hidden) .5f else 1f,
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${a.count} items  ·  ${a.path.trimEnd('/')}", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pinned) Icon(Icons.Filled.PushPin, "Pinned", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Album options") }
        } else Column(click) {
            Box {
                AsyncImage(
                    ThumbReq(a.cover.uri, 320, a.cover.isVideo), a.name,
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(CardShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop, alpha = if (hidden) .5f else 1f,
                )
                if (pinned) Icon(
                    Icons.Filled.PushPin, "Pinned",
                    Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(.45f), CircleShape).padding(4.dp).size(14.dp),
                    tint = Color.White,
                )
            }
            Text(
                a.name, Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp), style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Text(
                "${a.count}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        AppMenu(menu, { menu = false }) {
            MenuItem("Rename", Icons.Outlined.Edit) { menu = false; open(AlbumDlg.Rename(a)) }
            MenuItem("Change cover", Icons.Outlined.Image) { menu = false; open(AlbumDlg.Cover(a)) }
            MenuItem("Add photos here", Icons.Outlined.AddPhotoAlternate) { menu = false; vm.stack.add(Screen.Pick(a.path, a.name)) }
            MenuItem(if (pinned) "Unpin" else "Pin to top", Icons.Outlined.PushPin) { menu = false; vm.togglePin(a.id) }
            MenuItem(if (hidden) "Unhide album" else "Hide album", if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff) {
                menu = false; vm.toggleHiddenAlbum(a.id)
            }
            MenuItem("Move album to trash", Icons.Outlined.Delete) {
                menu = false; ask(vm.trashRequest(vm.all.filter { it.bucketId == a.id }, true), null)
            }
        }
    }
}

@Composable
private fun RenameAlbumDialog(vm: GalleryViewModel, a: Album, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(a.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename album") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Album name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "Changes the name shown in Gallery. The folder \"${a.folder}\" stays the same, so other apps keep working.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton({ vm.renameAlbum(a.id, name.takeIf { it != a.folder }); onDismiss() }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (a.id in vm.albumNames) TextButton({ vm.renameAlbum(a.id, null); onDismiss() }) { Text("Reset") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CoverPicker(vm: GalleryViewModel, a: Album, onDismiss: () -> Unit) {
    var everywhere by remember { mutableStateOf(false) }
    val items = remember(vm.all, everywhere) {
        (if (everywhere) vm.items(Source.All) else vm.all.filter { it.bucketId == a.id }.sortedByDescending { it.date })
            .filter { !it.isVideo }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Choose cover")
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!everywhere, { everywhere = false }, { Text("This album") })
                    FilterChip(everywhere, { everywhere = true }, { Text("All photos") })
                }
            }
        },
        text = {
            LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.heightIn(max = 420.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { it.id }) { m ->
                    val current = m.id == a.cover.id
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .then(if (current) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { vm.setAlbumCover(a.id, m.id); onDismiss() },
                    ) {
                        AsyncImage(ThumbReq(m.uri, 256, m.isVideo), m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (current) Icon(
                            Icons.Filled.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Cancel") } },
        dismissButton = { if (a.id in vm.albumCovers) TextButton({ vm.setAlbumCover(a.id, null); onDismiss() }) { Text("Use newest") } },
    )
}


/** One row of the "More" card: icon, label, count, chevron; divider under all but the last. */
@Composable
private fun MoreRow(icon: ImageVector, label: String, count: String?, last: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(label, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
            if (count != null) Text(count, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 58.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
    }
}
