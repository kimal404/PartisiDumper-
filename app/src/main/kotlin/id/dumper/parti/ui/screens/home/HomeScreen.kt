package id.dumper.parti.ui.screens.home

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dumper.parti.data.model.DumpHistoryItem
import id.dumper.parti.data.model.DumpState
import id.dumper.parti.data.model.RootState
import id.dumper.parti.data.model.VerifyResult
import id.dumper.parti.ui.components.PartitionCard
import id.dumper.parti.ui.theme.AccentCyan
import id.dumper.parti.ui.theme.SafeGreen
import id.dumper.parti.util.StorageUtil
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(viewModel: HomeViewModel = remember { HomeViewModel() }) {
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
    val deviceInfo by viewModel.deviceInfo.collectAsState()

    val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()
    val historyList by viewModel.historyList.collectAsState()
    val isHistoryLoading by viewModel.isHistoryLoading.collectAsState()

    val criticalCount = remember(allPartitions, activeSlot) {
        allPartitions.count { it.isCritical && (it.slot.isEmpty() || it.slot == activeSlot) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (val state = rootState) {
            is RootState.Checking -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Memeriksa akses root...", color = Color.Gray)
                    }
                }
            }
            is RootState.Denied -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Akses Root Tidak Ditemukan",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { viewModel.checkRootAndScan() },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Coba Lagi")
                            }
                        }
                    }
                }
            }
            is RootState.Granted -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp)
                        .padding(top = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "PartisiDumper",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (activeSlot.isEmpty()) "NON A/B" else "SLOT " + activeSlot.uppercase(),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${deviceInfo.device} • ${deviceInfo.platform} • Android ${deviceInfo.androidVersion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }

                        FilledTonalIconButton(
                            onClick = { if (selectedTabIndex == 0) viewModel.scan() else viewModel.loadHistory() },
                            modifier = Modifier.size(38.dp),
                            shape = CircleShape
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Rescan",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = Color.Transparent,
                        divider = {}
                    ) {
                        Tab(
                            selected = selectedTabIndex == 0,
                            onClick = { viewModel.selectedTabIndex.value = 0 },
                            text = { Text("Partisi (${partitions.size})", fontWeight = FontWeight.SemiBold) }
                        )
                        Tab(
                            selected = selectedTabIndex == 1,
                            onClick = {
                                viewModel.selectedTabIndex.value = 1
                                viewModel.loadHistory()
                            },
                            text = { Text("Riwayat (${historyList.size})", fontWeight = FontWeight.SemiBold) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (selectedTabIndex == 0) {
                        Button(
                            onClick = { viewModel.requestBatchDumpCritical(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Backup Semua Partisi Kritis ($criticalCount)", fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.searchQuery.value = it },
                            placeholder = { Text("Cari partisi...", fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = filterOnlyCritical,
                                onClick = { viewModel.filterOnlyCritical.value = !filterOnlyCritical },
                                label = { Text("Hanya Partisi Kritis", fontSize = 12.sp) },
                                shape = RoundedCornerShape(10.dp)
                            )
                            Text(
                                "Sisa: ${StorageUtil.formatBytes(StorageUtil.getAvailableInternalStorageBytes())}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (isScanning) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (partitions.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Partisi tidak ditemukan", color = Color.Gray)
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .navigationBarsPadding()
                            ) {
                                items(partitions, key = { it.blockPath + it.name }) { part ->
                                    PartitionCard(
                                        partition = part,
                                        onDumpClick = { viewModel.requestDump(context, part) }
                                    )
                                }
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { StorageUtil.openDumpsFolder(context) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buka Folder di File Manager", fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (isHistoryLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (historyList.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Belum ada file backup di /Download/PartitionDumps", color = Color.Gray)
                            }
                        } else {
                            val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .navigationBarsPadding()
                            ) {
                                items(historyList, key = { it.path }) { item ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(item.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                                    Text(
                                                        "${StorageUtil.formatBytes(item.sizeBytes)} • ${dateFormat.format(Date(item.lastModified))}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.Gray
                                                    )
                                                }
                                                IconButton(onClick = { viewModel.deleteHistoryDump(item) }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }

                                            if (!item.recordedHash.isNullOrEmpty()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    "SHA256: ${item.recordedHash.take(16)}...",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = Color.Gray
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                when (item.verifyResult) {
                                                    VerifyResult.NOT_CHECKED -> {
                                                        Text("Belum diverifikasi", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                                    }
                                                    VerifyResult.MATCH -> {
                                                        Text("Integritas Valid (Cocok)", style = MaterialTheme.typography.labelSmall, color = SafeGreen, fontWeight = FontWeight.Bold)
                                                    }
                                                    VerifyResult.MISMATCH -> {
                                                        Text("KORUP / Tidak Cocok!", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                                    }
                                                    VerifyResult.NO_HASH_FILE -> {
                                                        Text("File .sha256 tidak ada", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                                    }
                                                }

                                                FilledTonalButton(
                                                    onClick = { viewModel.verifyChecksum(item) },
                                                    enabled = !item.isVerifying,
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                ) {
                                                    if (item.isVerifying) {
                                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                                    } else {
                                                        Text("Verifikasi SHA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
        }
    }

    if (showStorageWarning && pendingPartition != null) {
        val part = pendingPartition!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissStorageWarning() },
            shape = RoundedCornerShape(22.dp),
            title = { Text("Peringatan Kapasitas Storage") },
            text = {
                Text(
                    "Ukuran partisi ${part.name} adalah ${StorageUtil.formatBytes(part.sizeBytes)}, sedangkan sisa memori internal Anda sangat menipis (${StorageUtil.formatBytes(StorageUtil.getAvailableInternalStorageBytes())}). Proses dump mungkin gagal."
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.proceedDumpAnyway(context) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Tetap Dump")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissStorageWarning() }) {
                    Text("Batal")
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
                shape = RoundedCornerShape(24.dp),
                title = { Text("Mengekstrak ${dump.partitionName} (${dump.current}/${dump.total})", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isVerifying || !hasTotalBytes) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = progressFloat,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = dump.stage,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (hasTotalBytes && !isVerifying) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${StorageUtil.formatBytes(dump.bytesCopied)} / ${StorageUtil.formatBytes(dump.totalBytes)} (${(progressFloat * 100).toInt()}%)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "CPU dikunci (WakeLock) agar proses aman di background.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                },
                confirmButton = {}
            )
        }
        is DumpState.Success -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetDumpStatus() },
                shape = RoundedCornerShape(24.dp),
                icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(32.dp)) },
                title = { Text("Dump Berhasil", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Partisi: ${dump.partitionName}", fontWeight = FontWeight.Bold)
                        Text("File: ${dump.outputPath}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text("SHA256: ${dump.sha256}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(dump.sha256))
                            viewModel.resetDumpStatus()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Salin SHA256")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        StorageUtil.openDumpsFolder(context)
                        viewModel.resetDumpStatus()
                    }) {
                        Text("Buka Folder")
                    }
                }
            )
        }
        is DumpState.BatchSuccess -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetDumpStatus() },
                shape = RoundedCornerShape(24.dp),
                icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(32.dp)) },
                title = { Text("Batch Dump Selesai", fontWeight = FontWeight.Bold) },
                text = {
                    Text("Berhasil mengekstrak ${dump.totalCount} partisi kritis ke ${dump.outputDir}")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            StorageUtil.openDumpsFolder(context)
                            viewModel.resetDumpStatus()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Buka di File Manager")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resetDumpStatus() }) {
                        Text("Tutup")
                    }
                }
            )
        }
        is DumpState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetDumpStatus() },
                shape = RoundedCornerShape(24.dp),
                icon = { Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp)) },
                title = { Text("Dump Gagal", fontWeight = FontWeight.Bold) },
                text = { Text(dump.message) },
                confirmButton = {
                    Button(
                        onClick = { viewModel.resetDumpStatus() },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Tutup")
                    }
                }
            )
        }
        is DumpState.Idle -> {}
    }
}
