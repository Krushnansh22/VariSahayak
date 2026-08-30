package com.varisahayak.data.repository

import com.varisahayak.core.common.AppError
import com.varisahayak.core.common.Clock
import com.varisahayak.core.common.DispatcherProvider
import com.varisahayak.core.common.Outcome
import com.varisahayak.data.local.dao.IncidentDao
import com.varisahayak.data.local.dao.IncidentEventDao
import com.varisahayak.data.local.entity.IncidentEntity
import com.varisahayak.data.local.entity.IncidentEventEntity
import com.varisahayak.data.remote.dto.IncidentEventDto
import com.varisahayak.data.local.entity.IncidentEventType
import com.varisahayak.data.local.entity.toDomain
import com.varisahayak.data.remote.dto.IncidentDto
import com.varisahayak.data.remote.dto.toEntity
import com.varisahayak.data.remote.dto.toUpsertDto
import com.varisahayak.data.sync.SyncScheduler
import com.varisahayak.domain.model.GeoPoint
import com.varisahayak.domain.model.Incident
import com.varisahayak.domain.model.IncidentCategory
import com.varisahayak.domain.model.IncidentStateMachine
import com.varisahayak.domain.model.IncidentEventKind
import com.varisahayak.domain.model.IncidentStatus

import com.varisahayak.domain.model.TimelineEvent

import com.varisahayak.domain.model.RewardEngine

import com.varisahayak.domain.model.SyncState
import com.varisahayak.domain.repository.IncidentRepository
import com.varisahayak.domain.repository.RewardRepository
import com.varisahayak.domain.repository.SyncSummary
import com.varisahayak.domain.usecase.PriorityEngine
import com.varisahayak.domain.usecase.PriorityInput
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncidentRepositoryImpl @Inject constructor(
    private val supabase: SupabaseClient,
    private val incidentDao: IncidentDao,
    private val incidentEventDao: IncidentEventDao,
    private val rewardRepository: RewardRepository,
    private val priorityEngine: PriorityEngine,
    private val syncScheduler: SyncScheduler,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) : IncidentRepository {

    override fun observeAll(): Flow<List<Incident>> =
        incidentDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeOpen(): Flow<List<Incident>> =
        incidentDao.observeOpen().map { entities -> entities.map { it.toDomain() } }

    override fun observeAssignedTo(userId: String): Flow<List<Incident>> =
        incidentDao.observeAssignedTo(userId).map { entities -> entities.map { it.toDomain() } }

    override fun observeActiveSos(): Flow<List<Incident>> =
        incidentDao.observeActiveSos().map { entities -> entities.map { it.toDomain() } }

    override fun observeById(clientId: String): Flow<Incident?> =
        incidentDao.observeByClientId(clientId).map { it?.toDomain() }

    override fun observeUnsyncedCount(): Flow<Int> = incidentDao.observeUnsyncedCount()

    override fun observeReportedCount(userId: String): Flow<Int> =
        incidentDao.observeReportedCount(userId)

    override fun observeResolvedCount(userId: String): Flow<Int> =
        incidentDao.observeResolvedCount(userId)

    /**
     * Creates an incident locally and returns immediately.
     *
     * Never touches the network. Validate, write to Room, enqueue sync, return — so this
     * succeeds with no connectivity and the caller can show the incident at once.
     */
    override suspend fun createIncident(
        category: IncidentCategory,
        description: String,
        location: GeoPoint?,
        photoLocalPath: String?,
        affectedPersonNote: String?,
        isSos: Boolean,
        sosBridgeToken: String?,
    ): Outcome<Incident> = withContext(dispatchers.io) {
        if (description.isBlank()) {
            return@withContext Outcome.Failure(
                AppError.Validation(field = "description", message = "Add a short description."),
            )
        }

        val reporterId = supabase.auth.currentUserOrNull()?.id
            ?: return@withContext Outcome.Failure(AppError.Unauthorised())

        // Prioritisation runs on-device and offline. An SOS is pinned to CRITICAL here,
        // before any network or AI involvement — that is the whole point of the rule.
        val decision = priorityEngine.prioritise(
            PriorityInput(category = category, isSos = isSos),
        )

        val now = clock.nowEpochMillis()
        val entity = IncidentEntity(
            clientId = UUID.randomUUID().toString(),
            category = category.wireName,
            description = description.trim(),
            latitude = location?.latitude,
            longitude = location?.longitude,
            locationAccuracyMeters = location?.accuracyMeters,
            locationIsApproximate = location?.isApproximate ?: false,
            reporterId = reporterId,
            reportedAtEpochMillis = now,
            photoLocalPath = photoLocalPath,
            affectedPersonNote = affectedPersonNote,
            // PENDING_SYNC until the server accepts it; the state machine promotes it to
            // REPORTED on reconciliation.
            status = IncidentStatus.PENDING_SYNC.wireName,
            priority = decision.priority.wireName,
            syncState = SyncState.PENDING.name,
            isSos = isSos,
            sosBridgeToken = sosBridgeToken,
            updatedAtEpochMillis = now,
        )

        incidentDao.upsert(entity)

        recordEvent(
            incidentClientId = entity.clientId,
            type = IncidentEventType.CREATED,
            actorId = reporterId,
            toValue = entity.status,
            note = "priority=${decision.priority.wireName} basis=${decision.basis}",
            at = now,
        )

        syncScheduler.requestSync()
        Outcome.Success(entity.toDomain())
    }

    /**
     * Applies a status change through [IncidentStateMachine].
     *
     * An illegal transition is refused rather than written. A stale realtime frame or a
     * double tap must not be able to move a resolved incident back to reported.
     */
    override suspend fun updateStatus(
        clientId: String,
        newStatus: IncidentStatus,
        note: String?,
    ): Outcome<Incident> = withContext(dispatchers.io) {
        val existing = incidentDao.getByClientId(clientId)
            ?: return@withContext Outcome.Failure(AppError.NotFound())

        val current = IncidentStatus.fromWire(existing.status)

        when (val result = IncidentStateMachine.transition(current, newStatus)) {
            is IncidentStateMachine.TransitionResult.Rejected -> {
                return@withContext Outcome.Failure(
                    AppError.Validation(
                        field = "status",
                        message = rejectionMessage(result),
                    ),
                )
            }

            is IncidentStateMachine.TransitionResult.Accepted -> {
                val now = clock.nowEpochMillis()
                incidentDao.setStatus(clientId, result.status.wireName, now)
                incidentDao.setSyncState(clientId, SyncState.PENDING.name)

                // Only for an incident the server has never seen. Once it has a serverId
                // the `incidents_log_transition` trigger writes this same STATUS_CHANGED
                // row on the next sync, and refreshTimeline pulls it back — so writing one
                // here too put every transition in the timeline twice.
                if (existing.serverId.isNullOrBlank()) {
                    recordEvent(
                        incidentClientId = clientId,
                        type = IncidentEventType.STATUS_CHANGED,
                        actorId = supabase.auth.currentUserOrNull()?.id,
                        fromValue = current.wireName,
                        toValue = result.status.wireName,
                        note = note,
                        at = now,
                    )
                }

                // Gamification: Award XP and record impact on resolution
                if (result.status == IncidentStatus.RESOLVED) {
                    val entity = incidentDao.getByClientId(clientId)
                    if (entity != null) {
                        val isSos = entity.isSos
                        val xp = if (isSos) RewardEngine.XP_RESOLVE_SOS else RewardEngine.XP_RESOLVE_INCIDENT
                        val reason = if (isSos) "Resolved SOS emergency" else "Resolved incident"
                        
                        rewardRepository.awardXp(xp, reason, clientId)
                        rewardRepository.recordImpact(
                            incidentsResolved = 1,
                            sosResponses = if (isSos) 1 else 0
                        )
                    }
                }

                syncScheduler.requestSync()

                val updated = incidentDao.getByClientId(clientId)
                    ?: return@withContext Outcome.Failure(AppError.NotFound())
                Outcome.Success(updated.toDomain())
            }
        }
    }

    /**
     * Drains the pending queue.
     *
     * Uploads use upsert on `client_id`, which is what makes a retry a no-op instead of a
     * duplicate incident. A failure marks the record FAILED and leaves it in place — it
     * is retried, never discarded.
     */
    override suspend fun syncPending(): Outcome<SyncSummary> = withContext(dispatchers.io) {
        val pending = incidentDao.getPendingSync()
        if (pending.isEmpty()) {
            return@withContext Outcome.Success(SyncSummary(0, 0, 0))
        }

        var succeeded = 0
        var failed = 0

        pending.forEach { entity ->
            try {
                incidentDao.markSyncAttempt(
                    clientId = entity.clientId,
                    syncState = SyncState.SYNCING.name,
                    attemptedAt = clock.nowEpochMillis(),
                )

                val reportedAtIso = Instant.ofEpochMilli(entity.reportedAtEpochMillis).toString()
                val saved = supabase.from("incidents")
                    .upsert(entity.toUpsertDto(reportedAtIso)) {
                        onConflict = "client_id"
                        // Required: returning defaults to Minimal, so without this the
                        // server row — and its id — never comes back.
                        select()
                    }
                    .decodeSingle<IncidentDto>()

                val serverId = saved.id
                if (serverId.isNullOrBlank()) {
                    // Never write a blank serverId: the column carries a unique index, so
                    // a second blank would collide and corrupt an unrelated record.
                    throw IllegalStateException("Server accepted the incident but returned no id")
                }

                incidentDao.markSynced(
                    clientId = entity.clientId,
                    serverId = serverId,
                    status = saved.status,
                    updatedAt = clock.nowEpochMillis(),
                )
                succeeded++
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failed++
                incidentDao.markSyncAttempt(
                    clientId = entity.clientId,
                    syncState = SyncState.FAILED.name,
                    attemptedAt = clock.nowEpochMillis(),
                )
            }
        }

        Outcome.Success(SyncSummary(pending.size, succeeded, failed))
    }

    /**
     * Pulls server state and merges it in.
     *
     * Reconciliation is delegated to the DAO, which leaves records that are still awaiting
     * sync untouched — the local copy is the newer truth until the server has accepted it.
     */
    override suspend fun refreshFromServer(): Outcome<Unit> = withContext(dispatchers.io) {
        try {
            val remote = supabase.from("incidents")
                .select()
                .decodeList<IncidentDto>()

            remote.forEach { dto ->
                incidentDao.reconcileFromServer(dto.toEntity(clock.nowEpochMillis()))
            }

            Outcome.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Outcome.Failure(AppError.Network(cause = error))
        }
    }

    override fun observeTimeline(incidentClientId: String): Flow<List<TimelineEvent>> =
        incidentEventDao.observeForIncident(incidentClientId)
            .map { rows -> rows.map { it.toTimelineEvent() } }

    /**
     * Pulls one incident's server-side events.
     *
     * Scoped to a single incident on purpose. Most of this trail is written by database
     * triggers — `assign_incident` records ASSIGNED and ASSIGNMENT_FAILED, the status
     * triggers record transitions — so a device that only ever wrote its own events has
     * never seen the half of the story that matters most to a command user.
     *
     * RLS decides what comes back: the read runs under the caller's session, and the
     * policy on incident_events admits command users and the incident's own participants
     * and nobody else. There is no client-side permission check here because there does
     * not need to be one.
     */
    override suspend fun refreshTimeline(incidentClientId: String): Outcome<Unit> =
        withContext(dispatchers.io) {
            val serverId = incidentDao.getByClientId(incidentClientId)?.serverId
                // Never synced, so the server has nothing to add. The local events are
                // already the complete story and this is a success, not a failure.
                ?: return@withContext Outcome.Success(Unit)

            try {
                val dtos = supabase.from("incident_events")
                    .select {
                        filter { eq("incident_id", serverId) }
                        order("occurred_at", Order.ASCENDING)
                    }
                    .decodeList<IncidentEventDto>()

                incidentEventDao.upsertAll(
                    dtos.map { it.toEntity(incidentClientId, serverId) },
                )
                Outcome.Success(Unit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The local trail still renders. A timeline missing its server rows is a
                // degraded view, never a blank screen.
                android.util.Log.d("IncidentRepository", "Timeline refresh failed: ${error.message}")
                Outcome.Failure(AppError.Network(cause = error))
            }
        }

    override suspend fun findClientIdByServerId(serverId: String): String? =
        withContext(dispatchers.io) {
            incidentDao.getByServerId(serverId)?.clientId
        }

    private suspend fun recordEvent(
        incidentClientId: String,
        type: IncidentEventType,
        actorId: String?,
        fromValue: String? = null,
        toValue: String? = null,
        note: String? = null,
        at: Long,
    ) {
        incidentEventDao.upsert(
            IncidentEventEntity(
                eventId = UUID.randomUUID().toString(),
                incidentClientId = incidentClientId,
                type = type.name,
                actorId = actorId,
                fromValue = fromValue,
                toValue = toValue,
                note = note,
                occurredAtEpochMillis = at,
                synced = false,
            ),
        )
    }

    private fun rejectionMessage(
        rejection: IncidentStateMachine.TransitionResult.Rejected,
    ): String = when (rejection.reason) {
        IncidentStateMachine.Reason.NO_CHANGE ->
            "This incident is already in that state."

        IncidentStateMachine.Reason.SOURCE_TERMINAL ->
            "This incident is already closed and cannot be changed."

        IncidentStateMachine.Reason.NOT_PERMITTED ->
            "That is not a valid next step for this incident."
    }
}

/** Local row -> domain. The raw type is kept so an unknown event still renders. */
private fun IncidentEventEntity.toTimelineEvent(): TimelineEvent = TimelineEvent(
    eventId = eventId,
    incidentClientId = incidentClientId,
    type = IncidentEventKind.fromWire(type, toValue),
    rawType = type,
    actorId = actorId,
    fromValue = fromValue,
    toValue = toValue,
    note = note,
    occurredAtEpochMillis = occurredAtEpochMillis,
    synced = synced,
)

/**
 * Server row -> local row.
 *
 * `synced = true`, because it came from the server by definition. The event id is the
 * server's, so a row pulled twice upserts onto itself instead of duplicating the timeline.
 */
private fun IncidentEventDto.toEntity(
    incidentClientId: String,
    incidentServerId: String,
): IncidentEventEntity = IncidentEventEntity(
    eventId = id,
    incidentClientId = incidentClientId,
    incidentServerId = incidentServerId,
    type = type,
    actorId = actorId,
    fromValue = fromValue,
    toValue = toValue,
    note = note,
    // Parsed defensively: one unreadable timestamp must not abort the whole timeline.
    occurredAtEpochMillis = runCatching { Instant.parse(occurredAt).toEpochMilli() }
        .getOrDefault(0L),
    synced = true,
)
