package com.varisahayak.data.repository

import android.util.Log
import com.varisahayak.core.common.AppError
import com.varisahayak.core.common.Outcome
import com.varisahayak.domain.model.CommunicationChannel
import com.varisahayak.domain.model.CommunicationMessage
import com.varisahayak.domain.repository.CommunicationRepository
import com.varisahayak.domain.repository.ProfileRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class CommunicationRepositoryImpl @Inject constructor(
    private val supabase: SupabaseClient,
    private val profileRepository: ProfileRepository,
) : CommunicationRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _channels = MutableStateFlow(initialChannels())
    
    private val activeChannels = mutableMapOf<String, RealtimeChannel>()

    override fun observeChannels(): Flow<List<CommunicationChannel>> = _channels

    override fun observeMessages(channelId: String): Flow<CommunicationMessage> {
        Log.d("CommsRepo", "observeMessages($channelId)")
        val channel = getOrCreateChannel(channelId)
        return channel.broadcastFlow<CommunicationMessage>(event = "message")
    }

    @OptIn(SupabaseInternal::class)
    private fun getOrCreateChannel(channelId: String): RealtimeChannel {
        return activeChannels.getOrPut(channelId) {
            Log.d("CommsRepo", "Creating new channel for $channelId")
            // Explicitly enable presence in the channel config
            supabase.channel("comms_$channelId").also { channel ->
                // Presence tracking
                channel.presenceChangeFlow()
                    .onEach {
                        // Use the raw presence state to get an accurate count of all connected clients
                        val state = channel.callbackManager.presenceState()
                        val count = state.size
                        Log.d("CommsRepo", "Presence change in $channelId: count=$count")
                        updateOnlineCount(channelId, count)
                    }
                    .launchIn(scope)

                scope.launch {
                    try {
                        Log.d("CommsRepo", "Subscribing to channel $channelId")
                        channel.subscribe() // blockUntilSubscribed=false to avoid potential hangs
                        
                        // Wait for actual subscription status
                        channel.status.first { it == RealtimeChannel.Status.SUBSCRIBED }
                        Log.d("CommsRepo", "Subscribed to channel $channelId")
                        
                        // Track ourselves
                        val profile = profileRepository.observeCurrentProfile().first()
                        if (profile != null) {
                            Log.d("CommsRepo", "Tracking user ${profile.userId} in $channelId")
                            channel.track(buildJsonObject { put("userId", profile.userId) })
                        }
                    } catch (e: Exception) {
                        Log.e("CommsRepo", "Failed to setup channel $channelId", e)
                    }
                }
            }
        }
    }

    private fun updateOnlineCount(channelId: String, count: Int) {
        _channels.update { channels ->
            channels.map { 
                if (it.id == channelId) {
                    // Always ensure count is at least 1 if we are successfully joined ourselves
                    val displayCount = if (count == 0 && activeChannels.containsKey(channelId)) 1 else count
                    Log.d("CommsRepo", "Updating $channelId onlineCount to $displayCount (raw=$count)")
                    it.copy(onlineCount = displayCount, isOnline = displayCount > 0)
                } else it
            }
        }
    }

    override suspend fun sendMessage(
        channelIds: List<String>,
        content: String,
        isSos: Boolean
    ): Outcome<List<CommunicationMessage>> {
        val profile = profileRepository.observeCurrentProfile().first() 
            ?: return Outcome.Failure(AppError.ProfileUnavailable())
        
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val sentMessages = mutableListOf<CommunicationMessage>()
        
        channelIds.forEach { channelId ->
            val message = CommunicationMessage(
                id = "msg_${System.nanoTime()}",
                channelId = channelId,
                senderId = profile.userId,
                senderName = profile.displayName,
                senderRole = profile.role.name,
                content = content,
                timestamp = now,
                isSos = isSos,
                isFromMe = false // Relative to others
            )

            try {
                val realtimeChannel = getOrCreateChannel(channelId)
                realtimeChannel.broadcast("message", message)
                // Add the local version (with isFromMe = true) to the return list
                sentMessages.add(message.copy(isFromMe = true))
            } catch (e: Exception) {
                Log.e("CommsRepo", "Failed to broadcast message to $channelId", e)
            }
        }
        
        return Outcome.Success(sentMessages)
    }

    override suspend fun markAsRead(channelId: String): Outcome<Unit> {
        _channels.update { channels ->
            channels.map { 
                if (it.id == channelId) it.copy(unreadCount = 0) else it
            }
        }
        return Outcome.Success(Unit)
    }

    private fun initialChannels() = listOf(
        CommunicationChannel(
            id = "all_hands",
            name = "All Hands",
            description = "Broadcast to all",
            onlineCount = 0,
            unreadCount = 0
        ),
        CommunicationChannel(
            id = "medical",
            name = "Medical Team",
            description = "Doctors, Nurses",
            onlineCount = 0
        ),
        CommunicationChannel(
            id = "police",
            name = "Police",
            description = "Security, Patrol",
            onlineCount = 0
        ),
        CommunicationChannel(
            id = "pandharpur_zone",
            name = "Pandharpur Zone",
            description = "Entry & Temple teams",
            hasSos = true
        ),
        CommunicationChannel(
            id = "lonand_zone",
            name = "Lonand Zone",
            description = "km 140-155"
        )
    )
}
