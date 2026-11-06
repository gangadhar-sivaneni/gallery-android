package com.ganga.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.exifinterface.media.ExifInterface
import androidx.print.PrintHelper

/** Starts [intent], showing a toast instead of crashing when no app can handle it. */
private fun Context.launch(intent: Intent) = try {
    startActivity(intent)
} catch (_: Exception) {
    Toast.makeText(this, "No app found for this action", Toast.LENGTH_SHORT).show()
}

private fun chooser(i: Intent, title: String) = Intent.createChooser(i, title)

fun share(ctx: Context, items: List<Media>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { it.uri })
    val type = items.map { it.mime.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    send.type = type
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.launch(chooser(send, "Share"))
}

fun edit(ctx: Context, m: Media) = ctx.launch(
    chooser(
        Intent(Intent.ACTION_EDIT).setDataAndType(m.uri, m.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
        "Edit with",
    )
)

fun setAs(ctx: Context, m: Media) = ctx.launch(
    chooser(
        Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(m.uri, m.mime).putExtra("mimeType", m.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        "Set as",
    )
)

fun openWith(ctx: Context, m: Media) = ctx.launch(
    chooser(Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, m.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with")
)

fun openCamera(ctx: Context) = ctx.launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))

fun copyToClipboard(ctx: Context, m: Media) {
    ctx.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newUri(ctx.contentResolver, m.name, m.uri))
}

fun print(ctx: Context, m: Media) = runCatching {
    PrintHelper(ctx).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap(m.name, m.uri)
}.onFailure { Toast.makeText(ctx, "Couldn't print this file", Toast.LENGTH_SHORT).show() }

/** Camera details for the Properties dialog. Call off the main thread. */
fun exifRows(ctx: Context, m: Media): List<Pair<String, String>> {
    if (m.isVideo) return emptyList()
    val e = runCatching {
        val uri = runCatching { MediaStore.setRequireOriginal(m.uri) }.getOrDefault(m.uri)
        ctx.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
    }.getOrNull() ?: return emptyList()
    return buildList {
        val cam = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").trim()
        if (cam.isNotEmpty()) add("Camera" to cam)
        val parts = listOfNotNull(
            e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
            e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { if (it < 1) "1/${(1 / it).toInt()}s" else "${it}s" },
            e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
            e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "${"%.1f".format(it)}mm" },
        )
        if (parts.isNotEmpty()) add("Exposure" to parts.joinToString("  ·  "))
        e.latLong?.let { add("Location" to "%.5f, %.5f".format(it[0], it[1])) }
    }
}

/** Pins a launcher shortcut that opens this item in the app. */
fun createShortcut(ctx: Context, m: Media) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
        Toast.makeText(ctx, "Your launcher doesn't support shortcuts", Toast.LENGTH_SHORT).show(); return
    }
    val icon = runCatching { ctx.contentResolver.loadThumbnail(m.uri, Size(256, 256), null) }
        .map { IconCompat.createWithBitmap(it) }
        .getOrElse { IconCompat.createWithResource(ctx, android.R.drawable.ic_menu_gallery) }
    val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_ID, m.id)
    val info = ShortcutInfoCompat.Builder(ctx, "m${m.id}").setShortLabel(m.name.take(24)).setIcon(icon).setIntent(intent).build()
    ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
}

const val EXTRA_ID = "media_id"

/** True when the user granted Gallery "Media management" (Android 12+): MediaStore then skips its confirm popups. */
fun canSkipConfirm(ctx: Context) = android.os.Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(ctx)

/** Android only lists files other apps hid (.nomedia folders) to apps with "All files access". */
fun hasAllFilesAccess() = android.os.Environment.isExternalStorageManager()

fun openAllFilesAccess(ctx: Context) = ctx.launch(
    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", ctx.packageName, null)),
)

/** Opens Android's own switch for Gallery's "Media management" access. */
fun openMediaManagement(ctx: Context) = ctx.launch(
    Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", ctx.packageName, null)),
)

/**
 * Sets exactly the chosen area as wallpaper, scaled to the screen so the system doesn't re-crop it.
 * [crop] is fractions of the (EXIF-rotated) image; [which] = FLAG_SYSTEM / FLAG_LOCK or both. Call off the main thread.
 */
fun setWallpaper(ctx: Context, m: Media, crop: android.graphics.RectF, screenW: Int, screenH: Int, which: Int): Boolean = runCatching {
    // ponytail: decode capped at 4096px; plenty for any phone screen
    val src = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, m.uri)) { d, info, _ ->
        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 4096) { val s = 4096f / longest; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
    }
    val x = (crop.left * src.width).toInt().coerceIn(0, src.width - 1)
    val y = (crop.top * src.height).toInt().coerceIn(0, src.height - 1)
    val w = (crop.width() * src.width).toInt().coerceIn(1, src.width - x)
    val h = (crop.height() * src.height).toInt().coerceIn(1, src.height - y)
    val area = android.graphics.Bitmap.createBitmap(src, x, y, w, h)
    val out = android.graphics.Bitmap.createScaledBitmap(area, screenW, screenH, true)
    android.app.WallpaperManager.getInstance(ctx).setBitmap(out, null, true, which)
    if (area !== src) area.recycle()
    src.recycle(); out.recycle()
}.onFailure { android.util.Log.w("Gallery", "wallpaper failed", it) }.isSuccess
