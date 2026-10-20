package id.dumper.parti.data.model

import android.net.Uri

enum class VerifyResult {
    NOT_CHECKED,
    MATCH,
    MISMATCH,
    NO_HASH_FILE
}

data class DumpHistoryItem(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val recordedHash: String?,
    val isVerifying: Boolean = false,
    val verifyResult: VerifyResult = VerifyResult.NOT_CHECKED,
    val isRootDump: Boolean = false,
    val contentUri: Uri? = null
)
