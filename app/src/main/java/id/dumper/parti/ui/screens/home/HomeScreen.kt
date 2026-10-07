package id.dumper.parti.ui.screens.home

import android.content.Intent
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dumper.parti.data.model.DumpState
import id.dumper.parti.data.model.RootState
import id.dumper.parti.data.model.VerifyResult
import id.dumper.parti.service.DumpService
import id.dumper.parti.ui.components.PartitionCard
import id.dumper.parti.ui.theme.*
import id.dumper.parti.util.StorageUtil
import java.text.SimpleDateFormat
import java.util.*

private fun formatEta(seconds: Long): String {
    return if (seconds < 0) "--:--" else String.format("%02d:%02d", seconds / 60, seconds % 60)
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = remember { HomeViewModel() },
    onNavigateToAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val rootState by viewModel.rootState.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val partitions by viewModel.filteredPartitions.collectAsState()
    val allPartitions by viewModel.allPartitions.collectAsState()
    val activeSlot by viewModel.activeSlot.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterOnlyCritical by viewModel.filterOnlyCritical.collectAsState()
    val dumpState by viewModel.dumpState.collectAsState()
    val showStorageWarning by viewModel.showStorageWarning.collectAsState()
    val pendingPartition by viewModel.pendingDumpPartition.collectAsState()

    val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()
    val historyList by viewModel.historyList.collectAsState()
    val isHistoryLoading by viewModel.isHistoryLoading.collectAsState()

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }

    val criticalCount = remember(allPartitions, activeSlot) {
        allPartitions.count { it.isCritical && (it.slot.isEmpty() || it.slot == activeSlot) }
    }

    val selectedPartitions = remember(partitions, selectedKeys) {
        partitions.filter { selectedKeys.contains(it.blockPath + it.name) }
    }
    val totalSelectedBytes = remember(selectedPartitions) {
        selectedPartitions.sumOf { it.sizeBytes }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBg
    ) {
        when (val state = rootState) {
            is RootState.Checking -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentPrimary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Memeriksa akses root...", color = TextSecondary)
                    }
                }
            }
            is RootState.Denied -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = CriticalRed, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("Akses Root Ditolak", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(state.message, color = TextSecondary, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { viewModel.checkRootAndScan() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = AccentOnPrimary)
                            ) {
                                Text("Coba Lagi", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            is RootState.Granted -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            DarkBg,
                                            DarkBg.copy(alpha = 0.95f),
                                            DarkBg.copy(alpha = 0.70f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .padding(horizontal = 18.dp)
                                .padding(top = 10.dp, bottom = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PartisiDumper",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 24.sp,
                                    color = AccentPrimary
                                )

                                Surface(
                                    shape = CircleShape,
                                    color = DarkSurface,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    IconButton(onClick = onNavigateToAbout) {
                                        Icon(
                                            Icons.Default.Settings,
                                            contentDescription = "Tentang / Pengaturan",
                                            tint = TextPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            TabRow(
                                selectedTabIndex = selectedTabIndex,
                                containerColor = Color.Transparent,
                                contentColor = TextPrimary,
                                divider = {}
                            ) {
                                Tab(
                                    selected = selectedTabIndex == 0,
                                    onClick = { viewModel.selectedTabIndex.value = 0 },
                                    selectedContentColor = TextPrimary,
                                    unselectedContentColor = TextSecondary,
                                    text = { Text("Partisi (${partitions.size})", fontWeight = FontWeight.SemiBold) }
                                )
                                Tab(
                                    selected = selectedTabIndex == 1,
                                    onClick = {
                                        viewModel.selectedTabIndex.value = 1
                                        viewModel.loadHistory()
                                    },
                                    selectedContentColor = TextPrimary,
                                    unselectedContentColor = TextSecondary,
                                    text = { Text("Riwayat (${historyList.size})", fontWeight = FontWeight.SemiBold) }
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (selectedTabIndex == 0) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { viewModel.searchQuery.value = it },
                                    placeholder = { Text("Cari partisi atau block...", color = TextSecondary, fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                                                Icon(Icons.Default.Clear, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = DarkSurface,
                                        unfocusedContainerColor = DarkSurface,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedBorderColor = AccentPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        FilterChip(
                                            selected = !filterOnlyCritical,
                                            onClick = { viewModel.filterOnlyCritical.value = false },
                                            label = { Text("Semua") },
                                            shape = RoundedCornerShape(12.dp),
                                            border = null,
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = AccentPrimary,
                                                selectedLabelColor = AccentOnPrimary,
                                                containerColor = DarkSurface,
                                                labelColor = TextSecondary
                                            )
                                        )

                                        FilterChip(
                                            selected = filterOnlyCritical,
                                            onClick = { viewModel.filterOnlyCritical.value = true },
                                            label = { Text("Kritis ($criticalCount)") },
                                            shape = RoundedCornerShape(12.dp),
                                            border = null,
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = AccentPrimary,
                                                selectedLabelColor = AccentOnPrimary,
                                                containerColor = DarkSurface,
                                                labelColor = TextSecondary
                                            )
                                        )

                                        FilterChip(
                                            selected = isSelectionMode,
                                            onClick = {
                                                isSelectionMode = !isSelectionMode
                                                if (!isSelectionMode) selectedKeys = emptySet()
                                            },
                                            label = { Text(if (isSelectionMode) "Mode Pilih" else "Multi-Pilih") },
                                            shape = RoundedCornerShape(12.dp),
                                            border = null,
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = AccentPrimary,
                                                selectedLabelColor = AccentOnPrimary,
                                                containerColor = DarkSurface,
                                                labelColor = TextSecondary
                                            )
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    Surface(
                                        shape = CircleShape,
                                        color = DarkSurface,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        IconButton(
                                            onClick = { if (selectedTabIndex == 0) viewModel.scan() else viewModel.loadHistory() },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = "Sinkronisasi",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (isScanning) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = AccentPrimary)
                                    }
                                } else if (partitions.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Partisi tidak ditemukan", color = TextSecondary)
                                    }
                                } else {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(partitions, key = { it.blockPath + it.name }) { part ->
                                            val key = part.blockPath + part.name
                                            val isChecked = selectedKeys.contains(key)

                                            PartitionCard(
                                                partition = part,
                                                isSelected = isChecked,
                                                isSelectionMode = isSelectionMode,
                                                onDumpClick = { viewModel.requestDump(context, part) },
                                                onCardClick = {
                                                    if (isSelectionMode) {
                                                        selectedKeys = if (isChecked) selectedKeys - key else selectedKeys + key
                                                    }
                                                },
                                                onCardLongClick = {
                                                    isSelectionMode = true
                                                    selectedKeys = if (isChecked) selectedKeys - key else selectedKeys + key
                                                },
                                                onToggleSelect = {
                                                    selectedKeys = if (isChecked) selectedKeys - key else selectedKeys + key
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { StorageUtil.openDumpsFolder(context) },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = DarkSurface,
                                        contentColor = TextPrimary
                                    ),
                                    border = null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Buka Folder Penyimpanan", fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                if (isHistoryLoading) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = AccentPrimary)
                                    }
                                } else if (historyList.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Belum ada file backup", color = TextSecondary)
                                    }
                                } else {
                                    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(bottom = 24.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(historyList, key = { it.path }) { item ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(18.dp),
                                                colors = CardDefaults.cardColors(containerColor = DarkSurface)
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                                                            Text(
                                                                "${StorageUtil.formatBytes(item.sizeBytes)} • ${dateFormat.format(Date(item.lastModified))}",
                                                                fontSize = 12.sp,
                                                                color = TextSecondary
                                                            )
                                                        }
                                                        IconButton(onClick = { viewModel.deleteHistoryDump(item) }) {
                                                            Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = CriticalRed)
                                                        }
                                                    }

                                                    if (!item.recordedHash.isNullOrEmpty()) {
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(
                                                            "SHA256: ${item.recordedHash.take(16)}...",
                                                            fontSize = 11.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = TextSecondary
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.height(8.dp))

                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        when (item.verifyResult) {
                                                            VerifyResult.NOT_CHECKED -> Text("Belum diverifikasi", fontSize = 11.sp, color = TextSecondary)
                                                            VerifyResult.MATCH -> Text("Valid (Cocok)", fontSize = 11.sp, color = SafeGreen, fontWeight = FontWeight.Bold)
                                                            VerifyResult.MISMATCH -> Text("KORUP!", fontSize = 11.sp, color = CriticalRed, fontWeight = FontWeight.Bold)
                                                            VerifyResult.NO_HASH_FILE -> Text("File hash hilang", fontSize = 11.sp, color = TextSecondary)
                                                        }

                                                        FilledTonalButton(
                                                            onClick = { viewModel.verifyChecksum(item) },
                                                            enabled = !item.isVerifying,
                                                            shape = RoundedCornerShape(10.dp),
                                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                                containerColor = DarkSurfaceVariant,
                                                                contentColor = TextPrimary
                                                            )
                                                        ) {
                                                            if (item.isVerifying) {
                                                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentPrimary)
                                                            } else {
                                                                Text("Verifikasi", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (selectedTabIndex == 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 16.dp)
                        ) {
                            AnimatedContent(
                                targetState = selectedKeys.isNotEmpty(),
                                transitionSpec = {
                                    slideInVertically { it } + fadeIn() togetherWith
                                    slideOutVertically { it } + fadeOut()
                                }
                            ) { hasSelection ->
                                if (hasSelection) {
                                    ExtendedFloatingActionButton(
                                        onClick = {
                                            val names = ArrayList(selectedPartitions.map { it.name })
                                            val paths = ArrayList(selectedPartitions.map { it.blockPath })
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
                                            selectedKeys = emptySet()
                                            isSelectionMode = false
                                        },
                                        shape = RoundedCornerShape(20.dp),
                                        containerColor = AccentPrimary,
                                        contentColor = AccentOnPrimary,
                                        icon = { Icon(Icons.Default.Download, contentDescription = null) },
                                        text = { Text("Dump ${selectedKeys.size} Partisi (${StorageUtil.formatBytes(totalSelectedBytes)})", fontWeight = FontWeight.Bold) }
                                    )
                                } else {
                                    ExtendedFloatingActionButton(
                                        onClick = { viewModel.requestBatchDumpCritical(context) },
                                        shape = RoundedCornerShape(20.dp),
                                        containerColor = AccentPrimary,
                                        contentColor = AccentOnPrimary,
                                        icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                                        text = { Text("Backup Kritis ($criticalCount)", fontWeight = FontWeight.Bold) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showStorageWarning && pendingPartition != null) {
            val part = pendingPartition!!
            AlertDialog(
                onDismissRequest = { viewModel.dismissStorageWarning() },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Peringatan Storage", color = TextPrimary) },
                text = {
                    Text("Ukuran partisi ${part.name} adalah ${StorageUtil.formatBytes(part.sizeBytes)}, sisa memori internal Anda sangat menipis.", color = TextSecondary)
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.proceedDumpAnyway(context) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CriticalRed, contentColor = CriticalBg)
                    ) {
                        Text("Tetap Dump", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissStorageWarning() }) {
                        Text("Batal", color = TextSecondary)
                    }
                }
            )
        }

        when (val dump = dumpState) {
            is DumpState.Dumping -> {
                val hasTotalBytes = dump.totalBytes > 0L
                val isVerifying = dump.stage.startsWith("Menghitung") || dump.stage.startsWith("Verifikasi")
                val progressFloat = if (hasTotalBytes && !isVerifying) {
                    (dump.bytesCopied.toFloat() / dump.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }

                AlertDialog(
                    onDismissRequest = {},
                    containerColor = DarkSurface,
                    shape = RoundedCornerShape(24.dp),
                    title = { Text("Mengekstrak ${dump.partitionName} (${dump.current}/${dump.total})", fontWeight = FontWeight.Bold, color = TextPrimary) },
                    text = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isVerifying || !hasTotalBytes) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), color = AccentPrimary)
                            } else {
                                LinearProgressIndicator(progress = progressFloat, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), color = AccentPrimary)
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = dump.stage,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentPrimary
                            )

                            if (hasTotalBytes && !isVerifying) {
                                Spacer(modifier = Modifier.height(4.dp))
                                val percent = (progressFloat * 100).toInt()
                                val speedText = if (dump.speedBytesPerSec > 0) "${StorageUtil.formatBytes(dump.speedBytesPerSec)}/s" else "-- MB/s"
                                val etaText = if (dump.etaSeconds >= 0) "Sisa: ${formatEta(dump.etaSeconds)}" else "Menghitung sisa..."

                                Text(
                                    text = "${StorageUtil.formatBytes(dump.bytesCopied)} / ${StorageUtil.formatBytes(dump.totalBytes)} ($percent%)",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "$speedText • $etaText",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentPrimary
                                )
                            }
                        }
                    },
                    confirmButton = {}
                )
            }
            is DumpState.Success -> {
                AlertDialog(
                    onDismissRequest = { viewModel.resetDumpStatus() },
                    containerColor = DarkSurface,
                    shape = RoundedCornerShape(24.dp),
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(32.dp)) },
                    title = { Text("Dump Berhasil", fontWeight = FontWeight.Bold, color = TextPrimary) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Partisi: ${dump.partitionName}", fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("File: ${dump.outputPath}", fontSize = 12.sp, color = TextSecondary)
                            Text("SHA256: ${dump.sha256}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentPrimary)
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(dump.sha256))
                                viewModel.resetDumpStatus()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = AccentOnPrimary)
                        ) {
                            Text("Salin SHA256", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            StorageUtil.openDumpsFolder(context)
                            viewModel.resetDumpStatus()
                        }) {
                            Text("Buka Folder", color = TextSecondary)
                        }
                    }
                )
            }
            is DumpState.BatchSummary -> {
                AlertDialog(
                    onDismissRequest = { viewModel.resetDumpStatus() },
                    containerColor = DarkSurface,
                    shape = RoundedCornerShape(24.dp),
                    icon = {
                        Icon(
                            if (dump.failed.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (dump.failed.isEmpty()) SafeGreen else CriticalRed,
                            modifier = Modifier.size(32.dp)
                        )
                    },
                    title = { Text("Ringkasan Batch Dump", fontWeight = FontWeight.Bold, color = TextPrimary) },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Berhasil: ${dump.success.size} • Gagal: ${dump.failed.size}",
                                fontWeight = FontWeight.Bold,
                                color = if (dump.failed.isEmpty()) SafeGreen else CriticalRed
                            )
                            if (dump.failed.isNotEmpty()) {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = CriticalBg),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        dump.failed.forEach { pair ->
                                            Text(
                                                text = "• ${pair.first}: ${pair.second}",
                                                fontSize = 12.sp,
                                                color = CriticalRed
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                StorageUtil.openDumpsFolder(context)
                                viewModel.resetDumpStatus()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = AccentOnPrimary)
                        ) {
                            Text("Buka Folder", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.resetDumpStatus() }) {
                            Text("Tutup", color = TextSecondary)
                        }
                    }
                )
            }
            is DumpState.Error -> {
                var showDetailLog by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = { viewModel.resetDumpStatus() },
                    containerColor = DarkSurface,
                    shape = RoundedCornerShape(24.dp),
                    icon = { Icon(Icons.Default.Error, contentDescription = null, tint = CriticalRed, modifier = Modifier.size(32.dp)) },
                    title = { Text("Dump Gagal", fontWeight = FontWeight.Bold, color = TextPrimary) },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(dump.message, color = TextPrimary, fontSize = 13.sp)

                            if (dump.technicalLog.isNotEmpty()) {
                                TextButton(
                                    onClick = { showDetailLog = !showDetailLog },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (showDetailLog) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = AccentPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            if (showDetailLog) "Tutup Log" else "Lihat Log Diagnostik",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentPrimary
                                        )
                                    }
                                }

                                if (showDetailLog) {
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = dump.technicalLog,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Row {
                            if (dump.technicalLog.isNotEmpty()) {
                                TextButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(dump.technicalLog)) }
                                ) {
                                    Text("Salin Log", color = AccentPrimary)
                                }
                            }
                            Button(
                                onClick = { viewModel.resetDumpStatus() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = AccentOnPrimary)
                            ) {
                                Text("Tutup", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                )
            }
            is DumpState.Idle -> {}
        }
    }
}
