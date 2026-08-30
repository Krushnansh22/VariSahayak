package com.varisahayak.feature.communication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.varisahayak.core.common.Outcome
import com.varisahayak.domain.model.BroadcastingState
import com.varisahayak.domain.model.CommunicationChannel
import com.varisahayak.domain.model.CommunicationMessage
import com.varisahayak.domain.repository.CommunicationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The text side of comms.
 *
 * Deliberately does **not** touch [com.varisahayak.core.walkie.WalkieController]. It used
 * to: selecting a channel here called `walkieController.join(channelId)` with an id from
 * this screen's own roster ("all_hands", "police", "pandharpur_zone", …), which shares no
 * values with WalkieChannels.ALL ("comm-1", "medical", "emergency"). That produced two
 * failures and no way to see either:
 *
 *  - "medical" happened to collide, so opening the Medical Team *chat* silently moved the
 *    volunteer's *radio* to the Medical net — off whatever channel they were monitoring,
 *    with the only indication on a different screen.
 *  - Every other id resolved to nothing, so join() returned early and the chat channel and
 *    the radio channel disagreed with no error anywhere.
 *
 * The radio's channel is owned by the walkie widget and its picker, which is the one
 * control that shows which channel you are actually on. Wiring voice into this screen means
 * giving it a PTT control of its own and one roster shared with WalkieChannels — not a
 * side effect on a chat tap.
 */
@HiltViewModel
class CommunicationViewModel @Inject constructor(
    private val repository: CommunicationRepository,
) : ViewModel() {

    private val _selectedChannelId = MutableStateFlow<String?>(null)
    val selectedChannelId = _selectedChannelId.asStateFlow()

    private val _broadcastingState = MutableStateFlow(BroadcastingState())
    val broadcastingState = _broadcastingState.asStateFlow()

    // In-memory messages for the current session (disappears when ViewModel is cleared)
    private val _sessionMessages = MutableStateFlow<Map<String, List<CommunicationMessage>>>(emptyMap())

    val channels = repository.observeChannels().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    // Current channel messages
    val messages = combine(_selectedChannelId, _sessionMessages) { id, messages ->
        if (id == null) emptyList() else messages[id] ?: emptyList()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val observedChannelIds = mutableSetOf<String>()

    init {
        // Default to the first channel
        viewModelScope.launch {
            repository.observeChannels().collect { channels ->
                if (_selectedChannelId.value == null && channels.isNotEmpty()) {
                    _selectedChannelId.value = channels.first().id
                }

                // Start collecting messages for each channel once
                channels.forEach { channel ->
                    if (observedChannelIds.add(channel.id)) {
                        repository.observeMessages(channel.id)
                            .onEach { message ->
                                addMessageToSession(message)
                            }
                            .launchIn(viewModelScope)
                    }
                }
            }
        }
    }

    private fun addMessageToSession(message: CommunicationMessage) {
        _sessionMessages.update { current ->
            val list = current[message.channelId] ?: emptyList()
            if (list.none { it.id == message.id }) {
                current + (message.channelId to (list + message))
            } else {
                current
            }
        }
    }

    fun selectChannel(channelId: String) {
        _selectedChannelId.value = channelId
        _broadcastingState.update { it.copy(isEnabled = false) }
        viewModelScope.launch {
            repository.markAsRead(channelId)
        }
    }

    fun toggleBroadcastingMode() {
        _broadcastingState.update { 
            val newState = !it.isEnabled
            it.copy(
                isEnabled = newState,
                selectedChannelIds = if (newState) setOf(_selectedChannelId.value).filterNotNull().toSet() else emptySet()
            )
        }
    }

    fun toggleChannelSelection(channelId: String) {
        _broadcastingState.update { state ->
            val newSelection = if (state.selectedChannelIds.contains(channelId)) {
                state.selectedChannelIds - channelId
            } else {
                state.selectedChannelIds + channelId
            }
            state.copy(selectedChannelIds = newSelection)
        }
    }

    fun selectAllChannels() {
        _broadcastingState.update { state ->
            state.copy(selectedChannelIds = channels.value.map { it.id }.toSet())
        }
    }

    fun deselectAllChannels() {
        _broadcastingState.update { state ->
            state.copy(selectedChannelIds = emptySet())
        }
    }

    fun sendMessage(content: String) {
        val state = _broadcastingState.value
        val targets = if (state.isEnabled) {
            state.selectedChannelIds.toList()
        } else {
            listOfNotNull(_selectedChannelId.value)
        }

        if (targets.isEmpty() || content.isBlank()) return

        viewModelScope.launch {
            val outcome = repository.sendMessage(targets, content)
            if (outcome is Outcome.Success) {
                outcome.data.forEach { addMessageToSession(it) }
            }
        }
    }

    fun sendSos() {
        // Quick action: send SOS to all hands
        viewModelScope.launch {
            repository.sendMessage(listOf("all_hands"), "SOS EMERGENCY TRIGGERED", isSos = true)
        }
    }

}
