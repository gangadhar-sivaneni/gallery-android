package com.ganga.gallery

import android.app.Application
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns as C
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties

data class Media(
    val id: Long,
    val uri: Uri,
    val name: String,
    val date: Long, // ms, date taken (falls back to modified)
    val modified: Long, // ms
    val size: Long,
    val width: Int,
    val height: Int,
    val mime: String,
    val bucketId: Long,
    val bucket: String,
    val path: String, // relative path, e.g. "DCIM/Camera/"
    val duration: Long,
    val favorite: Boolean,
) {
    val isVideo get() = mime.startsWith("video")
    val isGif get() = mime == "image/gif"
}

data class Album(val id: Long, val name: String, val folder: String, val path: String, val cover: Media, val count: Int, val latest: Long, val size: Long)

enum class SortBy(val label: String) { TAKEN("Date taken"), MODIFIED("Last modified"), NAME("Name"), PATH("Path"), SIZE("Size"), RANDOM("Random") }
enum class GroupBy(val label: String) { NONE("None"), DAY("Day"), MONTH("Month"), YEAR("Year") }
enum class Kind(val label: String) { IMAGES("Images"), VIDEOS("Videos"), GIFS("GIFs") }
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), BLACK("Black"), PHOTO("Photo") }
enum class AlbumSort(val label: String) { RECENT("Recent"), NAME("Name"), COUNT("Item count"), SIZE("Size") }
enum class StartTab(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), ALBUMS("Albums") }

/** Which date places an item on the timeline. */
enum class DateMode(val label: String, val hint: String) {
    SMART("Smart", "Camera photos use the date taken; downloads and shared files use the day they arrived"),
    TAKEN("Date taken", "Always the date stored inside the file"),
    ADDED("Date added", "The day the file arrived on this phone"),
}

enum class PinKind(val label: String) { APP("App lock"), VAULT("Private folder") }
enum class PinAction { CHANGE, ENABLE_APP_LOCK, DISABLE_APP_LOCK }

/** Automatic collections built from where files live; no manual sorting needed. */
enum class Smart(val label: String) {
    CAMERA("Camera"), SCREENSHOTS("Screenshots"), WHATSAPP("WhatsApp"), DOWNLOADS("Downloads"),
    RECENT("Last 30 days"), LARGE("Large files");

    fun matches(m: Media, now: Long): Boolean {
        val p = m.path.lowercase()
        return when (this) {
            CAMERA -> p.startsWith("dcim/camera")
            SCREENSHOTS -> "screenshot" in p || "screen_record" in p || "screenrecord" in p
            WHATSAPP -> "whatsapp" in p
            DOWNLOADS -> p.startsWith("download")
            RECENT -> now - m.date < 30L * 24 * 3600 * 1000
            LARGE -> m.size > 20L * 1024 * 1024
        }
    }
}

/** What a grid shows: everything, one album, or a virtual album. */
sealed interface Source {
    data object All : Source
    data object Favorites : Source
    data object Videos : Source
    data object Images : Source
    data object Trash : Source
    data object Vault : Source
    data class Bucket(val id: Long, val name: String) : Source
    data class Collection(val kind: Smart) : Source
    /** A single file opened from another app; may not be in MediaStore. */
    data class Single(val m: Media) : Source
}

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Pin(val kind: PinKind, val action: PinAction) : Screen
    data class Grid(val source: Source) : Screen
    /** Pick photos from anywhere to move/copy into [path]. */
    data class Pick(val path: String, val name: String) : Screen
    data class Editor(val m: Media) : Screen
    data class VideoEditor(val m: Media) : Screen
    data class WallpaperCrop(val m: Media, val which: Int) : Screen
    data class Viewer(val source: Source, val query: String, val startId: Long, val slideshow: Boolean = false, val exitOnBack: Boolean = false) : Screen
}

private val PinKind.key get() = if (this == PinKind.VAULT) "pinHash" else "appPinHash"

/** DCIM/, DCIM/Camera/, or numbered camera folders like DCIM/100ANDRO/ */
/** Longest side we decode for edits; bigger photos can only be saved as a copy. */
const val MAX_EDIT = 6000

/** True if MediaStore lets this kind of file live in [path]. */
fun canLiveIn(isVideo: Boolean, path: String) =
    path.startsWith("DCIM/") || path.startsWith("Pictures/") || (isVideo && path.startsWith("Movies/"))
fun canLiveIn(m: Media, path: String) = canLiveIn(m.isVideo, path)

/** [path] if allowed, otherwise Pictures/Edited/ (e.g. for files from Download/ or WhatsApp). */
fun writableDir(isVideo: Boolean, path: String) = if (canLiveIn(isVideo, path)) path else "Pictures/Edited/"
fun writableDir(m: Media, path: String) = writableDir(m.isVideo, path)

private val CAMERA_DIR = Regex("""^DCIM/((Camera|\d{3}[A-Za-z_]+)/)?$""", RegexOption.IGNORE_CASE)

/** EXIF orientation after a 90° clockwise turn; null for mirrored orientations (re-encode those instead). */
fun nextClockwise(o: Int): Int? = when (o) {
    ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL -> ExifInterface.ORIENTATION_ROTATE_90
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifInterface.ORIENTATION_ROTATE_180
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifInterface.ORIENTATION_ROTATE_270
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifInterface.ORIENTATION_NORMAL
    else -> null
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("gallery", 0).also { p ->
        // v2.0 stored the PIN hash under "pin", the same key as pinned albums; move it so they can't collide
        val old = p.all["pin"]
        if (old is String) p.edit().putString(PinKind.VAULT.key, old).remove("pin").commit()
        // app lock used to share the private-folder PIN; give it its own copy so they can differ from now on
        if (p.getBoolean("appLock", false) && !p.contains(PinKind.APP.key) && p.contains(PinKind.VAULT.key)) {
            p.edit().putString(PinKind.APP.key, p.getString(PinKind.VAULT.key, null)).commit()
        }
    }
    private val positions = app.getSharedPreferences("video_positions", 0)

    var all by mutableStateOf<List<Media>>(emptyList()); private set
    var trash by mutableStateOf<List<Media>>(emptyList()); private set
    var vault by mutableStateOf<List<Media>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set

    // --- persisted view settings ---
    var sortBy by mutableStateOf(enumPref("sort2", SortBy.TAKEN)); private set
    var ascending by mutableStateOf(prefs.getBoolean("asc", false)); private set
    var groupBy by mutableStateOf(enumPref("group", GroupBy.DAY)); private set
    var columns by mutableStateOf(prefs.getInt("cols", 4)); private set
    var albumColumns by mutableStateOf(prefs.getInt("acols", 2)); private set
    var albumList by mutableStateOf(prefs.getBoolean("alist", false)); private set
    var albumSort by mutableStateOf(enumPref("asort", AlbumSort.RECENT)); private set
    var kinds by mutableStateOf(prefs.getStringSet("kinds", null)?.map { Kind.valueOf(it) }?.toSet() ?: Kind.entries.toSet()); private set
    var hiddenAlbums by mutableStateOf(longSet("hAlb")); private set
    var hiddenItems by mutableStateOf(longSet("hItm")); private set
    var pinned by mutableStateOf(longSet("pin")); private set
    var albumNames by mutableStateOf(map("aName")); private set
    var albumCovers by mutableStateOf(map("aCover")); private set
    var recentTargets by mutableStateOf(prefs.getString("recentT", "")!!.split('\n').filter { it.isNotEmpty() }); private set
    var showHidden by mutableStateOf(false)
    /** Bumped after an edit/rotate so every on-screen image re-requests (same URI, new pixels). */
    var imageEpoch by mutableStateOf(0); private set
    /** A grid is in selection mode; Home hides its tab bar so the action bar takes its place. */
    var selectionActive by mutableStateOf(false)
    /** Brightness set by swiping on a video; only applies while the viewer is open. */
    var gestureBrightness by mutableStateOf<Float?>(null)

    // --- app settings ---
    var theme by mutableStateOf(enumPref("theme", ThemeMode.SYSTEM)); private set
    var accent by mutableStateOf(prefs.getInt("accent", 0xFF1A73E8.toInt())); private set
    var wallpaperColors by mutableStateOf(prefs.getBoolean("wpColors", false)); private set
    var backgroundDim by mutableStateOf(prefs.getFloat("bgDim", 0.45f)); private set
    var backgroundBlur by mutableStateOf(prefs.getBoolean("bgBlur", true)); private set
    var backgroundVersion by mutableStateOf(prefs.getInt("bgVer", 0)); private set
    val backgroundFile: File get() = File(getApplication<Application>().filesDir, "app_background.jpg")
    var startTab by mutableStateOf(enumPref("startTab2", StartTab.ALL)); private set
    var dateMode by mutableStateOf(enumPref("dateMode", DateMode.SMART)); private set
    var roundedThumbs by mutableStateOf(prefs.getBoolean("rounded", true)); private set
    var showCollections by mutableStateOf(prefs.getBoolean("collections", true)); private set
    var includeAppHidden by mutableStateOf(prefs.getBoolean("appHidden", false)); private set
    var autoplay by mutableStateOf(prefs.getBoolean("autoplay", true)); private set
    var loopVideos by mutableStateOf(prefs.getBoolean("loop", false)); private set
    var resumeVideos by mutableStateOf(prefs.getBoolean("resume", true)); private set
    var maxBrightness by mutableStateOf(prefs.getBoolean("bright", false)); private set
    var keepScreenOn by mutableStateOf(prefs.getBoolean("screenOn", false)); private set
    var slideSeconds by mutableStateOf(prefs.getInt("slide", 3)); private set
    var appLock by mutableStateOf(prefs.getBoolean("appLock", false)); private set
    var useBiometric by mutableStateOf(prefs.getBoolean("bio", true)); private set
    var secureScreen by mutableStateOf(prefs.getBoolean("secure", false)); private set

    // --- lock state (not persisted: locks again whenever the app leaves the screen) ---
    var vaultUnlocked by mutableStateOf(false)
    var appUnlocked by mutableStateOf(false)
    fun hasPin(kind: PinKind) = prefs.contains(kind.key)

    val stack = mutableStateListOf<Screen>(Screen.Home)

    /** Runs after the user approves / declines a system write/trash/delete dialog. */
    var afterApproval: (() -> Unit)? = null
    var afterDenial: (() -> Unit)? = null
    /** Denial action for the very next system dialog only; picked up (and cleared) by ask(). */
    var nextDenial: (() -> Unit)? = null

    /** Media id from a home-screen shortcut, opened once loading finishes. */
    var pendingOpen by mutableStateOf<Long?>(null)
    /** File opened from another app; the viewer closes back to that app. */
    var pendingExternal by mutableStateOf<Media?>(null)
    var openedFromOtherApp = false

    private inline fun <reified E : Enum<E>> enumPref(key: String, def: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, def.name)!!) }.getOrDefault(def)
    private fun strings(key: String): Set<String> = (prefs.all[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
    private fun longSet(key: String) = strings(key).mapNotNull { it.toLongOrNull() }.toSet()
    private fun map(key: String): Map<Long, String> = strings(key)
        .mapNotNull { e -> e.split('\t', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.toLongOrNull()?.let { it to v } } }.toMap()
    private fun saveMap(key: String, m: Map<Long, String>) =
        prefs.edit().putStringSet(key, m.map { (k, v) -> "$k\t$v" }.toSet()).apply()
    private fun saveSet(key: String, s: Set<Long>) = prefs.edit().putStringSet(key, s.map { it.toString() }.toSet()).apply()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onMediaChanged()
    }

    init {
        resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
    }

    override fun onCleared() = resolver.unregisterContentObserver(observer)

    private var foreground = false
    private var dirty = false
    private var debounce: Job? = null

    /** Called from the activity's onStart/onStop. Changes seen while hidden are applied once on return. */
    fun setForeground(v: Boolean) {
        foreground = v
        if (v && dirty) { dirty = false; reload() }
    }

    private fun onMediaChanged() {
        if (!foreground) { dirty = true; return } // zero work in the background
        debounce?.cancel()
        debounce = viewModelScope.launch { kotlinx.coroutines.delay(350); reload() } // one reload per burst
    }

    private var job: Job? = null
    fun reload(manual: Boolean = false) {
        if (manual) refreshing = true
        job?.cancel()
        job = viewModelScope.launch {
            val (a, t) = withContext(Dispatchers.IO) { query(trashed = false) to query(trashed = true) }
            all = a; trash = t; loading = false; refreshing = false
        }
    }

    private fun query(trashed: Boolean): List<Media> {
        val args = Bundle().apply {
            // media_type: 1 = image, 3 = video, 0 = none (files in folders other apps mark with .nomedia)
            val media = "media_type IN (1,3)"
            val hidden = "(media_type = 0 AND (mime_type LIKE 'image/%' OR mime_type LIKE 'video/%'))"
            // never: macOS zip leftovers ("._name" files / __MACOSX folders), which are not real images
            val junk = "(relative_path LIKE '%__MACOSX%' OR substr(_display_name, 1, 2) = '._')"
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "(${if (includeAppHidden) "$media OR $hidden" else media}) AND NOT $junk",
            )
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val proj = arrayOf(
            C._ID, C.DISPLAY_NAME, C.DATE_TAKEN, C.DATE_MODIFIED, C.SIZE, C.WIDTH, C.HEIGHT,
            C.MIME_TYPE, C.BUCKET_ID, C.BUCKET_DISPLAY_NAME, C.RELATIVE_PATH, C.DURATION, C.IS_FAVORITE, C.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val mode = dateMode
        val out = ArrayList<Media>()
        resolver.query(MediaStore.Files.getContentUri("external"), proj, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val mime = c.getString(7) ?: continue
                val modified = c.getLong(3) * 1000
                val taken = c.getLong(2)
                val added = c.getLong(13) * 1000
                val path = c.getString(10) ?: ""
                val arrived = if (added > 0) added else modified
                val shown = when (mode) {
                    DateMode.TAKEN -> if (taken > 0) taken else modified
                    DateMode.ADDED -> arrived
                    // files from the camera keep their capture time; downloads/shared files carry
                    // the original recording date inside them, which would bury them in the past
                    // only real camera folders count: downloaders also write into DCIM/<app>/ (e.g. DCIM/SealX/Video/)
                    DateMode.SMART -> if (CAMERA_DIR.matches(path) && taken > 0) taken else arrived
                }
                out += Media(
                    id = id,
                    uri = ContentUris.withAppendedId(
                        if (c.getInt(14) == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) MediaStore.Files.getContentUri("external") else collection(mime),
                        id,
                    ),
                    name = c.getString(1) ?: "",
                    date = shown,
                    modified = modified,
                    size = c.getLong(4),
                    width = c.getInt(5),
                    height = c.getInt(6),
                    mime = mime,
                    bucketId = c.getLong(8),
                    bucket = c.getString(9) ?: "Internal storage",
                    path = path,
                    duration = c.getLong(11),
                    favorite = c.getInt(12) == 1,
                )
            }
        }
        return out
    }

    private fun collection(mime: String) =
        if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun notHidden(m: Media) = showHidden || (m.bucketId !in hiddenAlbums && m.id !in hiddenItems)

    /** Media visible under current filters and hidden settings. */
    private fun visible(list: List<Media>) = list.filter { m ->
        notHidden(m) && when {
            m.isVideo -> Kind.VIDEOS in kinds
            m.isGif -> Kind.GIFS in kinds
            else -> Kind.IMAGES in kinds
        }
    }

    fun sorted(list: List<Media>): List<Media> {
        val s = when (sortBy) {
            SortBy.TAKEN -> list.sortedBy { it.date }
            SortBy.MODIFIED -> list.sortedBy { it.modified }
            SortBy.NAME -> list.sortedBy { it.name.lowercase() }
            SortBy.PATH -> list.sortedBy { (it.path + it.name).lowercase() }
            SortBy.SIZE -> list.sortedBy { it.size }
            SortBy.RANDOM -> list.shuffled(java.util.Random(42)) // stable between reloads
        }
        return if (ascending || sortBy == SortBy.RANDOM) s else s.asReversed()
    }

    fun items(source: Source, query: String = ""): List<Media> {
        val now = System.currentTimeMillis()
        return sorted(
            when (source) {
                Source.All -> visible(all)
                Source.Favorites -> visible(all).filter { it.favorite }
                Source.Videos -> all.filter { it.isVideo && notHidden(it) }
                Source.Images -> all.filter { !it.isVideo && notHidden(it) }
                Source.Trash -> trash
                Source.Vault -> vault
                is Source.Bucket -> visible(all.filter { it.bucketId == source.id })
                is Source.Collection -> visible(all).filter { source.kind.matches(it, now) }
                is Source.Single -> listOf(all.firstOrNull { it.id == source.m.id && it.uri == source.m.uri } ?: source.m)
            }.let { l -> if (query.isBlank()) l else l.filter { it.name.contains(query.trim(), ignoreCase = true) } },
        )
    }

    fun albumName(id: Long, folder: String) = albumNames[id] ?: folder

    fun albums(): List<Album> {
        val byId = all.associateBy { it.id }
        val list = all.filter { showHidden || it.id !in hiddenItems }
            .groupBy { it.bucketId }
            .filterKeys { showHidden || it !in hiddenAlbums }
            .map { (id, m) ->
                val newest = m.maxBy { it.date }
                val cover = albumCovers[id]?.toLongOrNull()?.let { byId[it] } ?: newest
                Album(id, albumName(id, newest.bucket), newest.bucket, newest.path, cover, m.size, newest.date, m.sumOf { it.size })
            }
        val sorted = when (albumSort) {
            AlbumSort.RECENT -> list.sortedByDescending { it.latest }
            AlbumSort.NAME -> list.sortedBy { it.name.lowercase() }
            AlbumSort.COUNT -> list.sortedByDescending { it.count }
            AlbumSort.SIZE -> list.sortedByDescending { it.size }
        }
        return sorted.sortedByDescending { it.id in pinned } // stable: pinned first, rest keep order
    }

    fun collectionCover(kind: Smart): Pair<Media?, Int> {
        val l = items(Source.Collection(kind))
        return l.firstOrNull() to l.size
    }

    // --- settings setters ---
    fun setSort(by: SortBy, asc: Boolean) {
        sortBy = by; ascending = asc
        prefs.edit().putString("sort2", by.name).putBoolean("asc", asc).apply()
    }
    fun setGroup(g: GroupBy) { groupBy = g; prefs.edit().putString("group", g.name).apply() }
    fun setCols(n: Int) { columns = n.coerceIn(2, 7); prefs.edit().putInt("cols", columns).apply() }
    fun setAlbumCols(n: Int) { albumColumns = n.coerceIn(2, 4); prefs.edit().putInt("acols", albumColumns).apply() }
    fun changeAlbumList(v: Boolean) { albumList = v; prefs.edit().putBoolean("alist", v).apply() }
    fun changeAlbumSort(s: AlbumSort) { albumSort = s; prefs.edit().putString("asort", s.name).apply() }
    fun toggleKind(k: Kind) {
        val next = if (k in kinds) kinds - k else kinds + k
        if (next.isEmpty()) return // always show something
        kinds = next; prefs.edit().putStringSet("kinds", next.map { it.name }.toSet()).apply()
    }
    fun changeTheme(t: ThemeMode) { theme = t; prefs.edit().putString("theme", t.name).apply() }
    fun changeAccent(c: Int) { accent = c; wallpaperColors = false; prefs.edit().putInt("accent", c).putBoolean("wpColors", false).apply() }
    fun changeWallpaperColors(v: Boolean) { wallpaperColors = v; prefs.edit().putBoolean("wpColors", v).apply() }
    fun changeBackgroundDim(v: Float) { backgroundDim = v; prefs.edit().putFloat("bgDim", v).apply() }
    fun changeBackgroundBlur(v: Boolean) { backgroundBlur = v; prefs.edit().putBoolean("bgBlur", v).apply() }

    /** Stores a screen-sized copy of the chosen photo in app storage, so the background never depends on the original. */
    fun setBackgroundPhoto(uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 1600) { val s = 1600f / longest; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
                }
                backgroundFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
            }.isSuccess
        }
        if (!ok) { toast("Couldn't use that photo"); return@launch }
        backgroundVersion++
        prefs.edit().putInt("bgVer", backgroundVersion).apply()
        changeTheme(ThemeMode.PHOTO)
    }
    fun changeStartTab(t: StartTab) { startTab = t; prefs.edit().putString("startTab2", t.name).apply() }
    fun changeDateMode(d: DateMode) { dateMode = d; prefs.edit().putString("dateMode", d.name).apply(); reload() }
    fun changeRounded(v: Boolean) { roundedThumbs = v; prefs.edit().putBoolean("rounded", v).apply() }
    fun changeCollections(v: Boolean) { showCollections = v; prefs.edit().putBoolean("collections", v).apply() }
    fun changeIncludeAppHidden(v: Boolean) { includeAppHidden = v; prefs.edit().putBoolean("appHidden", v).apply(); reload() }
    fun changeAutoplay(v: Boolean) { autoplay = v; prefs.edit().putBoolean("autoplay", v).apply() }
    fun setLoop(v: Boolean) { loopVideos = v; prefs.edit().putBoolean("loop", v).apply() }
    fun changeResume(v: Boolean) { resumeVideos = v; prefs.edit().putBoolean("resume", v).apply(); if (!v) positions.edit().clear().apply() }
    fun changeBrightness(v: Boolean) { maxBrightness = v; prefs.edit().putBoolean("bright", v).apply() }
    fun changeScreenOn(v: Boolean) { keepScreenOn = v; prefs.edit().putBoolean("screenOn", v).apply() }
    fun setSlide(s: Int) { slideSeconds = s.coerceIn(1, 15); prefs.edit().putInt("slide", slideSeconds).apply() }
    fun changeAppLock(v: Boolean) { appLock = v; appUnlocked = true; prefs.edit().putBoolean("appLock", v).apply() }
    fun changeBiometric(v: Boolean) { useBiometric = v; prefs.edit().putBoolean("bio", v).apply() }
    fun changeSecure(v: Boolean) { secureScreen = v; prefs.edit().putBoolean("secure", v).apply() }

    fun toggleHiddenAlbum(id: Long) {
        hiddenAlbums = if (id in hiddenAlbums) hiddenAlbums - id else hiddenAlbums + id
        saveSet("hAlb", hiddenAlbums)
    }
    fun setHidden(items: List<Media>, hide: Boolean) {
        val ids = items.map { it.id }.toSet()
        hiddenItems = if (hide) hiddenItems + ids else hiddenItems - ids
        saveSet("hItm", hiddenItems)
    }
    fun isHidden(m: Media) = m.id in hiddenItems || m.bucketId in hiddenAlbums
    fun togglePin(id: Long) {
        pinned = if (id in pinned) pinned - id else pinned + id
        saveSet("pin", pinned)
    }

    /** Display name only; the folder on disk keeps its name so other apps aren't affected. */
    fun renameAlbum(id: Long, name: String?) {
        albumNames = if (name.isNullOrBlank()) albumNames - id else albumNames + (id to name.trim())
        saveMap("aName", albumNames)
    }
    fun setAlbumCover(bucketId: Long, mediaId: Long?) {
        albumCovers = if (mediaId == null) albumCovers - bucketId else albumCovers + (bucketId to mediaId.toString())
        saveMap("aCover", albumCovers)
        toast(if (mediaId == null) "Cover reset" else "Album cover updated")
    }
    fun rememberTarget(path: String) {
        recentTargets = (listOf(path) + recentTargets.filter { it != path }).take(4)
        prefs.edit().putString("recentT", recentTargets.joinToString("\n")).apply()
    }

    // --- video resume ---
    fun videoPosition(m: Media): Long = if (resumeVideos) positions.getLong(m.id.toString(), 0) else 0
    fun saveVideoPosition(m: Media, pos: Long, dur: Long) {
        if (!resumeVideos) return
        val e = positions.edit()
        // only worth remembering mid-video
        if (pos > 5_000 && dur > 0 && pos < dur - 5_000) e.putLong(m.id.toString(), pos) else e.remove(m.id.toString())
        e.apply()
    }

    // --- PIN (salted SHA-256; the PIN itself is never stored) ---
    private fun hash(pin: String, salt: ByteArray) =
        MessageDigest.getInstance("SHA-256").apply { update(salt) }.digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }
    fun setPin(kind: PinKind, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(kind.key, salt.joinToString("") { "%02x".format(it) } + ":" + hash(pin, salt)).apply()
    }
    fun checkPin(kind: PinKind, pin: String): Boolean {
        val (s, h) = prefs.getString(kind.key, null)?.split(':')?.takeIf { it.size == 2 } ?: return false
        val salt = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(), h.toByteArray())
    }
    /** ms left before another PIN attempt is allowed (0 = allowed). */
    fun pinWait(kind: PinKind): Long = (prefs.getLong("lockUntil_${kind.name}", 0) - System.currentTimeMillis()).coerceAtLeast(0)
    /** 5 free tries, then 30s, 60s, 120s… lockouts. Survives app restarts. */
    fun pinFailed(kind: PinKind) {
        val n = prefs.getInt("fails_${kind.name}", 0) + 1
        val e = prefs.edit().putInt("fails_${kind.name}", n)
        if (n >= 5) e.putLong("lockUntil_${kind.name}", System.currentTimeMillis() + 30_000L * (1L shl (n - 5).coerceAtMost(6)))
        e.apply()
    }
    fun pinOk(kind: PinKind) = prefs.edit().remove("fails_${kind.name}").remove("lockUntil_${kind.name}").apply()

    fun lockAll() { vaultUnlocked = false; if (appLock) appUnlocked = false }

    // --- Private folder: files live in app-internal storage, invisible to other apps and MediaStore ---
    // ponytail: relies on Android's file-based encryption, not app-level encryption; add Jetpack Security if the phone may be rooted
    private val vaultDir = File(app.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(vaultDir, ".index")
    private fun index() = Properties().apply { if (indexFile.exists()) indexFile.inputStream().use { load(it) } }
    private fun saveIndex(p: Properties) = indexFile.outputStream().use { p.store(it, null) }

    // must come after vaultDir/indexFile: Kotlin initialises properties top to bottom
    init { loadVault() }

    private fun loadVault() = viewModelScope.launch(Dispatchers.IO) {
        val idx = index()
        val list = vaultDir.listFiles().orEmpty().filter { it.isFile && it.name != ".index" }.map { f ->
            // index value: originalPath|mime|dateTaken
            val parts = idx.getProperty(f.name, "").split('|')
            val mime = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "image/jpeg"
            Media(
                id = -(f.name.hashCode().toLong() and 0x7fffffff) - 1, uri = Uri.fromFile(f), name = f.name.substringAfter('_'),
                date = parts.getOrNull(2)?.toLongOrNull() ?: f.lastModified(), modified = f.lastModified(), size = f.length(),
                width = 0, height = 0, mime = mime, bucketId = -1, bucket = "Private", path = parts.getOrNull(0) ?: "",
                duration = 0, favorite = false,
            )
        }
        withContext(Dispatchers.Main) { vault = list }
    }

    private fun vaultFile(m: Media) = File(m.uri.path!!)

    /**
     * Moves into the private folder: copy first, then ask Android to delete the originals.
     * Runs in the ViewModel so leaving the screen can't cancel it halfway. If the delete is
     * declined, only *this* batch's copies are removed, so nothing is ever left with zero copies.
     */
    fun moveToVault(items: List<Media>, ask: Ask) = viewModelScope.launch {
        val copied = withContext(Dispatchers.IO) { copyToVault(items) }
        if (copied.isEmpty()) { toast("Couldn't move to Private"); return@launch }
        val files = copied.map { it.second }
        nextDenial = { viewModelScope.launch(Dispatchers.IO) { dropFromVault(files) } }
        ask(deleteRequest(copied.map { it.first })) { toast("Moved to Private") }
    }

    /** Returns (original, private copy) for every item that copied completely. */
    private fun copyToVault(items: List<Media>): List<Pair<Media, File>> {
        val idx = index()
        val ok = items.mapNotNull { m ->
            runCatching {
                val f = File(vaultDir, "${System.nanoTime()}_${m.name}")
                // original bytes incl. GPS (setRequireOriginal), since the source is about to be deleted
                val src = runCatching { MediaStore.setRequireOriginal(m.uri) }.getOrDefault(m.uri)
                (runCatching { resolver.openInputStream(src) }.getOrNull() ?: resolver.openInputStream(m.uri))!!
                    .use { input -> f.outputStream().use { input.copyTo(it) } }
                if (m.size > 0 && f.length() != m.size) { f.delete(); error("incomplete copy") }
                idx.setProperty(f.name, "${m.path}|${m.mime}|${m.date}")
                m to f
            }.onFailure { android.util.Log.w("Gallery", "vault copy failed", it) }.getOrNull()
        }
        saveIndex(idx)
        loadVault()
        return ok
    }

    private fun dropFromVault(files: List<File>) {
        val idx = index()
        files.forEach { idx.remove(it.name); it.delete() }
        saveIndex(idx); loadVault()
    }

    fun restoreFromVault(items: List<Media>) = io("restored") {
        val idx = index()
        val failed = items.count { m ->
            val f = vaultFile(m)
            runCatching {
                val target = m.path.takeIf { it.startsWith("DCIM/") || it.startsWith("Pictures/") || (m.isVideo && it.startsWith("Movies/")) }
                    ?: "Pictures/Restored/"
                insert(m.mime, m.name, target) { out -> f.inputStream().use { it.copyTo(out) } }
                f.delete(); idx.remove(f.name)
            }.isFailure
        }
        saveIndex(idx); loadVault()
        failed
    }

    fun deleteFromVault(items: List<Media>) = io("deleted") {
        val idx = index()
        val failed = items.count { m -> val f = vaultFile(m); idx.remove(f.name); !f.delete() }
        saveIndex(idx); loadVault()
        failed
    }

    // --- Actions that need a system confirmation dialog (scoped storage) ---
    fun trashRequest(items: List<Media>, trash: Boolean): PendingIntent =
        MediaStore.createTrashRequest(resolver, items.map { it.uri }, trash)

    fun deleteRequest(items: List<Media>): PendingIntent =
        MediaStore.createDeleteRequest(resolver, items.map { it.uri })

    fun favoriteRequest(items: List<Media>, fav: Boolean): PendingIntent =
        MediaStore.createFavoriteRequest(resolver, items.map { it.uri }, fav)

    fun writeRequest(items: List<Media>): PendingIntent =
        MediaStore.createWriteRequest(resolver, items.map { it.uri })

    fun rename(m: Media, newName: String) = update(listOf(m)) { put(C.DISPLAY_NAME, newName) }

    fun move(items: List<Media>, relativePath: String) {
        rememberTarget(relativePath)
        update(items) { put(C.RELATIVE_PATH, relativePath) }
    }

    private fun update(items: List<Media>, values: ContentValues.() -> Unit) = io("changed") {
        val v = ContentValues().apply(values)
        items.count { runCatching { resolver.update(it.uri, v, null, null) }.isFailure }
    }

    /** Copies into [relativePath] as new files. No permission dialog needed for creating files. */
    fun copy(items: List<Media>, relativePath: String) {
        rememberTarget(relativePath)
        io("copied") {
            items.count { m ->
                runCatching {
                    insert(m.mime, m.name, writableDir(m, relativePath)) { out -> resolver.openInputStream(m.uri)!!.use { it.copyTo(out) } }
                }.isFailure
            }
        }
    }

    /** Saves a scaled copy next to the original. */
    fun resize(m: Media, w: Int, h: Int) = io("resized") {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, m.uri)) { d, _, _ ->
            d.setTargetSize(w, h); d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val png = m.mime == "image/png"
        val name = m.name.substringBeforeLast('.') + "_${w}x$h." + if (png) "png" else "jpg"
        val ok = runCatching {
            insert(if (png) "image/png" else "image/jpeg", name, writableDir(m, m.path)) { out ->
                bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
            }
        }.isSuccess
        bmp.recycle()
        if (ok) 0 else 1
    }

    /** Rotates 90° clockwise. JPEG: lossless via EXIF. Others: re-encoded. Needs write approval first. */
    fun rotate(items: List<Media>) = io("rotated") {
        items.count { m ->
            val exifOk = m.mime == "image/jpeg" && runCatching {
                resolver.openFileDescriptor(m.uri, "rw")!!.use { fd ->
                    val e = ExifInterface(fd.fileDescriptor)
                    // ExifInterface.rotate() turns a missing/0 orientation into 0 ("undefined") instead of 90°,
                    // which is why files without orientation (WhatsApp, downloads) never turned. Do it ourselves.
                    val next = nextClockwise(e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
                        ?: error("mirrored orientation")
                    e.setAttribute(ExifInterface.TAG_ORIENTATION, next.toString())
                    e.saveAttributes()
                }
                // verify it stuck; otherwise fall back to re-encoding
                val now = resolver.openInputStream(m.uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) }
                check(now != ExifInterface.ORIENTATION_UNDEFINED) { "orientation didn't stick" }
            }.onFailure { android.util.Log.w("Gallery", "EXIF rotate failed, re-encoding", it) }.isSuccess
            if (exifOk) return@count false
            if (!canReplace(m)) return@count true // HEIC/WebP/RAW: don't convert the user's file to JPEG
            !writeEdit(m, EditOps(quarterTurns = 1), replace = true)
        }.also { withContext(Dispatchers.Main) { clearImageCache() } }
    }

    fun clearImageCache() {
        val loader = SingletonImageLoader.get(getApplication())
        loader.memoryCache?.clear(); loader.diskCache?.clear()
        imageEpoch++
    }

    /** Saves an edit made in the editor. Copy goes next to the original; replace overwrites it (write approval must already be granted). */
    suspend fun saveEdit(m: Media, ops: EditOps, replace: Boolean): Boolean {
        val ok = withContext(Dispatchers.IO) { writeEdit(m, ops, replace) }
        toast(if (ok) (if (replace) "Saved" else "Saved as a copy") else "Couldn't save the edit")
        clearImageCache()
        reload()
        return ok
    }

    /** Copies an exported video next to the original (or Pictures/Edited/ if that folder can't hold it). */
    suspend fun saveVideoCopy(m: Media, file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val name = m.name.substringBeforeLast('.') + "_edited.mp4"
            insertUri("video/mp4", name, writableDir(true, m.path)) { out -> file.inputStream().use { it.copyTo(out) } }
        }.onFailure { android.util.Log.w("Gallery", "video save failed", it) }.isSuccess
    }.also { reload() }

    /** Saves one video frame as a JPEG photo beside the video. */
    suspend fun saveFrame(m: Media, frame: Bitmap, atMs: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val name = m.name.substringBeforeLast('.') + "_frame_${atMs / 1000}s.jpg"
            insertUri("image/jpeg", name, writableDir(false, m.path)) { out -> frame.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            frame.recycle()
        }.isSuccess
    }.also { reload() }

    private val keepTags = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
    )

    /** Overwriting is only offered for formats we can write back unchanged, and sizes we decode in full. */
    fun canReplace(m: Media) = (m.mime == "image/jpeg" || m.mime == "image/png") && maxOf(m.width, m.height) <= MAX_EDIT

    private fun writeEdit(m: Media, ops: EditOps, replace: Boolean): Boolean = runCatching {
        if (replace) check(canReplace(m)) { "replace not supported for ${m.mime} ${m.width}x${m.height}" }
        // ponytail: full-res decode capped at 6000px on the long side; tiled rendering if 50MP+ edits must keep every pixel
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, m.uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_EDIT) { val s = MAX_EDIT.toFloat() / longest; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
        }
        val out = renderEdit(src, ops)
        src.recycle()
        val png = m.mime == "image/png"
        val fmt = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        // remember capture date/camera/GPS so the edited photo stays in the same place on the timeline
        val exif = if (png) emptyMap() else runCatching {
            resolver.openInputStream(m.uri)!!.use { s -> ExifInterface(s).let { e -> keepTags.mapNotNull { t -> e.getAttribute(t)?.let { t to it } }.toMap() } }
        }.getOrDefault(emptyMap())
        val target: Uri = if (replace) {
            // encode fully in memory first, so a failure can't leave a half-written original
            val bytes = java.io.ByteArrayOutputStream().also { check(out.compress(fmt, 95, it)) }.toByteArray()
            resolver.openOutputStream(m.uri, "wt")!!.use { it.write(bytes) }
            m.uri
        } else {
            val name = m.name.substringBeforeLast('.') + "_edited." + if (png) "png" else "jpg"
            insertUri(if (png) "image/png" else "image/jpeg", name, writableDir(m, m.path)) { out.compress(fmt, 95, it) }
        }
        out.recycle()
        if (exif.isNotEmpty()) runCatching {
            resolver.openFileDescriptor(target, "rw")!!.use { fd ->
                ExifInterface(fd.fileDescriptor).apply {
                    exif.forEach { (k, v) -> setAttribute(k, v) }
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    saveAttributes()
                }
            }
        }
        true
    }.onFailure { android.util.Log.w("Gallery", "edit save failed", it) }.getOrDefault(false)

    private fun insert(mime: String, name: String, relativePath: String, write: (java.io.OutputStream) -> Unit) {
        insertUri(mime, name, relativePath, write)
    }

    private fun insertUri(mime: String, name: String, relativePath: String, write: (java.io.OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(C.DISPLAY_NAME, name); put(C.MIME_TYPE, mime); put(C.RELATIVE_PATH, relativePath); put(C.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection(mime), values) ?: error("insert failed")
        try {
            resolver.openOutputStream(uri)!!.use(write)
            resolver.update(uri, ContentValues().apply { put(C.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null); throw e
        }
        return uri
    }

    /** Runs [block] off the main thread; block returns the failure count. */
    private fun io(verb: String, block: suspend () -> Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val failed = runCatching { block() }.getOrDefault(1)
            withContext(Dispatchers.Main) {
                // success is visible on screen; only speak up when something failed
                if (failed > 0) toast("$failed item(s) could not be $verb")
                reload()
            }
        }
    }

    fun toast(msg: String) = Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()

    fun hintSkipConfirmOnce() {
        if (prefs.getBoolean("hintSkip", false)) return
        prefs.edit().putBoolean("hintSkip", true).apply()
        Toast.makeText(getApplication(), "Tip: turn off these popups in Gallery Settings → Skip confirmations", Toast.LENGTH_LONG).show()
    }
}
