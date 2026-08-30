package com.varisahayak.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.varisahayak.data.local.entity.IncidentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Local incident access.
 *
 * Reads are exposed as [Flow] because the database is the single source of truth for the
 * UI: network and realtime results are written here, and screens observe the result. That
 * is what makes online and offline behave identically.
 */
@Dao
interface IncidentDao {

    @Query("SELECT * FROM incidents ORDER BY reportedAtEpochMillis DESC")
    fun observeAll(): Flow<List<IncidentEntity>>

    @Query(
        """
        SELECT * FROM incidents
        WHERE status NOT IN ('RESOLVED', 'CANCELLED')
        ORDER BY
            CASE priority
                WHEN 'CRITICAL' THEN 0
                WHEN 'HIGH' THEN 1
                WHEN 'MEDIUM' THEN 2
                ELSE 3
            END,
            reportedAtEpochMillis DESC
        """,
    )
    fun observeOpen(): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents WHERE assigneeId = :userId AND status NOT IN ('RESOLVED', 'CANCELLED') ORDER BY reportedAtEpochMillis DESC")
    fun observeAssignedTo(userId: String): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents WHERE isSos = 1 AND status NOT IN ('RESOLVED', 'CANCELLED') ORDER BY reportedAtEpochMillis DESC")
    fun observeActiveSos(): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents WHERE clientId = :clientId")
    fun observeByClientId(clientId: String): Flow<IncidentEntity?>

    @Query("SELECT COUNT(*) FROM incidents WHERE reporterId = :userId")
    fun observeReportedCount(userId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM incidents WHERE assigneeId = :userId AND status = 'RESOLVED'")
    fun observeResolvedCount(userId: String): Flow<Int>

    @Query("SELECT * FROM incidents WHERE clientId = :clientId")
    suspend fun getByClientId(clientId: String): IncidentEntity?

    @Query("SELECT * FROM incidents WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): IncidentEntity?

    /** The sync queue. FAILED records are included — a failure is a retry, not a discard. */
    @Query("SELECT * FROM incidents WHERE syncState IN ('PENDING', 'FAILED') ORDER BY reportedAtEpochMillis ASC")
    suspend fun getPendingSync(): List<IncidentEntity>

    @Query("SELECT COUNT(*) FROM incidents WHERE syncState IN ('PENDING', 'FAILED')")
    fun observeUnsyncedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(incident: IncidentEntity)

    @Upsert
    suspend fun upsert(incident: IncidentEntity)

    @Upsert
    suspend fun upsertAll(incidents: List<IncidentEntity>)

    @Query("UPDATE incidents SET syncState = :syncState, lastSyncAttemptEpochMillis = :attemptedAt, syncAttemptCount = syncAttemptCount + 1 WHERE clientId = :clientId")
    suspend fun markSyncAttempt(clientId: String, syncState: String, attemptedAt: Long)

    @Query("UPDATE incidents SET serverId = :serverId, status = :status, syncState = 'SYNCED', updatedAtEpochMillis = :updatedAt WHERE clientId = :clientId")
    suspend fun markSynced(
        clientId: String,
        serverId: String,
        status: String,
        updatedAt: Long,
    )

    @Query("UPDATE incidents SET syncState = :syncState WHERE clientId = :clientId")
    suspend fun setSyncState(clientId: String, syncState: String)

    @Query("UPDATE incidents SET status = :status, updatedAtEpochMillis = :updatedAt WHERE clientId = :clientId")
    suspend fun setStatus(clientId: String, status: String, updatedAt: Long)

    @Query("UPDATE incidents SET priority = :priority, updatedAtEpochMillis = :updatedAt WHERE clientId = :clientId")
    suspend fun setPriority(clientId: String, priority: String, updatedAt: Long)

    /**
     * Merges a server row into the local store without clobbering unsynced local work.
     *
     * Conflict rule: the server owns status, priority, and assignment; the device owns
     * reporter-authored content. A record still waiting to sync is left alone entirely —
     * the local copy is the newer truth until it has been accepted.
     */
    @Transaction
    suspend fun reconcileFromServer(remote: IncidentEntity) {
        val local = remote.serverId?.let { getByServerId(it) }
            ?: getByClientId(remote.clientId)

        if (local == null) {
            upsert(remote)
            return
        }

        // FAILED counts as still-unsynced. Without it, an upload that errored had its
        // local edit silently overwritten by the stale server row on the next refresh —
        // the responder's Accept vanished and they tapped it again, and again.
        if (local.syncState == "PENDING" || local.syncState == "SYNCING" ||
            local.syncState == "FAILED"
        ) {
            return
        }

        upsert(
            local.copy(
                serverId = remote.serverId ?: local.serverId,
                status = remote.status,
                priority = remote.priority,
                assigneeId = remote.assigneeId,
                areaId = remote.areaId ?: local.areaId,
                organisationId = remote.organisationId ?: local.organisationId,
                photoRemotePath = remote.photoRemotePath ?: local.photoRemotePath,
                syncState = "SYNCED",
                updatedAtEpochMillis = maxOf(remote.updatedAtEpochMillis, local.updatedAtEpochMillis),
            ),
        )
    }

    @Query("DELETE FROM incidents")
    suspend fun clear()
}
