package id.dumper.parti.data.model

sealed class DumpState {
    object Idle : DumpState()
    data class Dumping(
        val partitionName: String,
        val current: Int = 1,
        val total: Int = 1,
        val bytesCopied: Long = 0L,
        val totalBytes: Long = 0L,
        val stage: String = "Mengekstrak..."
    ) : DumpState()
    data class Success(val partitionName: String, val outputPath: String, val sha256: String) : DumpState()
    data class BatchSuccess(val totalCount: Int, val outputDir: String) : DumpState()
    data class Error(val message: String) : DumpState()
}
