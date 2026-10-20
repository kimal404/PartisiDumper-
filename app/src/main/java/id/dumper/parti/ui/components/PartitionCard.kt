package id.dumper.parti.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dumper.parti.R
import id.dumper.parti.data.model.PartitionInfo
import id.dumper.parti.ui.theme.*
import id.dumper.parti.util.StorageUtil

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PartitionCard(
    partition: PartitionInfo,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onDumpClick: () -> Unit,
    onCardClick: () -> Unit,
    onCardLongClick: () -> Unit,
    onToggleSelect: () -> Unit = onCardClick
) {
    var isExpanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Card(
        shape = RoundedCornerShape(24.dp),
        border = if (isSelected) BorderStroke(1.5.dp, AccentPrimary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AccentPrimary.copy(alpha = 0.16f) else DarkSurface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) onCardClick() else isExpanded = !isExpanded
                },
                onLongClick = onCardLongClick
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentPrimary,
                            checkmarkColor = AccentOnPrimary
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = partition.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )

                        if (partition.isCritical) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = CriticalBg
                            ) {
                                Text(
                                    text = stringResource(R.string.tag_critical),
                                    color = CriticalRed,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        if (partition.slot.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = DarkSurfaceVariant
                            ) {
                                Text(
                                    text = partition.slot.uppercase(),
                                    color = AccentPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = StorageUtil.formatSize(partition.sizeBytes),
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = DarkSurfaceVariant,
                    modifier = Modifier.size(42.dp)
                ) {
                    IconButton(onClick = onDumpClick) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Dump",
                            tint = AccentPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    HorizontalDivider(color = DarkSurfaceVariant, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.block_path),
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = partition.blockPath,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                        }

                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(partition.blockPath)) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.action_copy),
                                tint = AccentPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
