package com.varisahayak.domain.model

/**
 * What the signed-in user is allowed to do.
 *
 * This exists so the shared screens — incident list, incident detail, map — can be one
 * implementation each instead of one per role. A screen asks the capability set what to
 * render; it never branches on [UserRole] directly. Adding a role becomes one edit here
 * rather than a hunt through every composable.
 *
 * **This is a rendering aid, not a security control.** Every capability below mirrors a
 * rule that Postgres row-level security enforces independently — `is_command()`,
 * `is_responder()`, and the per-table policies. A tampered client that flips one of these
 * flags gains a button, not a permission: the write still reaches RLS and is still
 * refused.
 *
 * The reason to mirror the rules at all is honesty. Showing a volunteer an "Escalate"
 * button that Postgres will reject teaches them the app is broken. Deriving the button
 * from the same rule the server enforces means the UI only ever offers what will work.
 */
data class Capabilities(
    /** File a new incident from the report form. */
    val canReportIncident: Boolean,
    /** Raise an SOS from the dashboard in one tap. */
    val canRaiseSos: Boolean,
    /** File and search Lost & Found reports. */
    val canUseLostFound: Boolean,
    /** Accept or reject an assignment addressed to them. */
    val canAcceptAssignment: Boolean,
    /** Move an incident they own through the state machine. */
    val canProgressOwnIncident: Boolean,
    /** Assign an incident to somebody else. Command only. */
    val canAssignToOthers: Boolean,
    /** Escalate an incident, recording actor and reason. Command only. */
    val canEscalate: Boolean,
    /** Override the deterministic priority. Command only, and always audited. */
    val canOverridePriority: Boolean,
    /** See incidents across the whole area rather than only their own and assigned. */
    val canSeeAreaWideIncidents: Boolean,
    /** See the responder roster and their availability. */
    val canSeeResponderRoster: Boolean,
    /** See operational metrics and hotspots. */
    val canSeeOperationalReporting: Boolean,
    /** Publish their own availability state. Responders only. */
    val canSetOwnAvailability: Boolean,
) {
    companion object {
        /**
         * The single place a role becomes a set of permissions.
         *
         * Read the `when` branches against the RLS policies they mirror:
         *  - [canSeeAreaWideIncidents] ⇔ `is_command()` or `is_responder()` on
         *    `public.incidents`
         *  - [canAssignToOthers] ⇔ the `"Command creates assignments"` policy
         *  - [canEscalate] / [canOverridePriority] ⇔ `"Command updates area incidents"`
         */
        fun of(role: UserRole): Capabilities = when {
            role.isCommand -> Capabilities(
                // Command users coordinate rather than attend. They keep the ability to
                // file and raise — an organiser standing next to an emergency must not
                // have to hand their phone to somebody else — but Lost & Found is a field
                // surface they have no reason to carry.
                canReportIncident = true,
                canRaiseSos = true,
                canUseLostFound = false,
                canAcceptAssignment = false,
                canProgressOwnIncident = true,
                canAssignToOthers = true,
                canEscalate = true,
                canOverridePriority = true,
                canSeeAreaWideIncidents = true,
                canSeeResponderRoster = true,
                canSeeOperationalReporting = true,
                canSetOwnAvailability = false,
            )

            role.isResponder -> Capabilities(
                canReportIncident = true,
                canRaiseSos = true,
                canUseLostFound = true,
                canAcceptAssignment = true,
                canProgressOwnIncident = true,
                // Dispatch decisions belong to command. A responder who cannot take a
                // case asks for reassignment; they do not reassign it themselves.
                canAssignToOthers = false,
                canEscalate = false,
                canOverridePriority = false,
                canSeeAreaWideIncidents = true,
                canSeeResponderRoster = false,
                canSeeOperationalReporting = false,
                canSetOwnAvailability = true,
            )

            else -> Capabilities(
                // The volunteer baseline: report, raise, search. Everything that
                // decides who goes where is somebody else's.
                canReportIncident = true,
                canRaiseSos = true,
                canUseLostFound = true,
                canAcceptAssignment = false,
                canProgressOwnIncident = true,
                canAssignToOthers = false,
                canEscalate = false,
                canOverridePriority = false,
                canSeeAreaWideIncidents = false,
                canSeeResponderRoster = false,
                canSeeOperationalReporting = false,
                canSetOwnAvailability = false,
            )
        }

        /**
         * What an unresolved profile gets.
         *
         * Everything is denied. A cold start with no cached profile must not briefly
         * render command actions before the real role arrives — so the fallback is the
         * empty set, never the volunteer set.
         */
        val NONE = Capabilities(
            canReportIncident = false,
            canRaiseSos = false,
            canUseLostFound = false,
            canAcceptAssignment = false,
            canProgressOwnIncident = false,
            canAssignToOthers = false,
            canEscalate = false,
            canOverridePriority = false,
            canSeeAreaWideIncidents = false,
            canSeeResponderRoster = false,
            canSeeOperationalReporting = false,
            canSetOwnAvailability = false,
        )
    }
}

/** Convenience so a screen can go straight from the cached profile to what it may show. */
val Profile?.capabilities: Capabilities
    get() = this?.let { Capabilities.of(it.role) } ?: Capabilities.NONE
