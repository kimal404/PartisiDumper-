package id.dumper.parti.ui.screens.home

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import id.dumper.parti.PartiApp
import id.dumper.parti.data.model.*
import id.dumper.parti.data.repository.PartitionScanner
import id.dumper.parti.data.repository.PayloadParser
import id.dumper.parti.data.repository.RootRepository
import id.dumper.parti.service.DumpService
import id.dumper.parti.util.StorageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class HistoryCategory {
    DUMP,
    OTA
}

class HomeViewModel : ViewModel() {

    private val rootRepo = RootRepository()
    private val scanner = PartitionScanner()
    private val payloadParser = PayloadParser()

    val rootState = MutableStateFlow<RootState>(RootState.Checking)
    val isScanning = MutableStateFlow(false)

    val allPartitions = MutableStateFlow<List<PartitionInfo>>(emptyList())
    val searchQuery = MutableStateFlow("")
    val filterOnlyCritical = MutableStateFlow(false)
    val activeSlot = MutableStateFlow("")

    val dumpState: StateFlow<DumpState> = DumpService.dumpState
    val otaPartitionStatus: StateFlow<Map<String, ExtractionStatus>> = DumpService.otaPartitionStatus

    val showStorageWarning = MutableStateFlow(false)
    val pendingDumpPartition = MutableStateFlow<PartitionInfo?>(null)

    val selectedTabIndex = MutableStateFlow(0)
    val historyCategory = MutableStateFlow(HistoryCategory.DUMP)
    val dumpHistoryList = MutableStateFlow<List<DumpHistoryItem>>(emptyList())
    val otaHistoryList = MutableStateFlow<List<DumpHistoryItem>>(emptyList())
    val isHistoryLoading = MutableStateFlow(false)

    val currentHistoryList: StateFlow<List<DumpHistoryItem>> = combine(
        dumpHistoryList,
        otaHistoryList,
        historyCategory
    ) { dumps, otas, cat ->
        if (cat == HistoryCategory.DUMP) dumps else otas
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalHistoryCount: StateFlow<Int> = combine(
        dumpHistoryList,
        otaHistoryList
    ) { dumps, otas ->
        dumps.size + otas.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val selectedPayloadUri = MutableStateFlow<Uri?>(null)
    val payloadFileName = MutableStateFlow("")
    val payloadFileSize = MutableStateFlow(0L)
    val payloadPartitions = MutableStateFlow<List<PayloadPartitionInfo>>(emptyList())
    val payloadSearchQuery = MutableStateFlow("")
    val isParsingPayload = MutableStateFlow(false)
    val payloadError = MutableStateFlow<String?>(null)

    val filteredPartitions: StateFlow<List<PartitionInfo>> = combine(
        allPartitions,
        searchQuery,
        filterOnlyCritical,
        activeSlot
    ) { parts, query, onlyCrit, slot ->
        parts.filter { part ->
            val matchesQuery = if (query.isBlank()) true else {
                part.name.contains(query, ignoreCase = true) ||
                part.blockPath.contains(query, ignoreCase = true)
            }
            val matchesCrit = if (!onlyCrit) true else part.isCritical
            val matchesSlot = if (slot.isEmpty()) true else {
                part.slot.isEmpty() || part.slot == slot
            }
            matchesQuery && matchesCrit && matchesSlot
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredPayloadPartitions: StateFlow<List<PayloadPartitionInfo>> = combine(
        payloadPartitions,
        payloadSearchQuery
    ) { list, query ->
        if (query.isBlank()) list else {
            list.filter { it.name.contains(query, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        checkRootAndScan()
        viewModelScope.launch {
            dumpState.collect { state ->
                if (state is DumpState.Success || state is DumpState.BatchSummary) {
                    loadHistory()
                }
            }
        }
    }

    fun setHistoryCategory(category: HistoryCategory) {
        historyCategory.value = category
    }

    fun checkRootAndScan() {
        viewModelScope.launch {
            rootState.value = RootState.Checking
            val slot = withContext(Dispatchers.IO) { scanner.getActiveSlot() }
            activeSlot.value = slot
            val state = rootRepo.checkRootAccess()
            rootState.value = state
            if (state is RootState.Granted) {
                scan()
                loadHistory()
            }
        }
    }

    fun scan() {
        if (rootState.value !is RootState.Granted) return
        viewModelScope.launch {
            isScanning.value = true
            val parts = withContext(Dispatchers.IO) { scanner.scanPartitions() }
            val slot = withContext(Dispatchers.IO) { scanner.getActiveSlot() }
            allPartitions.value = parts
            activeSlot.value = slot
            isScanning.value = false
        }
    }

    fun onPayloadFileSelected(context: Context, uri: Uri) {
        selectedPayloadUri.value = uri
        payloadError.value = null

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) payloadFileName.value = cursor.getString(nameIndex) ?: "OTA_Payload.bin"
                    if (sizeIndex != -1) payloadFileSize.value = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {
            payloadFileName.value = "OTA_Payload.bin"
        }

        viewModelScope.launch {
            isParsingPayload.value = true
            val result = payloadParser.parsePayload(context, uri)
            result.onSuccess { list ->
                payloadPartitions.value = list
            }.onFailure { err ->
                payloadPartitions.value = emptyList()
                payloadError.value = err.localizedMessage ?: "Gagal memproses file OTA"
            }
            isParsingPayload.value = false
        }
    }

    fun togglePayloadSelection(name: String) {
        payloadPartitions.value = payloadPartitions.value.map {
            if (it.name == name) it.copy(isSelected = !it.isSelected) else it
        }
    }

    fun selectAllPayloadPartitions(selectAll: Boolean) {
        payloadPartitions.value = payloadPartitions.value.map {
            it.copy(isSelected = selectAll)
        }
    }

    fun clearPayload() {
        selectedPayloadUri.value = null
        payloadFileName.value = ""
        payloadFileSize.value = 0L
        payloadPartitions.value = emptyList()
        payloadError.value = null
    }

    fun extractSelectedPayloadPartitions(context: Context) {
        val uri = selectedPayloadUri.value ?: return
        val targets = payloadPartitions.value.filter { it.isSelected }
        if (targets.isEmpty()) return

        val totalBytes = targets.sumOf { it.sizeBytes }
        if (!StorageUtil.hasEnoughSpace(totalBytes)) {
            return
        }

        val folderName = payloadFileName.value.substringBeforeLast('.').ifEmpty { "OTA_Dump" }
        val names = ArrayList(targets.map { it.name })
        val sizes = targets.map { it.sizeBytes }.toLongArray()

        val intent = Intent(context, DumpService::class.java).apply {
            action = DumpService.ACTION_START_OTA
            data = uri
            putExtra(DumpService.EXTRA_OTA_URI, uri.toString())
            putExtra(DumpService.EXTRA_OTA_FOLDER, folderName)
            putStringArrayListExtra(DumpService.EXTRA_OTA_NAMES, names)
            putExtra(DumpService.EXTRA_OTA_SIZES, sizes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            isHistoryLoading.value = true
            withContext(Dispatchers.IO) {
                val baseDir = StorageUtil.BASE_FOLDER_PATH

                // 1. Eksekusi via shell root untuk partisi fisik
                val dumpResults = mutableListOf<DumpHistoryItem>()
                val dumpLocations = listOf(
                    "${StorageUtil.BASE_FOLDER_PATH}/Dump",
                    StorageUtil.BASE_FOLDER_PATH,
                    StorageUtil.LEGACY_ROOT_PATH
                )

                if (Shell.isAppGrantedRoot() == true) {
                    for (dPath in dumpLocations) {
                        val out = Shell.cmd("ls -1 \"$dPath\"/*.img 2>/dev/null").exec().out
                        for (p in out) {
                            val fPath = p.trim()
                            if (!fPath.endsWith(".img")) continue
                            val fName = fPath.substringAfterLast('/')
                            if (fName.startsWith(".")) continue

                            val size = Shell.cmd("stat -c %s \"$fPath\" 2>/dev/null").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                            val time = Shell.cmd("stat -c %Y \"$fPath\" 2>/dev/null").exec().out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                            dumpResults.add(
                                DumpHistoryItem(
                                    name = fName,
                                    path = fPath,
                                    sizeBytes = size,
                                    lastModified = if (time > 0) time * 1000L else System.currentTimeMillis(),
                                    recordedHash = StorageUtil.findRecordedHash(fPath),
                                    isRootDump = true,
                                    contentUri = null
                                )
                            )
                        }
                    }
                } else {
                    for (dPath in dumpLocations) {
                        val dir = File(dPath)
                        if (dir.exists()) {
                            dir.listFiles { f -> f.isFile && f.extension == "img" && !f.name.startsWith(".") }?.forEach { f ->
                                dumpResults.add(
                                    DumpHistoryItem(
                                        name = f.name,
                                        path = f.absolutePath,
                                        sizeBytes = f.length(),
                                        lastModified = f.lastModified(),
                                        recordedHash = StorageUtil.findRecordedHash(f.absolutePath),
                                        isRootDump = true,
                                        contentUri = null
                                    )
                                )
                            }
                        }
                    }
                }
                dumpHistoryList.value = dumpResults.distinctBy { it.path }.sortedByDescending { it.lastModified }

                // 2. Ambil seluruh hasil OTA via MediaStore Downloads
                val otaResults = mutableListOf<DumpHistoryItem>()
                try {
                    val context = PartiApp.instance
                    val resolver = context.contentResolver
                    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    val projection = arrayOf(
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_MODIFIED,
                        MediaStore.Downloads.RELATIVE_PATH
                    )
                    val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
                    val selectionArgs = arrayOf("${StorageUtil.RELATIVE_BASE_DIR}/%", "%.img")

                    resolver.query(collection, projection, selection, selectionArgs, "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val fName = cursor.getString(nameCol) ?: continue
                            if (fName.startsWith(".")) continue

                            val size = cursor.getLong(sizeCol)
                            val dateSec = cursor.getLong(dateCol)
                            val relPath = cursor.getString(pathCol) ?: ""
                            val itemUri = ContentUris.withAppendedId(collection, id)

                            val subFolder = relPath.removePrefix("${StorageUtil.RELATIVE_BASE_DIR}/").removeSuffix("/")
                            if (subFolder == "Dump") continue

                            val displayName = if (subFolder.isNotEmpty()) "$subFolder/$fName" else fName
                            val recordedHash = StorageUtil.findOtaRecordedHash(context, subFolder, fName)

                            otaResults.add(
                                DumpHistoryItem(
                                    name = displayName,
                                    path = itemUri.toString(),
                                    sizeBytes = size,
                                    lastModified = dateSec * 1000L,
                                    recordedHash = recordedHash,
                                    isRootDump = false,
                                    contentUri = itemUri
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}

                otaHistoryList.value = otaResults.distinctBy { it.path }.sortedByDescending { it.lastModified }
            }
            isHistoryLoading.value = false
        }
    }

    fun verifyChecksum(item: DumpHistoryItem) {
        viewModelScope.launch {
            if (item.isRootDump) {
                dumpHistoryList.value = dumpHistoryList.value.map {
                    if (it.path == item.path) it.copy(isVerifying = true) else it
                }
            } else {
                otaHistoryList.value = otaHistoryList.value.map {
                    if (it.path == item.path) it.copy(isVerifying = true) else it
                }
            }

            var resolvedHash = item.recordedHash

            val result = withContext(Dispatchers.IO) {
                if (resolvedHash.isNullOrEmpty()) {
                    resolvedHash = if (item.isRootDump) {
                        StorageUtil.findRecordedHash(item.path)
                    } else {
                        val subFolder = item.name.substringBeforeLast('/', "")
                        val fName = item.name.substringAfterLast('/')
                        StorageUtil.findOtaRecordedHash(PartiApp.instance, subFolder, fName)
                    }
                }

                if (resolvedHash.isNullOrEmpty()) {
                    VerifyResult.NO_HASH_FILE
                } else {
                    val actualHash = try {
                        if (item.contentUri != null) {
                            PartiApp.instance.contentResolver.openInputStream(item.contentUri)?.use {
                                StorageUtil.calculateSha256(it)
                            } ?: ""
                        } else {
                            Shell.cmd("sha256sum \"${item.path}\" 2>/dev/null").exec().out.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
                        }
                    } catch (_: Exception) {
                        ""
                    }

                    if (actualHash.isNotEmpty() && actualHash.equals(resolvedHash, ignoreCase = true)) {
                        VerifyResult.MATCH
                    } else {
                        VerifyResult.MISMATCH
                    }
                }
            }

            if (item.isRootDump) {
                dumpHistoryList.value = dumpHistoryList.value.map {
                    if (it.path == item.path) it.copy(isVerifying = false, verifyResult = result, recordedHash = resolvedHash ?: it.recordedHash) else it
                }
            } else {
                otaHistoryList.value = otaHistoryList.value.map {
                    if (it.path == item.path) it.copy(isVerifying = false, verifyResult = result, recordedHash = resolvedHash ?: it.recordedHash) else it
                }
            }
        }
    }

    fun deleteHistoryDump(item: DumpHistoryItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (item.isRootDump || item.path.startsWith("/")) {
                    Shell.cmd("rm -f \"${item.path}\" \"${item.path}.sha256\" \"${item.path}.sha256.txt\"").exec()
                } else if (item.contentUri != null) {
                    val context = PartiApp.instance
                    context.contentResolver.delete(item.contentUri, null, null)

                    val subFolder = item.name.substringBeforeLast('/', "")
                    val fName = item.name.substringAfterLast('/')
                    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    val relativePath = if (subFolder.isNotEmpty()) "${StorageUtil.RELATIVE_BASE_DIR}/$subFolder/" else "${StorageUtil.RELATIVE_BASE_DIR}/"

                    val shaSelection = "(${MediaStore.Downloads.RELATIVE_PATH} LIKE ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?) AND (${MediaStore.Downloads.DISPLAY_NAME} = ? OR ${MediaStore.Downloads.DISPLAY_NAME} = ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?)"
                    val shaArgs = arrayOf(
                        "$relativePath%",
                        relativePath,
                        "$fName.sha256",
                        "$fName.sha256.txt",
                        "$fName%.sha256%"
                    )
                    context.contentResolver.query(collection, arrayOf(MediaStore.Downloads._ID), shaSelection, shaArgs, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                            context.contentResolver.delete(ContentUris.withAppendedId(collection, id), null, null)
                        }
                    }
                }
            } catch (_: Exception) {}
            loadHistory()
        }
    }

    fun requestDump(context: Context, partition: PartitionInfo) {
        if (!StorageUtil.hasEnoughSpace(partition.sizeBytes)) {
            pendingDumpPartition.value = partition
            showStorageWarning.value = true
        } else {
            startDumpService(context, partition)
        }
    }

    fun proceedDumpAnyway(context: Context) {
        val part = pendingDumpPartition.value ?: return
        showStorageWarning.value = false
        pendingDumpPartition.value = null
        startDumpService(context, part)
    }

    fun dismissStorageWarning() {
        showStorageWarning.value = false
        pendingDumpPartition.value = null
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

    fun requestBatchDumpCritical(context: Context) {
        val criticals = allPartitions.value.filter {
            it.isCritical && (it.slot.isEmpty() || it.slot == activeSlot.value)
        }
        if (criticals.isEmpty()) return

        val names = ArrayList(criticals.map { it.name })
        val paths = ArrayList(criticals.map { it.blockPath })

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

    fun resetDumpStatus() {
        DumpService.resetState()
        loadHistory()
    }
}
