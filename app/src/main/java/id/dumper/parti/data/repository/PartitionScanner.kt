package id.dumper.parti.data.repository

import com.topjohnwu.superuser.Shell
import id.dumper.parti.data.model.PartitionInfo
import java.io.BufferedReader
import java.io.InputStreamReader

class PartitionScanner {

    private val criticalNames = setOf(
        "boot", "init_boot", "vendor_boot", "dtbo",
        "vbmeta", "vbmeta_system", "vbmeta_vendor",
        "recovery", "persist", "metadata", "misc",
        "modem", "bluetooth", "dsp"
    )

    private val excludedNames = setOf(
        "userdata", "data"
    )

    fun getActiveSlot(): String {
        return try {
            if (Shell.isAppGrantedRoot() == true) {
                val out = Shell.cmd("getprop ro.boot.slot_suffix").exec().out
                if (out.isNotEmpty()) {
                    val raw = out[0].trim().replace("_", "")
                    if (raw.isNotEmpty()) return raw
                }
            }
            val proc = Runtime.getRuntime().exec("getprop ro.boot.slot_suffix")
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val line = reader.readLine()?.trim() ?: ""
            proc.waitFor()
            line.replace("_", "")
        } catch (_: Exception) {
            ""
        }
    }

    fun scanPartitions(): List<PartitionInfo> {
        return try {
            val byNameDir = findByNameDir() ?: return emptyList()
            val activeSlot = getActiveSlot()

            val listCmd = Shell.cmd("ls -l $byNameDir").exec()
            if (!listCmd.isSuccess) return emptyList()

            val result = mutableListOf<PartitionInfo>()
            val seenPaths = mutableSetOf<String>()

            for (line in listCmd.out) {
                val parts = line.trim().split(Regex("\\s+"))
                val arrowIndex = parts.indexOf("->")
                if (arrowIndex < 1 || arrowIndex >= parts.size - 1) continue

                val name = parts[arrowIndex - 1]
                val rawTarget = parts[arrowIndex + 1]

                val targetPath = if (rawTarget.startsWith("/")) {
                    rawTarget
                } else {
                    val parent = byNameDir.substringBeforeLast('/')
                    normalizePath("$parent/$rawTarget")
                }

                val resolvedBlock = resolveRealPath(targetPath)
                if (seenPaths.contains(resolvedBlock)) continue
                seenPaths.add(resolvedBlock)

                // Filter blok disk mentah (sda/sdc/lun) dan partisi userdata
                if (isExcluded(name, resolvedBlock)) continue

                val sizeBytes = getBlockSize(resolvedBlock)
                val slot = detectSlot(name)
                val isCrit = isCriticalPartition(name)

                result.add(
                    PartitionInfo(
                        name = name,
                        blockPath = resolvedBlock,
                        sizeBytes = sizeBytes,
                        isCritical = isCrit,
                        slot = slot
                    )
                )
            }

            result.sortedWith(
                Comparator<PartitionInfo> { a, b ->
                    val critCmp = b.isCritical.compareTo(a.isCritical)
                    if (critCmp != 0) return@Comparator critCmp
                    val slotA = if (a.slot == activeSlot) 0 else 1
                    val slotB = if (b.slot == activeSlot) 0 else 1
                    val slotCmp = slotA.compareTo(slotB)
                    if (slotCmp != 0) return@Comparator slotCmp
                    a.name.compareTo(b.name)
                }
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isExcluded(name: String, resolvedBlock: String): Boolean {
        val baseName = name.removeSuffix("_a").removeSuffix("_b").lowercase()
        if (excludedNames.contains(baseName)) return true

        // Abaikan block path induk chip UFS/eMMC mentah (misal: /dev/block/sda, sdb, sdc, mmcblk0)
        val blockName = resolvedBlock.substringAfterLast('/')
        val isWholeDisk = blockName.matches(Regex("^(sd[a-z]|mmcblk\\d+|nvme\\d+n\\d+)$"))
        if (isWholeDisk) return true

        // Abaikan symlink yang dinamai langsung dengan identitas disk fisik
        if (name.matches(Regex("^(sd[a-z]|mmcblk\\d+|lun\\d+)$", RegexOption.IGNORE_CASE))) return true

        return false
    }

    private fun findByNameDir(): String? {
        val candidates = listOf(
            "/dev/block/by-name",
            "/dev/block/bootdevice/by-name",
            "/dev/block/platform/soc/by-name",
            "/dev/block/platform/soc.0/by-name"
        )
        for (dir in candidates) {
            val check = Shell.cmd("[ -d $dir ] && echo OK").exec()
            if (check.isSuccess && check.out.contains("OK")) return dir
        }
        val findCmd = Shell.cmd("find /dev/block -name 'by-name' 2>/dev/null | head -1").exec()
        return findCmd.out.firstOrNull()?.trim()?.ifEmpty { null }
    }

    private fun resolveRealPath(path: String): String {
        val cmd = Shell.cmd("readlink -f $path 2>/dev/null").exec()
        val out = cmd.out.firstOrNull()?.trim()
        return if (!out.isNullOrEmpty()) out else path
    }

    private fun getBlockSize(blockPath: String): Long {
        val cmd = Shell.cmd("blockdev --getsize64 $blockPath 2>/dev/null").exec()
        val out = cmd.out.firstOrNull()?.trim()
        return out?.toLongOrNull() ?: 0L
    }

    private fun detectSlot(name: String): String {
        return when {
            name.endsWith("_a") -> "a"
            name.endsWith("_b") -> "b"
            else -> ""
        }
    }

    private fun isCriticalPartition(name: String): Boolean {
        val baseName = name.removeSuffix("_a").removeSuffix("_b")
        return criticalNames.contains(baseName)
    }

    private fun normalizePath(path: String): String {
        val segments = path.split("/").filter { it.isNotEmpty() && it != "." }
        val stack = mutableListOf<String>()
        for (seg in segments) {
            if (seg == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            } else {
                stack.add(seg)
            }
        }
        return "/" + stack.joinToString("/")
    }
}
