package com.duchock.claudette.media

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Local store for photos John feeds Nova. Saved in app-internal storage (sandboxed) under
 * nova_media/, ready for the future off-device check-in backup (D18). Nothing leaves the device
 * here; the raw image is only sent to Claude at analysis time.
 */
object ImageStore {
    private const val DIR = "nova_media"

    fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Copy [bytes] into a timestamped file and return it. */
    fun save(context: Context, bytes: ByteArray, stampMs: Long): File {
        val f = File(dir(context), "img_$stampMs.jpg")
        f.writeBytes(bytes)
        Log.i(TAG, "saved image ${f.name} (${bytes.size} bytes)")
        return f
    }

    private const val TAG = "ImageStore"
}
