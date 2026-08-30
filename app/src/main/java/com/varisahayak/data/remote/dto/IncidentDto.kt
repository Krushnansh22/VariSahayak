package com.varisahayak.data.remote.dto

import com.varisahayak.data.local.entity.IncidentEntity
import com.varisahayak.domain.model.IncidentStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for the `incidents` table.
 *
 * Every field is explicitly named with [SerialName]. supabase-kt would otherwise apply its
 * camelCase-to-snake_case conversion implicitly; being explicit means a Kotlin rename can
 * never silently change what column is written.
 *
 * `client_id` is unique server-side. Uploads go through upsert on it, which is what makes
 * a retried sync a no-op instead of a duplicate incident.
 */
@Serializable
data class IncidentDto(
    @SerialName("client_id") val clientId: String,
    @SerialName("id") val id: String? = null,
    @SerialName("category") val category: String,
    @SerialName("description") val description: String,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("location_accuracy_m") val locationAccuracyMeters: Float? = null,
    @SerialName("location_is_approximate") val locationIsApproximate: Boolean = false,
    @SerialName("reporter_id") val reporterId: String,
    @SerialName("reported_at") val reportedAt: String,
    @SerialName("photo_path") val photoPath: String? = null,
    @SerialName("affected_person_note") val affectedPersonNote: String? = null,
    @SerialName("status") val status: String,
    @SerialName("priority") val priority: String,
    @SerialName("is_sos") val isSos: Boolean = false,
    @SerialName("sos_bridge_token") val sosBridgeToken: String? = null,
    @SerialName("assignee_id") val assigneeId: String? = null,
    @SerialName("area_id") val areaId: String? = null,
    @SerialName("organisation_id") val organisationId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * Payload for creating or replaying an incident.
 *
 * Status and assignment are server-owned and stay excluded: the device proposes an
 * incident, the server decides how it is routed.
 *
 * Priority is different. It is computed on-device by [PriorityEngine], deterministically
 * and without a network — an SOS is CRITICAL before anything is uploaded. Omitting it
 * meant every synced incident fell back to the column default of MEDIUM, so a critical
 * report reached the responder's dashboard indistinguishable from a routine one. The
 * `incidents_preserve_triage` trigger is what stops a replayed offline write from undoing
 * a later triage decision.
 */
@Serializable
data class IncidentUpsertDto(
    @SerialName("client_id") val clientId: String,
    @SerialName("category") val category: String,
    @SerialName("description") val description: String,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("location_accuracy_m") val locationAccuracyMeters: Float? = null,
    @SerialName("location_is_approximate") val locationIsApproximate: Boolean = false,
    @SerialName("reporter_id") val reporterId: String,
    @SerialName("reported_at") val reportedAt: String,
    @SerialName("photo_path") val photoPath: String? = null,
    @SerialName("affected_person_note") val affectedPersonNote: String? = null,
    @SerialName("priority") val priority: String,
    @SerialName("is_sos") val isSos: Boolean = false,
    @SerialName("sos_bridge_token") val sosBridgeToken: String? = null,
    @SerialName("area_id") val areaId: String? = null,
    @SerialName("organisation_id") val organisationId: String? = null,
    /**
     * The one field that makes a responder's Accept stick.
     *
     * Without it the upsert left `status` alone, the server echoed back the row it already
     * had, and markSynced wrote that stale value straight over the local ACCEPTED — so the
     * Accept button came back and every re-tap logged another event.
     */
    @SerialName("status") val status: String,
)

@Serializable
data class ProfileDto(
    @SerialName("id") val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("role") val role: String,
    @SerialName("organisation_id") val organisationId: String? = null,
    @SerialName("organisation_name") val organisationName: String? = null,
    @SerialName("area_id") val areaId: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("capabilities") val capabilities: List<String> = emptyList(),
)

@Serializable
data class ResponderDto(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("role") val role: String,
    @SerialName("availability") val availability: String,
    @SerialName("area_id") val areaId: String? = null,
    @SerialName("organisation_id") val organisationId: String? = null,
    @SerialName("capabilities") val capabilities: List<String> = emptyList(),
    @SerialName("last_latitude") val lastLatitude: Double? = null,
    @SerialName("last_longitude") val lastLongitude: Double? = null,
    @SerialName("last_location_at") val lastLocationAt: String? = null,
    @SerialName("active_assignment_count") val activeAssignmentCount: Int = 0,
)

@Serializable
data class IncidentEventDto(
    @SerialName("id") val id: String,
    @SerialName("incident_id") val incidentId: String,
    @SerialName("incident_client_id") val incidentClientId: String? = null,
    @SerialName("type") val type: String,
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("from_value") val fromValue: String? = null,
    @SerialName("to_value") val toValue: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
)

/**
 * One point on a user's device track, for `public.locations`.
 *
 * accuracy_m and is_approximate travel with the point rather than being inferred later:
 * a fix taken under an approximate-location grant is a different kind of fact from a GPS
 * one, and whoever reads the track has to be able to tell them apart.
 */
@Serializable
data class DeviceLocationDto(
    @SerialName("user_id") val userId: String,
    @SerialName("latitude") val latitude: Double,
    @SerialName("longitude") val longitude: Double,
    @SerialName("accuracy_m") val accuracyMetres: Float? = null,
    @SerialName("is_approximate") val isApproximate: Boolean = false,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
data class DeviceTokenDto(
    @SerialName("user_id") val userId: String,
    @SerialName("token") val token: String,
    @SerialName("platform") val platform: String = "android",
)

/**
 * Response from the classify-incident edge function.
 *
 * Every field is optional: the function returns a no-suggestion result whenever Gemini is
 * unavailable or its output fails validation, and the client must treat that as normal.
 */
@Serializable
data class ClassificationDto(
    @SerialName("category") val category: String? = null,
    @SerialName("severity") val severity: Int? = null,
    @SerialName("rationale") val rationale: String? = null,
    @SerialName("available") val available: Boolean = false,
)

fun IncidentEntity.toUpsertDto(reportedAtIso: String): IncidentUpsertDto = IncidentUpsertDto(
    clientId = clientId,
    category = category,
    description = description,
    latitude = latitude,
    longitude = longitude,
    locationAccuracyMeters = locationAccuracyMeters,
    locationIsApproximate = locationIsApproximate,
    reporterId = reporterId,
    reportedAt = reportedAtIso,
    photoPath = photoRemotePath,
    affectedPersonNote = affectedPersonNote,
    priority = priority,
    isSos = isSos,
    sosBridgeToken = sosBridgeToken,
    areaId = areaId,
    organisationId = organisationId,
    // PENDING_SYNC is a device-only state meaning "not uploaded yet". Uploading it as
    // itself would park the incident in a status the server's own triggers ignore, so the
    // act of uploading is what makes it REPORTED.
    status = if (status == IncidentStatus.PENDING_SYNC.wireName) {
        IncidentStatus.REPORTED.wireName
    } else {
        status
    },
)

/**
 * Maps a server row into the local store.
 *
 * Timestamps are parsed defensively: a malformed or absent value falls back to [fetchedAt]
 * rather than throwing, because one unparseable row must not abort the whole refresh and
 * strand every other incident.
 */
fun IncidentDto.toEntity(fetchedAt: Long): IncidentEntity = IncidentEntity(
    clientId = clientId,
    serverId = id,
    category = category,
    description = description,
    latitude = latitude,
    longitude = longitude,
    locationAccuracyMeters = locationAccuracyMeters,
    locationIsApproximate = locationIsApproximate,
    reporterId = reporterId,
    reportedAtEpochMillis = reportedAt.toEpochMillisOr(fetchedAt),
    photoRemotePath = photoPath,
    affectedPersonNote = affectedPersonNote,
    status = status,
    priority = priority,
    syncState = "SYNCED",
    isSos = isSos,
    sosBridgeToken = sosBridgeToken,
    assigneeId = assigneeId,
    areaId = areaId,
    organisationId = organisationId,
    updatedAtEpochMillis = updatedAt.toEpochMillisOr(fetchedAt),
)

private fun String?.toEpochMillisOr(fallback: Long): Long =
    this?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: fallback
