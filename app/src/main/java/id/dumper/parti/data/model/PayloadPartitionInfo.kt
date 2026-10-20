package id.dumper.parti.data.model

enum class ExtractionStatus {
    IDLE,
    QUEUED,
    EXTRACTING,
    SUCCESS,
    FAILED
}

data class PayloadPartitionInfo(
    val name: String,
    val sizeBytes: Long,
    val isCritical: Boolean = false,
    val isSelected: Boolean = false,
    val status: ExtractionStatus = ExtractionStatus.IDLE
)
