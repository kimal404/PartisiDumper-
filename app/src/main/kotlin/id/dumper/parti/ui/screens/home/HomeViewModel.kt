package id.dumper.parti.ui.screens.home

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import id.dumper.parti.data.model.*
import id.dumper.parti.data.repository.PartitionScanner
import id.dumper.parti.data.repository.RootRepository
import id.dumper.parti.service.DumpService
import id.dumper.parti.util.StorageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel : ViewModel() {
    private val rootRepo = RootRepository()
    private val scanner = PartitionScanner()

    val rootState = MutableStateFlow<RootState>(RootState.Checking)
    val isScanning = MutableStateFlow(false)
    val allPartitions = MutableStateFlow<List<PartitionInfo>>(emptyList())
    val activeSlot = MutableStateFlow("")
    val searchQuery = MutableStateFlow("")
    val filterOnlyCritical = MutableStateFlow(false)
    val deviceInfo = MutableStateFlow(DeviceInfo())

    val selectedTabIndex = MutableStateFlow(0)
    val historyList = MutableStateFlow<List<DumpHistoryItem>>(emptyList())
    val isHistoryLoading = MutableStateFlow(false)

    val dumpState: StateFlow<DumpState> = DumpService.dumpState
    val pendingDumpPartition = MutableStateFlow<PartitionInfo?>(null)
    val showStorageWarning = MutableStateFlow(false)

    val filteredPartitions: StateFlow<List<PartitionInfo>> = combine(
        allPartitions,
        searchQuery,
        filterOnlyCritical
    ) { list, query, onlyCrit ->
        list.filter { part ->
            val matchesQuery = query.isBlank() || part.name.contains(query.trim(), ignoreCase = true)
            val matchesCritical = !onlyCrit || part.isCritical
            matchesQuery && matchesCritical
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        checkRootAndScan()
    }

    fun checkRootAndScan() {
        viewModelScope.launch {
            rootState.value = RootState.Checking
            val state = rootRepo.checkRootAccess()
            rootState.value = state
            if (state is RootState.Granted) {
                deviceInfo.value = scanner.getDeviceInfo()
                scan()
                loadHistory()
            }
        }
    }

    fun scan() {
        viewModelScope.launch {
            isScanning.value = true
            activeSlot.value = scanner.getActiveSlot()
            allPartitions.value = scanner.scanPartitions()
            isScanning.value = false
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            isHistoryLoading.value = true
            historyList.value = withContext(Dispatchers.IO) {
                val dir = StorageUtil.DUMP_FOLDER_PATH
                val cmd = "for f in $dir/*.img; do [ -f \"\$f\" ] && echo \"\$(basename \"\$f\")|\$f|\$(stat -c %s \"\$f\" 2>/dev/null || stat -c %s \"\$f\")|\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0)\"; done"
                val output = Shell.cmd(cmd).exec().out
                val items = mutableListOf<DumpHistoryItem>()

                for (line in output) {
                    val parts = line.split("|")
                    if (parts.size >= 4) {
                        val name = parts[0].trim()
                        val path = parts[1].trim()
                        val size = parts[2].trim().toLongOrNull() ?: 0L
                        val modTime = (parts[3].trim().toLongOrNull() ?: 0L) * 1000L

                        val shaCmd = "cat $path.sha256 2>/dev/null"
                        val shaLine = Shell.cmd(shaCmd).exec().out.firstOrNull()?.trim() ?: ""
                        val recorded = shaLine.split(Regex("\\s+")).firstOrNull()

                        items.add(
                            DumpHistoryItem(
                                name = name,
                                path = path,
                                sizeBytes = size,
                                lastModified = modTime,
                                recordedHash = recorded
                            )
                        )
                    }
                }
                items.sortedByDescending { it.lastModified }
            }
            isHistoryLoading.value = false
        }
    }

    fun verifyChecksum(item: DumpHistoryItem) {
        viewModelScope.launch {
            val updated = historyList.value.map {
                if (it.path == item.path) it.copy(isVerifying = true) else it
            }
            historyList.value = updated

            val result = withContext(Dispatchers.IO) {
                if (item.recordedHash.isNullOrEmpty()) {
                    VerifyResult.NO_HASH_FILE
                } else {
                    val hashCmd = Shell.cmd("sha256sum ${item.path}").exec()
                    val currentHash = hashCmd.out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
                    if (currentHash.equals(item.recordedHash, ignoreCase = true)) {
                        VerifyResult.MATCH
                    } else {
                        VerifyResult.MISMATCH
                    }
                }
            }

            historyList.value = historyList.value.map {
                if (it.path == item.path) it.copy(isVerifying = false, verifyResult = result) else it
            }
        }
    }

    fun deleteHistoryDump(item: DumpHistoryItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Shell.cmd("rm -f ${item.path} ${item.path}.sha256").exec()
            }
            loadHistory()
        }
    }

    fun requestDump(context: Context, partition: PartitionInfo) {
        if (!StorageUtil.hasEnoughSpace(partition.sizeBytes)) {
            pendingDumpPartition.value = partition
            showStorageWarning.value = true
            return
        }
        startDumpService(context, partition)
    }

    fun requestBatchDumpCritical(context: Context) {
        val currentSlot = activeSlot.value
        val targets = allPartitions.value.filter {
            it.isCritical && (it.slot.isEmpty() || it.slot == currentSlot)
        }

        if (targets.isEmpty()) return

        val names = ArrayList(targets.map { it.name })
        val paths = ArrayList(targets.map { it.blockPath })

        val intent = Intent(context, DumpService::class.java).apply {
            action = DumpService.ACTION_START_BATCH
            putStringArrayListExtra(DumpService.EXTRA_NAMES, names)
            putStringArrayListExtra(DumpService.EXTRA_PATHS, paths)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun proceedDumpAnyway(context: Context) {
        pendingDumpPartition.value?.let { partition ->
            showStorageWarning.value = false
            startDumpService(context, partition)
        }
    }

    fun dismissStorageWarning() {
        showStorageWarning.value = false
        pendingDumpPartition.value = null
    }

    fun resetDumpStatus() {
        DumpService.resetState()
        loadHistory()
    }

    private fun startDumpService(context: Context, partition: PartitionInfo) {
        val intent = Intent(context, DumpService::class.java).apply {
            action = DumpService.ACTION_START_DUMP
            putExtra(DumpService.EXTRA_PARTITION_NAME, partition.name)
            putExtra(DumpService.EXTRA_BLOCK_PATH, partition.blockPath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
