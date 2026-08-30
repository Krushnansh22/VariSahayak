package com.varisahayak.app.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes.
 *
 * Routes are serializable objects rather than string literals so a typo is a compile
 * error, and so arguments carry their types instead of being parsed out of a URL.
 */
sealed interface Destination {

    @Serializable
    data object Splash : Destination

    @Serializable
    data object SignIn : Destination

    @Serializable
    data object ForgotPassword : Destination

    @Serializable
    data object SignUp : Destination

    // --- volunteer / responder ---
    @Serializable
    data object VolunteerDashboard : Destination

    @Serializable
    data object ResponderDashboard : Destination

    // --- command ---
    @Serializable
    data object CommandDashboard : Destination

    @Serializable
    data object AdminDashboard : Destination

    // --- shared ---
    @Serializable
    data object IncidentList : Destination

    @Serializable
    data class IncidentDetail(val clientId: String) : Destination

    /**
     * [qrLocationToken] is the fixed sign the report is filed against, and
     * [qrLocationName] is carried alongside it so the form can name the place even when
     * the token has not resolved yet.
     */
    @Serializable
    data class ReportIncident(
        val qrLocationToken: String? = null,
        val qrLocationName: String? = null,
        val isSos: Boolean = false,
    ) : Destination

    @Serializable
    data object IncidentMap : Destination

    @Serializable
    data class LostAndFound(
        val qrLocationToken: String? = null,
        val qrLocationName: String? = null,
        /** LOST or FOUND, so a scan can open the right form directly. */
        val kind: String? = null,
    ) : Destination

    @Serializable
    data class LostFoundDetail(val clientId: String) : Destination

    /** The protected match-review surface. Human confirmation lives here. */
    @Serializable
    data object MatchReview : Destination

    @Serializable
    data object Documentation : Destination

    @Serializable
    data object Communication : Destination

    @Serializable
    data class Conversation(val channelId: String) : Destination

    @Serializable
    data object Notifications : Destination

    @Serializable
    data object Profile : Destination

    @Serializable
    data object BulkRegistration : Destination

    /** Shown when a signed-in account has no recognised role. */
    @Serializable
    data object NoRole : Destination
}
