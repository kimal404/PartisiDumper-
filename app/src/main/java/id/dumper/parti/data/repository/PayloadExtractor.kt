package id.dumper.parti.data.repository

import android.content.Context
import android.net.Uri
import id.dumper.parti.data.model.ExtractionStatus
import id.dumper.parti.data.model.PayloadPartitionInfo
import id.dumper.parti.util.StorageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PayloadExtractor {

    data class Extent(val startBlock: Long, val numBlocks: Long)

    data class PayloadOp(
        val type: Int,
        val dataOffset: Long,
        val dataLength: Long,
        val dstExtents: List<Extent>
    )

    data class PartitionData(
        val name: String,
        val sizeBytes: Long,
        val operations: List<PayloadOp>
    )

    suspend fun extractPartitions(
        context: Context,
        uri: Uri,
        folderName: String,
        targetPartitions: List<PayloadPartitionInfo>,
        onStatusChange: (partName: String, status: ExtractionStatus) -> Unit = { _, _ -> },
        onProgress: (currentPart: String, partIndex: Int, totalParts: Int, bytesCopied: Long, totalBytes: Long, speed: Long, eta: Long) -> Unit
    ): Result<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        val targetNames = targetPartitions.map { it.name }.toSet()
        val successList = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: return@withContext Result.failure(Exception("Tidak dapat membuka file OTA"))

        pfd.use {
            val fileChannel = FileInputStream(pfd.fileDescriptor).channel

            try {
                val payloadOffset = ZipPayloadFinder.getPayloadOffset(fileChannel)

                val headerBuf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                fileChannel.position(payloadOffset)
                fileChannel.read(headerBuf)
                headerBuf.flip()

                val magic = ByteArray(4)
                headerBuf.get(magic)
                if (String(magic, Charsets.US_ASCII) != "CrAU") {
                    return@withContext Result.failure(Exception("Header file bukan CrAU payload resmi."))
                }

                val formatVersion = headerBuf.long
                val manifestSize = headerBuf.long
                val metadataSigSize = if (formatVersion >= 2) {
                    headerBuf.int.toLong() and 0xFFFFFFFFL
                } else 0L

                val headerSize = if (formatVersion >= 2) 24L else 20L
                val dataOffsetBase = payloadOffset + headerSize + manifestSize + metadataSigSize

                val manifestBytes = ByteArray(manifestSize.toInt())
                fileChannel.position(payloadOffset + headerSize)
                val mBuffer = ByteBuffer.wrap(manifestBytes)
                fileChannel.read(mBuffer)

                val blockSize = parseBlockSize(manifestBytes)
                val partitionDataList = parseTargetOperations(manifestBytes, targetNames)

                val cleanFolder = folderName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "OTA_Dump" }
                val totalParts = partitionDataList.size
                val totalBytesAll = partitionDataList.sumOf { it.sizeBytes }
                val totalWrittenAll = AtomicLong(0L)
                val completedCount = AtomicInteger(0)
                val startTime = System.currentTimeMillis()

                // Tandai semua partisi terpilih masuk antrean
                partitionDataList.forEach { onStatusChange(it.name, ExtractionStatus.QUEUED) }

                // Ekstraksi paralel menggunakan 3 worker bersamaan
                val semaphore = Semaphore(3)

                coroutineScope {
                    partitionDataList.map { part ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                onStatusChange(part.name, ExtractionStatus.EXTRACTING)
                                try {
                                    val res = extractSinglePartition(
                                        context = context,
                                        fileChannel = fileChannel,
                                        dataOffsetBase = dataOffsetBase,
                                        blockSize = blockSize,
                                        cleanFolder = cleanFolder,
                                        part = part,
                                        onBytesWritten = { delta ->
                                            val currentTotal = totalWrittenAll.addAndGet(delta)
                                            val now = System.currentTimeMillis()
                                            val elapsedSec = (now - startTime) / 1000.0
                                            val speed = if (elapsedSec > 0) (currentTotal / elapsedSec).toLong() else 0L
                                            val remaining = (totalBytesAll - currentTotal).coerceAtLeast(0L)
                                            val eta = if (speed > 0) remaining / speed else -1L

                                            onProgress(
                                                part.name,
                                                completedCount.get() + 1,
                                                totalParts,
                                                currentTotal.coerceAtMost(totalBytesAll),
                                                totalBytesAll,
                                                speed,
                                                eta
                                            )
                                        }
                                    )

                                    if (res.isSuccess) {
                                        successList.add(res.getOrThrow())
                                        onStatusChange(part.name, ExtractionStatus.SUCCESS)
                                    } else {
                                        onStatusChange(part.name, ExtractionStatus.FAILED)
                                    }
                                } catch (_: Exception) {
                                    onStatusChange(part.name, ExtractionStatus.FAILED)
                                } finally {
                                    completedCount.incrementAndGet()
                                }
                            }
                        }
                    }.awaitAll()
                }

                Result.success(successList.toList())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun extractSinglePartition(
        context: Context,
        fileChannel: FileChannel,
        dataOffsetBase: Long,
        blockSize: Long,
        cleanFolder: String,
        part: PartitionData,
        onBytesWritten: (Long) -> Unit
    ): Result<Pair<String, String>> {
        val outFileName = "${part.name}.img"
        val targetUri = StorageUtil.createOrReplaceOtaDownloadFile(context, cleanFolder, outFileName, "application/octet-stream")
            ?: return Result.failure(Exception("Gagal membuat entri MediaStore untuk $outFileName"))

        val destPfd = context.contentResolver.openFileDescriptor(targetUri, "rw")
            ?: return Result.failure(Exception("Gagal membuka stream tulis MediaStore untuk $outFileName"))

        destPfd.use { pfdOut ->
            val outChannel = FileOutputStream(pfdOut.fileDescriptor).channel

            if (part.sizeBytes > 0) {
                outChannel.position(part.sizeBytes - 1)
                outChannel.write(ByteBuffer.wrap(byteArrayOf(0)))
            }

            for (op in part.operations) {
                var uncompressedData: ByteArray? = null

                if (op.dataLength > 0) {
                    val absOffset = dataOffsetBase + op.dataOffset
                    val compressedBytes = ByteArray(op.dataLength.toInt())
                    val cBuf = ByteBuffer.wrap(compressedBytes)

                    var bytesRead = 0
                    while (bytesRead < op.dataLength) {
                        val r = fileChannel.read(cBuf, absOffset + bytesRead)
                        if (r <= 0) break
                        bytesRead += r
                    }

                    uncompressedData = decompress(op.type, compressedBytes)
                }

                var dataCursor = 0
                for (extent in op.dstExtents) {
                    val extentBytes = extent.numBlocks * blockSize
                    if (extent.startBlock != -1L && extent.startBlock != 0xFFFFFFFFFFFFFFFFUL.toLong()) {
                        val outOffset = extent.startBlock * blockSize
                        outChannel.position(outOffset)

                        if (uncompressedData != null && dataCursor < uncompressedData.size) {
                            val takeLen = (extentBytes).coerceAtMost((uncompressedData.size - dataCursor).toLong()).toInt()
                            outChannel.write(ByteBuffer.wrap(uncompressedData, dataCursor, takeLen))
                            dataCursor += takeLen

                            if (takeLen < extentBytes) {
                                val pad = ByteArray((extentBytes - takeLen).toInt())
                                outChannel.write(ByteBuffer.wrap(pad))
                            }
                        } else {
                            val zeroBuf = ByteArray(extentBytes.toInt())
                            outChannel.write(ByteBuffer.wrap(zeroBuf))
                        }
                    }
                    onBytesWritten(extentBytes)
                }
            }
        }

        StorageUtil.finalizeDownloadFile(context, targetUri)

        val hash = context.contentResolver.openInputStream(targetUri)?.use {
            StorageUtil.calculateSha256(it)
        } ?: ""

        val shaFileName = "$outFileName.sha256"
        val shaUri = StorageUtil.createOrReplaceOtaDownloadFile(context, cleanFolder, shaFileName, "text/plain")
        if (shaUri != null) {
            context.contentResolver.openOutputStream(shaUri, "wt")?.use { os ->
                os.write("$hash  $outFileName\n".toByteArray(Charsets.UTF_8))
            }
            StorageUtil.finalizeDownloadFile(context, shaUri)
        }

        return Result.success(Pair(part.name, hash))
    }

    private fun decompress(type: Int, compressed: ByteArray): ByteArray {
        return when (type) {
            0 -> compressed
            1 -> BZip2CompressorInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            8 -> XZInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            else -> compressed
        }
    }

    private fun parseBlockSize(data: ByteArray): Long {
        var offset = 0
        val length = data.size
        while (offset < length) {
            val (tag, newOffset) = readVarint(data, offset)
            offset = newOffset
            if (offset >= length) break
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (wireType == 0) {
                val (v, next) = readVarint(data, offset)
                offset = next
                if (fieldNum == 3) return v
            } else if (wireType == 2) {
                val (len, next) = readVarint(data, offset)
                offset = next + len.toInt()
            } else if (wireType == 1) offset += 8
            else if (wireType == 5) offset += 4
            else break
        }
        return 4096L
    }

    private fun parseTargetOperations(data: ByteArray, targetNames: Set<String>): List<PartitionData> {
        val result = mutableListOf<PartitionData>()
        var offset = 0
        val length = data.size

        while (offset < length) {
            val (tag, newOffset) = readVarint(data, offset)
            offset = newOffset
            if (offset >= length) break
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            if (wireType == 2) {
                val (len, bodyOffset) = readVarint(data, offset)
                val fieldLen = len.toInt()
                offset = bodyOffset

                if (fieldNum == 13) {
                    val pData = parseSinglePartitionData(data, offset, fieldLen, targetNames)
                    if (pData != null) {
                        result.add(pData)
                    }
                }
                offset += fieldLen
            } else if (wireType == 0) {
                val (_, next) = readVarint(data, offset)
                offset = next
            } else if (wireType == 1) offset += 8
            else if (wireType == 5) offset += 4
            else break
        }
        return result
    }

    private fun parseSinglePartitionData(data: ByteArray, start: Int, length: Int, targetNames: Set<String>): PartitionData? {
        var offset = start
        val end = (start + length).coerceAtMost(data.size)
        var name = ""
        var size = 0L
        val ops = mutableListOf<PayloadOp>()

        while (offset < end) {
            val (tag, newOffset) = readVarint(data, offset)
            offset = newOffset
            if (offset >= end) break
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            if (wireType == 2) {
                val (len, bodyOffset) = readVarint(data, offset)
                val fieldLen = len.toInt()
                offset = bodyOffset

                when (fieldNum) {
                    1 -> {
                        val nameEnd = (offset + fieldLen).coerceAtMost(data.size)
                        name = String(data, offset, nameEnd - offset, Charsets.UTF_8)
                    }
                    7 -> size = parseSizeOnly(data, offset, fieldLen)
                    8 -> {
                        val op = parseOperation(data, offset, fieldLen)
                        ops.add(op)
                    }
                }
                offset += fieldLen
            } else if (wireType == 0) {
                val (_, next) = readVarint(data, offset)
                offset = next
            } else if (wireType == 1) offset += 8
            else if (wireType == 5) offset += 4
            else break
        }

        return if (targetNames.contains(name)) {
            PartitionData(name = name, sizeBytes = size, operations = ops)
        } else null
    }

    private fun parseSizeOnly(data: ByteArray, start: Int, length: Int): Long {
        var offset = start
        val end = (start + length).coerceAtMost(data.size)
        var size = 0L
        while (offset < end) {
            val (tag, newOffset) = readVarint(data, offset)
            offset = newOffset
            if (offset >= end) break
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (wireType == 0) {
                val (v, next) = readVarint(data, offset)
                offset = next
                if (fieldNum == 1) size = v
            } else if (wireType == 2) {
                val (len, next) = readVarint(data, offset)
                offset = next + len.toInt()
            } else if (wireType == 1) offset += 8
            else if (wireType == 5) offset += 4
            else break
        }
        return size
    }

    private fun parseOperation(data: ByteArray, start: Int, length: Int): PayloadOp {
        var offset = start
        val end = (start + length).coerceAtMost(data.size)
        var type = 0
        var dataOffset = 0L
        var dataLength = 0L
        val dstExtents = mutableListOf<Extent>()

        while (offset < end) {
            val (tag, newOffset) = readVarint(data, offset)
            offset = newOffset
            if (offset >= end) break
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            when (wireType) {
                0 -> {
                    val (v, next) = readVarint(data, offset)
                    offset = next
                    when (fieldNum) {
                        1 -> type = v.toInt()
                        2 -> dataOffset = v
                        3 -> dataLength = v
                    }
                }
                2 -> {
                    val (len, bodyOffset) = readVarint(data, offset)
                    val fieldLen = len.toInt()
                    offset = bodyOffset
                    if (fieldNum == 6) {
                        val ext = parseExtent(data, offset, fieldLen)
                        dstExtents.add(ext)
                    }
                    offset += fieldLen
                }
                1 -> offset += 8
                5 -> offset += 4
                else -> break
            }
        }
        return PayloadOp(type, dataOffset, dataLength, dstExtents)
    }

    private fun parseExtent(data: ByteArray, start: Int, length: Int): Extent {
        var offset = start
        val end = (start + length).coerceAtMost(data.size)
        var startBlock = 0L
        var numBlocks = 0L

        while (offset < end) {
            val (tag, newOffset) = readVarint(data, offset)
            offset = newOffset
            if (offset >= end) break
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            if (wireType == 0) {
                val (v, next) = readVarint(data, offset)
                offset = next
                if (fieldNum == 1) startBlock = v
                else if (fieldNum == 2) numBlocks = v
            } else if (wireType == 2) {
                val (len, next) = readVarint(data, offset)
                offset = next + len.toInt()
            } else if (wireType == 1) offset += 8
            else if (wireType == 5) offset += 4
            else break
        }
        return Extent(startBlock, numBlocks)
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var current = start
        while (current < data.size) {
            val b = data[current].toInt()
            current++
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift >= 64) break
        }
        return Pair(result, current)
    }
}
