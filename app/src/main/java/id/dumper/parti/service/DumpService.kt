package id.dumper.parti.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import id.dumper.parti.data.model.DumpState
import id.dumper.parti.util.StorageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DumpService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START_DUMP = "id.dumper.parti.ACTION_START_DUMP"
        const val ACTION_START_BATCH = "id.dumper.parti.ACTION_START_BATCH"
        const val EXTRA_PARTITION_NAME = "EXTRA_PARTITION_NAME"
        const val EXTRA_BLOCK_PATH = "EXTRA_BLOCK_PATH"
        const val EXTRA_NAMES = "EXTRA_NAMES"
        const val EXTRA_PATHS = "EXTRA_PATHS"

        private const val CHANNEL_ID = "partidumper_dump_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MONITOR_THRESHOLD_BYTES = 100 * 1024 * 1024L

        private val _dumpState = MutableStateFlow<DumpState>(DumpState.Idle)
        val dumpState = _dumpState.asStateFlow()

        fun resetState() {
            _dumpState.value = DumpState.Idle
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PartisiDumper:DumpLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DUMP -> {
                val partitionName = intent.getStringExtra(EXTRA_PARTITION_NAME) ?: ""
                val blockPath = intent.getStringExtra(EXTRA_BLOCK_PATH) ?: ""
                if (partitionName.isNotEmpty() && blockPath.isNotEmpty()) {
                    updateNotification("Mempersiapkan dump $partitionName...", 1, 1, true, 0)
                    executeSingleDump(partitionName, blockPath)
                }
            }
            ACTION_START_BATCH -> {
                val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
                val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
                if (names.isNotEmpty() && names.size == paths.size) {
                    executeBatchDump(names, paths)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Partition Dump Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi proses dump partisi"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String, current: Int, total: Int, isIndeterminate: Boolean, progressPercent: Int) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PartisiDumper ($current/$total)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isIndeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), false)
        }

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun getBlockSize(path: String): Long {
        return Shell.cmd("blockdev --getsize64 $path 2>/dev/null || echo 0")
            .exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
    }

    private fun acquireDynamicWakeLock(totalBytes: Long) {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        val totalMb = totalBytes / (1024 * 1024)
        val estimatedSec = (totalMb / 30L) * 2
        val timeoutMs = ((estimatedSec + 600) * 1000L).coerceIn(15 * 60 * 1000L, 6 * 3600 * 1000L)
        wakeLock?.acquire(timeoutMs)
    }

    private fun formatEta(seconds: Long): String {
        return if (seconds < 0) "--:--" else String.format("%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun diagnoseFailure(partitionName: String, blockPath: String, exitCode: Int, stderr: String): Pair<String, String> {
        val selinux = Shell.cmd("getenforce 2>/dev/null").exec().out.firstOrNull()?.trim() ?: "Unknown"
        val dmesg = Shell.cmd("dmesg 2>/dev/null | grep -iE 'defex|rkp|avc|denied' | tail -n 5").exec().out.joinToString("\n").trim()
        val blockExists = Shell.cmd("[ -e \"$blockPath\" ] && echo 'EXISTS' || echo 'MISSING'").exec().out.firstOrNull()?.trim() ?: "UNKNOWN"

        val reason = when {
            exitCode == 137 -> "Proses dibunuh kernel (SIGKILL/Exit 137). Terdeteksi proteksi kernel aktif (Samsung DEFEX/RKP)."
            exitCode == 139 -> "Segmentation fault pada dd/shell (Exit 139)."
            stderr.contains("Permission denied", ignoreCase = true) -> "Akses ditolak (Permission Denied). Izin domain root dibatasi SELinux."
            stderr.contains("Operation not permitted", ignoreCase = true) -> "Operasi ditolak. Blok dikunci security kernel vendor."
            stderr.contains("No space left", ignoreCase = true) -> "Memori internal penuh saat ekstraksi."
            blockExists == "MISSING" -> "Path partisi tidak valid atau link putus ($blockPath)."
            stderr.contains("Input/output error", ignoreCase = true) -> "Hardware I/O Error pada chip UFS/eMMC."
            stderr.isNotEmpty() -> stderr
            else -> "Proses stream terputus secara tidak wajar (Exit: $exitCode)."
        }

        val logDir = StorageUtil.DUMP_FOLDER_PATH
        val techLog = buildString {
            appendLine("=== PartisiDumper Diagnostic Log ===")
            appendLine("Partisi    : $partitionName")
            appendLine("Block Path : $blockPath ($blockExists)")
            appendLine("Exit Code  : $exitCode")
            appendLine("SELinux    : $selinux")
            if (stderr.isNotEmpty()) {
                appendLine("Stderr     : $stderr")
            }
            if (dmesg.isNotEmpty()) {
                appendLine("Kernel Log :")
                appendLine(dmesg)
            }
            appendLine("====================================")
        }

        try {
            val safeLog = techLog.replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
            Shell.cmd("mkdir -p \"$logDir\" && echo \"$safeLog\" > \"$logDir/last_error.log\"").exec()
        } catch (_: Exception) {}

        return Pair(reason, techLog)
    }

    private fun executeSingleDump(partitionName: String, blockPath: String) {
        serviceScope.launch {
            try {
                val totalBytes = getBlockSize(blockPath)
                acquireDynamicWakeLock(totalBytes)

                val dumpDir = StorageUtil.DUMP_FOLDER_PATH
                val outImgPath = "$dumpDir/$partitionName.img"
                val outShaPath = "$dumpDir/$partitionName.img.sha256"
                val errFile = "$dumpDir/.err_${partitionName}"

                Shell.cmd("mkdir -p $dumpDir").exec()

                var isSuccess = false
                var lastExitCode = 0
                var lastStderr = ""

                if (totalBytes in 1L until MONITOR_THRESHOLD_BYTES) {
                    _dumpState.value = DumpState.Dumping(
                        partitionName = partitionName,
                        current = 1,
                        total = 1,
                        bytesCopied = 0L,
                        totalBytes = totalBytes,
                        stage = "Mengekstrak..."
                    )
                    updateNotification("Mengekstrak $partitionName...", 1, 1, true, 0)
                    val cmd = "touch \"$outImgPath\" && chmod 664 \"$outImgPath\" && dd if=\"$blockPath\" of=\"$outImgPath\" bs=4M status=none 2>\"$errFile\""
                    val res = Shell.cmd(cmd).exec()
                    lastExitCode = res.code
                    isSuccess = res.isSuccess
                    if (!isSuccess) {
                        lastStderr = Shell.cmd("cat \"$errFile\" 2>/dev/null; rm -f \"$errFile\"").exec().out.joinToString("\n").trim()
                    }
                } else {
                    _dumpState.value = DumpState.Dumping(
                        partitionName = partitionName,
                        current = 1,
                        total = 1,
                        bytesCopied = 0L,
                        totalBytes = totalBytes,
                        stage = "Mengekstrak..."
                    )

                    val statusFile = "$outImgPath.status"
                    val monitorScript = "touch \"$outImgPath\" && chmod 664 \"$outImgPath\" && ( dd if=\"$blockPath\" of=\"$outImgPath\" bs=4M status=none 2>\"$errFile\"; echo \$? > \"$statusFile\" ) & PID=\$!; while kill -0 \$PID 2>/dev/null; do stat -c %s \"$outImgPath\" 2>/dev/null || echo 0; sleep 0.5; done; STATUS=\$(cat \"$statusFile\" 2>/dev/null || echo 1); rm -f \"$statusFile\"; echo DONE:\$STATUS"

                    var lastBytes = 0L
                    var lastTime = System.currentTimeMillis()
                    var currentSpeed = 0L
                    var etaSeconds = -1L

                    val callback = object : CallbackList<String>() {
                        override fun onAddElement(line: String) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("DONE:")) {
                                val code = trimmed.removePrefix("DONE:").toIntOrNull() ?: 1
                                lastExitCode = code
                                isSuccess = (code == 0)
                            } else {
                                val currentCopied = trimmed.toLongOrNull()
                                if (currentCopied != null && currentCopied > 0L) {
                                    val now = System.currentTimeMillis()
                                    val timeDiff = now - lastTime
                                    if (timeDiff >= 500) {
                                        val bytesDiff = currentCopied - lastBytes
                                        if (bytesDiff >= 0) {
                                            currentSpeed = (bytesDiff * 1000L) / timeDiff
                                            if (currentSpeed > 0L && totalBytes > currentCopied) {
                                                etaSeconds = (totalBytes - currentCopied) / currentSpeed
                                            }
                                        }
                                        lastBytes = currentCopied
                                        lastTime = now
                                    }

                                    val percent = if (totalBytes > 0L) ((currentCopied.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0
                                    _dumpState.value = DumpState.Dumping(
                                        partitionName = partitionName,
                                        current = 1,
                                        total = 1,
                                        bytesCopied = currentCopied,
                                        totalBytes = totalBytes,
                                        stage = "Mengekstrak...",
                                        speedBytesPerSec = currentSpeed,
                                        etaSeconds = etaSeconds
                                    )
                                    val speedText = if (currentSpeed > 0) "${StorageUtil.formatBytes(currentSpeed)}/s" else "-- MB/s"
                                    val etaText = formatEta(etaSeconds)
                                    updateNotification("$partitionName: $percent% • $speedText • Sisa: $etaText", 1, 1, false, percent)
                                }
                            }
                        }
                    }

                    Shell.cmd(monitorScript).to(callback).exec()
                    if (!isSuccess) {
                        lastStderr = Shell.cmd("cat \"$errFile\" 2>/dev/null; rm -f \"$errFile\"").exec().out.joinToString("\n").trim()
                    }
                }

                if (!isSuccess) {
                    Shell.cmd("rm -f \"$outImgPath\" \"$errFile\"").exec()
                    val (reason, techLog) = diagnoseFailure(partitionName, blockPath, lastExitCode, lastStderr)
                    _dumpState.value = DumpState.Error("Gagal menyalin partisi $partitionName: $reason", techLog)
                    return@launch
                }

                _dumpState.value = DumpState.Dumping(
                    partitionName = partitionName,
                    current = 1,
                    total = 1,
                    bytesCopied = totalBytes,
                    totalBytes = totalBytes,
                    stage = "Menghitung SHA-256 blok hardware..."
                )
                updateNotification("Verifikasi SHA-256 blok hardware...", 1, 1, true, 0)

                val rawBlockHash = Shell.cmd("sha256sum $blockPath").exec().out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""

                _dumpState.value = DumpState.Dumping(
                    partitionName = partitionName,
                    current = 1,
                    total = 1,
                    bytesCopied = totalBytes,
                    totalBytes = totalBytes,
                    stage = "Menghitung SHA-256 file dump..."
                )
                updateNotification("Verifikasi SHA-256 file dump...", 1, 1, true, 0)

                val dumpFileHash = Shell.cmd("sha256sum $outImgPath").exec().out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""

                if (rawBlockHash.isEmpty() || dumpFileHash.isEmpty() || !rawBlockHash.equals(dumpFileHash, ignoreCase = true)) {
                    Shell.cmd("rm -f $outImgPath").exec()
                    val hashLog = "SHA Hardware : $rawBlockHash\nSHA Dump File : $dumpFileHash"
                    _dumpState.value = DumpState.Error("Verifikasi integritas gagal! File dump korup.", hashLog)
                    return@launch
                }

                Shell.cmd("echo \"$dumpFileHash  $partitionName.img\" > $outShaPath && chmod 664$outShaPath").exec()
                MediaScannerConnection.scanFile(applicationContext, arrayOf(outImgPath, outShaPath), null, null)

                _dumpState.value = DumpState.Success(
                    partitionName = partitionName,
                    outputPath = outImgPath,
                    sha256 = dumpFileHash
                )
            } catch (e: Exception) {
                _dumpState.value = DumpState.Error(e.localizedMessage ?: "Unknown error", e.stackTraceToString())
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun executeBatchDump(names: List<String>, paths: List<String>) {
        serviceScope.launch {
            try {
                val total = names.size
                val dumpDir = StorageUtil.DUMP_FOLDER_PATH
                Shell.cmd("mkdir -p $dumpDir").exec()

                val totalBatchBytes = paths.sumOf { getBlockSize(it) }
                acquireDynamicWakeLock(totalBatchBytes)

                val scannedFiles = mutableListOf<String>()
                val successList = mutableListOf<String>()
                val failedList = mutableListOf<Pair<String, String>>()

                for (i in 0 until total) {
                    val pName = names[i]
                    val bPath = paths[i]
                    val totalBytes = getBlockSize(bPath)

                    val outImgPath = "$dumpDir/$pName.img"
                    val outShaPath = "$dumpDir/$pName.img.sha256"
                    val errFile = "$dumpDir/.err_${pName}"

                    var isSuccess = false
                    var lastExitCode = 0
                    var lastStderr = ""

                    if (totalBytes in 1L until MONITOR_THRESHOLD_BYTES) {
                        _dumpState.value = DumpState.Dumping(
                            partitionName = pName,
                            current = i + 1,
                            total = total,
                            bytesCopied = 0L,
                            totalBytes = totalBytes,
                            stage = "Mengekstrak..."
                        )
                        updateNotification("Mengekstrak $pName...", i + 1, total, true, 0)
                        val cmd = "touch \"$outImgPath\" && chmod 664 \"$outImgPath\" && dd if=\"$bPath\" of=\"$outImgPath\" bs=4M status=none 2>\"$errFile\""
                        val res = Shell.cmd(cmd).exec()
                        lastExitCode = res.code
                        isSuccess = res.isSuccess
                        if (!isSuccess) {
                            lastStderr = Shell.cmd("cat \"$errFile\" 2>/dev/null; rm -f \"$errFile\"").exec().out.joinToString("\n").trim()
                        }
                    } else {
                        _dumpState.value = DumpState.Dumping(
                            partitionName = pName,
                            current = i + 1,
                            total = total,
                            bytesCopied = 0L,
                            totalBytes = totalBytes,
                            stage = "Mengekstrak..."
                        )

                        val statusFile = "$outImgPath.status"
                        val monitorScript = "touch \"$outImgPath\" && chmod 664 \"$outImgPath\" && ( dd if=\"$bPath\" of=\"$outImgPath\" bs=4M status=none 2>\"$errFile\"; echo \$? > \"$statusFile\" ) & PID=\$!; while kill -0 \$PID 2>/dev/null; do stat -c %s \"$outImgPath\" 2>/dev/null || echo 0; sleep 0.5; done; STATUS=\$(cat \"$statusFile\" 2>/dev/null || echo 1); rm -f \"$statusFile\"; echo DONE:\$STATUS"

                        var lastBytes = 0L
                        var lastTime = System.currentTimeMillis()
                        var currentSpeed = 0L
                        var etaSeconds = -1L

                        val callback = object : CallbackList<String>() {
                            override fun onAddElement(line: String) {
                                val trimmed = line.trim()
                                if (trimmed.startsWith("DONE:")) {
                                    val code = trimmed.removePrefix("DONE:").toIntOrNull() ?: 1
                                    lastExitCode = code
                                    isSuccess = (code == 0)
                                } else {
                                    val currentCopied = trimmed.toLongOrNull()
                                    if (currentCopied != null && currentCopied > 0L) {
                                        val now = System.currentTimeMillis()
                                        val timeDiff = now - lastTime
                                        if (timeDiff >= 500) {
                                            val bytesDiff = currentCopied - lastBytes
                                            if (bytesDiff >= 0) {
                                                currentSpeed = (bytesDiff * 1000L) / timeDiff
                                                if (currentSpeed > 0L && totalBytes > currentCopied) {
                                                    etaSeconds = (totalBytes - currentCopied) / currentSpeed
                                                }
                                            }
                                            lastBytes = currentCopied
                                            lastTime = now
                                        }

                                        val percent = if (totalBytes > 0L) ((currentCopied.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0
                                        _dumpState.value = DumpState.Dumping(
                                            partitionName = pName,
                                            current = i + 1,
                                            total = total,
                                            bytesCopied = currentCopied,
                                            totalBytes = totalBytes,
                                            stage = "Mengekstrak...",
                                            speedBytesPerSec = currentSpeed,
                                            etaSeconds = etaSeconds
                                        )
                                        val speedText = if (currentSpeed > 0) "${StorageUtil.formatBytes(currentSpeed)}/s" else "-- MB/s"
                                        val etaText = formatEta(etaSeconds)
                                        updateNotification("$pName ($percent%) • $speedText • Sisa: $etaText", i + 1, total, false, percent)
                                    }
                                }
                            }
                        }

                        Shell.cmd(monitorScript).to(callback).exec()
                        if (!isSuccess) {
                            lastStderr = Shell.cmd("cat \"$errFile\" 2>/dev/null; rm -f \"$errFile\"").exec().out.joinToString("\n").trim()
                        }
                    }

                    if (!isSuccess) {
                        Shell.cmd("rm -f \"$outImgPath\" \"$errFile\"").exec()
                        val (reason, _) = diagnoseFailure(pName, bPath, lastExitCode, lastStderr)
                        failedList.add(pName to reason)
                        continue
                    }

                    _dumpState.value = DumpState.Dumping(
                        partitionName = pName,
                        current = i + 1,
                        total = total,
                        bytesCopied = totalBytes,
                        totalBytes = totalBytes,
                        stage = "Verifikasi SHA-256..."
                    )
                    updateNotification("Verifikasi SHA-256 $pName...", i + 1, total, true, 0)

                    val rawHash = Shell.cmd("sha256sum $bPath").exec().out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
                    val fileHash = Shell.cmd("sha256sum $outImgPath").exec().out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""

                    if (rawHash.isNotEmpty() && rawHash.equals(fileHash, ignoreCase = true)) {
                        Shell.cmd("echo \"$fileHash$pName.img\" > $outShaPath && chmod 664$outShaPath").exec()
                        scannedFiles.add(outShaPath)
                        scannedFiles.add(outImgPath)
                        successList.add(pName)
                    } else {
                        Shell.cmd("rm -f $outImgPath").exec()
                        failedList.add(pName to "Hash SHA-256 tidak cocok")
                    }
                }

                if (scannedFiles.isNotEmpty()) {
                    MediaScannerConnection.scanFile(applicationContext, scannedFiles.toTypedArray(), null, null)
                }
                _dumpState.value = DumpState.BatchSummary(
                    success = successList,
                    failed = failedList,
                    outputDir = dumpDir
                )
            } catch (e: Exception) {
                _dumpState.value = DumpState.Error(e.localizedMessage ?: "Unknown error", e.stackTraceToString())
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
