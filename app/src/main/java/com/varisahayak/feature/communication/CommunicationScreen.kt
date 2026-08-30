package com.varisahayak.feature.communication

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.varisahayak.R
import com.varisahayak.core.designsystem.Dimens
import com.varisahayak.core.designsystem.VariTheme
import com.varisahayak.core.designsystem.component.*
import com.varisahayak.domain.model.BroadcastingState
import com.varisahayak.domain.model.CommunicationChannel
import com.varisahayak.domain.model.CommunicationMessage
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun CommunicationScreen(
    viewModel: CommunicationViewModel = hiltViewModel()
) {
    val channels by viewModel.channels.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val selectedId by viewModel.selectedChannelId.collectAsState()
    val broadcastingState by viewModel.broadcastingState.collectAsState()

    var showBroadcastInput by remember { mutableStateOf(false) }

    if (channels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = VariTheme.colors.brandSolid)
        }
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(VariTheme.colors.canvas)
    ) {
        val isWide = maxWidth > 600.dp
        
        if (isWide) {
            // Wide Screen Layout (Sidebar + Chat)
            Row(modifier = Modifier.fillMaxSize()) {
                ChannelSidebar(
                    channels = channels,
                    selectedId = selectedId,
                    broadcastingState = broadcastingState,
                    onChannelSelect = viewModel::selectChannel,
                    onToggleBroadcasting = viewModel::toggleBroadcastingMode,
                    onToggleChannelSelection = viewModel::toggleChannelSelection,
                    onSendSos = viewModel::sendSos,
                    onComposeBroadcast = { /* Not needed in wide layout */ },
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .padding(Dimens.SpaceMd)
                )

                ChatArea(
                    channel = if (broadcastingState.isEnabled) null else channels.find { it.id == selectedId },
                    messages = messages,
                    broadcastingState = broadcastingState,
                    showBackButton = false,
                    onBack = {},
                    onSendMessage = viewModel::sendMessage,
                    onToggleBroadcasting = viewModel::toggleBroadcastingMode,
                    onSelectAll = viewModel::selectAllChannels,
                    onDeselectAll = viewModel::deselectAllChannels,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = Dimens.SpaceMd, horizontal = Dimens.SpaceSm)
                )
            }
        } else {
            // Mobile Layout (Switching between List and Chat)
            val showChat = (selectedId != null && !broadcastingState.isEnabled) || showBroadcastInput

            if (showChat) {
                BackHandler { 
                    if (showBroadcastInput) showBroadcastInput = false else viewModel.selectChannel("") 
                }
                ChatArea(
                    channel = if (showBroadcastInput) null else channels.find { it.id == selectedId },
                    messages = if (showBroadcastInput) emptyList() else messages,
                    broadcastingState = broadcastingState,
                    showBackButton = true,
                    onBack = { if (showBroadcastInput) showBroadcastInput = false else viewModel.selectChannel("") },
                    onSendMessage = {
                        viewModel.sendMessage(it)
                        if (showBroadcastInput) showBroadcastInput = false
                    },
                    onToggleBroadcasting = viewModel::toggleBroadcastingMode,
                    onSelectAll = viewModel::selectAllChannels,
                    onDeselectAll = viewModel::deselectAllChannels,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Dimens.SpaceSm)
                )
            } else {
                ChannelSidebar(
                    channels = channels,
                    selectedId = selectedId,
                    broadcastingState = broadcastingState,
                    onChannelSelect = viewModel::selectChannel,
                    onToggleBroadcasting = viewModel::toggleBroadcastingMode,
                    onToggleChannelSelection = viewModel::toggleChannelSelection,
                    onSendSos = viewModel::sendSos,
                    onComposeBroadcast = { showBroadcastInput = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Dimens.SpaceMd)
                )
            }
        }
    }
}

@Composable
private fun ChannelSidebar(
    channels: List<CommunicationChannel>,
    selectedId: String?,
    broadcastingState: BroadcastingState,
    onChannelSelect: (String) -> Unit,
    onToggleBroadcasting: () -> Unit,
    onToggleChannelSelection: (String) -> Unit,
    onSendSos: () -> Unit,
    onComposeBroadcast: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassSurface(modifier = modifier) {
        Column(modifier = Modifier.padding(Dimens.SpaceMd)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.comms_channels).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = VariTheme.colors.textMuted
                )

                IconButton(onClick = onToggleBroadcasting) {
                    Icon(
                        imageVector = if (broadcastingState.isEnabled) Icons.Default.Podcasts else Icons.Default.ChatBubble,
                        contentDescription = null,
                        tint = if (broadcastingState.isEnabled) VariTheme.colors.critical else VariTheme.colors.brandSolid
                    )
                }
            }

            Spacer(Modifier.height(Dimens.SpaceMd))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
            ) {
                items(channels) { channel ->
                    val isSelected = selectedId == channel.id
                    val isBroadcastingSelected = broadcastingState.selectedChannelIds.contains(channel.id)

                    ChannelItem(
                        channel = channel,
                        isSelected = isSelected,
                        isBroadcastingMode = broadcastingState.isEnabled,
                        isBroadcastingSelected = isBroadcastingSelected,
                        onClick = {
                            if (broadcastingState.isEnabled) {
                                onToggleChannelSelection(channel.id)
                            } else {
                                onChannelSelect(channel.id)
                            }
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = broadcastingState.isEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(Dimens.SpaceMd))
                    VariPrimaryButton(
                        text = stringResource(R.string.comms_broadcast),
                        enabled = broadcastingState.selectedChannelIds.isNotEmpty(),
                        onClick = onComposeBroadcast,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(Dimens.SpaceMd))

            Text(
                text = stringResource(R.string.comms_quick_actions).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = VariTheme.colors.textMuted
            )

            Spacer(Modifier.height(Dimens.SpaceMd))

            SosButton(
                text = stringResource(R.string.comms_send_sos),
                onClick = onSendSos,
                modifier = Modifier.height(56.dp)
            )

            Spacer(Modifier.height(Dimens.SpaceSm))

            VariSecondaryButton(
                text = stringResource(R.string.comms_flash_notification),
                onClick = {}, // TODO: Flash notification
                icon = Icons.Default.Bolt,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            )
        }
    }
}

@Composable
private fun ChannelItem(
    channel: CommunicationChannel,
    isSelected: Boolean,
    isBroadcastingMode: Boolean,
    isBroadcastingSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = VariTheme.colors
    val backgroundColor = when {
        isBroadcastingMode && isBroadcastingSelected -> colors.brandSubtle.copy(alpha = 0.5f)
        !isBroadcastingMode && isSelected -> colors.brandSubtle
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerMd))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(Dimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(Dimens.CornerSm))
                .background(colors.cardSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getChannelIcon(channel.id),
                contentDescription = null,
                tint = if (channel.hasSos) colors.critical else colors.brandSolid
            )
        }

        Spacer(Modifier.width(Dimens.SpaceSm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = channel.description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isBroadcastingMode) {
            Checkbox(
                checked = isBroadcastingSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(checkedColor = colors.brandSolid)
            )
        } else {
            if (channel.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(colors.brandSolid),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = channel.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onBrandSolid
                    )
                }
            } else if (channel.hasSos) {
                StatusPill(
                    text = "SOS",
                    tone = colors.criticalTone(),
                    modifier = Modifier
                )
            }
        }
    }
}

@Composable
private fun ChatArea(
    channel: CommunicationChannel?,
    messages: List<CommunicationMessage>,
    broadcastingState: BroadcastingState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onToggleBroadcasting: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = VariTheme.colors
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    GlassSurface(modifier = modifier) {
        Column {
            // Chat Header
            ChatHeader(
                channel = channel,
                broadcastingState = broadcastingState,
                showBackButton = showBackButton,
                onBack = onBack,
                onToggleBroadcasting = onToggleBroadcasting,
                onSelectAll = onSelectAll,
                onDeselectAll = onDeselectAll
            )

            HorizontalDivider(color = colors.cardBorder)

            // Messages
            Box(modifier = Modifier.weight(1f)) {
                if (broadcastingState.isEnabled && channel == null) {
                    // Broadcast Compose Mode
                    Column(
                        modifier = Modifier.fillMaxSize().padding(Dimens.SpaceMd),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Podcasts,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(Dimens.SpaceMd))
                        Text(
                            text = stringResource(R.string.comms_broadcast_hint, broadcastingState.selectedChannelIds.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = Dimens.SpaceMd),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
                        contentPadding = PaddingValues(vertical = Dimens.SpaceMd)
                    ) {
                        items(messages) { message ->
                            MessageBubble(message = message)
                        }
                    }
                }
            }

            // Input
            ChatInput(
                onSendMessage = onSendMessage,
                isBroadcasting = broadcastingState.isEnabled,
                broadcastCount = broadcastingState.selectedChannelIds.size
            )
        }
    }
}

@Composable
private fun ChatHeader(
    channel: CommunicationChannel?,
    broadcastingState: BroadcastingState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onToggleBroadcasting: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit
) {
    val colors = VariTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBackButton) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = colors.textPrimary
                )
            }
        }

        Icon(
            imageVector = if (broadcastingState.isEnabled) Icons.Default.Podcasts else getChannelIcon(channel?.id ?: ""),
            contentDescription = null,
            tint = if (broadcastingState.isEnabled) colors.critical else colors.brandSolid,
            modifier = Modifier.size(Dimens.IconMd)
        )

        Spacer(Modifier.width(Dimens.SpaceSm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (broadcastingState.isEnabled) stringResource(R.string.comms_broadcasting_mode) else (channel?.name ?: ""),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!broadcastingState.isEnabled && channel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (channel.isOnline) Color(0xFF4CAF50) else colors.textMuted)
                    )
                    Spacer(Modifier.width(Dimens.SpaceXs))
                    Text(
                        text = stringResource(R.string.comms_online, channel.onlineCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted
                    )
                }
            }
        }

        if (broadcastingState.isEnabled) {
            TextButton(onClick = onSelectAll) {
                Text(stringResource(R.string.comms_select_all))
            }
            TextButton(onClick = onDeselectAll) {
                Text(stringResource(R.string.comms_deselect_all))
            }
        } else if (!showBackButton) {
            // Only show the toggle in header on wide screens where sidebar is always visible
            IconButton(onClick = onToggleBroadcasting) {
                Icon(
                    imageVector = Icons.Default.Podcasts,
                    contentDescription = null,
                    tint = colors.textMuted
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: CommunicationMessage) {
    val colors = VariTheme.colors
    
    if (message.isSos) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.critical.copy(alpha = 0.1f)),
            border = BorderStroke(1.dp, colors.critical.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceSm)
        ) {
            Row(
                modifier = Modifier.padding(Dimens.SpaceSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = colors.critical,
                    modifier = Modifier.size(Dimens.IconSm)
                )
                Spacer(Modifier.width(Dimens.SpaceSm))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.critical,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!message.isFromMe) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colors.brandSolid.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = message.senderName.take(2).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.brandSolid
                    )
                }
                Spacer(Modifier.width(Dimens.SpaceSm))
            }

            Column(horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${message.senderName} • ${message.senderRole}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.brandSolid,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(Modifier.height(Dimens.SpaceXs))
                
                Surface(
                    color = if (message.isFromMe) colors.brandSolid.copy(alpha = 0.1f) else colors.cardSurface,
                    shape = RoundedCornerShape(
                        topStart = Dimens.CornerMd,
                        topEnd = Dimens.CornerMd,
                        bottomStart = if (message.isFromMe) Dimens.CornerMd else 0.dp,
                        bottomEnd = if (message.isFromMe) 0.dp else Dimens.CornerMd
                    ),
                    border = BorderStroke(1.dp, colors.cardBorder)
                ) {
                    Column(modifier = Modifier.padding(Dimens.SpaceSm)) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = formatTimestamp(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInput(
    onSendMessage: (String) -> Unit,
    isBroadcasting: Boolean,
    broadcastCount: Int
) {
    var text by remember { mutableStateOf("") }
    val colors = VariTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.SpaceMd)
    ) {
        if (isBroadcasting) {
            Text(
                text = stringResource(R.string.comms_broadcast_hint, broadcastCount),
                style = MaterialTheme.typography.labelSmall,
                color = colors.brandSolid,
                modifier = Modifier.padding(bottom = Dimens.SpaceXs)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.comms_message_hint)) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.cardSurface,
                    unfocusedContainerColor = colors.cardSurface,
                    disabledContainerColor = colors.cardSurface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(Dimens.CornerMd)
            )
            
            Spacer(Modifier.width(Dimens.SpaceSm))
            
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSendMessage(text)
                        text = ""
                    }
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.CornerSm))
                    .background(colors.brandSolid)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = colors.onBrandSolid
                )
            }
        }
    }
}

private fun getChannelIcon(id: String): ImageVector = when (id) {
    "all_hands" -> Icons.Default.Podcasts
    "medical" -> Icons.Default.LocalHospital
    "police" -> Icons.Default.LocalPolice
    "pandharpur_zone" -> Icons.Default.Domain
    "lonand_zone" -> Icons.Default.LocationOn
    else -> Icons.Default.ChatBubble
}

private fun formatTimestamp(timestamp: Instant): String {
    val localDateTime = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = localDateTime.hour.toString().padStart(2, '0')
    val minute = localDateTime.minute.toString().padStart(2, '0')
    return "$hour:$minute AM" // Simplified for demo
}
