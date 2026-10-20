package id.dumper.parti.util

import android.app.DownloadManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.DecimalFormat

object StorageUtil {
    private const val SAFETY_BUFFER_BYTES = 500L * 1024 * 1024
    const val BASE_FOLDER_PATH = "/sdcard/Download/PartisiDumper"
    const val LEGACY_ROOT_PATH = "/sdcard/PartisiDumper"
    const val RELATIVE_BASE_DIR = "Download/PartisiDumper"

    fun getAvailableInternalStorageBytes(): Long {
        return try {
            val path = Environment.getExternalStorageDirectory().path
            val stat = StatFs(path)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    fun hasEnoughSpace(requiredBytes: Long): Boolean {
        val available = getAvailableInternalStorageBytes()
        return available >= (requiredBytes + SAFETY_BUFFER_BYTES)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIdx = 0
        while (size >= 1024.0 && unitIdx < units.size - 1) {
            size /= 1024.0
            unitIdx++
        }
        val df = DecimalFormat("#,##0.##")
        return "${df.format(size)} ${units[unitIdx]}"
    }

    fun formatBytes(bytes: Long): String = formatSize(bytes)

    fun openDumpsFolder(context: Context) {
        try {
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FPartisiDumper")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun createOrReplaceOtaDownloadFile(context: Context, subFolder: String, fileName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "$RELATIVE_BASE_DIR/$subFolder/"

        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "(${MediaStore.Downloads.DISPLAY_NAME} = ? OR ${MediaStore.Downloads.DISPLAY_NAME} = ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?) AND (${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)"
        val selectionArgs = arrayOf(fileName, "$fileName.txt", "$fileName%", relativePath, "$RELATIVE_BASE_DIR/$subFolder")

        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val existingUri = ContentUris.withAppendedId(collection, id)
                try {
                    resolver.delete(existingUri, null, null)
                } catch (_: Exception) {}
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        return resolver.insert(collection, values)
    }

    fun finalizeDownloadFile(context: Context, uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }

    fun findOtaRecordedHash(context: Context, subFolder: String, partitionName: String): String? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "$RELATIVE_BASE_DIR/$subFolder/"

        val shaSelection = "(${MediaStore.Downloads.RELATIVE_PATH} LIKE ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?) AND (${MediaStore.Downloads.DISPLAY_NAME} = ? OR ${MediaStore.Downloads.DISPLAY_NAME} = ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?)"
        val shaArgs = arrayOf(
            "$relativePath%",
            relativePath,
            "$partitionName.sha256",
            "$partitionName.sha256.txt",
            "$partitionName%.sha256%"
        )

        try {
            resolver.query(collection, arrayOf(MediaStore.Downloads._ID), shaSelection, shaArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val shaId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val shaUri = ContentUris.withAppendedId(collection, shaId)
                    resolver.openInputStream(shaUri)?.use { isStream ->
                        val text = isStream.bufferedReader().readLine()?.trim()
                        val hash = text?.split(Regex("\\s+"))?.firstOrNull()
                        if (!hash.isNullOrEmpty() && hash.length >= 64) {
                            return hash.take(64)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (Shell.isAppGrantedRoot() == true) {
            try {
                val out = Shell.cmd("cat /sdcard/$relativePath$partitionName.sha256* 2>/dev/null").exec().out
                val raw = out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                if (!raw.isNullOrEmpty() && raw.length >= 64) {
                    return raw.take(64)
                }
            } catch (_: Exception) {}
        }

        return null
    }

    fun findRecordedHash(targetFilePath: String): String? {
        val candidates = listOf(
            File("$targetFilePath.sha256"),
            File("$targetFilePath.sha256.txt")
        )
        for (file in candidates) {
            if (file.exists() && file.canRead()) {
                try {
                    val text = file.bufferedReader().readLine()?.trim()
                    val hash = text?.split(Regex("\\s+"))?.firstOrNull()
                    if (!hash.isNullOrEmpty() && hash.length >= 64) {
                        return hash.take(64)
                    }
                } catch (_: Exception) {}
            }
        }

        if (Shell.isAppGrantedRoot() == true) {
            try {
                val out = Shell.cmd("cat \"$targetFilePath.sha256\" \"$targetFilePath.sha256.txt\" 2>/dev/null").exec().out
                val raw = out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                if (!raw.isNullOrEmpty() && raw.length >= 64) {
                    return raw.take(64)
                }
            } catch (_: Exception) {}
        }

        return null
    }

    fun calculateSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
