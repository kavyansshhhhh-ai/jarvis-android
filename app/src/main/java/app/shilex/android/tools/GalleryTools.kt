package app.shilex.android.tools

import android.Manifest
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import app.shilex.android.Diag
import app.shilex.android.control.ControlService
import app.shilex.android.data.AutomationCategory
import app.shilex.android.data.Settings as AppSettings
import org.json.JSONObject

/**
 * Pure photo candidate matching & safety confirmation helper (unit-tested without Android).
 */
object GalleryPhotoMatcher {
    data class MediaPhoto(
        val id: Long,
        val displayName: String,
        val relativePath: String = "",
        val dateModifiedSec: Long = 0L,
        val mimeType: String = "image/jpeg",
    )

    sealed interface Resolution {
        data class Single(val photo: MediaPhoto) : Resolution
        data class Ambiguous(val candidates: List<MediaPhoto>) : Resolution
        data object None : Resolution
    }

    fun resolve(query: String, photos: List<MediaPhoto>): Resolution {
        if (photos.isEmpty()) return Resolution.None
        val ordered = photos.sortedByDescending { it.dateModifiedSec }
        val q = query.lowercase().trim()
        if (q.isEmpty() || q in LATEST_SYNONYMS) {
            return Resolution.Single(ordered.first())
        }
        val exactName = ordered.filter { it.displayName.lowercase() == q || it.displayName.substringBeforeLast('.').lowercase() == q }
        if (exactName.size == 1) return Resolution.Single(exactName.single())
        if (exactName.size > 1) return Resolution.Ambiguous(exactName.take(5))

        val partial = ordered.filter { q in it.displayName.lowercase() || q in it.relativePath.lowercase() }
        return when {
            partial.size == 1 -> Resolution.Single(partial.single())
            partial.size > 1 -> Resolution.Ambiguous(partial.take(5))
            else -> Resolution.None
        }
    }

    fun confirmationPrompt(photo: MediaPhoto): String =
        "Kya aap isi photo ko delete karna chahte hain? (${photo.displayName})"

    private val LATEST_SYNONYMS = setOf("latest", "last", "recent", "this", "current", "ye", "yeh", "is", "this photo", "yeh photo", "ye photo", "is photo", "selected")
}

/**
 * Android Scoped-Storage & MediaStore photo deletion tool.
 * Requires explicit yes/no confirmation ("Kya aap isi photo ko delete karna chahte hain?")
 * BEFORE invoking MediaStore.createDeleteRequest (Android system confirmation dialog),
 * then re-queries MediaStore to verify the item is actually deleted/trashed.
 */
object GalleryTools {
    val DeletePhoto: PhoneTool = DeletePhotoTool

    fun requiredReadPermission(sdkInt: Int = Build.VERSION.SDK_INT): String =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private object DeletePhotoTool : PhoneTool {
        override val name = "gallery_delete_photo"

        override fun declaration() = Schema.function(
            name,
            "Finds the targeted or latest photo in the user's gallery, asks explicit yes/no confirmation ('Kya aap isi photo ko delete karna chahte hain?'), launches Android's official delete confirmation dialog, and verifies whether the photo was deleted.",
            Schema.string("target", "Photo filename or 'latest'/'current'."),
            Schema.string("confirmed", "true ONLY after the user explicitly answered yes to deleting this specific photo.", choices = listOf("false", "true")),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val target = args.text("target")
            if (target.isEmpty()) {
                return Results.failed("no_target", "Never delete automatically just because gallery was mentioned. Ask which exact photo the user wants to delete.")
            }

            val perm = requiredReadPermission()
            if (!host.hasPermission(perm)) {
                host.requestPermission(perm)
                return Results.needsPermission("Photos / Gallery")
            }

            val photos = queryPhotos(host)
            if (photos.isEmpty()) {
                return Results.failed("no_photos", "No photos were found in MediaStore gallery.")
            }

            val selected = when (val res = GalleryPhotoMatcher.resolve(target, photos)) {
                is GalleryPhotoMatcher.Resolution.Single -> res.photo
                is GalleryPhotoMatcher.Resolution.Ambiguous -> {
                    return Results.ask(
                        "Several photos match \"$target\". Ask which exact photo to delete.",
                        res.candidates.map { it.displayName },
                    )
                }
                GalleryPhotoMatcher.Resolution.None -> {
                    return Results.failed("not_found", "Could not find a gallery photo matching \"$target\".")
                }
            }

            // Explicit mandatory confirmation BEFORE invoking Android's delete request — never bypassed!
            val prompt = GalleryPhotoMatcher.confirmationPrompt(selected)
            val confirmedByArg = args.text("confirmed").equals("true", ignoreCase = true)
            val confirmed = if (confirmedByArg) {
                true
            } else {
                val overlay = ControlService.instance?.overlay?.takeUnless { it.stopped }
                overlay?.confirm(prompt) ?: return Results.ask(prompt, listOf("Haan, delete karo", "Nahi, cancel karo"))
            }
            if (!confirmed) {
                return Results.done("Photo deletion cancelled. \"${selected.displayName}\" was kept safe.")
            }

            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selected.id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent = runCatching {
                    MediaStore.createDeleteRequest(host.context.contentResolver, listOf(uri))
                }.getOrNull() ?: return Results.failed("delete_request_failed", "Android couldn't create the delete request for ${selected.displayName}.")

                val systemApproved = host.requestGalleryDelete(pendingIntent.intentSender)
                if (!systemApproved) {
                    return Results.done("Deletion of \"${selected.displayName}\" was cancelled in Android's confirmation dialog.")
                }
            } else {
                val rows = runCatching {
                    host.context.contentResolver.delete(uri, null, null)
                }.getOrDefault(0)
                if (rows <= 0) {
                    return Results.failed("delete_failed", "Android did not delete \"${selected.displayName}\".")
                }
            }

            // Verify actual MediaStore state after deletion before claiming deleted
            val stillExists = photoExists(host, uri)
            return if (!stillExists) {
                Diag.i("gallery: verified deleted ${selected.displayName}")
                val noteSuffix = if (AppSettings(host.context).isAutomationEnabled(AutomationCategory.GalleryManagement)) "" else ""
                Results.done("Verified: \"${selected.displayName}\" has been deleted from the gallery.$noteSuffix")
            } else {
                Results.failed("verify_failed", "\"${selected.displayName}\" still appears in MediaStore after the delete request; it was not verified as deleted.")
            }
        }

        private fun queryPhotos(host: ToolHost): List<GalleryPhotoMatcher.MediaPhoto> {
            val out = mutableListOf<GalleryPhotoMatcher.MediaPhoto>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.MIME_TYPE,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA,
            )
            runCatching {
                host.context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                    val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
                    val pathIdx = cursor.getColumnIndexOrThrow(pathCol)
                    while (cursor.moveToNext() && out.size < 40) {
                        out += GalleryPhotoMatcher.MediaPhoto(
                            id = cursor.getLong(idIdx),
                            displayName = cursor.getString(nameIdx).orEmpty().ifBlank { "Photo #${cursor.getLong(idIdx)}" },
                            relativePath = cursor.getString(pathIdx).orEmpty(),
                            dateModifiedSec = cursor.getLong(dateIdx),
                            mimeType = cursor.getString(mimeIdx).orEmpty(),
                        )
                    }
                }
            }
            return out
        }

        private fun photoExists(host: ToolHost, uri: Uri): Boolean = runCatching {
            host.context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { c ->
                c.moveToFirst()
            } ?: false
        }.getOrDefault(false)
    }
}
