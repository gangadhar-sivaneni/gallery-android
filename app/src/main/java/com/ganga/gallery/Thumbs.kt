package com.ganga.gallery

import android.content.Context
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options

/** A small picture for a grid tile or album cover, [px] on its short side. */
data class ThumbReq(val uri: Uri, val px: Int, val video: Boolean)

/**
 * Uses Android's own cached thumbnail (what the phone's Photos app shows), which is far cheaper than
 * decoding the full photo or a video frame; falls back to decoding the file if there is none.
 */
class ThumbFetcher(private val req: ThumbReq, private val ctx: Context) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val r = ctx.contentResolver
        val bmp = runCatching { r.loadThumbnail(req.uri, android.util.Size(req.px, req.px), null) }.getOrNull()
            ?: if (req.video) MediaMetadataRetriever().use { m ->
                m.setDataSource(ctx, req.uri)
                m.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, req.px * 2, req.px * 2) ?: error("no frame")
            } else ImageDecoder.decodeBitmap(ImageDecoder.createSource(r, req.uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val k = req.px.toFloat() / minOf(info.size.width, info.size.height)
                if (k < 1f) d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            }
        return ImageFetchResult(bmp.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<ThumbReq> {
        override fun create(data: ThumbReq, options: Options, imageLoader: ImageLoader) = ThumbFetcher(data, options.context)
    }
}

/** Lets the memory cache keep thumbnails (requests that don't set their own cache key). */
class ThumbKeyer : coil3.key.Keyer<ThumbReq> {
    override fun key(data: ThumbReq, options: Options) = "thumb:${data.uri}:${data.px}"
}
