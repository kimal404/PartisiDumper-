package id.dumper.parti.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import java.text.DecimalFormat

object StorageUtil {
    private const val SAFETY_BUFFER_BYTES = 500L * 1024 * 1024
    const val DUMP_FOLDER_PATH = "/storage/emulated/0/Download/PartitionDumps"

    fun getAvailableInternalStorageBytes(): Long {
        return try {
            val path = Environment.getExternalStorageDirectory().path
            val stat = StatFs(path)
            stat.availableBytes
        } catch (e: Exception) {
            0L
        }
    }

    fun hasEnoughSpace(requiredBytes: Long): Boolean {
        val available = getAvailableInternalStorageBytes()
        return available >= (requiredBytes + SAFETY_BUFFER_BYTES)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val df = DecimalFormat("#,##0.#")
        return "${df.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }

    fun openDumpsFolder(context: Context) {
        try {
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FPartitionDumps")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
            }
        }
    }
}
