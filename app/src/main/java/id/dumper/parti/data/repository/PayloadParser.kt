package id.dumper.parti.data.repository

import android.content.Context
import android.net.Uri
import id.dumper.parti.data.model.PayloadPartitionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PayloadParser {

    private val criticalNames = setOf(
        "boot", "init_boot", "vendor_boot", "dtbo",
        "vbmeta", "vbmeta_system", "vbmeta_vendor",
        "recovery", "persist", "metadata", "misc",
        "modem", "bluetooth", "dsp"
    )

    suspend fun parsePayload(context: Context, uri: Uri): Result<List<PayloadPartitionInfo>> = withContext(Dispatchers.IO) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return@withContext Result.failure(Exception("Gagal membuka file OTA"))

            pfd.use {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                val payloadOffset = ZipPayloadFinder.getPayloadOffset(channel)

                val headerBuf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                channel.position(payloadOffset)
                channel.read(headerBuf)
                headerBuf.flip()

                val magic = ByteArray(4)
                headerBuf.get(magic)
                if (String(magic, Charsets.US_ASCII) != "CrAU") {
                    return@withContext Result.failure(Exception("Format payload tidak valid (CrAU magic mismatch)"))
                }

                val version = headerBuf.long
                val manifestSize = headerBuf.long

                if (manifestSize <= 0 || manifestSize > 50 * 1024 * 1024) {
                    return@withContext Result.failure(Exception("Ukuran manifest tidak valid ($manifestSize bytes)"))
                }

                val headerSize = if (version >= 2) 24L else 20L
                channel.position(payloadOffset + headerSize)

                val manifestBytes = ByteArray(manifestSize.toInt())
                val mBuffer = ByteBuffer.wrap(manifestBytes)
                var totalRead = 0
                while (totalRead < manifestSize) {
                    val read = channel.read(mBuffer)
                    if (read == -1) break
                    totalRead += read
                }

                val partitions = decodeManifestProtobuf(manifestBytes)
                Result.success(partitions)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun decodeManifestProtobuf(bytes: ByteArray): List<PayloadPartitionInfo> {
        val partitions = mutableListOf<PayloadPartitionInfo>()
        val reader = ProtoReader(bytes)

        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            if (fieldNum == 13 && wireType == 2) {
                val partBytes = reader.readLengthDelimited()
                val partInfo = parsePartitionUpdate(partBytes)
                if (partInfo != null) {
                    partitions.add(partInfo)
                }
            } else {
                reader.skip(wireType)
            }
        }

        return partitions.sortedWith(
            compareByDescending<PayloadPartitionInfo> { it.isCritical }
                .thenBy { it.name }
        )
    }

    private fun parsePartitionUpdate(bytes: ByteArray): PayloadPartitionInfo? {
        val reader = ProtoReader(bytes)
        var name = ""
        var sizeBytes = 0L

        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            when {
                fieldNum == 1 && wireType == 2 -> {
                    name = String(reader.readLengthDelimited(), Charsets.UTF_8)
                }
                fieldNum == 7 && wireType == 2 -> {
                    // Field 7: PartitionInfo new_partition_info (size & hash pada Android update_metadata.proto)
                    val infoBytes = reader.readLengthDelimited()
                    val infoReader = ProtoReader(infoBytes)
                    while (infoReader.hasMore()) {
                        val subTag = infoReader.readVarint()
                        val subField = (subTag ushr 3).toInt()
                        val subWire = (subTag and 0x7).toInt()
                        if (subField == 1 && subWire == 0) {
                            sizeBytes = infoReader.readVarint()
                        } else {
                            infoReader.skip(subWire)
                        }
                    }
                }
                fieldNum == 6 && wireType == 2 -> {
                    // Fallback jika new_partition_info belum dibaca
                    if (sizeBytes == 0L) {
                        val infoBytes = reader.readLengthDelimited()
                        val infoReader = ProtoReader(infoBytes)
                        while (infoReader.hasMore()) {
                            val subTag = infoReader.readVarint()
                            val subField = (subTag ushr 3).toInt()
                            val subWire = (subTag and 0x7).toInt()
                            if (subField == 1 && subWire == 0) {
                                sizeBytes = infoReader.readVarint()
                            } else {
                                infoReader.skip(subWire)
                            }
                        }
                    } else {
                        reader.skip(wireType)
                    }
                }
                else -> reader.skip(wireType)
            }
        }

        if (name.isEmpty()) return null
        val baseName = name.removeSuffix("_a").removeSuffix("_b")
        val isCrit = criticalNames.contains(baseName)

        return PayloadPartitionInfo(
            name = name,
            sizeBytes = sizeBytes,
            isCritical = isCrit
        )
    }

    private class ProtoReader(private val buffer: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < buffer.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < buffer.size) {
                val b = buffer[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return result
        }

        fun readLengthDelimited(): ByteArray {
            val len = readVarint().toInt()
            val end = minOf(pos + len, buffer.size)
            val chunk = buffer.copyOfRange(pos, end)
            pos = end
            return chunk
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos = minOf(pos + 8, buffer.size)
                2 -> {
                    val len = readVarint().toInt()
                    pos = minOf(pos + len, buffer.size)
                }
                5 -> pos = minOf(pos + 4, buffer.size)
            }
        }
    }
}
