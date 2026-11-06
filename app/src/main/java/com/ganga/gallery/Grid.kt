package com.ganga.gallery

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun Source.title() = when (this) {
    Source.All -> "All"
    Source.Images -> "Photos"
    Source.Favorites -> "Favorites"
    Source.Videos -> "Videos"
    Source.Trash -> "Trash"
    Source.Vault -> "Private"
    is Source.Bucket -> name
    is Source.Collection -> kind.label
    is Source.Single -> m.name
}

private sealed interface Cell {
    data class Header(val label: String, val ids: List<Long>) : Cell
    data class Item(val m: Media) : Cell
}

private val DAY = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy")
fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DAY)
    }
}

private fun groupLabel(vm: GalleryViewModel, m: Media): String {
    val d = dayOf(if (vm.sortBy == SortBy.MODIFIED) m.modified else m.date)
    return when (vm.groupBy) {
        GroupBy.DAY, GroupBy.NONE -> dayLabel(d)
        GroupBy.MONTH -> d.format(MONTH)
        GroupBy.YEAR -> d.year.toString()
    }
}

private val SortBy.isDate get() = this == SortBy.TAKEN || this == SortBy.MODIFIED

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(vm: GalleryViewModel, source: Source, ask: Ask, onBack: (() -> Unit)?, pick: Screen.Pick? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var menu by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    val items = remember(vm.all, vm.trash, vm.vault, source, query, vm.sortBy, vm.ascending, vm.kinds, vm.hiddenItems, vm.hiddenAlbums, vm.showHidden) {
        vm.items(source, query)
    }
    val byId = remember(items) { items.associateBy { it.id } }
    val sel = remember(selected, byId) { items.filter { it.id in selected } }
    val grouped = vm.sortBy.isDate && vm.groupBy != GroupBy.NONE
    val cells = remember(items, grouped, vm.groupBy) {
        if (!grouped) items.map<Media, Cell> { Cell.Item(it) }
        else buildList<Cell> {
            // items are date-sorted, so groupBy keeps order
            items.groupBy { groupLabel(vm, it) }.forEach { (label, g) ->
                add(Cell.Header(label, g.map { it.id }))
                g.forEach { add(Cell.Item(it)) }
            }
        }
    }
    val inTrash = source == Source.Trash
    val inVault = source == Source.Vault
    val bucket = source as? Source.Bucket
    // picker mode: every tap selects; the bar offers "Move here" / "Copy here"
    val selecting = selected.isNotEmpty() || pick != null
    val title = bucket?.let { vm.albumName(it.id, it.name) } ?: source.title()
    val cols = if (isLandscape()) (vm.columns * 1.8f).toInt() else vm.columns
    val haptic = LocalHapticFeedback.current
    var suppressClickUntil by remember { mutableLongStateOf(0L) }
    var dragSelecting by remember { mutableStateOf(false) }
    BackHandler(selecting) { selected = emptySet() }
    BackHandler(searching && !selecting) { searching = false; query = "" }
    val done = { selected = emptySet() }
    LaunchedEffect(selecting) { vm.selectionActive = selecting }
    DisposableEffect(Unit) { onDispose { vm.selectionActive = false } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    when {
                        pick != null -> IconButton({ onBack?.invoke() }) { Icon(Icons.Default.Close, "Cancel") }
                        selecting -> IconButton(done) { Icon(Icons.Default.Close, "Clear selection") }
                        searching -> IconButton({ searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                        onBack != null -> IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                title = {
                    when {
                        pick != null -> Column {
                            Text("Add to ${pick.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("${selected.size} selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        selecting -> Text("${selected.size} selected", maxLines = 1)
                        searching -> TextField(
                            query, { query = it }, placeholder = { Text("Search by name") }, singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (inVault) "Locks when you leave" else "${items.size} items", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        val all = selected.size == items.size
                        IconButton({ selected = if (all) emptySet() else items.map { it.id }.toSet() }) {
                            Icon(if (all) Icons.Default.Deselect else Icons.Default.SelectAll, if (all) "Select none" else "Select all")
                        }
                    } else if (!searching) {
                        IconButton({ searching = true }) { Icon(Icons.Default.Search, "Search") }
                        IconButton({ options = true }) { Icon(Icons.Default.Tune, "View options") }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                            AppMenu(menu, { menu = false }) {
                                if (bucket != null && items.isNotEmpty()) {
                                    MenuItem("Add photos here", Icons.Outlined.AddPhotoAlternate) {
                                        menu = false; vm.stack.add(Screen.Pick(items.first().path, title))
                                    }
                                }
                                if (inVault) {
                                    if (items.isNotEmpty()) MenuItem("Slideshow", Icons.Outlined.Slideshow) {
                                        menu = false; vm.stack.add(Screen.Viewer(source, query, items.first().id, slideshow = true))
                                    }
                                    MenuItem("Lock now", Icons.Outlined.Lock) { menu = false; vm.vaultUnlocked = false; onBack?.invoke() }
                                } else if (!inTrash) {
                                    MenuItem("Open camera", Icons.Outlined.PhotoCamera) { menu = false; openCamera(ctx) }
                                    if (items.isNotEmpty()) MenuItem("Slideshow", Icons.Outlined.Slideshow) {
                                        menu = false; vm.stack.add(Screen.Viewer(source, query, items.first().id, slideshow = true))
                                    }
                                    MenuItem(if (vm.showHidden) "Stop showing hidden" else "Temporarily show hidden", Icons.Outlined.VisibilityOff) {
                                        menu = false; vm.showHidden = !vm.showHidden
                                    }
                                } else if (items.isNotEmpty()) {
                                    MenuItem("Empty trash", Icons.Outlined.DeleteForever) { menu = false; ask(vm.deleteRequest(items), null) }
                                }
                                MenuItem("Settings", Icons.Outlined.Settings) { menu = false; vm.stack.add(Screen.Settings) }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            AnimatedVisibility(selecting, enter = fadeIn(), exit = fadeOut()) {
                when {
                    pick != null -> PickBar(vm, sel, pick, ask, onBack)
                    inVault -> VaultBar(vm, sel, done)
                    else -> SelectionBar(vm, sel, inTrash, bucket, ask, done) { dialog = it }
                }
            }
        },
    ) { pad ->
        val state = rememberLazyGridState()
        val dir = LocalLayoutDirection.current
        // sides too: in landscape the notch and the nav bar sit left/right of the grid
        PullToRefreshBox(vm.refreshing, { vm.reload(manual = true) }, Modifier.fillMaxSize().padding(
            start = pad.calculateStartPadding(dir), top = pad.calculateTopPadding(), end = pad.calculateEndPadding(dir),
        )) {
            when {
                vm.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                items.isEmpty() -> Empty(
                    when {
                        query.isNotBlank() -> "No matches for \"$query\""
                        inTrash -> "Trash is empty"
                        inVault -> "Nothing here yet\nSelect photos, then tap More → Move to Private"
                        source == Source.Favorites -> "Tap the heart on a photo to add it here"
                        else -> "Nothing here yet"
                    },
                )
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        state = state,
                        contentPadding = PaddingValues(
                            start = if (vm.roundedThumbs) 4.dp else 0.dp, end = if (vm.roundedThumbs) 4.dp else 0.dp,
                            bottom = pad.calculateBottomPadding() + 8.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(if (vm.roundedThumbs) 4.dp else 2.dp),
                        verticalArrangement = Arrangement.spacedBy(if (vm.roundedThumbs) 4.dp else 2.dp),
                        modifier = Modifier.fillMaxSize().pinchToResize(vm).dragToSelect(
                            state, cells,
                            idAt = { i -> (cells.getOrNull(i) as? Cell.Item)?.m?.id },
                            current = { selected },
                            onChange = { selected = it },
                            onStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            suppressClickUntil = { suppressClickUntil = it },
                            onActive = { dragSelecting = it },
                        ),
                        // the finger is selecting, not scrolling (edge auto-scroll still works)
                        userScrollEnabled = !dragSelecting,
                    ) {
                        items(
                            cells, key = { if (it is Cell.Item) it.m.id else "h" + (it as Cell.Header).label },
                            span = { if (it is Cell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                            contentType = { it::class },
                        ) { cell ->
                            when (cell) {
                                is Cell.Header -> Row(
                                    Modifier.fillMaxWidth().padding(16.dp, 18.dp, 8.dp, 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(cell.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    if (selecting) {
                                        val all = selected.containsAll(cell.ids)
                                        IconButton({ selected = if (all) selected - cell.ids.toSet() else selected + cell.ids }, Modifier.size(28.dp)) {
                                            Icon(
                                                if (all) Icons.Default.CheckCircle else Icons.Outlined.Circle, "Select group",
                                                tint = if (all) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                is Cell.Item -> {
                                    val m = cell.m
                                    val isSel = m.id in selected
                                    Thumb(
                                        m, selecting, isSel, vm.isHidden(m), vm.roundedThumbs, vm.imageEpoch, compact = cols >= 5,
                                        onClick = {
                                            // the finger lifting after a long-press/drag isn't a tap
                                            if (android.os.SystemClock.uptimeMillis() >= suppressClickUntil) {
                                                if (selecting) selected = if (isSel) selected - m.id else selected + m.id
                                                else vm.stack.add(Screen.Viewer(source, query, m.id))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    FastScroller(
                        state, cells.size, Modifier.align(Alignment.CenterEnd).padding(bottom = pad.calculateBottomPadding()),
                    ) { i ->
                        when (val c = cells.getOrNull(i)) {
                            is Cell.Header -> c.label
                            is Cell.Item -> when {
                                vm.sortBy.isDate -> groupLabel(vm, c.m)
                                vm.sortBy == SortBy.NAME -> c.m.name.take(1).uppercase()
                                vm.sortBy == SortBy.PATH -> c.m.path.trimEnd('/').substringAfterLast('/')
                                vm.sortBy == SortBy.SIZE -> android.text.format.Formatter.formatShortFileSize(ctx, c.m.size)
                                else -> null
                            }
                            null -> null
                        }
                    }
                }
            }
        }
    }

    if (options) ViewOptionsSheet(vm) { options = false }
    MediaDialogs(vm, dialog, ask, onDone = { dialog = null; done() }, onDismiss = { dialog = null })
}

@Composable
fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun MenuItem(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
    { Text(text, style = MaterialTheme.typography.bodyLarge) }, onClick, enabled = enabled,
    leadingIcon = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
)

/** Every menu in the app: solid (never see-through), rounded, lifted off the page so it's always readable. */
@Composable
fun AppMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) = DropdownMenu(
    expanded, onDismissRequest,
    shape = RoundedCornerShape(18.dp),
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f),
    tonalElevation = 0.dp, shadowElevation = 10.dp,
    content = content,
)

/**
 * Long-press a tile, keep the finger down and slide: every tile between the first one and the finger is
 * selected (or deselected, if the first was already selected). Scrolls by itself near the top/bottom edge.
 */
fun Modifier.dragToSelect(
    state: LazyGridState, key: Any, idAt: (Int) -> Long?, current: () -> Set<Long>, onChange: (Set<Long>) -> Unit,
    onStart: () -> Unit, suppressClickUntil: (Long) -> Unit, onActive: (Boolean) -> Unit,
) = composed {
    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    pointerInput(key) {
        var anchor = -1
        var base = emptySet<Long>()
        var adding = true
        var pos = Offset.Zero
        var scroller: kotlinx.coroutines.Job? = null
        fun indexAt(p: Offset) = state.layoutInfo.visibleItemsInfo.firstOrNull {
            p.x >= it.offset.x && p.x < it.offset.x + it.size.width && p.y >= it.offset.y && p.y < it.offset.y + it.size.height
        }?.index
        fun update() {
            val i = indexAt(pos) ?: return
            if (anchor < 0) return
            val range = (minOf(anchor, i)..maxOf(anchor, i)).mapNotNull(idAt).toSet()
            onChange(if (adding) base + range else base - range)
        }
        fun stop() { anchor = -1; scroller?.cancel(); scroller = null; onActive(false); suppressClickUntil(android.os.SystemClock.uptimeMillis() + 400) }
        detectDragGesturesAfterLongPress(
            onDragStart = { p ->
                val i = indexAt(p) ?: return@detectDragGesturesAfterLongPress
                val id = idAt(i) ?: return@detectDragGesturesAfterLongPress
                anchor = i; pos = p; base = current(); adding = id !in base
                onStart(); onActive(true); update()
                suppressClickUntil(Long.MAX_VALUE)
                scroller = scope.launch {
                    while (true) {
                        val h = size.height
                        val dy = when { pos.y < edge -> -(edge - pos.y) / 3f; pos.y > h - edge -> (pos.y - (h - edge)) / 3f; else -> 0f }
                        if (dy != 0f) { state.scrollBy(dy); update() }
                        kotlinx.coroutines.delay(16)
                    }
                }
            },
            onDrag = { change, _ -> change.consume(); pos = change.position; update() },
            onDragEnd = { stop() },
            onDragCancel = { stop() },
        )
    }
}

/** Bottom action bar while selecting. Labels under icons so nothing gets squeezed. */
@Composable
private fun SelectionBar(vm: GalleryViewModel, sel: List<Media>, inTrash: Boolean, bucket: Source.Bucket?, ask: Ask, done: () -> Unit, open: (Dlg) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var more by remember { mutableStateOf(false) }
    val one = sel.singleOrNull()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (sel.isEmpty()) return@Row
            if (inTrash) {
                BarAction(Icons.Outlined.RestoreFromTrash, "Restore") { ask(vm.trashRequest(sel, false), done) }
                BarAction(Icons.Outlined.DeleteForever, "Delete") { ask(vm.deleteRequest(sel), done) }
                return@Row
            }
            val allFav = sel.isNotEmpty() && sel.all { it.favorite }
            BarAction(Icons.Outlined.Share, "Share") { share(ctx, sel) }
            BarAction(if (allFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (allFav) "Unfavorite" else "Favorite") {
                ask(vm.favoriteRequest(sel, !allFav), done)
            }
            BarAction(Icons.Outlined.DriveFileMove, "Move") { open(Dlg.Move(sel, copy = false)) }
            BarAction(Icons.Outlined.Delete, "Delete") { ask(vm.trashRequest(sel, true), done) }
            Box {
                BarAction(Icons.Default.MoreHoriz, "More") { more = true }
                AppMenu(more, { more = false }) {
                    MenuItem("Copy to album", Icons.Outlined.ContentCopy) { more = false; open(Dlg.Move(sel, copy = true)) }
                    MenuItem("Move to Private", Icons.Outlined.Lock) { more = false; vm.moveToVault(sel, ask); done() }
                    val images = sel.filter { it.mime == "image/jpeg" || it.mime == "image/png" }
                    MenuItem("Rotate right", Icons.Outlined.RotateRight, enabled = images.isNotEmpty()) {
                        more = false; ask(vm.writeRequest(images)) { vm.rotate(images); done() }
                    }
                    val allHidden = sel.all { vm.isHidden(it) }
                    MenuItem(if (allHidden) "Unhide" else "Hide", if (allHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff) {
                        more = false; vm.setHidden(sel, !allHidden); done()
                    }
                    if (one != null) {
                        MenuItem("Rename", Icons.Outlined.DriveFileRenameOutline) { more = false; open(Dlg.Rename(one)) }
                        if (bucket != null && !one.isVideo) MenuItem("Set as album cover", Icons.Outlined.Image) { more = false; vm.setAlbumCover(bucket.id, one.id); done() }
                        MenuItem("Properties", Icons.Outlined.Info) { more = false; open(Dlg.Info(one)) }
                        if (!one.isVideo && !one.isGif) MenuItem("Edit", Icons.Outlined.Edit) { more = false; vm.stack.add(Screen.Editor(one)); done() }
                        if (one.isVideo) MenuItem("Edit video", Icons.Outlined.ContentCut) { more = false; vm.stack.add(Screen.VideoEditor(one)); done() }
                        if (!one.isVideo && !one.isGif) MenuItem("Resize", Icons.Outlined.PhotoSizeSelectLarge) { more = false; open(Dlg.Resize(one)) }
                        if (!one.isVideo) MenuItem("Set as wallpaper", Icons.Outlined.Wallpaper) { more = false; open(Dlg.Wallpaper(one)) }
                        if (!one.isVideo) MenuItem("Set as…", Icons.Outlined.AccountBox) { more = false; setAs(ctx, one) }
                        MenuItem("Open with…", Icons.AutoMirrored.Outlined.OpenInNew) { more = false; openWith(ctx, one) }
                        MenuItem("Create shortcut", Icons.Outlined.AddToHomeScreen) {
                            more = false; scope.launch(Dispatchers.IO) { createShortcut(ctx, one) }; done()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickBar(vm: GalleryViewModel, sel: List<Media>, pick: Screen.Pick, ask: Ask, onBack: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton({ vm.copy(sel, pick.path); onBack?.invoke() }, Modifier.weight(1f), enabled = sel.isNotEmpty()) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy here")
            }
            Button({ ask(vm.writeRequest(sel)) { vm.move(sel, pick.path); onBack?.invoke() } }, Modifier.weight(1f), enabled = sel.isNotEmpty()) {
                Icon(Icons.Outlined.DriveFileMove, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Move here")
            }
        }
    }
}

@Composable
private fun VaultBar(vm: GalleryViewModel, sel: List<Media>, done: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarAction(Icons.Outlined.LockOpen, "Move out") { vm.restoreFromVault(sel); done() }
            BarAction(Icons.Outlined.DeleteForever, "Delete") { confirm = true }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete ${sel.size} item(s) forever?") },
        text = { Text("Private items don't go to trash. This can't be undone.") },
        confirmButton = { TextButton({ confirm = false; vm.deleteFromVault(sel); done() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

@Composable
fun BarAction(icon: ImageVector, label: String, tint: Color = LocalContentColor.current, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).combinedClickableNoLong(onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoLong(onClick: () -> Unit) = combinedClickable(onClick = onClick)

/** Two-finger pinch on the grid changes column count. One finger scrolls as normal. */
fun Modifier.pinchToResize(vm: GalleryViewModel) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        do {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.changes.size >= 2) {
                zoom *= e.calculateZoom()
                if (zoom > 1.3f) { vm.setCols(vm.columns - 1); zoom = 1f }
                if (zoom < 0.75f) { vm.setCols(vm.columns + 1); zoom = 1f }
                e.changes.forEach { it.consume() }
            }
        } while (e.changes.any { it.pressed })
    }
}

/** Draggable thumb on the right edge with a label bubble. Appears while scrolling. */
@Composable
private fun FastScroller(state: LazyGridState, count: Int, modifier: Modifier, label: (Int) -> String?) {
    if (count < 60) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true else { delay(1500); visible = false }
    }
    val frac = if (dragging) dragFrac else state.firstVisibleItemIndex / (count - 1f)
    BoxWithConstraints(modifier.fillMaxHeight().width(160.dp).padding(vertical = 8.dp)) {
        val thumbH = 48.dp
        val trackPx = with(density) { (maxHeight - thumbH).toPx() }
        val y = (frac.coerceIn(0f, 1f) * trackPx).toInt()
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }
                        .size(width = 24.dp, height = thumbH)
                        .draggable(
                            rememberDraggableState { d ->
                                dragFrac = (dragFrac + d / trackPx).coerceIn(0f, 1f)
                                scope.launch { state.scrollToItem((dragFrac * (count - 1)).toInt()) }
                            },
                            Orientation.Vertical,
                            onDragStarted = { dragging = true; dragFrac = frac },
                            onDragStopped = { dragging = false },
                        ),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier.padding(end = 3.dp).size(width = if (dragging) 6.dp else 4.dp, height = thumbH)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else .7f), RoundedCornerShape(3.dp)),
                    )
                }
                val text = if (dragging) label((dragFrac * (count - 1)).toInt()) else null
                if (text != null) Text(
                    text, Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.padding(end = 24.dp, top = 8.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumb(
    m: Media, selecting: Boolean, isSel: Boolean, hidden: Boolean, rounded: Boolean, epoch: Int, compact: Boolean,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val corner = if (rounded) (if (compact) 6.dp else 8.dp) else 0.dp
    // selection shrinks the photo smoothly instead of jumping
    val selScale by animateFloatAsState(if (isSel) 0.86f else 1f, tween(160), label = "sel")
    val selCorner by animateDpAsState(if (isSel) 10.dp else corner, tween(160), label = "corner")
    var thumbFailed by remember(m.uri) { mutableStateOf(false) }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(corner)).clickable(onClick = onClick)) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(m.uri).memoryCacheKey("t${m.id}:${m.size}:${m.modified}:$epoch").build(),
            contentDescription = m.name,
            contentScale = ContentScale.Crop,
            alpha = if (hidden) 0.5f else 1f,
            onError = { thumbFailed = true },
            modifier = Modifier.fillMaxSize().scale(selScale).clip(RoundedCornerShape(selCorner))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        if (m.isVideo) Row(
            Modifier.align(Alignment.BottomEnd).padding(if (compact) 3.dp else 5.dp)
                .background(Color.Black.copy(.6f), RoundedCornerShape(if (compact) 4.dp else 6.dp))
                .padding(horizontal = if (compact) 3.dp else 5.dp, vertical = if (compact) 0.dp else 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) Icon(Icons.Default.PlayArrow, null, Modifier.size(13.dp), tint = Color.White)
            Text(
                duration(m.duration), color = Color.White, fontSize = if (compact) 9.sp else 11.sp,
                maxLines = 1, softWrap = false, lineHeight = if (compact) 11.sp else 14.sp,
            )
        }
        // unreadable format: show its extension instead of an empty tile
        if (thumbFailed) Text(
            m.name.substringAfterLast('.', "?").uppercase().take(4), Modifier.align(Alignment.Center),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = if (compact) 9.sp else 12.sp, fontWeight = FontWeight.Bold,
        )
        if (m.isGif) Text(
            "GIF", Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color.Black.copy(.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        )
        if (m.favorite) Icon(
            Icons.Default.Favorite, null,
            Modifier.align(Alignment.BottomStart).padding(if (compact) 3.dp else 5.dp).size(if (compact) 11.dp else 15.dp), tint = Color.White,
        )
        if (selecting) Icon(
            if (isSel) Icons.Default.CheckCircle else Icons.Outlined.Circle, null,
            Modifier.align(Alignment.TopStart).padding(if (compact) 3.dp else 6.dp).size(if (compact) 16.dp else 22.dp)
                .background(if (isSel) MaterialTheme.colorScheme.surface else Color.Black.copy(.15f), CircleShape),
            tint = if (isSel) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ViewOptionsSheet(vm: GalleryViewModel, onDismiss: () -> Unit) {
    // open fully, so the last row isn't left half-hidden behind the navigation buttons
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Sort by", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortBy.entries.forEach { s -> FilterChip(vm.sortBy == s, { vm.setSort(s, vm.ascending) }, { Text(s.label) }) }
            }
            if (vm.sortBy != SortBy.RANDOM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!vm.ascending, { vm.setSort(vm.sortBy, false) }, { Text("Descending") }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null, Modifier.size(16.dp)) })
                FilterChip(vm.ascending, { vm.setSort(vm.sortBy, true) }, { Text("Ascending") }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null, Modifier.size(16.dp)) })
            }
            Spacer(Modifier.height(8.dp))
            Text("Group by", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupBy.entries.forEach { g -> FilterChip(vm.groupBy == g, { vm.setGroup(g) }, { Text(g.label) }, enabled = vm.sortBy.isDate) }
            }
            if (!vm.sortBy.isDate) Text(
                "Grouping works when sorted by date", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("Show", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kind.entries.forEach { k -> FilterChip(k in vm.kinds, { vm.toggleKind(k) }, { Text(k.label) }) }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Columns", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                FilledTonalIconButton({ vm.setCols(vm.columns - 1) }, enabled = vm.columns > 2) { Icon(Icons.Default.Remove, "Fewer columns") }
                Text("${vm.columns}", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                FilledTonalIconButton({ vm.setCols(vm.columns + 1) }, enabled = vm.columns < 7) { Icon(Icons.Default.Add, "More columns") }
            }
            Text("Tip: pinch the grid with two fingers to change columns.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
