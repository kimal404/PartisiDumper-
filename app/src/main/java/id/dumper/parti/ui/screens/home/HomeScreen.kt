package id.dumper.parti.ui.screens.home

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dumper.parti.R
import id.dumper.parti.data.model.DumpState
import id.dumper.parti.data.model.ExtractionStatus
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
    val otaPartitionStatus by viewModel.otaPartitionStatus.collectAsState()
    val showStorageWarning by viewModel.showStorageWarning.collectAsState()
    val pendingPartition by viewModel.pendingDumpPartition.collectAsState()

    val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()
    val historyCategory by viewModel.historyCategory.collectAsState()
    val currentHistoryList by viewModel.currentHistoryList.collectAsState()
    val dumpHistoryList by viewModel.dumpHistoryList.collectAsState()
    val otaHistoryList by viewModel.otaHistoryList.collectAsState()
    val totalHistoryCount by viewModel.totalHistoryCount.collectAsState()
    val isHistoryLoading by viewModel.isHistoryLoading.collectAsState()

    val selectedPayloadUri by viewModel.selectedPayloadUri.collectAsState()
    val payloadFileName by viewModel.payloadFileName.collectAsState()
    val payloadFileSize by viewModel.payloadFileSize.collectAsState()
    val payloadPartitions by viewModel.filteredPayloadPartitions.collectAsState()
    val rawPayloadPartitions by viewModel.payloadPartitions.collectAsState()
    val payloadSearchQuery by viewModel.payloadSearchQuery.collectAsState()
    val isParsingPayload by viewModel.isParsingPayload.collectAsState()
    val payloadError by viewModel.payloadError.collectAsState()

    var isDumpingDialogMinimized by remember { mutableStateOf(false) }

    LaunchedEffect(dumpState) {
        if (dumpState is DumpState.Success || dumpState is DumpState.BatchSummary || dumpState is DumpState.Error) {
            isDumpingDialogMinimized = false
        }
    }

    val selectedPayloadCount = remember(rawPayloadPartitions) {
        rawPayloadPartitions.count { it.isSelected }
    }
    val selectedPayloadTotalBytes = remember(rawPayloadPartitions) {
        rawPayloadPartitions.filter { it.isSelected }.sumOf { it.sizeBytes }
    }

    val payloadFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onPayloadFileSelected(context, it) }
    }

    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedKeys.isNotEmpty()

    val isAllPartitionsSelected = remember(partitions, selectedKeys) {
        partitions.isNotEmpty() && partitions.all { selectedKeys.contains(it.blockPath + it.name) }
    }

    val isRootGranted = rootState is RootState.Granted

    val criticalCount = remember(allPartitions, activeSlot) {
        allPartitions.count { it.isCritical && (it.slot.isEmpty() || it.slot == activeSlot) }
    }

    val selectedPartitions = remember(partitions, selectedKeys) {
        partitions.filter { selectedKeys.contains(it.blockPath + it.name) }
    }
    val totalSelectedBytes = remember(selectedPartitions) {
        selectedPartitions.sumOf { it.sizeBytes }
    }

    Scaffold(
        containerColor = DarkBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { viewModel.selectedTabIndex.value = 0 },
                    icon = { Icon(Icons.Default.Layers, contentDescription = null) },
                    label = {
                        Text(
                            text = stringResource(R.string.tab_partitions, partitions.size),
                            fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentOnPrimary,
                        selectedTextColor = AccentPrimary,
                        indicatorColor = AccentPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { viewModel.selectedTabIndex.value = 1 },
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                    label = {
                        Text(
                            text = stringResource(R.string.tab_payload),
                            fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentOnPrimary,
                        selectedTextColor = AccentPrimary,
                        indicatorColor = AccentPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 2,
                    onClick = {
                        viewModel.selectedTabIndex.value = 2
                        viewModel.loadHistory()
                    },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = {
                        Text(
                            text = stringResource(R.string.tab_history, totalHistoryCount),
                            fontWeight = if (selectedTabIndex == 2) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentOnPrimary,
                        selectedTextColor = AccentPrimary,
                        indicatorColor = AccentPrimary,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
            }
        },
        floatingActionButton = {
            if (selectedTabIndex == 0 && isRootGranted) {
                AnimatedContent(
                    targetState = selectedKeys.isNotEmpty(),
                    transitionSpec = {
                        slideInVertically { it } + fadeIn() togetherWith
                        slideOutVertically { it } + fadeOut()
                    },
                    label = "FabTransition"
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
                            },
                            shape = RoundedCornerShape(26.dp),
                            containerColor = AccentPrimary,
                            contentColor = AccentOnPrimary,
                            icon = { Icon(Icons.Default.Download, contentDescription = null) },
                            text = {
                                Text(
                                    stringResource(R.string.btn_dump_selected, selectedKeys.size, StorageUtil.formatSize(totalSelectedBytes)),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                    } else {
                        ExtendedFloatingActionButton(
                            onClick = { viewModel.requestBatchDumpCritical(context) },
                            shape = RoundedCornerShape(26.dp),
                            containerColor = AccentPrimary,
                            contentColor = AccentOnPrimary,
                            icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                            text = { Text(stringResource(R.string.btn_backup_critical, criticalCount), fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            } else if (selectedTabIndex == 1 && selectedPayloadCount > 0 && dumpState !is DumpState.Dumping) {
                val extractBytesText = if (selectedPayloadTotalBytes <= 0L) stringResource(R.string.unknown_size) else StorageUtil.formatSize(selectedPayloadTotalBytes)
                ExtendedFloatingActionButton(
                    onClick = { viewModel.extractSelectedPayloadPartitions(context) },
                    shape = RoundedCornerShape(26.dp),
                    containerColor = AccentPrimary,
                    contentColor = AccentOnPrimary,
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    text = {
                        Text(
                            stringResource(R.string.payload_btn_extract, selectedPayloadCount, extractBytesText),
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = innerPadding.calculateBottomPadding())
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
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Black,
                            fontSize = 26.sp,
                            color = AccentPrimary,
                            letterSpacing = (-0.5).sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (activeSlot.isEmpty()) {
                                stringResource(R.string.slot_non_ab)
                            } else {
                                stringResource(R.string.slot_active, activeSlot.uppercase())
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = DarkSurface,
                        modifier = Modifier.size(44.dp)
                    ) {
                        IconButton(onClick = onNavigateToAbout) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.about_title),
                                tint = TextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                when (selectedTabIndex) {
                    0 -> {
                        when {
                            rootState is RootState.Checking -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = AccentPrimary)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(stringResource(R.string.root_checking), color = TextSecondary)
                                    }
                                }
                            }
                            !isRootGranted -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Card(
                                        shape = RoundedCornerShape(28.dp),
                                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = DarkSurfaceVariant,
                                                modifier = Modifier.size(68.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Icons.Default.Lock,
                                                        contentDescription = null,
                                                        tint = AccentPrimary,
                                                        modifier = Modifier.size(34.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(18.dp))

                                            Text(
                                                text = stringResource(R.string.root_denied_title),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                color = TextPrimary
                                            )

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Text(
                                                text = stringResource(R.string.root_locked_desc),
                                                color = TextSecondary,
                                                fontSize = 13.sp,
                                                textAlign = TextAlign.Center,
                                                lineHeight = 18.sp
                                            )

                                            Spacer(modifier = Modifier.height(22.dp))

                                            Button(
                                                onClick = { viewModel.checkRootAndScan() },
                                                shape = RoundedCornerShape(18.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = AccentPrimary,
                                                    contentColor = AccentOnPrimary
                                                ),
                                                modifier = Modifier.fillMaxWidth().height(48.dp)
                                            ) {
                                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(stringResource(R.string.btn_grant_root), fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { viewModel.searchQuery.value = it },
                                        placeholder = { Text(stringResource(R.string.search_placeholder), color = TextSecondary, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                                                    Icon(Icons.Default.Clear, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(22.dp),
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
                                                label = { Text(stringResource(R.string.filter_all)) },
                                                shape = RoundedCornerShape(14.dp),
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
                                                label = { Text(stringResource(R.string.filter_critical, criticalCount)) },
                                                shape = RoundedCornerShape(14.dp),
                                                border = null,
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = AccentPrimary,
                                                    selectedLabelColor = AccentOnPrimary,
                                                    containerColor = DarkSurface,
                                                    labelColor = TextSecondary
                                                )
                                            )

                                            FilterChip(
                                                selected = isAllPartitionsSelected || selectedKeys.isNotEmpty(),
                                                onClick = {
                                                    if (isAllPartitionsSelected) {
                                                        selectedKeys = emptySet()
                                                    } else {
                                                        selectedKeys = partitions.map { it.blockPath + it.name }.toSet()
                                                    }
                                                },
                                                label = {
                                                    Text(
                                                        if (isAllPartitionsSelected) stringResource(R.string.payload_deselect_all)
                                                        else stringResource(R.string.payload_select_all)
                                                    )
                                                },
                                                shape = RoundedCornerShape(14.dp),
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
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            IconButton(
                                                onClick = { viewModel.scan() },
                                                modifier = Modifier.size(38.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Refresh,
                                                    contentDescription = stringResource(R.string.action_sync),
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
                                            Text(stringResource(R.string.partitions_not_found), color = TextSecondary)
                                        }
                                    } else {
                                        LazyColumn(
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                                        selectedKeys = if (isChecked) selectedKeys - key else selectedKeys + key
                                                    },
                                                    onToggleSelect = {
                                                        selectedKeys = if (isChecked) selectedKeys - key else selectedKeys + key
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        if (selectedPayloadUri == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    shape = RoundedCornerShape(28.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = DarkSurfaceVariant,
                                            modifier = Modifier.size(72.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.CloudDownload,
                                                    contentDescription = null,
                                                    tint = AccentPrimary,
                                                    modifier = Modifier.size(36.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(18.dp))

                                        Text(
                                            text = stringResource(R.string.payload_empty_title),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = TextPrimary
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = stringResource(R.string.payload_empty_desc),
                                            color = TextSecondary,
                                            fontSize = 13.sp,
                                            textAlign = TextAlign.Center,
                                            lineHeight = 18.sp
                                        )

                                        Spacer(modifier = Modifier.height(22.dp))

                                        Button(
                                            onClick = { payloadFilePickerLauncher.launch(arrayOf("*/*")) },
                                            shape = RoundedCornerShape(18.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = AccentPrimary,
                                                contentColor = AccentOnPrimary
                                            ),
                                            modifier = Modifier.fillMaxWidth().height(48.dp)
                                        ) {
                                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(stringResource(R.string.payload_btn_choose), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else if (isParsingPayload) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = AccentPrimary)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(stringResource(R.string.payload_parsing), color = TextSecondary)
                                }
                            }
                        } else if (payloadError != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    shape = RoundedCornerShape(28.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.Error, contentDescription = null, tint = CriticalRed, modifier = Modifier.size(48.dp))
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Text(stringResource(R.string.payload_error_title), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(payloadError ?: "", color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                                        Spacer(modifier = Modifier.height(20.dp))
                                        Button(
                                            onClick = { payloadFilePickerLauncher.launch(arrayOf("*/*")) },
                                            shape = RoundedCornerShape(16.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = AccentOnPrimary)
                                        ) {
                                            Text(stringResource(R.string.payload_change_file), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Card(
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(26.dp))
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(payloadFileName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                                val summarySizeText = if (payloadFileSize > 0L) StorageUtil.formatSize(payloadFileSize) else stringResource(R.string.unknown_size)
                                                Text(
                                                    "$summarySizeText • ${rawPayloadPartitions.size} Partisi",
                                                    fontSize = 12.sp,
                                                    color = TextSecondary
                                                )
                                            }
                                        }
                                        IconButton(onClick = { payloadFilePickerLauncher.launch(arrayOf("*/*")) }) {
                                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_change), tint = TextSecondary)
                                        }
                                        IconButton(onClick = { viewModel.clearPayload() }) {
                                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.btn_close), tint = TextSecondary)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = payloadSearchQuery,
                                    onValueChange = { viewModel.payloadSearchQuery.value = it },
                                    placeholder = { Text(stringResource(R.string.payload_search_placeholder), color = TextSecondary, fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(22.dp),
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
                                    Text(stringResource(R.string.payload_partitions_found, payloadPartitions.size), fontSize = 12.sp, color = TextSecondary)

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { viewModel.selectAllPayloadPartitions(true) }) {
                                            Text(stringResource(R.string.payload_select_all), fontSize = 12.sp, color = AccentPrimary)
                                        }
                                        TextButton(onClick = { viewModel.selectAllPayloadPartitions(false) }) {
                                            Text(stringResource(R.string.payload_deselect_all), fontSize = 12.sp, color = TextSecondary)
                                        }
                                    }
                                }

                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(payloadPartitions, key = { it.name }) { item ->
                                        val status = otaPartitionStatus[item.name] ?: ExtractionStatus.IDLE

                                        Card(
                                            shape = RoundedCornerShape(22.dp),
                                            border = if (item.isSelected) BorderStroke(1.5.dp, AccentPrimary) else null,
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (item.isSelected) AccentPrimary.copy(alpha = 0.16f) else DarkSurface
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(22.dp))
                                                .clickable { viewModel.togglePayloadSelection(item.name) }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    Checkbox(
                                                        checked = item.isSelected,
                                                        onCheckedChange = { viewModel.togglePayloadSelection(item.name) },
                                                        colors = CheckboxDefaults.colors(
                                                            checkedColor = AccentPrimary,
                                                            checkmarkColor = AccentOnPrimary
                                                        )
                                                    )
                                                    Column {
                                                        Text(item.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                                                        val partitionSizeText = if (item.sizeBytes <= 0L) stringResource(R.string.unknown_size) else StorageUtil.formatSize(item.sizeBytes)
                                                        Text(partitionSizeText, fontSize = 12.sp, color = TextSecondary)
                                                    }
                                                }

                                                // Indikator status live per kartu
                                                when (status) {
                                                    ExtractionStatus.EXTRACTING -> {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            CircularProgressIndicator(
                                                                modifier = Modifier.size(16.dp),
                                                                strokeWidth = 2.dp,
                                                                color = AccentPrimary
                                                            )
                                                            Surface(
                                                                shape = RoundedCornerShape(50),
                                                                color = AccentPrimary.copy(alpha = 0.2f),
                                                            ) {
                                                                Text(
                                                                    text = "Mengekstrak...",
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = AccentPrimary,
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    ExtractionStatus.QUEUED -> {
                                                        Surface(
                                                            shape = RoundedCornerShape(50),
                                                            color = DarkSurfaceVariant,
                                                        ) {
                                                            Text(
                                                                text = "Antrean",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = TextSecondary,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                            )
                                                        }
                                                    }
                                                    ExtractionStatus.SUCCESS -> {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.CheckCircle,
                                                                contentDescription = null,
                                                                tint = SafeGreen,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                            Surface(
                                                                shape = RoundedCornerShape(50),
                                                                color = SafeGreen.copy(alpha = 0.15f),
                                                            ) {
                                                                Text(
                                                                    text = "Selesai",
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = SafeGreen,
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    ExtractionStatus.FAILED -> {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Error,
                                                                contentDescription = null,
                                                                tint = CriticalRed,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                            Surface(
                                                                shape = RoundedCornerShape(50),
                                                                color = CriticalBg,
                                                            ) {
                                                                Text(
                                                                    text = "Gagal",
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = CriticalRed,
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    ExtractionStatus.IDLE -> {
                                                        Surface(
                                                            shape = RoundedCornerShape(50),
                                                            color = DarkBg,
                                                            modifier = Modifier.padding(end = 4.dp)
                                                        ) {
                                                            Text(
                                                                stringResource(R.string.tag_ota),
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = AccentPrimary,
                                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                            )
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

                    2 -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedButton(
                                onClick = { StorageUtil.openDumpsFolder(context) },
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = DarkSurface,
                                    contentColor = TextPrimary
                                ),
                                border = null,
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.btn_open_folder), fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = historyCategory == HistoryCategory.DUMP,
                                    onClick = { viewModel.setHistoryCategory(HistoryCategory.DUMP) },
                                    label = { Text(stringResource(R.string.history_tab_dump, dumpHistoryList.size)) },
                                    shape = RoundedCornerShape(16.dp),
                                    border = null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentPrimary,
                                        selectedLabelColor = AccentOnPrimary,
                                        containerColor = DarkSurface,
                                        labelColor = TextSecondary
                                    )
                                )

                                FilterChip(
                                    selected = historyCategory == HistoryCategory.OTA,
                                    onClick = { viewModel.setHistoryCategory(HistoryCategory.OTA) },
                                    label = { Text(stringResource(R.string.history_tab_ota, otaHistoryList.size)) },
                                    shape = RoundedCornerShape(16.dp),
                                    border = null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentPrimary,
                                        selectedLabelColor = AccentOnPrimary,
                                        containerColor = DarkSurface,
                                        labelColor = TextSecondary
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (isHistoryLoading) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = AccentPrimary)
                                }
                            } else if (currentHistoryList.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (historyCategory == HistoryCategory.DUMP) stringResource(R.string.history_empty_dump) else stringResource(R.string.history_empty_ota),
                                        color = TextSecondary
                                    )
                                }
                            } else {
                                val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(bottom = 24.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(currentHistoryList, key = { it.path }) { item ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(22.dp),
                                            colors = CardDefaults.cardColors(containerColor = DarkSurface)
                                        ) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(item.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                                                        Text(
                                                            "${StorageUtil.formatSize(item.sizeBytes)} • ${dateFormat.format(Date(item.lastModified))}",
                                                            fontSize = 12.sp,
                                                            color = TextSecondary
                                                        )
                                                    }
                                                    IconButton(onClick = { viewModel.deleteHistoryDump(item) }) {
                                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = CriticalRed)
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

                                                Spacer(modifier = Modifier.height(10.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    when (item.verifyResult) {
                                                        VerifyResult.NOT_CHECKED -> Text(stringResource(R.string.verify_not_checked), fontSize = 11.sp, color = TextSecondary)
                                                        VerifyResult.MATCH -> Text(stringResource(R.string.verify_match), fontSize = 11.sp, color = SafeGreen, fontWeight = FontWeight.Bold)
                                                        VerifyResult.MISMATCH -> Text(stringResource(R.string.verify_mismatch), fontSize = 11.sp, color = CriticalRed, fontWeight = FontWeight.Bold)
                                                        VerifyResult.NO_HASH_FILE -> Text(stringResource(R.string.verify_no_hash), fontSize = 11.sp, color = TextSecondary)
                                                    }

                                                    FilledTonalButton(
                                                        onClick = { viewModel.verifyChecksum(item) },
                                                        enabled = !item.isVerifying,
                                                        shape = RoundedCornerShape(12.dp),
                                                        colors = ButtonDefaults.filledTonalButtonColors(
                                                            containerColor = DarkSurfaceVariant,
                                                            contentColor = TextPrimary
                                                        )
                                                    ) {
                                                        if (item.isVerifying) {
                                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentPrimary)
                                                        } else {
                                                            Text(stringResource(R.string.btn_verify), fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
    }

    if (showStorageWarning && pendingPartition != null) {
        val part = pendingPartition!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissStorageWarning() },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.dialog_storage_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.dialog_storage_desc, part.name, StorageUtil.formatSize(part.sizeBytes)),
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.proceedDumpAnyway(context) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CriticalRed, contentColor = CriticalBg)
                ) {
                    Text(stringResource(R.string.btn_dump_anyway), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissStorageWarning() }) {
                    Text(stringResource(R.string.btn_cancel), color = TextSecondary)
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

            // Dialog hanya muncul jika tidak diminimalkan
            if (!isDumpingDialogMinimized) {
                AlertDialog(
                    onDismissRequest = { isDumpingDialogMinimized = true },
                    containerColor = DarkSurface,
                    shape = RoundedCornerShape(28.dp),
                    title = { Text(stringResource(R.string.dump_extracting, dump.partitionName, dump.current, dump.total), fontWeight = FontWeight.Bold, color = TextPrimary) },
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
                                val speedText = if (dump.speedBytesPerSec > 0) "${StorageUtil.formatSize(dump.speedBytesPerSec)}/s" else "-- MB/s"
                                val etaText = if (dump.etaSeconds >= 0) "Sisa: ${formatEta(dump.etaSeconds)}" else "Menghitung..."

                                Text(
                                    text = "${StorageUtil.formatSize(dump.bytesCopied)} / ${StorageUtil.formatSize(dump.totalBytes)} ($percent%)",
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
                    confirmButton = {
                        TextButton(onClick = { isDumpingDialogMinimized = true }) {
                            Text("Minimalkan", color = AccentPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        }
        is DumpState.Success -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetDumpStatus() },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(28.dp),
                icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(36.dp)) },
                title = { Text(stringResource(R.string.dump_success_title), fontWeight = FontWeight.Bold, color = TextPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.dump_success_partition, dump.partitionName), fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(stringResource(R.string.dump_success_file, dump.outputPath), fontSize = 12.sp, color = TextSecondary)
                        Text("SHA256: ${dump.sha256}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentPrimary)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(dump.sha256))
                            viewModel.resetDumpStatus()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = AccentOnPrimary)
                    ) {
                        Text(stringResource(R.string.btn_copy_sha256), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        StorageUtil.openDumpsFolder(context)
                        viewModel.resetDumpStatus()
                    }) {
                        Text(stringResource(R.string.btn_open_folder), color = TextSecondary)
                    }
                }
            )
        }
        is DumpState.BatchSummary -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetDumpStatus() },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(28.dp),
                icon = {
                    Icon(
                        if (dump.failed.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (dump.failed.isEmpty()) SafeGreen else CriticalRed,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = { Text(stringResource(R.string.batch_summary_title), fontWeight = FontWeight.Bold, color = TextPrimary) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.batch_summary_stats, dump.success.size, dump.failed.size),
                            fontWeight = FontWeight.Bold,
                            color = if (dump.failed.isEmpty()) SafeGreen else CriticalRed
                        )
                        if (dump.failed.isNotEmpty()) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = CriticalBg),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
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
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = AccentOnPrimary)
                    ) {
                        Text(stringResource(R.string.btn_open_folder), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resetDumpStatus() }) {
                        Text(stringResource(R.string.btn_close), color = TextSecondary)
                    }
                }
            )
        }
        is DumpState.Error -> {
            var showDetailLog by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { viewModel.resetDumpStatus() },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(28.dp),
                icon = { Icon(Icons.Default.Error, contentDescription = null, tint = CriticalRed, modifier = Modifier.size(36.dp)) },
                title = { Text(stringResource(R.string.dump_error_title), fontWeight = FontWeight.Bold, color = TextPrimary) },
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
                                        if (showDetailLog) stringResource(R.string.log_hide) else stringResource(R.string.log_view),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentPrimary
                                    )
                                }
                            }

                            if (showDetailLog) {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
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
                                Text(stringResource(R.string.btn_copy_log), color = AccentPrimary)
                            }
                        }
                        Button(
                            onClick = { viewModel.resetDumpStatus() },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = AccentOnPrimary)
                        ) {
                            Text(stringResource(R.string.btn_close), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }
        is DumpState.Idle -> {}
    }
}
