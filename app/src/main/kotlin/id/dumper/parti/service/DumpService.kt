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

    private fun executeSingleDump(partitionName: String, blockPath: String) {
        serviceScope.launch {
            try {
                wakeLock?.acquire(60 * 60 * 1000L)
                val dumpDir = StorageUtil.DUMP_FOLDER_PATH
                val outImgPath = "$dumpDir/$partitionName.img"
                val outShaPath = "$dumpDir/$partitionName.img.sha256"

                Shell.cmd("mkdir -p $dumpDir").exec()

                val totalSizeCmd = Shell.cmd("blockdev --getsize64 $blockPath 2>/dev/null || echo 0").exec()
                val totalBytes = totalSizeCmd.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L

                _dumpState.value = DumpState.Dumping(
                    partitionName = partitionName,
                    current = 1,
                    total = 1,
                    bytesCopied = 0L,
                    totalBytes = totalBytes,
                    stage = "Mengekstrak..."
                )

                val monitorScript = "touch $outImgPath && chmod 664 $outImgPath && dd if=$blockPath of=$outImgPath bs=4M status=none & PID=\$!; while kill -0 \$PID 2>/dev/null; do stat -c %s $outImgPath 2>/dev/null || echo 0; sleep 0.5; done; wait \$PID; echo DONE:\$?"

                var isSuccess = false
                val callback = object : CallbackList<String>() {
                    override fun onAddElement(line: String) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("DONE:")) {
                            isSuccess = trimmed == "DONE:0"
                        } else {
                            val currentCopied = trimmed.toLongOrNull()
                            if (currentCopied != null && currentCopied > 0L) {
                                val percent = if (totalBytes > 0L) ((currentCopied.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0
                                _dumpState.value = DumpState.Dumping(
                                    partitionName = partitionName,
                                    current = 1,
                                    total = 1,
                                    bytesCopied = currentCopied,
                                    totalBytes = totalBytes,
                                    stage = "Mengekstrak..."
                                )
                                updateNotification("$partitionName: $percent% (${StorageUtil.formatBytes(currentCopied)} / ${StorageUtil.formatBytes(totalBytes)})", 1, 1, false, percent)
                            }
                        }
                    }
                }

                Shell.cmd(monitorScript).to(callback).exec()

                if (!isSuccess) {
                    _dumpState.value = DumpState.Error("Gagal menyalin partisi $partitionName: proses I/O terputus")
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
                    _dumpState.value = DumpState.Error("Verifikasi integritas gagal! File dump tidak cocok dengan data blok hardware. File telah dibersihkan.")
                    return@launch
                }

                Shell.cmd("echo \"$dumpFileHash  $partitionName.img\" > $outShaPath && chmod 664 $outShaPath").exec()
                MediaScannerConnection.scanFile(applicationContext, arrayOf(outImgPath, outShaPath), null, null)

                _dumpState.value = DumpState.Success(
                    partitionName = partitionName,
                    outputPath = outImgPath,
                    sha256 = dumpFileHash
                )
            } catch (e: Exception) {
                _dumpState.value = DumpState.Error(e.localizedMessage ?: "Unknown error")
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
                wakeLock?.acquire(90 * 60 * 1000L)
                val total = names.size
                val dumpDir = StorageUtil.DUMP_FOLDER_PATH
                Shell.cmd("mkdir -p $dumpDir").exec()

                val scannedFiles = mutableListOf<String>()

                for (i in 0 until total) {
                    val pName = names[i]
                    val bPath = paths[i]

                    val totalSizeCmd = Shell.cmd("blockdev --getsize64 $bPath 2>/dev/null || echo 0").exec()
                    val totalBytes = totalSizeCmd.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L

                    val outImgPath = "$dumpDir/$pName.img"
                    val outShaPath = "$dumpDir/$pName.img.sha256"

                    _dumpState.value = DumpState.Dumping(
                        partitionName = pName,
                        current = i + 1,
                        total = total,
                        bytesCopied = 0L,
                        totalBytes = totalBytes,
                        stage = "Mengekstrak..."
                    )

                    val monitorScript = "touch $outImgPath && chmod 664 $outImgPath && dd if=$bPath of=$outImgPath bs=4M status=none & PID=\$!; while kill -0 \$PID 2>/dev/null; do stat -c %s $outImgPath 2>/dev/null || echo 0; sleep 0.5; done; wait \$PID; echo DONE:\$?"

                    var isSuccess = false
                    val callback = object : CallbackList<String>() {
                        override fun onAddElement(line: String) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("DONE:")) {
                                isSuccess = trimmed == "DONE:0"
                            } else {
                                val currentCopied = trimmed.toLongOrNull()
                                if (currentCopied != null && currentCopied > 0L) {
                                    val percent = if (totalBytes > 0L) ((currentCopied.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0
                                    _dumpState.value = DumpState.Dumping(
                                        partitionName = pName,
                                        current = i + 1,
                                        total = total,
                                        bytesCopied = currentCopied,
                                        totalBytes = totalBytes,
                                        stage = "Mengekstrak..."
                                    )
                                    updateNotification("$pName: $percent% (${StorageUtil.formatBytes(currentCopied)} / ${StorageUtil.formatBytes(totalBytes)})", i + 1, total, false, percent)
                                }
                            }
                        }
                    }

                    Shell.cmd(monitorScript).to(callback).exec()

                    if (isSuccess) {
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
                            Shell.cmd("echo \"$fileHash  $pName.img\" > $outShaPath && chmod 664 $outShaPath").exec()
                            scannedFiles.add(outShaPath)
                            scannedFiles.add(outImgPath)
                        } else {
                            Shell.cmd("rm -f $outImgPath").exec()
                        }
                    }
                }

                MediaScannerConnection.scanFile(applicationContext, scannedFiles.toTypedArray(), null, null)
                _dumpState.value = DumpState.BatchSuccess(scannedFiles.size / 2, dumpDir)
            } catch (e: Exception) {
                _dumpState.value = DumpState.Error(e.localizedMessage ?: "Unknown error")
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
