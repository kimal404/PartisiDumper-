package id.dumper.parti.data.repository

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object ZipPayloadFinder {

    fun getPayloadOffset(channel: FileChannel): Long {
        val checkBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        channel.position(0)
        channel.read(checkBuf)
        checkBuf.flip()

        if (checkBuf.remaining() >= 4) {
            val magic = ByteArray(4)
            checkBuf.get(magic)
            if (String(magic, Charsets.US_ASCII) == "CrAU") {
                return 0L
            }
        }

        var pos = 0L
        val size = channel.size()
        val headerBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)

        while (pos + 30 <= size) {
            channel.position(pos)
            headerBuf.clear()
            val read = channel.read(headerBuf)
            if (read < 30) break
            headerBuf.flip()

            val sig = headerBuf.int
            if (sig != 0x04034b50) break

            headerBuf.short // version
            val flags = headerBuf.short.toInt()
            headerBuf.short // method
            headerBuf.short // time
            headerBuf.short // date
            headerBuf.int   // crc
            val compSize = headerBuf.int.toLong() and 0xFFFFFFFFL
            headerBuf.int   // uncompSize
            val nameLen = headerBuf.short.toInt() and 0xFFFF
            val extraLen = headerBuf.short.toInt() and 0xFFFF

            val nameBytes = ByteArray(nameLen)
            channel.read(ByteBuffer.wrap(nameBytes))
            val entryName = String(nameBytes, Charsets.UTF_8)
            val dataOffset = pos + 30 + nameLen + extraLen

            if (entryName == "payload.bin" || entryName.endsWith("/payload.bin")) {
                val crauBuf = ByteBuffer.allocate(4)
                channel.position(dataOffset)
                channel.read(crauBuf)
                crauBuf.flip()
                val m = ByteArray(4)
                crauBuf.get(m)
                if (String(m, Charsets.US_ASCII) == "CrAU") {
                    return dataOffset
                }
            }

            if ((flags and 0x08) != 0 || compSize == 0xFFFFFFFFL || compSize == 0L) {
                break
            }
            pos = dataOffset + compSize
        }

        return findPayloadFromCentralDirectory(channel) ?: 0L
    }

    private fun findPayloadFromCentralDirectory(channel: FileChannel): Long? {
        val size = channel.size()
        val maxScan = minOf(size, 65557L).toInt()
        val scanBuf = ByteBuffer.allocate(maxScan).order(ByteOrder.LITTLE_ENDIAN)
        val scanStart = size - maxScan
        channel.position(scanStart)
        channel.read(scanBuf)
        scanBuf.flip()

        var eocdPos = -1
        for (i in maxScan - 22 downTo 0) {
            if (scanBuf.getInt(i) == 0x06054b50) {
                eocdPos = i
                break
            }
        }
        if (eocdPos == -1) return null

        var cdOffset = scanBuf.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL
        val cdSize = scanBuf.getInt(eocdPos + 12).toLong() and 0xFFFFFFFFL

        if (cdOffset == 0xFFFFFFFFL && eocdPos >= 20) {
            val locatorPos = eocdPos - 20
            if (scanBuf.getInt(locatorPos) == 0x07064b50) {
                val zip64EocdOffset = scanBuf.getLong(locatorPos + 8)
                val z64Buf = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
                channel.position(zip64EocdOffset)
                channel.read(z64Buf)
                z64Buf.flip()
                if (z64Buf.int == 0x06064b50) {
                    z64Buf.position(48)
                    cdOffset = z64Buf.long
                }
            }
        }

        var curCd = cdOffset
        val cdEnd = cdOffset + cdSize
        val cdEntryHeader = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)

        while (curCd + 46 <= cdEnd) {
            channel.position(curCd)
            cdEntryHeader.clear()
            val r = channel.read(cdEntryHeader)
            if (r < 46) break
            cdEntryHeader.flip()

            if (cdEntryHeader.int != 0x02014b50) break

            cdEntryHeader.position(28)
            val nameLen = cdEntryHeader.short.toInt() and 0xFFFF
            val extraLen = cdEntryHeader.short.toInt() and 0xFFFF
            val commentLen = cdEntryHeader.short.toInt() and 0xFFFF
            cdEntryHeader.position(42)
            val localOffset = cdEntryHeader.int.toLong() and 0xFFFFFFFFL

            val nameBytes = ByteArray(nameLen)
            channel.read(ByteBuffer.wrap(nameBytes))
            val entryName = String(nameBytes, Charsets.UTF_8)

            if (entryName == "payload.bin" || entryName.endsWith("/payload.bin")) {
                val locBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                channel.position(localOffset)
                channel.read(locBuf)
                locBuf.flip()
                if (locBuf.int == 0x04034b50) {
                    locBuf.position(26)
                    val locNameLen = locBuf.short.toInt() and 0xFFFF
                    val locExtraLen = locBuf.short.toInt() and 0xFFFF
                    return localOffset + 30 + locNameLen + locExtraLen
                }
            }

            curCd += 46 + nameLen + extraLen + commentLen
        }

        return null
    }
}
