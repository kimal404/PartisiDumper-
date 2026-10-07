package id.dumper.parti.data.model
data class PartitionInfo(
    val name: String,
    val blockPath: String,
    val sizeBytes: Long,
    val isCritical: Boolean,
    val slot: String = ""
)
