package id.dumper.parti.data.repository

import com.topjohnwu.superuser.Shell
import id.dumper.parti.data.model.DeviceInfo
import id.dumper.parti.data.model.PartitionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PartitionScanner {

    private val candidatePaths = listOf(
        "/dev/block/by-name",
        "/dev/block/bootdevice/by-name",
        "/dev/block/platform/bootdevice/by-name",
        "/dev/block/mapper"
    )

    private val criticalNames = setOf(
        "boot",
        "init_boot",
        "vendor_boot",
        "vbmeta",
        "vbmeta_system",
        "vbmeta_vendor",
        "dtbo",
        "recovery",
        "misc"
    )

    suspend fun getDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        val model = Shell.cmd("getprop ro.product.model").exec().out.firstOrNull()?.trim() ?: "Android Device"
        val dev = Shell.cmd("getprop ro.product.device").exec().out.firstOrNull()?.trim() ?: "unknown"
        val plat = Shell.cmd("getprop ro.board.platform").exec().out.firstOrNull()?.trim() ?: "generic"
        val release = Shell.cmd("getprop ro.build.version.release").exec().out.firstOrNull()?.trim() ?: ""
        val patch = Shell.cmd("getprop ro.build.version.security_patch").exec().out.firstOrNull()?.trim() ?: ""

        DeviceInfo(
            model = model,
            device = dev,
            platform = plat,
            androidVersion = release,
            securityPatch = patch
        )
    }

    suspend fun resolveBasePath(): String? = withContext(Dispatchers.IO) {
        for (path in candidatePaths) {
            val result = Shell.cmd("test -d $path && echo 1 || echo 0").exec()
            if (result.out.firstOrNull() == "1") {
                return@withContext path
            }
        }
        null
    }

    suspend fun getActiveSlot(): String = withContext(Dispatchers.IO) {
        val result = Shell.cmd("getprop ro.boot.slot_suffix").exec()
        val raw = result.out.firstOrNull()?.trim() ?: ""
        if (raw.startsWith("_")) raw.substring(1) else raw
    }

    suspend fun scanPartitions(): List<PartitionInfo> = withContext(Dispatchers.IO) {
        val basePath = resolveBasePath() ?: return@withContext emptyList()
        val activeSlot = getActiveSlot()

        val cmd = "for node in " + basePath + "/*; do [ -e \"\$node\" ] && echo \"\$(basename \"\$node\")|\$(readlink -f \"\$node\")|\$(blockdev --getsize64 \"\$node\" 2>/dev/null || echo 0)\"; done"
        val output = Shell.cmd(cmd).exec().out
        val partitions = mutableListOf<PartitionInfo>()

        for (line in output) {
            val parts = line.split("|")
            if (parts.size >= 3) {
                val name = parts[0].trim()
                val realPath = parts[1].trim()
                val sizeBytes = parts[2].trim().toLongOrNull() ?: 0L

                if (name.isEmpty() || !name.matches(Regex("^[a-zA-Z0-9_.-]+$"))) {
                    continue
                }

                val slot = when {
                    name.endsWith("_a") -> "a"
                    name.endsWith("_b") -> "b"
                    else -> ""
                }

                val baseName = if (slot.isNotEmpty()) name.dropLast(2) else name
                val isCritical = criticalNames.contains(baseName.lowercase())

                partitions.add(
                    PartitionInfo(
                        name = name,
                        blockPath = realPath,
                        sizeBytes = sizeBytes,
                        isCritical = isCritical,
                        slot = slot
                    )
                )
            }
        }

        partitions.sortedWith(
            compareByDescending<PartitionInfo> { it.isCritical }
                .thenByDescending { it.slot == activeSlot }
                .thenBy { it.name }
        )
    }
}
